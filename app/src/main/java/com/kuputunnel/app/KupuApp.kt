package com.kuputunnel.app

import android.app.Application
import com.kuputunnel.app.vpn.XrayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KupuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Прогрев Xray-core в фоне (как Hiddify setup)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                XrayEngine.init(this@KupuApp)
            } catch (_: Exception) {
            }
        }
    }
}
