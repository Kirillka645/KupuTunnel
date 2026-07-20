package com.kuputunnel.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kuputunnel.app.updater.ApkDownloader
import com.kuputunnel.app.updater.GitHubRelease
import com.kuputunnel.app.updater.UpdateChecker
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : AppCompatActivity() {

    private lateinit var btnHelp: MaterialButton
    private lateinit var btnTheme: MaterialButton
    private lateinit var btnBestConnect: MaterialButton
    private lateinit var btnMegaScan: MaterialButton
    private lateinit var btnLastWifi: MaterialButton
    private lateinit var btnLastMobile: MaterialButton
    private lateinit var btnFavorites: MaterialButton
    private lateinit var btnOfflineSeed: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvNetworkNow: TextView
    private lateinit var tvProfileHint: TextView
    private lateinit var profileToggle: MaterialButtonToggleGroup

    private val client = OkHttpClient()
    private lateinit var updateChecker: UpdateChecker
    private lateinit var apkDownloader: ApkDownloader
    private var pendingUpdate: GitHubRelease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateChecker = UpdateChecker(this, client)
        apkDownloader = ApkDownloader(this)
        initViews()
        bindSourceCards()
        setupProfileToggle()
        setupClickListeners()
        setupVersion()
        refreshNetworkLabel()
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkLabel()
        updateOfflineButtons()
        pendingUpdate?.let { release ->
            if (apkDownloader.canInstallPackages()) {
                val r = release
                pendingUpdate = null
                startApkDownloadAndInstall(r)
            }
        }
        // keep button labels fresh after scans
    }

    private fun applySavedTheme() {
        val themeMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, 0)
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun initViews() {
        btnHelp = findViewById(R.id.btnHelp)
        btnTheme = findViewById(R.id.btnTheme)
        btnBestConnect = findViewById(R.id.btnBestConnect)
        btnMegaScan = findViewById(R.id.btnMegaScan)
        btnLastWifi = findViewById(R.id.btnLastWifi)
        btnLastMobile = findViewById(R.id.btnLastMobile)
        btnFavorites = findViewById(R.id.btnFavorites)
        btnOfflineSeed = findViewById(R.id.btnOfflineSeed)
        tvStatus = findViewById(R.id.statusText)
        tvVersion = findViewById(R.id.tvVersion)
        tvNetworkNow = findViewById(R.id.tvNetworkNow)
        tvProfileHint = findViewById(R.id.tvProfileHint)
        profileToggle = findViewById(R.id.profileToggle)
    }

    private fun bindSourceCards() {
        fun card(id: Int, title: String, desc: String, sourceId: String) {
            val root = findViewById<View>(id)
            root.findViewById<TextView>(R.id.tvSourceTitle).text = title
            root.findViewById<TextView>(R.id.tvSourceDesc).text = desc
            root.setOnClickListener {
                startScan(MODE_SOURCE, title, sourceId)
            }
        }
        card(R.id.cardRuMobile, "🇷🇺 RU Mobile", "White lists · Reality · igareck", "igareck_mobile")
        card(R.id.cardVans, "🦊 vansFenix", "WVFMINI · @wildVF", "vansfenix")
        card(R.id.cardMatin, "📦 Matin VLESS", "Filtered subscription", "matin_vless")
        card(R.id.cardEbrasha, "🔥 EbraSha", "Auto public VLESS", "ebrasha_vless")
        card(R.id.cardTgparse, "📡 TGParse", "Telegram channels", "tgparse_mixed")
        card(R.id.cardRadikal, "⚡ 0xRadikal", "Every 30 minutes", "radikal_vless")
    }

    private fun setupVersion() {
        tvVersion.text = try {
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) {
            "v1.0.0"
        }
    }

    private fun setupProfileToggle() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getInt(KEY_PROFILE, 0)
        profileToggle.check(
            when (saved) {
                1 -> R.id.chipWifi
                2 -> R.id.chipMobile
                else -> R.id.chipAuto
            }
        )
        updateProfileHint()

        profileToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.chipWifi -> 1
                R.id.chipMobile -> 2
                else -> 0
            }
            prefs.edit().putInt(KEY_PROFILE, mode).apply()
            updateProfileHint()
            refreshNetworkLabel()
        }
    }

    private fun currentProfileMode(): NetworkProfileMode {
        return when (getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PROFILE, 0)) {
            1 -> NetworkProfileMode.WIFI
            2 -> NetworkProfileMode.MOBILE
            else -> NetworkProfileMode.AUTO
        }
    }

    private fun updateProfileHint() {
        val settings = ProfileSettings.forMode(currentProfileMode(), this)
        tvProfileHint.text = when (settings.mode) {
            NetworkProfileMode.MOBILE ->
                "LTE: до ${settings.maxToCheck} · ${settings.batchSize} параллельно · стоп на ${settings.stopWhenFound}"
            else ->
                "Wi‑Fi: до ${settings.maxToCheck} · ${settings.batchSize} параллельно · стоп на ${settings.stopWhenFound}"
        }
    }

    private fun refreshNetworkLabel() {
        tvNetworkNow.text = ProfileSettings.currentLabel(this) +
            " · профиль: ${ProfileSettings.forMode(currentProfileMode(), this).label}"
    }

    private fun updateOfflineButtons() {
        val wifi = ConfigCache.loadWorking(this, NetworkProfileMode.WIFI).size
        val mobile = ConfigCache.loadWorking(this, NetworkProfileMode.MOBILE).size
        val fav = ConfigCache.getFavorites(this).size
        val seed = ConfigCache.loadSeedFromAssets(this).size
        val cache = ConfigCache.loadRawList(this).size

        btnLastWifi.text = if (wifi > 0) "📶 Последние Wi‑Fi ($wifi)" else "📶 Последние Wi‑Fi (пусто)"
        btnLastMobile.text = if (mobile > 0) "📱 Последние LTE ($mobile)" else "📱 Последние LTE (пусто)"
        btnFavorites.text = if (fav > 0) "⭐ Избранное ($fav)" else "⭐ Избранное"
        btnOfflineSeed.text = "📦 Seed (~$seed) · кэш $cache"
    }

    private fun setupClickListeners() {
        btnBestConnect.setOnClickListener {
            startScan(MODE_BEST, "Лучший VPN")
        }
        btnMegaScan.setOnClickListener {
            startScan(MODE_MEGA, "Мега-скан")
        }

        btnLastWifi.setOnClickListener {
            openCachedList(NetworkProfileMode.WIFI, "Последние Wi‑Fi")
        }
        btnLastMobile.setOnClickListener {
            openCachedList(NetworkProfileMode.MOBILE, "Последние LTE")
        }
        btnFavorites.setOnClickListener {
            val favs = ConfigCache.getFavorites(this).toList()
            if (favs.isEmpty()) {
                Toast.makeText(this, "Избранное пусто", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val list = favs.map { url ->
                val node = ConfigManager.parseNode(url)
                ConfigWithPing(
                    url = url,
                    pingMs = 0,
                    protocol = node?.protocol?.uppercase().orEmpty(),
                    remark = node?.remark.orEmpty(),
                    host = node?.host.orEmpty(),
                    port = node?.port ?: 0
                )
            }
            openList(list, "Избранное")
        }
        btnOfflineSeed.setOnClickListener {
            val seed = ConfigCache.loadSeedFromAssets(this)
            val cache = ConfigCache.loadRawList(this)
            val merged = ConfigManager.deduplicate(seed + cache)
            if (merged.isEmpty()) {
                Toast.makeText(this, "Нет seed/кэша — сделай мега-скан", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startScan(MODE_OFFLINE, "Seed / кэш", offline = merged)
        }

        btnHelp.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("KupuTunnel")
                .setMessage(getString(R.string.help_message))
                .setPositiveButton("GitHub") { _, _ ->
                    openUrl("https://github.com/${BuildConfig.GITHUB_REPO}")
                }
                .setNegativeButton("OK", null)
                .show()
        }

        btnTheme.setOnClickListener {
            val options = arrayOf("Системная", "Светлая", "Тёмная")
            MaterialAlertDialogBuilder(this)
                .setTitle("Тема")
                .setItems(options) { _, which ->
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_THEME, which).apply()
                    AppCompatDelegate.setDefaultNightMode(
                        when (which) {
                            1 -> AppCompatDelegate.MODE_NIGHT_NO
                            2 -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                }
                .show()
        }
    }

    private fun startScan(
        mode: String,
        title: String,
        sourceId: String? = null,
        offline: List<String>? = null
    ) {
        val intent = Intent(this, ConfigLoadingActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_SOURCE_NAME, title)
            putExtra(EXTRA_SOURCE_ID, sourceId)
            putExtra(EXTRA_PROFILE, currentProfileMode().name)
            if (offline != null) {
                putStringArrayListExtra(EXTRA_OFFLINE_LIST, ArrayList(offline))
            }
        }
        startActivity(intent)
    }

    private fun openCachedList(profile: NetworkProfileMode, title: String) {
        val list = ConfigCache.loadWorking(this, profile)
        if (list.isEmpty()) {
            Toast.makeText(this, "Пока пусто — запусти скан", Toast.LENGTH_SHORT).show()
            return
        }
        openList(list, title)
    }

    private fun openList(list: List<ConfigWithPing>, title: String) {
        startActivity(
            Intent(this, ConfigListActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_NAME, title)
                putExtra(EXTRA_CONFIGS, ArrayList(list))
            }
        )
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
                val release = updateChecker.checkForUpdate(version) ?: return@launch
                val changelog = release.changelog.ifBlank { "Доступна новая версия KupuTunnel" }
                val hasApk = release.apkUrl.isNotBlank()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Обновление ${release.tagName}")
                    .setMessage(changelog.take(800))
                    .setPositiveButton(if (hasApk) "Скачать" else "GitHub") { _, _ ->
                        if (hasApk) {
                            if (!apkDownloader.canInstallPackages()) {
                                pendingUpdate = release
                                Toast.makeText(
                                    this@MainActivity,
                                    "Разрешите установку из этого источника",
                                    Toast.LENGTH_LONG
                                ).show()
                                apkDownloader.openInstallPermissionSettings(this@MainActivity)
                            } else {
                                startApkDownloadAndInstall(release)
                            }
                        } else {
                            updateChecker.openReleasePage(release.htmlUrl)
                        }
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            } catch (_: Exception) {
            }
        }
    }

    private fun startApkDownloadAndInstall(release: GitHubRelease) {
        val indicator = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val statusTv = TextView(this).apply {
            text = "Скачивание APK…"
            setPadding(48, 16, 48, 8)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(statusTv)
            addView(indicator)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Обновление ${release.tagName}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()

        lifecycleScope.launch {
            try {
                val file = apkDownloader.download(
                    release.apkUrl,
                    "KupuTunnel-${release.tagName}.apk"
                ) { pct ->
                    indicator.progress = pct
                    statusTv.text = "Скачивание… $pct%"
                }
                dialog.dismiss()
                if (!apkDownloader.canInstallPackages()) {
                    pendingUpdate = release
                    apkDownloader.openInstallPermissionSettings(this@MainActivity)
                    return@launch
                }
                apkDownloader.installApk(this@MainActivity, file)
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val PREFS = "kuputunnel"
        const val KEY_THEME = "theme"
        const val KEY_PROFILE = "profile"

        const val EXTRA_MODE = "mode"
        const val EXTRA_SOURCE_NAME = "source_name"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_CONFIGS = "configs"
        const val EXTRA_OFFLINE_LIST = "offline_list"
        const val EXTRA_AUTO_CONNECT = "auto_connect"

        const val MODE_MEGA = "mega"
        const val MODE_SOURCE = "source"
        const val MODE_BEST = "best"
        const val MODE_OFFLINE = "offline"
    }
}
