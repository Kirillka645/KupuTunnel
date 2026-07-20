package com.kuputunnel.app

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
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
import java.io.File

/**
 * Скан/пинг. Результаты ТОЛЬКО через ScanResultStore (диск),
 * иначе TransactionTooLarge → краш → стартовый экран.
 */
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var finishedOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_loading)

        // Не гасим экран и не убиваем процесс при «idle»
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        acquireScanWake()

        job = lifecycleScope.launch {
            try {
                runPipeline(mode, sourceId, profileMode, offline)
            } catch (_: CancellationException) {
                // cancel
            } catch (_: TimeoutCancellationException) {
                Toast.makeText(
                    this@ConfigLoadingActivity,
                    "Таймаут — открою что успели найти",
                    Toast.LENGTH_LONG
                ).show()
                // если уже что-то в store — покажем
                openListSafe()
            } catch (oom: OutOfMemoryError) {
                System.gc()
                logCrash("OOM: ${oom.message}")
                Toast.makeText(
                    this@ConfigLoadingActivity,
                    "Мало памяти — уменьши скан или перезапусти",
                    Toast.LENGTH_LONG
                ).show()
                openListSafe()
            } catch (e: Exception) {
                logCrash(e.stackTraceToString())
                Toast.makeText(
                    this@ConfigLoadingActivity,
                    "Ошибка: ${e.message?.take(80)}",
                    Toast.LENGTH_LONG
                ).show()
                openListSafe()
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

        val raw: List<String> = withTimeout(40_000L) {
            when (mode) {
                MainActivity.MODE_SOURCE -> withTimeout(18_000L) {
                    ConfigManager.fetchSourceById(sourceId ?: "", this@ConfigLoadingActivity)
                }
                MainActivity.MODE_OFFLINE -> offline ?: emptyList()
                else -> {
                    val result = withTimeout(35_000L) {
                        ConfigManager.fetchAllSources(this@ConfigLoadingActivity) { idx, total, name, count ->
                            if (!isFinishing) {
                                tvStatus.text = "[$idx/$total] $name · $count"
                            }
                        }
                    }
                    tvStatus.text = "Собрано ${result.configs.size}"
                    // не держим FetchResult
                    result.configs
                }
            }
        }

        if (raw.isEmpty()) {
            Toast.makeText(this, "Конфиги не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Жёсткий лимит — меньше RAM, без краша
        val toCheck = ConfigManager.prepareForProfile(raw, settings).take(settings.maxToCheck)
        // raw больше не нужен — помогаем GC
        @Suppress("UNUSED_VALUE")
        tvTitle.text = getString(R.string.checking_title)
        tvStatus.text = "Пинг ${toCheck.size} узлов…"
        progressLinear.isIndeterminate = false
        progressLinear.max = 100
        progressLinear.progress = 0

        val working = withTimeout(55_000L) {
            ConfigManager.checkConfigsParallel(
                configs = toCheck,
                settings = settings,
                profileLabel = settings.label,
                onProgress = { processed, total, alive ->
                    if (isFinishing) return@checkConfigsParallel
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

        val top = working.sortedBy { it.pingMs }.take(ScanResultStore.MAX_ITEMS)
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

        finishedOk = true
        openListSafe()
    }

    private fun openListSafe() {
        if (isFinishing) return
        try {
            val has = ScanResultStore.load(this).isNotEmpty() ||
                ScanResultStore.memory.isNotEmpty()
            if (!has && !finishedOk) {
                // нечего показывать — просто назад
                if (!isFinishing) finish()
                return
            }
            val i = Intent(this, ConfigListActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SOURCE_NAME, title)
                putExtra(MainActivity.EXTRA_LOAD_FROM_STORE, true)
                // Явно: не тащим список в extras
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
        } catch (e: Exception) {
            logCrash("openList: ${e.message}")
            Toast.makeText(this, "Не удалось открыть список", Toast.LENGTH_LONG).show()
        } finally {
            if (!isFinishing) finish()
        }
    }

    private fun acquireScanWake() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kuputunnel:scan").apply {
                setReferenceCounted(false)
                acquire(90_000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseScanWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun logCrash(text: String) {
        try {
            File(filesDir, "last_scan_error.txt").writeText(text.take(8000))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        job?.cancel()
        releaseScanWake()
        super.onDestroy()
    }
}
