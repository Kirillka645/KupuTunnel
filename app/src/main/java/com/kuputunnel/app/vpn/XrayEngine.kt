package com.kuputunnel.app.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над AndroidLibXrayLite. Ленивая загрузка native-кода
 * только при connect — не при старте приложения.
 */
object XrayEngine {

    private const val TAG = "KupuXray"
    private val initialized = AtomicBoolean(false)
    private val initLock = Any()

    @Volatile private var controller: Any? = null
    @Volatile private var runningConfig: String? = null
    @Volatile private var runningRemark: String = ""

    fun isRunning(): Boolean = try {
        val c = controller ?: return false
        val m = c.javaClass.getMethod("getIsRunning")
        m.invoke(c) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    fun runningRemark(): String = runningRemark

    fun init(context: Context) {
        if (initialized.get() && controller != null) return
        synchronized(initLock) {
            if (initialized.get() && controller != null) return
            try {
                // go.Seq + libv2ray — грузятся только здесь
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
                ) { _, method, _ ->
                    when (method.name) {
                        "startup", "shutdown", "onEmitStatus" -> 0L
                        else -> null
                    }
                }

                val newCtrl = lib.getMethod("newCoreController", handlerClass)
                controller = newCtrl.invoke(null, handler)
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

    fun start(context: Context, shareLink: String, tunFd: Int): Result<XrayConfigBuilder.Built> {
        return try {
            init(context)
            if (isRunning()) stop()

            val built = XrayConfigBuilder.build(shareLink)
                ?: return Result.failure(IllegalArgumentException("Не удалось разобрать конфиг"))

            val core = controller
                ?: return Result.failure(IllegalStateException("Core not ready"))

            Log.i(TAG, "start ${built.protocol} ${built.host}:${built.port} tunFd=$tunFd")
            val startLoop = core.javaClass.getMethod(
                "startLoop",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            startLoop.invoke(core, built.json, tunFd)

            if (!isRunning()) {
                return Result.failure(IllegalStateException("Xray не запустился"))
            }
            runningConfig = shareLink
            runningRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
            Result.success(built)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            Log.e(TAG, "start failed", cause)
            Result.failure(cause)
        }
    }

    fun stop() {
        try {
            val core = controller
            if (core != null) {
                core.javaClass.getMethod("stopLoop").invoke(core)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "stop failed", e)
        }
        runningConfig = null
        runningRemark = ""
    }

    fun measureDelay(testUrl: String = "https://www.google.com/generate_204"): Long {
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
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val out = File(dir, name)
            if (out.exists() && out.length() > 1024) return@forEach
            try {
                context.assets.open(name).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "asset $name missing: ${e.message}")
            }
        }
        return dir
    }
}
