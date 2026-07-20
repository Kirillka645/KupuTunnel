package com.kuputunnel.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class ConfigListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fabBest: ExtendedFloatingActionButton
    private lateinit var tvSubtitle: TextView

    private var configsList: List<ConfigWithPing> = emptyList()
    private var filteredList: List<ConfigWithPing> = emptyList()
    private var sourceName: String = ""
    private var maxPingFilter = Int.MAX_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_list)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        sourceName = intent.getStringExtra(MainActivity.EXTRA_SOURCE_NAME) ?: "Узлы"
        supportActionBar?.title = sourceName

        @Suppress("UNCHECKED_CAST")
        configsList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(MainActivity.EXTRA_CONFIGS, ArrayList::class.java)
                as? List<ConfigWithPing> ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(MainActivity.EXTRA_CONFIGS) as? ArrayList<ConfigWithPing>
                ?: emptyList()
        }

        filteredList = configsList
        tvSubtitle = findViewById(R.id.tvListSubtitle)
        updateSubtitle()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ConfigAdapter(this, filteredList)

        fabBest = findViewById(R.id.fabBest)
        fabBest.setOnClickListener { connectBest() }

        setupToolbarMenu()

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

    private fun setupToolbarMenu() {
        toolbar.inflateMenu(R.menu.config_list_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                R.id.action_copy_all -> {
                    copyAll()
                    true
                }
                R.id.action_filter -> {
                    showFilterDialog()
                    true
                }
                R.id.action_share -> {
                    shareList()
                    true
                }
                else -> false
            }
        }
    }

    private fun connectBest() {
        val best = filteredList.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }
            ?: filteredList.firstOrNull()
        if (best == null) {
            Toast.makeText(this, R.string.no_configs, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "→ ${best.host}:${best.port} · ${best.pingMs} ms", Toast.LENGTH_SHORT)
            .show()
        ConfigLauncher.launch(this, best.url)
    }

    private fun showFilterDialog() {
        val options = arrayOf("Все", "≤ 100 ms", "≤ 200 ms", "≤ 300 ms", "≤ 500 ms")
        val values = intArrayOf(Int.MAX_VALUE, 100, 200, 300, 500)
        MaterialAlertDialogBuilder(this)
            .setTitle("Фильтр по пингу")
            .setItems(options) { _, which ->
                maxPingFilter = values[which]
                filteredList = if (maxPingFilter == Int.MAX_VALUE) {
                    configsList
                } else {
                    configsList.filter { it.pingMs in 1..maxPingFilter }
                }
                recyclerView.adapter = ConfigAdapter(this, filteredList)
                updateSubtitle()
            }
            .show()
    }

    private fun shareList() {
        val text = formatWithFooter(filteredList.take(50))
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Поделиться конфигами"
            )
        )
    }

    private fun copyAll() {
        if (filteredList.isEmpty()) {
            Toast.makeText(this, R.string.no_configs, Toast.LENGTH_SHORT).show()
            return
        }
        ConfigLauncher.copy(this, formatWithFooter(filteredList))
        Toast.makeText(this, "Скопировано ${filteredList.size}", Toast.LENGTH_SHORT).show()
    }

    private fun formatWithFooter(configs: List<ConfigWithPing>): String {
        val body = configs.mapIndexed { i, c ->
            if (c.pingMs > 0) "${i + 1}. ${c.url}  (${c.pingMs}ms)"
            else "${i + 1}. ${c.url}"
        }.joinToString("\n")
        return "$body\n\nKupuTunnel — https://github.com/${BuildConfig.GITHUB_REPO}"
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("KupuTunnel v${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Радар бесплатных VPN-нод.\n" +
                    "VLESS / Trojan / Hy2 / VMess / SS · TCP-пинг · one-tap best.\n\n" +
                    "https://github.com/${BuildConfig.GITHUB_REPO}"
            )
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
    }
}
