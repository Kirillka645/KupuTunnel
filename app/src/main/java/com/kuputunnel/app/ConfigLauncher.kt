package com.kuputunnel.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/**
 * Открывает конфиг во внешнем VPN-клиенте (v2rayNG / Hiddify / NekoBox).
 * Полный системный VPN-туннель делает сам клиент.
 */
object ConfigLauncher {

    private val CLIENT_PACKAGES = listOf(
        "com.v2ray.ang",
        "com.v2ray.ang.play",
        "app.hiddify.com",
        "moe.nb4a",
        "io.nekohasekai.sfa",
        "com.github.shadowsocks"
    )

    fun launch(context: Context, configUrl: String): Boolean {
        // 1) Native scheme (vless://, trojan://, …)
        if (tryView(context, Uri.parse(configUrl))) return true

        // 2) v2rayNG install-config deep link
        val encoded = URLEncoder.encode(configUrl, "UTF-8")
        if (tryView(context, Uri.parse("v2rayng://install-config?url=$encoded"))) return true
        if (tryView(context, Uri.parse("v2rayng://install-sub?url=$encoded"))) return true
        if (tryView(context, Uri.parse("hiddify://import/$encoded"))) return true
        if (tryView(context, Uri.parse("sn://subscription?url=$encoded"))) return true

        // 3) Share to known package
        for (pkg in CLIENT_PACKAGES) {
            if (!isInstalled(context, pkg)) continue
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, configUrl)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }

        // 4) Generic share chooser
        try {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, configUrl)
                    },
                    "Открыть в VPN-клиенте"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (_: Exception) {
        }

        // 5) Clipboard fallback
        copy(context, configUrl)
        Toast.makeText(context, R.string.client_missing, Toast.LENGTH_LONG).show()
        return false
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KupuTunnel", text))
    }

    fun isAnyClientInstalled(context: Context): Boolean =
        CLIENT_PACKAGES.any { isInstalled(context, it) }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun tryView(context: Context, uri: Uri): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
