package com.kuputunnel.app

import android.content.Context
import android.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object ConfigManager {

    private const val MAX_CONFIGS = 3_000

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    data class Source(
        val id: String,
        val name: String,
        val description: String,
        val urls: List<String>,
        val region: String = "ALL"
    )

    /** 2–3 быстрых зеркала, без длинной цепочки (не зависаем). */
    private fun ghMirrors(owner: String, repo: String, branch: String, path: String): List<String> {
        val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            raw
        )
    }

    val SOURCES: List<Source> = listOf(
        Source(
            id = "igareck_mobile",
            name = "RU Mobile White Lists",
            description = "VLESS Reality · mobile CIDR · igareck",
            region = "RU",
            urls = ghMirrors(
                "igareck", "vpn-configs-for-russia", "main",
                "Vless-Reality-White-Lists-Rus-Mobile.txt"
            )
        ),
        Source(
            id = "vansfenix",
            name = "vansFenix WVFMINI",
            description = "Сборка @wildVF · VLESS Reality",
            region = "ALL",
            urls = listOf(
                "https://gitverse.ru/api/repos/vansfenix/vansFenix/raw/branch/master/WVFMINI",
                "https://gitverse.ru/vansfenix/vansFenix/raw/branch/master/WVFMINI",
                "https://gitverse.ru/api/v1/repos/vansfenix/vansFenix/raw/WVFMINI?ref=master"
            )
        ),
        Source(
            id = "matin_vless",
            name = "MatinGhanbari VLESS",
            description = "Filtered VLESS subscription",
            urls = ghMirrors(
                "MatinGhanbari", "v2ray-configs", "main",
                "subscriptions/filtered/subs/vless.txt"
            )
        ),
        Source(
            id = "ebrasha_vless",
            name = "EbraSha VLESS",
            description = "Auto-update public list",
            urls = ghMirrors("ebrasha", "free-v2ray-public-list", "main", "vless_configs.txt")
        ),
        Source(
            id = "tgparse_vless",
            name = "TGParse VLESS",
            description = "VLESS from TG channels",
            urls = ghMirrors("Surfboardv2ray", "TGParse", "main", "splitted/vless")
        ),
        Source(
            id = "radikal_vless",
            name = "0xRadikal VLESS",
            description = "Auto every 30m",
            urls = ghMirrors("0xRadikal", "Free-v2ray-Configs", "main", "protocols/vless.txt")
        )
    )

    private val LINK_REGEX = Regex(
        """(?:vless|vmess|trojan|ss|ssr|socks5?|hysteria2|hy2|tuic|wireguard)://[^\s<>"'`)\]#,]+""",
        RegexOption.IGNORE_CASE
    )

    suspend fun fetchSource(source: Source): Pair<List<String>, String?> =
        withContext(Dispatchers.IO) {
            for (url in source.urls) {
                try {
                    val body = downloadText(url) ?: continue
                    val parsed = parseConfigLinks(body)
                    if (parsed.isNotEmpty()) {
                        return@withContext parsed to url
                    }
                } catch (_: Exception) {
                }
            }
            emptyList<String>() to null
        }

    /**
     * Параллельная загрузка источников (до 4 сразу) — без зависаний UI.
     */
    suspend fun fetchAllSources(
        context: Context? = null,
        onProgress: (sourceIndex: Int, total: Int, name: String, count: Int) -> Unit = { _, _, _, _ -> }
    ): FetchResult = withContext(Dispatchers.IO) {
        val all = LinkedHashSet<String>()
        val hits = linkedMapOf<String, Int>()
        val mirrors = mutableListOf<String>()
        val mutex = Mutex()
        var done = 0
        val total = SOURCES.size
        val gate = Semaphore(4)

        val jobs = SOURCES.map { source ->
            async {
                gate.withPermit {
                    val (list, mirror) = try {
                        fetchSource(source)
                    } catch (_: Exception) {
                        emptyList<String>() to null
                    }
                    mutex.withLock {
                        hits[source.name] = list.size
                        all.addAll(list)
                        if (mirror != null) mirrors.add("${source.name} ← $mirror")
                        done++
                        val d = done
                        withContext(Dispatchers.Main) {
                            onProgress(d, total, source.name, list.size)
                        }
                    }
                }
            }
        }
        jobs.awaitAll()

        var fromCache = false
        var fromSeed = false

        if (all.size < 20 && context != null) {
            val cached = ConfigCache.loadRawList(context)
            if (cached.isNotEmpty()) {
                all.addAll(cached)
                fromCache = true
            }
        }
        if (all.size < 20 && context != null) {
            val seed = ConfigCache.loadSeedFromAssets(context)
            if (seed.isNotEmpty()) {
                all.addAll(seed)
                fromSeed = true
            }
        }

        val unique = deduplicate(all.toList())
        if (context != null && unique.isNotEmpty()) {
            ConfigCache.saveRawList(context, unique)
        }

        FetchResult(
            configs = unique,
            sourceHits = hits,
            usedMirrors = mirrors,
            fromCache = fromCache,
            fromSeed = fromSeed
        )
    }

    suspend fun fetchSourceById(sourceId: String, context: Context? = null): List<String> {
        val source = SOURCES.find { it.id == sourceId } ?: return emptyList()
        val (list, _) = fetchSource(source)
        if (list.isNotEmpty()) {
            context?.let { ConfigCache.saveRawList(it, list) }
            return deduplicate(list)
        }
        context?.let {
            val cache = ConfigCache.loadRawList(it)
            if (cache.isNotEmpty()) return cache
            val seed = ConfigCache.loadSeedFromAssets(it)
            if (seed.isNotEmpty()) return seed
        }
        return emptyList()
    }

    private fun downloadText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "KupuTunnel/1.0 Android")
            .header("Accept", "text/plain,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    fun parseConfigLinks(body: String): List<String> {
        val result = LinkedHashSet<String>()

        // Plain text links
        LINK_REGEX.findAll(body).forEach { match ->
            val raw = match.value.trim().trimEnd(')', ']', ',', ';', '"', '\'')
            if (isValidScheme(raw)) result.add(raw)
        }

        body.lineSequence().forEach { line ->
            val t = line.trim()
            if (isValidScheme(t)) {
                result.add(t)
            }
        }

        // Base64 subscription body
        if (result.isEmpty()) {
            decodeBase64Subscription(body)?.let { decoded ->
                LINK_REGEX.findAll(decoded).forEach { match ->
                    val raw = match.value.trim().trimEnd(')', ']', ',', ';', '"', '\'')
                    if (isValidScheme(raw)) result.add(raw)
                }
                decoded.lineSequence().forEach { line ->
                    val t = line.trim()
                    if (isValidScheme(t)) result.add(t)
                }
            }
        }

        return result.toList().take(MAX_CONFIGS)
    }

    private fun isValidScheme(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("vless://") ||
            lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") ||
            lower.startsWith("ss://") ||
            lower.startsWith("ssr://") ||
            lower.startsWith("socks://") ||
            lower.startsWith("socks5://") ||
            lower.startsWith("hysteria2://") ||
            lower.startsWith("hy2://") ||
            lower.startsWith("tuic://") ||
            lower.startsWith("wireguard://")
    }

    private fun decodeBase64Subscription(body: String): String? {
        val cleaned = body.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.length < 32) return null
        return try {
            val pad = "=".repeat((4 - cleaned.length % 4) % 4)
            val bytes = Base64.decode(cleaned + pad, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                val urlSafe = cleaned.replace('-', '+').replace('_', '/')
                val pad = "=".repeat((4 - urlSafe.length % 4) % 4)
                val bytes = Base64.decode(urlSafe + pad, Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun deduplicate(configs: List<String>): List<String> =
        configs.distinctBy { normalizeKey(it) }

    fun normalizeKey(url: String): String {
        val info = parseNode(url) ?: return url
        return "${info.protocol}|${info.host}|${info.port}"
    }

    fun parseNode(url: String): NodeInfo? {
        return try {
            val scheme = url.substringBefore("://").lowercase()
            val rest = url.substringAfter("://")
            val remark = if ("#" in rest) {
                java.net.URLDecoder.decode(rest.substringAfterLast("#"), "UTF-8")
            } else ""
            val main = rest.substringBefore("#")

            when (scheme) {
                "vmess" -> parseVmess(main, remark)
                "ss", "ssr" -> parseSsLike(scheme, main, remark)
                else -> {
                    // user@host:port or host:port
                    val authority = main.substringBefore("?")
                    val hostPort = if ("@" in authority) authority.substringAfter("@") else authority
                    val host = hostPort.substringBeforeLast(":").trim().trim('[', ']')
                    val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 0
                    if (host.isBlank() || port !in 1..65535) null
                    else NodeInfo(scheme, host, port, remark)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmess(b64: String, remarkFallback: String): NodeInfo? {
        return try {
            val pad = "=".repeat((4 - b64.length % 4) % 4)
            val json = String(Base64.decode(b64 + pad, Base64.DEFAULT), Charsets.UTF_8)
            val o = org.json.JSONObject(json)
            val host = o.optString("add").ifBlank { o.optString("host") }
            val port = o.optInt("port", 0)
            val remark = o.optString("ps").ifBlank { remarkFallback }
            if (host.isBlank() || port !in 1..65535) null
            else NodeInfo("vmess", host, port, remark)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSsLike(scheme: String, main: String, remark: String): NodeInfo? {
        return try {
            // ss://base64@host:port or ss://method:pass@host:port
            val authority = main.substringBefore("?")
            val hostPort = if ("@" in authority) authority.substringAfterLast("@") else {
                // pure base64 form
                val pad = "=".repeat((4 - authority.length % 4) % 4)
                val decoded = String(Base64.decode(authority + pad, Base64.DEFAULT), Charsets.UTF_8)
                if ("@" in decoded) decoded.substringAfter("@") else decoded
            }
            val host = hostPort.substringBeforeLast(":").trim().trim('[', ']')
            val port = hostPort.substringAfterLast(":").filter { it.isDigit() }.toIntOrNull() ?: 0
            if (host.isBlank() || port !in 1..65535) null
            else NodeInfo(scheme, host, port, remark)
        } catch (_: Exception) {
            null
        }
    }

    fun prepareForProfile(configs: List<String>, settings: ProfileSettings): List<String> {
        val unique = deduplicate(configs)
        if (unique.size <= settings.maxToCheck) return unique
        return if (settings.mode == NetworkProfileMode.MOBILE) {
            unique.shuffled().take(settings.maxToCheck)
        } else {
            unique.take(settings.maxToCheck)
        }
    }

    suspend fun checkConfigsParallel(
        configs: List<String>,
        settings: ProfileSettings,
        profileLabel: String = settings.label,
        onProgress: (processed: Int, total: Int, working: Int) -> Unit,
        onFound: (ConfigWithPing) -> Unit = {}
    ): List<ConfigWithPing> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ConfigWithPing>()
        val mutex = Mutex()
        val total = configs.size
        var processed = 0
        var working = 0
        var stop = false
        var lastUiMs = 0L

        val concurrency = settings.batchSize.coerceIn(8, 32)
        val connectMs = settings.connectTimeoutMs.coerceIn(400, 2000)
        val stopAt = settings.stopWhenFound
        val semaphore = Semaphore(concurrency)

        val jobs = configs.map { configUrl ->
            async {
                if (stop) {
                    mutex.withLock { processed++ }
                    return@async
                }

                semaphore.withPermit {
                    if (stop) {
                        mutex.withLock { processed++ }
                        return@withPermit
                    }

                    val node = parseNode(configUrl)
                    val item = if (node != null) {
                        val ping = try {
                            TcpPinger.ping(node.host, node.port, connectMs)
                        } catch (_: Exception) {
                            TcpPinger.Result(false, -1, "error")
                        }
                        if (
                            ping.ok &&
                            ping.rttMs in 1 until settings.maxPingMs.coerceAtLeast(3000)
                        ) {
                            ConfigWithPing(
                                url = configUrl,
                                pingMs = ping.rttMs,
                                profileLabel = profileLabel,
                                protocol = node.protocol.uppercase(),
                                remark = node.remark,
                                host = node.host,
                                port = node.port,
                                status = ConfigStatus.AVAILABLE,
                                statusText = "Доступен"
                            )
                        } else null
                    } else null

                    val snapshot = mutex.withLock {
                        processed++
                        if (item != null) {
                            results.add(item)
                            working++
                            if (stopAt > 0 && working >= stopAt) stop = true
                        }
                        // UI не чаще 8 раз/сек — иначе Main Thread захлёбывается
                        val now = System.currentTimeMillis()
                        val pushUi = now - lastUiMs >= 120 || processed >= total || item != null
                        if (pushUi) lastUiMs = now
                        Quad(processed, working, item, pushUi)
                    }
                    if (snapshot.pushUi) {
                        withContext(Dispatchers.Main) {
                            onProgress(snapshot.p, total, snapshot.w)
                            if (snapshot.found != null) onFound(snapshot.found)
                        }
                    }
                }
            }
        }

        jobs.awaitAll()
        // финальный progress
        withContext(Dispatchers.Main) {
            onProgress(total, total, results.size)
        }
        results.sortedBy { it.pingMs }
    }

    private data class Quad(
        val p: Int,
        val w: Int,
        val found: ConfigWithPing?,
        val pushUi: Boolean
    )
}
