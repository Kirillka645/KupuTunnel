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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConfigLoadingActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLive: TextView
    private lateinit var progressLinear: LinearProgressIndicator
    private lateinit var progressCircular: CircularProgressIndicator
    private lateinit var btnCancel: MaterialButton

    private var job: Job? = null
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
            } catch (e: TimeoutCancellationException) {
                Toast.makeText(this@ConfigLoadingActivity, "Таймаут загрузки", Toast.LENGTH_LONG)
                    .show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ConfigLoadingActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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

        // Общий лимит: не висим больше 45с на fetch
        val raw: List<String> = withTimeout(45_000L) {
            when (mode) {
                MainActivity.MODE_SOURCE -> {
                    tvStatus.text = "Источник: $sourceId"
                    withTimeout(20_000L) {
                        ConfigManager.fetchSourceById(sourceId ?: "", this@ConfigLoadingActivity)
                    }
                }
                MainActivity.MODE_OFFLINE -> offline ?: emptyList()
                else -> {
                    val result = withTimeout(40_000L) {
                        ConfigManager.fetchAllSources(this@ConfigLoadingActivity) { idx, total, name, count ->
                            // уже на Main из ConfigManager
                            tvStatus.text = "[$idx/$total] $name → $count"
                        }
                    }
                    tvStatus.text = buildString {
                        append("Собрано ${result.configs.size}")
                        if (result.fromCache) append(" · +кэш")
                        if (result.fromSeed) append(" · +seed")
                    }
                    result.configs
                }
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

        // Пинг: лимит 60с
        val working = withTimeout(60_000L) {
            ConfigManager.checkConfigsParallel(
                configs = toCheck,
                settings = settings,
                profileLabel = settings.label,
                onProgress = { processed, total, alive ->
                    val pct = if (total > 0) (processed * 100 / total) else 0
                    progressLinear.progress = pct
                    tvStatus.text = "Проверено $processed / $total"
                    tvLive.text = "Живых: $alive"
                }
            )
        }

        val profileForCache =
            if (settings.mode == NetworkProfileMode.MOBILE) NetworkProfileMode.MOBILE
            else NetworkProfileMode.WIFI
        if (working.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                ConfigCache.saveWorking(this@ConfigLoadingActivity, profileForCache, working)
            }
        }

        if (working.isEmpty()) {
            Toast.makeText(
                this,
                "Живых узлов не найдено — попробуй другой источник",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        startActivity(
            Intent(this, ConfigListActivity::class.java).apply {
                putExtra(
                    MainActivity.EXTRA_SOURCE_NAME,
                    intent.getStringExtra(MainActivity.EXTRA_SOURCE_NAME)
                )
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
