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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kuputunnel.app.MainActivity
import com.kuputunnel.app.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Стабильный VPN-сервис.
 * - FGS type для Android 14+
 * - Не стопается при null-intent restart
 * - Нет «health restart» thrashing
 * - WakeLock на время старта
 */
class KupuVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kupu-vpn").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        try {
            startAsForeground("KupuTunnel VPN")
        } catch (e: Exception) {
            Log.e(TAG, "fg onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground("KupuTunnel…")
        } catch (e: Exception) {
            Log.e(TAG, "fg start", e)
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
                    ?: VpnSession.loadPersisted(this)
                    ?: VpnSession.pendingConfig
                    ?: VpnSession.activeConfig

                if (link.isNullOrBlank()) {
                    if (running.get()) return START_STICKY
                    log("no config, stop")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (running.get() && VpnSession.activeConfig == link) {
                    updateNotification("VPN · ${VpnSession.activeProtocol} · ${VpnSession.activeRemark}")
                    return START_STICKY
                }

                worker.execute { startVpnInternal(link) }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        log("onRevoke")
        worker.execute {
            stopVpnInternal()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        // Не гасим core синхронно — только флаги; worker сделает cleanup
        try {
            running.set(false)
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun startVpnInternal(shareLink: String) {
        acquireWake()
        try {
            if (running.get()) {
                // уже на другом узле — мягкий restart
                try {
                    XrayEngine.stop()
                } catch (_: Exception) {
                }
                try {
                    tun?.close()
                } catch (_: Exception) {
                }
                tun = null
                running.set(false)
                Thread.sleep(300)
            }

            if (prepare(this) != null) {
                log("permission missing")
                broadcast(STATE_FAILED, "Нет разрешения VPN")
                stopForegroundSafe()
                stopSelf()
                return
            }

            val builder = Builder()
                .setSession("KupuTunnel")
                .setMtu(1500)
                .addAddress("10.10.14.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                log("establish() null")
                broadcast(STATE_FAILED, "TUN establish failed")
                stopForegroundSafe()
                stopSelf()
                return
            }
            tun = pfd
            log("TUN fd=${pfd.fd}")

            // TUN mode
            var result = XrayEngine.start(this, shareLink, pfd.fd, useTun = true)
            if (result.isFailure) {
                log("tun start fail: ${result.exceptionOrNull()?.message}")
                // retry once without tun inbound, fd still open for system
                result = XrayEngine.start(this, shareLink, 0, useTun = false)
            }

            if (result.isFailure) {
                log("xray fail: ${result.exceptionOrNull()?.message}")
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
                tun = null
                broadcast(STATE_FAILED, shortErr(result.exceptionOrNull()))
                stopForegroundSafe()
                stopSelf()
                return
            }

            val built = result.getOrThrow()
            running.set(true)
            VpnSession.pendingConfig = shareLink
            VpnSession.activeConfig = shareLink
            VpnSession.activeRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
            VpnSession.activeProtocol = built.protocol
            VpnSession.persist(this, shareLink)
            updateNotification("VPN · ${built.protocol} · ${VpnSession.activeRemark}")
            broadcast(STATE_CONNECTED, VpnSession.activeRemark)
            log("UP ${built.protocol} ${built.host}:${built.port}")
        } catch (e: Exception) {
            log("startVpn error: ${e.message}")
            stopVpnInternal()
            broadcast(STATE_FAILED, e.message ?: "error")
            stopSelf()
        } finally {
            releaseWake()
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
        log("DOWN")
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kuputunnel:vpn").apply {
                setReferenceCounted(false)
                acquire(60_000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun startAsForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, n)
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
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KupuTunnel VPN", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
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

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            File(filesDir, "vpn.log").appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {
        }
    }

    private fun shortErr(t: Throwable?): String {
        val m = t?.message ?: return "Xray error"
        return m.take(120)
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
            VpnSession.persist(context, shareLink)
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
    private const val PREFS = "vpn_session"
    private const val KEY_LINK = "link"

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

    fun persist(context: Context, link: String) {
        pendingConfig = link
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LINK, link).apply()
        } catch (_: Exception) {
        }
    }

    fun loadPersisted(context: Context): String? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LINK, null)
    } catch (_: Exception) {
        null
    }
}
