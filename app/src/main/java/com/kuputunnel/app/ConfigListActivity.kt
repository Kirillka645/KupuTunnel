package com.kuputunnel.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.kuputunnel.app.vpn.VpnSession

class ConfigListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fabBest: ExtendedFloatingActionButton
    private lateinit var tvSubtitle: TextView

    private var configsList: List<ConfigWithPing> = emptyList()
    private var filteredList: List<ConfigWithPing> = emptyList()
    private var sourceName: String = ""
    private var maxPingFilter = Int.MAX_VALUE
    private var autoConnectDone = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ConfigLauncher.onVpnPermissionResult(this, result.resultCode == RESULT_OK)
        refreshFab()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_list)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Всегда с диска/памяти — НЕ из Intent extras (TransactionTooLarge)
        sourceName = intent.getStringExtra(MainActivity.EXTRA_SOURCE_NAME)
            ?: ScanResultStore.title
        supportActionBar?.title = sourceName

        configsList = try {
            ScanResultStore.load(this)
        } catch (e: Exception) {
            emptyList()
        }

        // fallback: кэш Wi‑Fi/LTE
        if (configsList.isEmpty()) {
            try {
                configsList = ConfigCache.loadWorking(this, NetworkProfileMode.WIFI)
                if (configsList.isEmpty()) {
                    configsList = ConfigCache.loadWorking(this, NetworkProfileMode.MOBILE)
                }
            } catch (_: Exception) {
                configsList = emptyList()
            }
        }

        // жёсткий cap — UI не взрывается
        if (configsList.size > 80) {
            configsList = configsList.take(80)
        }

        filteredList = configsList
        tvSubtitle = findViewById(R.id.tvListSubtitle)
        updateSubtitle()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(12)
        recyclerView.adapter = ConfigAdapter(this, filteredList)

        fabBest = findViewById(R.id.fabBest)
        fabBest.setOnClickListener {
            if (VpnSession.isConnected()) {
                ConfigLauncher.disconnect(this)
            } else {
                connectBest()
            }
            refreshFab()
        }
        refreshFab()
        setupToolbarMenu()

        // Авто-connect только один раз
        val wantAuto = ScanResultStore.consumeAutoConnect() ||
            intent.getBooleanExtra(MainActivity.EXTRA_AUTO_CONNECT, false)
        if (wantAuto && configsList.isNotEmpty() && !autoConnectDone) {
            autoConnectDone = true
            recyclerView.post { connectBest() }
        }

        if (configsList.isEmpty()) {
            Toast.makeText(this, "Список пуст — запусти скан", Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    private fun updateSubtitle() {
        val list = filteredList
        val withPing = list.filter { it.pingMs > 0 }
        val avg = if (withPing.isNotEmpty()) withPing.map { it.pingMs }.average().toInt() else 0
        val best = withPing.minByOrNull { it.pingMs }
        tvSubtitle.text = buildString {
            append("${list.size} узлов")
            if (avg > 0) append(" · ср. $avg ms")
            if (best != null) append(" · лучший ${best.pingMs} ms")
            if (maxPingFilter < Int.MAX_VALUE) append(" · ≤ ${maxPingFilter}ms")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFab()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ConfigLauncher.REQ_VPN_PERMISSION) {
            ConfigLauncher.onVpnPermissionResult(this, resultCode == RESULT_OK)
            refreshFab()
        }
    }

    private fun refreshFab() {
        fabBest.text = if (VpnSession.isConnected()) "Отключить VPN" else "⚡ Подключить лучший"
    }

    private fun connectBest() {
        val best = filteredList.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }
            ?: filteredList.firstOrNull()
        if (best == null) {
            Toast.makeText(this, R.string.no_configs, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            "VPN → ${best.host}:${best.port} · ${best.pingMs} ms",
            Toast.LENGTH_SHORT
        ).show()
        val prep = android.net.VpnService.prepare(this)
        if (prep != null) {
            VpnSession.pendingConfig = best.url
            vpnPermissionLauncher.launch(prep)
        } else {
            ConfigLauncher.launch(this, best.url)
        }
        refreshFab()
    }

    private fun setupToolbarMenu() {
        toolbar.inflateMenu(R.menu.config_list_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("KupuTunnel v${BuildConfig.VERSION_NAME}")
                        .setMessage("Системный VPN · Xray\nVLESS Reality / VMess / Trojan / SS / Socks")
                        .setPositiveButton("GitHub") { _, _ ->
                            try {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}")
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                        .setNegativeButton("OK", null)
                        .show()
                    true
                }
                R.id.action_filter -> {
                    val options = arrayOf("Все", "≤ 100 ms", "≤ 200 ms", "≤ 300 ms", "≤ 500 ms")
                    val values = intArrayOf(Int.MAX_VALUE, 100, 200, 300, 500)
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Фильтр по пингу")
                        .setItems(options) { _, which ->
                            maxPingFilter = values[which]
                            filteredList = if (maxPingFilter == Int.MAX_VALUE) configsList
                            else configsList.filter { it.pingMs in 1..maxPingFilter }
                            recyclerView.adapter = ConfigAdapter(this, filteredList)
                            updateSubtitle()
                        }
                        .show()
                    true
                }
                R.id.action_copy_all -> {
                    if (filteredList.isEmpty()) {
                        Toast.makeText(this, R.string.no_configs, Toast.LENGTH_SHORT).show()
                    } else {
                        ConfigLauncher.copy(
                            this,
                            filteredList.take(30).joinToString("\n") { it.url }
                        )
                        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_share -> {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    filteredList.take(20).joinToString("\n") { it.url }
                                )
                            },
                            "Поделиться"
                        )
                    )
                    true
                }
                else -> false
            }
        }
    }
}
