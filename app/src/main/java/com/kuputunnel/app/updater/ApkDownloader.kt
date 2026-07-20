package com.kuputunnel.app.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * РЎРєР°С‡РёРІР°РЅРёРµ APK РёР· GitHub Releases Рё СѓСЃС‚Р°РЅРѕРІРєР° Р±РµР· Р±СЂР°СѓР·РµСЂР°.
 */
class ApkDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    suspend fun download(
        apkUrl: String,
        fileName: String = "KupuTunnel-update.apk",
        onProgress: (percent: Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(apkUrl.isNotBlank()) { "РќРµС‚ СЃСЃС‹Р»РєРё РЅР° APK РІ СЂРµР»РёР·Рµ" }

        val dir = File(context.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }
        // clean old
        dir.listFiles()?.forEach { it.delete() }

        val outFile = File(dir, fileName)
        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "KupuTunnel-Android-Updater")
            .header("Accept", "application/octet-stream,*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("РџСѓСЃС‚РѕР№ РѕС‚РІРµС‚")
            val total = body.contentLength()
            var read = 0L

            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                    output.flush()
                }
            }
        }

        if (!outFile.exists() || outFile.length() < 10_000) {
            throw IllegalStateException("APK РїРѕРІСЂРµР¶РґС‘РЅ РёР»Рё СЃР»РёС€РєРѕРј РјР°Р»РµРЅСЊРєРёР№")
        }
        withContext(Dispatchers.Main) { onProgress(100) }
        outFile
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    fun installApk(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}

