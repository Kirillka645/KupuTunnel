package com.kuputunnel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kuputunnel.app.MainActivity
import com.kuputunnel.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System VPN — как Hiddify VPNService:
 * prepare → Builder.establish() → fd → native core (Xray).
 * protect() для сокетов ядра (PlatformInterface.autoDetectInterfaceControl).
 */
class KupuVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val link = intent?.getStringExtra(EXTRA_CONFIG)
                    ?: VpnSession.pendingConfig
                if (link.isNullOrBlank()) {
                    Log.e(TAG, "no config")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(link)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(shareLink: String) {
        if (running.get()) {
            stopVpn()
        }
        startForeground(NOTIF_ID, buildNotification("Подключение…"))

        if (prepare(this) != null) {
            Log.e(TAG, "VPN permission missing")
            broadcast(STATE_FAILED, "Нет разрешения VPN")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession("KupuTunnel")
                .setMtu(1500)
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            tun?.close()
            val pfd = builder.establish()
            if (pfd == null) {
                broadcast(STATE_FAILED, "Не удалось создать TUN")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            tun = pfd

            val result = XrayEngine.start(this, shareLink, pfd.fd)
            if (result.isFailure) {
                Log.e(TAG, "core fail", result.exceptionOrNull())
                pfd.close()
                tun = null
                broadcast(STATE_FAILED, result.exceptionOrNull()?.message ?: "Xray error")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val built = result.getOrThrow()
            running.set(true)
            VpnSession.activeConfig = shareLink
            VpnSession.activeRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
            VpnSession.activeProtocol = built.protocol
            updateNotification("VPN · ${built.protocol} · ${VpnSession.activeRemark}")
            broadcast(STATE_CONNECTED, VpnSession.activeRemark)
            Log.i(TAG, "VPN up ${built.protocol} ${built.host}:${built.port}")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn", e)
            stopVpn()
            broadcast(STATE_FAILED, e.message ?: "error")
            stopSelf()
        }
    }

    private fun stopVpn() {
        if (!running.getAndSet(false) && tun == null && !XrayEngine.isRunning()) {
            return
        }
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "VPN down")
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KupuVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KupuTunnel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_star)
            .setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "KupuTunnel VPN",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun broadcast(state: String, message: String) {
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
        )
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
            context.startService(
                Intent(context, KupuVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/** Глобальная сессия VPN (простая, без MMKV). */
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
