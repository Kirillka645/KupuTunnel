package com.kuputunnel.app.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.kuputunnel.app.R

/**
 * Запрос VPN-разрешения и старт KupuVpnService (паттерн Hiddify: prepare → service).
 */
object VpnController {

    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun connect(context: Context, shareLink: String) {
        val prep = VpnService.prepare(context)
        if (prep != null) {
            // Caller must request permission; store config
            VpnSession.pendingConfig = shareLink
            if (context is Activity) {
                context.startActivityForResult(prep, REQ_VPN)
            } else {
                Toast.makeText(context, "Открой приложение и нажми Подключить", Toast.LENGTH_LONG)
                    .show()
            }
            return
        }
        KupuVpnService.start(context, shareLink)
        Toast.makeText(context, "VPN подключается…", Toast.LENGTH_SHORT).show()
    }

    fun connectAfterPermission(context: Context, granted: Boolean) {
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
        Toast.makeText(context, "VPN отключается…", Toast.LENGTH_SHORT).show()
    }

    fun toggle(context: Context, shareLink: String) {
        if (VpnSession.isConnected()) disconnect(context)
        else connect(context, shareLink)
    }

    const val REQ_VPN = 0x71_01
}

/**
 * Хелпер для Activity с Activity Result API.
 */
fun ComponentActivity.registerVpnPermissionLauncher(
    onResult: (granted: Boolean) -> Unit
) = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    onResult(result.resultCode == Activity.RESULT_OK)
}
