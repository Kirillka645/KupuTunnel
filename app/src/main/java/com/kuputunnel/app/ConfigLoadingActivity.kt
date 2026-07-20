package com.kuputunnel.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ConfigLoadingActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLive: TextView
    private lateinit var progressLinear: LinearProgressIndicator
    private lateinit var progressCircular: CircularProgressIndicator
    private lateinit var btnCancel: MaterialButton

    private var job: Job? = null
    private val liveList = mutableListOf<ConfigWithPing>()
    private var autoConnectBest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_loading)

        tvTitle = findViewById(R.id.tvLoadingTitle)
        tvStatus = findViewById(R.id.tvLoadingStatus)
        tvLive = findViewById(R.id.tvLiveCount)
        progressLinear = findViewById(R.id.progressLinear)
        progressCircular = findViewById(R.id.progressCircular)
        btnCancel = findViewById(R.id.btnCancel)

        val mode = intent.getStringExtra(MainActivity.EXTRA_MODE) ?: MainActivity.MODE_MEGA
        val title = intent.getStringExtra(MainActivity.EXTRA_SOURCE_NAME) ?: "Загрузка"
        val sourceId = intent.getStringExtra(MainActivity.EXTRA_SOURCE_ID)
        val profileName = intent.getStringExtra(MainActivity.EXTRA_PROFILE)
            ?: NetworkProfileMode.AUTO.name
        val profileMode = try {
            NetworkProfileMode.valueOf(profileName)
        } catch (_: Exception) {
            NetworkProfileMode.AUTO
        }
        val offline = intent.getStringArrayListExtra(MainActivity.EXTRA_OFFLINE_LIST)

        autoConnectBest = mode == MainActivity.MODE_BEST
        tvTitle.text = title
        btnCancel.setOnClickListener {
            job?.cancel()
            finish()
        }

        job = lifecycleScope.launch {
            try {
                runPipeline(mode, sourceId, profileMode, offline)
            } catch (_: CancellationException) {
                // cancelled
            } catch (e: Exception) {
                Toast.makeText(this@ConfigLoadingActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }
    }

    private suspend fun runPipeline(
        mode: String,
        sourceId: String?,
        profileMode: NetworkProfileMode,
        offline: ArrayList<String>?
    ) {
        val settings = ProfileSettings.forMode(profileMode, this)
        progressLinear.isIndeterminate = true
        tvStatus.text = "Загрузка парсеров…"

        val raw: List<String> = when (mode) {
            MainActivity.MODE_SOURCE -> {
                tvStatus.text = "Источник: $sourceId"
                ConfigManager.fetchSourceById(sourceId ?: "", this)
            }
            MainActivity.MODE_OFFLINE -> offline ?: emptyList()
            else -> {
                // mega + best
                val result = ConfigManager.fetchAllSources(this) { idx, total, name, count ->
                    tvStatus.text = "[$idx/$total] $name → $count"
                }
                val hint = buildString {
                    append("Собрано ${result.configs.size}")
                    if (result.fromCache) append(" · +кэш")
                    if (result.fromSeed) append(" · +seed")
                }
                tvStatus.text = hint
                result.configs
            }
        }

        if (raw.isEmpty()) {
            Toast.makeText(this, "Конфиги не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val toCheck = ConfigManager.prepareForProfile(raw, settings)
        tvTitle.text = getString(R.string.checking_title)
        tvStatus.text = "TCP-пинг ${toCheck.size} узлов…"
        progressLinear.isIndeterminate = false
        progressLinear.max = 100
        progressLinear.progress = 0

        liveList.clear()
        val working = ConfigManager.checkConfigsParallel(
            configs = toCheck,
            settings = settings,
            profileLabel = settings.label,
            onProgress = { processed, total, alive ->
                val pct = if (total > 0) (processed * 100 / total) else 0
                progressLinear.progress = pct
                tvStatus.text = "Проверено $processed / $total"
                tvLive.text = "Живых: $alive"
            },
            onFound = { item ->
                liveList.add(item)
                liveList.sortBy { it.pingMs }
            }
        )

        val profileForCache =
            if (settings.mode == NetworkProfileMode.MOBILE) NetworkProfileMode.MOBILE
            else NetworkProfileMode.WIFI
        if (working.isNotEmpty()) {
            ConfigCache.saveWorking(this, profileForCache, working)
        }

        if (working.isEmpty()) {
            Toast.makeText(this, "Живых узлов не найдено — попробуй Wi‑Fi / другой источник", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        // Список сначала — VPN permission удобнее из Activity с launcher
        startActivity(
            Intent(this, ConfigListActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SOURCE_NAME, intent.getStringExtra(MainActivity.EXTRA_SOURCE_NAME))
                putExtra(MainActivity.EXTRA_CONFIGS, ArrayList(working))
                putExtra(MainActivity.EXTRA_AUTO_CONNECT, autoConnectBest)
            }
        )
        finish()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
