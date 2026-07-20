package com.kuputunnel.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import com.kuputunnel.app.vpn.KupuVpnService
import com.kuputunnel.app.vpn.VpnSession
import com.kuputunnel.app.vpn.XrayConfigBuilder

/**
 * Подключение: системный VPN (Happ/Hiddify-style).
 * VLESS Reality · VMess · Trojan · Shadowsocks · Socks.
 */
object ConfigLauncher {

    fun launch(context: Context, configUrl: String): Boolean {
        val built = XrayConfigBuilder.build(configUrl)
        if (built == null) {
            Toast.makeText(context, "Неподдерживаемый формат конфига", Toast.LENGTH_LONG).show()
            return false
        }

        // Уже подключены к этому же узлу → отключить
        if (VpnSession.isConnected() && VpnSession.activeConfig == configUrl) {
            KupuVpnService.stop(context)
            Toast.makeText(context, "VPN отключается…", Toast.LENGTH_SHORT).show()
            return true
        }

        VpnSession.pendingConfig = configUrl
        val prep = VpnService.prepare(context)
        if (prep != null) {
            // Разрешение VPN: Activity должна вызвать launcher (см. ConfigListActivity)
            if (context is Activity) {
                @Suppress("DEPRECATION")
                context.startActivityForResult(prep, REQ_VPN_PERMISSION)
            } else {
                Toast.makeText(context, "Нужно разрешение VPN", Toast.LENGTH_LONG).show()
            }
            return true
        }

        KupuVpnService.start(context, configUrl)
        Toast.makeText(
            context,
            "VPN · ${built.protocol} · ${built.host}:${built.port}",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    fun onVpnPermissionResult(context: Context, granted: Boolean) {
        val link = VpnSession.pendingConfig
        if (!granted || link.isNullOrBlank()) {
            Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_LONG).show()
            return
        }
        KupuVpnService.start(context, link)
        Toast.makeText(context, "VPN подключается…", Toast.LENGTH_SHORT).show()
    }

    fun disconnect(context: Context) {
        KupuVpnService.stop(context)
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KupuTunnel", text))
    }

    /** Export в другой клиент (опционально). */
    fun exportExternal(context: Context, configUrl: String) {
        try {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, configUrl)
                    },
                    "Экспорт конфига"
                )
            )
        } catch (_: Exception) {
            copy(context, configUrl)
            Toast.makeText(context, R.string.config_copied, Toast.LENGTH_SHORT).show()
        }
    }

    const val REQ_VPN_PERMISSION = 0x71_01
}
