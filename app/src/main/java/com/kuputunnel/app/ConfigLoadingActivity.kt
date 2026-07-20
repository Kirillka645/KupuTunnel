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
    private var title: String = "Скан"

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
        title = intent.getStringExtra(MainActivity.EXTRA_SOURCE_NAME) ?: "Загрузка"
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
            } catch (_: TimeoutCancellationException) {
                Toast.makeText(this@ConfigLoadingActivity, "Таймаут — попробуй снова", Toast.LENGTH_LONG)
                    .show()
                openListOrHome(emptyList())
            } catch (e: Exception) {
                Toast.makeText(
                    this@ConfigLoadingActivity,
                    "Ошибка: ${e.message?.take(80)}",
                    Toast.LENGTH_LONG
                ).show()
                openListOrHome(emptyList())
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
        tvStatus.text = "Загрузка…"

        val raw: List<String> = withTimeout(35_000L) {
            when (mode) {
                MainActivity.MODE_SOURCE -> withTimeout(15_000L) {
                    ConfigManager.fetchSourceById(sourceId ?: "", this@ConfigLoadingActivity)
                }
                MainActivity.MODE_OFFLINE -> offline ?: emptyList()
                else -> {
                    val result = withTimeout(30_000L) {
                        ConfigManager.fetchAllSources(this@ConfigLoadingActivity) { idx, total, name, count ->
                            tvStatus.text = "[$idx/$total] $name · $count"
                        }
                    }
                    tvStatus.text = "Собрано ${result.configs.size}"
                    result.configs
                }
            }
        }

        if (raw.isEmpty()) {
            Toast.makeText(this, "Конфиги не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Жёсткий лимит — меньше RAM, быстрее, без краша
        val toCheck = ConfigManager.prepareForProfile(raw, settings).take(settings.maxToCheck)
        tvTitle.text = getString(R.string.checking_title)
        tvStatus.text = "Пинг ${toCheck.size} узлов…"
        progressLinear.isIndeterminate = false
        progressLinear.max = 100
        progressLinear.progress = 0

        val working = withTimeout(50_000L) {
            ConfigManager.checkConfigsParallel(
                configs = toCheck,
                settings = settings,
                profileLabel = settings.label,
                onProgress = { processed, total, alive ->
                    val pct = if (total > 0) (processed * 100 / total) else 0
                    progressLinear.progress = pct
                    tvStatus.text = "$processed / $total"
                    tvLive.text = "Живых: $alive"
                }
            )
        }

        val profileForCache =
            if (settings.mode == NetworkProfileMode.MOBILE) NetworkProfileMode.MOBILE
            else NetworkProfileMode.WIFI

        // Только топ-80 — в файл, НЕ в Intent
        val top = working.sortedBy { it.pingMs }.take(80)
        withContext(Dispatchers.IO) {
            if (top.isNotEmpty()) {
                ConfigCache.saveWorking(this@ConfigLoadingActivity, profileForCache, top)
            }
            ScanResultStore.save(
                this@ConfigLoadingActivity,
                top,
                title,
                autoConnect = autoConnectBest && top.isNotEmpty()
            )
        }

        if (top.isEmpty()) {
            Toast.makeText(this, "Живых узлов нет", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        openListOrHome(top)
    }

    private fun openListOrHome(list: List<ConfigWithPing>) {
        try {
            // Только флаги — список уже в ScanResultStore
            startActivity(
                Intent(this, ConfigListActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_SOURCE_NAME, title)
                    putExtra(MainActivity.EXTRA_LOAD_FROM_STORE, true)
                    // НЕ кладём ArrayList конфигов — TransactionTooLargeException
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть список: ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
