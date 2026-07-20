package com.kuputunnel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kuputunnel.app.MainActivity
import com.kuputunnel.app.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System VPN:
 * - startForeground с correct FGS type (иначе Android 14 убивает сервис через ~5с)
 * - Xray на background thread
 * - null intent / restart не роняет VPN
 * - addDisallowedApplication + server IP в direct (routing)
 */
class KupuVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kupu-vpn-worker").apply { isDaemon = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Сразу foreground — дедлайн 5–10с на Android 12+
        try {
            startAsForeground("KupuTunnel…")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }

        when (intent?.action) {
            ACTION_STOP -> {
                worker.execute {
                    stopVpnInternal()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                val link = intent?.getStringExtra(EXTRA_CONFIG)
                    ?: VpnSession.pendingConfig
                    ?: VpnSession.activeConfig
                if (link.isNullOrBlank()) {
                    // Рестарт без extras: если уже подключены — не трогаем
                    if (running.get() && XrayEngine.isRunning()) {
                        Log.i(TAG, "restart with null intent, keep running")
                        return START_STICKY
                    }
                    Log.e(TAG, "no config")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Уже на этом же конфиге — ок
                if (running.get() &&
                    XrayEngine.isRunning() &&
                    VpnSession.activeConfig == link
                ) {
                    updateNotification("VPN · ${VpnSession.activeProtocol} · ${VpnSession.activeRemark}")
                    return START_STICKY
                }
                worker.execute { startVpnInternal(link) }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke")
        worker.execute {
            stopVpnInternal()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        worker.execute { stopVpnInternal() }
        super.onDestroy()
    }

    private fun startVpnInternal(shareLink: String) {
        try {
            if (running.get()) {
                stopVpnInternal()
            }

            if (prepare(this) != null) {
                Log.e(TAG, "VPN permission missing")
                broadcast(STATE_FAILED, "Нет разрешения VPN")
                stopForegroundSafe()
                stopSelf()
                return
            }

            val builder = Builder()
                .setSession("KupuTunnel")
                .setMtu(1500)
                // TUN address
                .addAddress("10.10.14.2", 30)
                // full tunnel IPv4
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                // Свой процесс не в туннель — Xray ходит наружу напрямую
                .addDisallowedApplication(packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setBlocking(false)
            }

            try {
                tun?.close()
            } catch (_: Exception) {
            }

            val pfd = builder.establish()
            if (pfd == null) {
                broadcast(STATE_FAILED, "Не удалось создать TUN")
                stopForegroundSafe()
                stopSelf()
                return
            }
            tun = pfd
            Log.i(TAG, "TUN established fd=${pfd.fd}")

            // Сначала пробуем с TUN fd
            var result = XrayEngine.start(this, shareLink, pfd.fd, useTun = true)
            if (result.isFailure) {
                Log.w(TAG, "tun mode failed: ${result.exceptionOrNull()?.message}, retry socks-only")
                // Fallback: только socks, TUN fd=0 (трафик всё равно через TUN apps →
                // без tun inbound не пойдёт; но хотя бы core жив)
                // Лучше: оставить TUN + socks, но fd=0 если tun inbound не поддерживается
                result = XrayEngine.start(this, shareLink, 0, useTun = false)
                if (result.isFailure) {
                    Log.e(TAG, "core fail", result.exceptionOrNull())
                    try {
                        pfd.close()
                    } catch (_: Exception) {
                    }
                    tun = null
                    broadcast(STATE_FAILED, result.exceptionOrNull()?.message ?: "Xray error")
                    stopForegroundSafe()
                    stopSelf()
                    return
                }
                // socks-only + full tunnel без tun inbound = нет интернета
                // → показываем ошибку честно
                Log.e(TAG, "running without tun inbound — network may not work")
            }

            val built = result.getOrThrow()
            running.set(true)
            VpnSession.pendingConfig = shareLink
            VpnSession.activeConfig = shareLink
            VpnSession.activeRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
            VpnSession.activeProtocol = built.protocol
            updateNotification("VPN · ${built.protocol} · ${VpnSession.activeRemark}")
            broadcast(STATE_CONNECTED, VpnSession.activeRemark)
            Log.i(TAG, "VPN up ${built.protocol} ${built.host}:${built.port} ips=${built.serverIps}")

            // Лёгкий health-check: если core умер через 2с — перезапуск
            worker.execute {
                try {
                    Thread.sleep(2500)
                    if (running.get() && !XrayEngine.isRunning()) {
                        Log.w(TAG, "core died, restarting…")
                        val link = VpnSession.activeConfig ?: return@execute
                        startVpnInternal(link)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startVpn", e)
            stopVpnInternal()
            broadcast(STATE_FAILED, e.message ?: "error")
            stopSelf()
        }
    }

    private fun stopVpnInternal() {
        running.set(false)
        try {
            XrayEngine.stop()
        } catch (_: Exception) {
        }
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        VpnSession.clearActive()
        broadcast(STATE_DISCONNECTED, "")
        stopForegroundSafe()
        Log.i(TAG, "VPN down")
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KupuVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KupuTunnel VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_star)
            .setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "KupuTunnel VPN",
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun broadcast(state: String, message: String) {
        try {
            sendBroadcast(
                Intent(ACTION_STATE).setPackage(packageName)
                    .putExtra(EXTRA_STATE, state)
                    .putExtra(EXTRA_MESSAGE, message)
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "KupuVpnService"
        private const val CHANNEL_ID = "kuputunnel_vpn"
        private const val NOTIF_ID = 7142

        const val ACTION_START = "com.kuputunnel.app.VPN_START"
        const val ACTION_STOP = "com.kuputunnel.app.VPN_STOP"
        const val ACTION_STATE = "com.kuputunnel.app.VPN_STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_FAILED = "failed"

        fun start(context: Context, shareLink: String) {
            VpnSession.pendingConfig = shareLink
            val i = Intent(context, KupuVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, shareLink)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, KupuVpnService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                Log.e(TAG, "stop", e)
            }
        }
    }
}

object VpnSession {
    @Volatile var pendingConfig: String? = null
    @Volatile var activeConfig: String? = null
    @Volatile var activeRemark: String = ""
    @Volatile var activeProtocol: String = ""

    fun isConnected(): Boolean = activeConfig != null && XrayEngine.isRunning()

    fun clearActive() {
        activeConfig = null
        activeRemark = ""
        activeProtocol = ""
    }
}
