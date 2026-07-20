package com.kuputunnel.app.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ленивая обёртка над AndroidLibXrayLite.
 * startLoop(config, tunFd) → env xray.tun.fd + core.Start
 */
object XrayEngine {

    private const val TAG = "KupuXray"
    private val initialized = AtomicBoolean(false)
    private val initLock = Any()
    private val startLock = Any()

    @Volatile private var controller: Any? = null
    @Volatile private var runningConfig: String? = null
    @Volatile private var runningRemark: String = ""

    fun isRunning(): Boolean = try {
        val c = controller ?: return false
        c.javaClass.getMethod("getIsRunning").invoke(c) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    fun runningRemark(): String = runningRemark

    fun init(context: Context) {
        if (initialized.get() && controller != null) return
        synchronized(initLock) {
            if (initialized.get() && controller != null) return
            try {
                val seq = Class.forName("go.Seq")
                seq.getMethod("setContext", Context::class.java)
                    .invoke(null, context.applicationContext)

                val assetDir = prepareAssets(context)
                val deviceKey = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "kuputunnel"

                val lib = Class.forName("libv2ray.Libv2ray")
                lib.getMethod("initCoreEnv", String::class.java, String::class.java)
                    .invoke(null, assetDir.absolutePath, deviceKey)

                val handlerClass = Class.forName("libv2ray.CoreCallbackHandler")
                val handler = java.lang.reflect.Proxy.newProxyInstance(
                    handlerClass.classLoader,
                    arrayOf(handlerClass)
                ) { _, method, args ->
                    when (method.name) {
                        "startup" -> {
                            Log.i(TAG, "core startup")
                            0L
                        }
                        "shutdown" -> {
                            Log.i(TAG, "core shutdown")
                            0L
                        }
                        "onEmitStatus" -> {
                            Log.i(TAG, "status=${args?.getOrNull(0)} msg=${args?.getOrNull(1)}")
                            0L
                        }
                        else -> 0L
                    }
                }

                controller = lib.getMethod("newCoreController", handlerClass)
                    .invoke(null, handler)
                initialized.set(true)
                val ver = lib.getMethod("checkVersionX").invoke(null) as? String
                Log.i(TAG, "core ready: $ver")
            } catch (e: Throwable) {
                initialized.set(false)
                controller = null
                Log.e(TAG, "init failed", e)
                throw e
            }
        }
    }

    fun start(
        context: Context,
        shareLink: String,
        tunFd: Int,
        useTun: Boolean = true
    ): Result<XrayConfigBuilder.Built> {
        synchronized(startLock) {
            return try {
                init(context)
                if (isRunning()) {
                    stopQuiet()
                    Thread.sleep(250)
                }

                if (useTun && tunFd < 3) {
                    return Result.failure(
                        IllegalArgumentException("Некорректный TUN fd=$tunFd")
                    )
                }

                val built = XrayConfigBuilder.build(shareLink, useTun = useTun)
                    ?: return Result.failure(IllegalArgumentException("Не удалось разобрать конфиг"))

                val core = controller
                    ?: return Result.failure(IllegalStateException("Core not ready"))

                Log.i(
                    TAG,
                    "startLoop ${built.protocol} ${built.host}:${built.port} " +
                        "tunFd=$tunFd useTun=$useTun jsonLen=${built.json.length} " +
                        "serverIps=${built.serverIps}"
                )
                try {
                    File(context.filesDir, "last_xray_config.json").writeText(built.json)
                } catch (_: Exception) {
                }

                val startLoop = core.javaClass.getMethod(
                    "startLoop",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                try {
                    // fd=0 → без TUN (AndroidLibXrayLite)
                    startLoop.invoke(core, built.json, if (useTun) tunFd else 0)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.cause ?: e
                    Log.e(TAG, "startLoop threw", cause)
                    try {
                        File(context.filesDir, "last_xray_error.txt")
                            .writeText(cause.stackTraceToString())
                    } catch (_: Exception) {
                    }
                    return Result.failure(cause)
                }

                // Дать core подняться (до ~2s)
                var ok = false
                repeat(40) {
                    Thread.sleep(50)
                    if (isRunning()) {
                        ok = true
                        return@repeat
                    }
                }
                if (!ok) {
                    return Result.failure(
                        IllegalStateException("Xray не запустился (см. last_xray_error.txt)")
                    )
                }

                // Доп. пауза — tun endpoint инициализируется async
                Thread.sleep(200)
                if (!isRunning()) {
                    return Result.failure(
                        IllegalStateException("Xray упал сразу после старта")
                    )
                }

                runningConfig = shareLink
                runningRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
                Result.success(built)
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                Log.e(TAG, "start failed", cause)
                try {
                    File(context.filesDir, "last_xray_error.txt")
                        .writeText(cause.stackTraceToString())
                } catch (_: Exception) {
                }
                Result.failure(cause)
            }
        }
    }

    fun stop() {
        synchronized(startLock) {
            stopQuiet()
            runningConfig = null
            runningRemark = ""
        }
    }

    private fun stopQuiet() {
        try {
            val core = controller ?: return
            if (isRunning()) {
                core.javaClass.getMethod("stopLoop").invoke(core)
            }
            // дать потокам gVisor сдохнуть
            Thread.sleep(150)
        } catch (e: Throwable) {
            Log.e(TAG, "stop failed", e)
        }
    }

    fun measureDelay(testUrl: String = "https://www.gstatic.com/generate_204"): Long {
        return try {
            if (!isRunning()) return -1L
            val core = controller ?: return -1L
            val m = core.javaClass.getMethod("measureDelay", String::class.java)
            (m.invoke(core, testUrl) as? Long) ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun prepareAssets(context: Context): File {
        val dir = File(context.filesDir, "xray_assets").also { if (!it.exists()) it.mkdirs() }
        // geo из AAR assets + app assets
        listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat").forEach { name ->
            val out = File(dir, name)
            if (out.exists() && out.length() > 1024) return@forEach
            try {
                context.assets.open(name).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "copied asset $name size=${out.length()}")
            } catch (e: Exception) {
                Log.w(TAG, "asset $name missing: ${e.message}")
            }
        }
        return dir
    }
}
