package com.kuputunnel.app.vpn

import android.content.Context
import android.util.Log
import go.Seq
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Обёртка над AndroidLibXrayLite (Xray-core) — тот же подход, что у Happ (Xray)
 * и Hiddify (native core + callback): init env → startLoop(config, tunFd).
 */
object XrayEngine {

    private const val TAG = "KupuXray"
    private val initialized = AtomicBoolean(false)
    @Volatile private var controller: CoreController? = null
    @Volatile private var runningConfig: String? = null
    @Volatile private var runningRemark: String = ""

    fun isRunning(): Boolean = try {
        controller?.isRunning == true // getIsRunning()
    } catch (_: Exception) {
        false
    }

    fun runningRemark(): String = runningRemark

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            Seq.setContext(context.applicationContext)
            val assetDir = prepareAssets(context)
            val deviceKey = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "kuputunnel"
            Libv2ray.initCoreEnv(assetDir.absolutePath, deviceKey)
            controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(status: Long, message: String?): Long {
                    Log.i(TAG, "status=$status msg=$message")
                    return 0
                }
            })
            Log.i(TAG, "core ready: ${Libv2ray.checkVersionX()}")
        } catch (e: Exception) {
            initialized.set(false)
            Log.e(TAG, "init failed", e)
            throw e
        }
    }

    fun start(context: Context, shareLink: String, tunFd: Int): Result<XrayConfigBuilder.Built> {
        return try {
            init(context)
            if (isRunning()) {
                stop()
            }
            val built = XrayConfigBuilder.build(shareLink)
                ?: return Result.failure(IllegalArgumentException("Не удалось разобрать конфиг"))
            val core = controller ?: return Result.failure(IllegalStateException("Core not ready"))
            Log.i(TAG, "start ${built.protocol} ${built.host}:${built.port} tunFd=$tunFd")
            core.startLoop(built.json, tunFd)
            if (!core.isRunning) {
                return Result.failure(IllegalStateException("Xray не запустился"))
            }
            runningConfig = shareLink
            runningRemark = built.remark.ifBlank { "${built.host}:${built.port}" }
            Result.success(built)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            Result.failure(e)
        }
    }

    fun stop() {
        try {
            controller?.stopLoop()
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
        }
        runningConfig = null
        runningRemark = ""
    }

    fun measureDelay(testUrl: String = "https://www.google.com/generate_204"): Long {
        return try {
            if (!isRunning()) return -1L
            controller?.measureDelay(testUrl) ?: -1L
        } catch (_: Exception) {
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
