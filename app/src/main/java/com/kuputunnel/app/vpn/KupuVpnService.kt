package com.kuputunnel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kuputunnel.app.MainActivity
import com.kuputunnel.app.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Системный VPN (Happ/Hiddify-style) через Xray + TUN fd.
 *
 * Ключевые стабилизаторы:
 * - FGS specialUse (API 34+)
 * - stats/policy в JSON (см. XrayConfigBuilder)
 * - addDisallowedApplication — anti-loop без protect-callback
 * - только IPv4 (меньше сюрпризов)
 * - wake lock на всю сессию
 * - мягкий health-check (1 рестарт, без thrashing)
 * - не закрываем TUN fd пока core жив
 */
class KupuVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentLink: String? = null
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kupu-vpn").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kupu-vpn-health").apply { isDaemon = true }
    }
    private var healthFuture: ScheduledFuture<*>? = null
    private val restartBudget = AtomicInteger(1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                    stopVpnInternal(clearPersist = false)
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
                    if (running.get() && XrayEngine.isRunning()) return START_STICKY
                    log("no config, stop")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (running.get() &&
                    XrayEngine.isRunning() &&
                    VpnSession.activeConfig == link
                ) {
                    updateNotification(
                        "VPN · ${VpnSession.activeProtocol} · ${VpnSession.activeRemark}"
                    )
                    return START_STICKY
                }

                restartBudget.set(1)
                worker.execute { startVpnInternal(link) }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        log("onRevoke")
        worker.execute {
            stopVpnInternal(clearPersist = false)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            cancelHealth()
            unbindNetwork()
            running.set(false)
            releaseWake()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun startVpnInternal(shareLink: String) {
        acquireWake()
        try {
            // Сброс предыдущей сессии
            if (running.get() || XrayEngine.isRunning() || tun != null) {
                try {
                    cancelHealth()
                    XrayEngine.stop()
                } catch (_: Exception) {
                }
                try {
                    tun?.close()
                } catch (_: Exception) {
                }
                tun = null
                running.set(false)
                Thread.sleep(350)
            }

            if (prepare(this) != null) {
                log("permission missing")
                broadcast(STATE_FAILED, "Нет разрешения VPN")
                stopForegroundSafe()
                stopSelf()
                return
            }

            bindUnderlyingNetwork()

            val builder = Builder()
                .setSession("KupuTunnel")
                .setMtu(1500)
                .addAddress("10.10.14.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setBlocking(true)

            // Только IPv4 — IPv6 без маршрута ломает часть ROM
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    builder.allowFamily(OsConstants.AF_INET)
                }
            } catch (_: Exception) {
            }

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                log("disallow self fail: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // underlying network → VPN не «зацикливается» на себе
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val active = cm.activeNetwork
                if (active != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    builder.setUnderlyingNetworks(arrayOf(active))
                }
            } catch (_: Exception) {
            }

            val pfd = builder.establish()
            if (pfd == null) {
                log("establish() null")
                broadcast(STATE_FAILED, "TUN establish failed")
                stopForegroundSafe()
                stopSelf()
                return
            }
            // Держим PFD живым пока VPN работает (не detachFd)
            tun = pfd
            val fd = pfd.fd
            log("TUN fd=$fd")

            // Только TUN-режим. Без TUN = чёрная дыра (весь трафик в пустой interface).
            val result = XrayEngine.start(this, shareLink, fd, useTun = true)

            if (result.isFailure) {
                val err = result.exceptionOrNull()
                log("xray fail: ${err?.message}")
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
                tun = null
                broadcast(STATE_FAILED, shortErr(err))
                stopForegroundSafe()
                stopSelf()
                return
            }

            val built = result.getOrThrow()
            currentLink = shareLink
            running.set(true)
            VpnSession.pendingConfig = shareLink
            VpnSession.activeConfig = shareLink
            VpnSession.activeRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
            VpnSession.activeProtocol = built.protocol
            VpnSession.persist(this, shareLink)
            updateNotification("VPN · ${built.protocol} · ${VpnSession.activeRemark}")
            broadcast(STATE_CONNECTED, VpnSession.activeRemark)
            log("UP ${built.protocol} ${built.host}:${built.port}")
            scheduleHealth()
        } catch (e: Exception) {
            log("startVpn error: ${e.message}")
            stopVpnInternal(clearPersist = false)
            broadcast(STATE_FAILED, e.message ?: "error")
            stopSelf()
        }
    }

    private fun scheduleHealth() {
        cancelHealth()
        healthFuture = scheduler.scheduleWithFixedDelay({
            try {
                if (!running.get()) return@scheduleWithFixedDelay
                if (XrayEngine.isRunning()) return@scheduleWithFixedDelay

                log("health: core dead")
                val link = currentLink ?: VpnSession.activeConfig
                if (link != null && restartBudget.getAndDecrement() > 0) {
                    log("health: one soft restart")
                    worker.execute { startVpnInternal(link) }
                } else {
                    log("health: giving up")
                    worker.execute {
                        stopVpnInternal(clearPersist = false)
                        broadcast(STATE_FAILED, "Xray остановился")
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                log("health err: ${e.message}")
            }
        }, 4, 6, TimeUnit.SECONDS)
    }

    private fun cancelHealth() {
        try {
            healthFuture?.cancel(false)
        } catch (_: Exception) {
        }
        healthFuture = null
    }

    private fun stopVpnInternal(clearPersist: Boolean) {
        cancelHealth()
        running.set(false)
        currentLink = null
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
        if (clearPersist) {
            VpnSession.clearPersisted(this)
        }
        unbindNetwork()
        releaseWake()
        broadcast(STATE_DISCONNECTED, "")
        stopForegroundSafe()
        log("DOWN")
    }

    private fun bindUnderlyingNetwork() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            unbindNetwork()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                // не process-default (ломает VPN), только для reference
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                networkCallback = cb
                cm.registerNetworkCallback(req, cb)
            }
        } catch (e: Exception) {
            log("bind net: ${e.message}")
        }
    }

    private fun unbindNetwork() {
        try {
            val cb = networkCallback ?: return
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun acquireWake() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kuputunnel:vpn").apply {
                setReferenceCounted(false)
                // держим всю сессию (max 3 часа, потом health/перезапуск)
                acquire(3 * 60 * 60 * 1000L)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
            val f = File(filesDir, "vpn.log")
            if (f.length() > 200_000) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {
        }
    }

    private fun shortErr(t: Throwable?): String {
        val m = t?.message ?: return "Xray error"
        return m.take(140)
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

    fun clearPersisted(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LINK).apply()
        } catch (_: Exception) {
        }
        pendingConfig = null
    }
}
