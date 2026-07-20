package com.kuputunnel.app

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ConfigCache {
    private const val CACHE_FILE = "configs_cache.txt"
    private const val FAVORITES = "favorites.json"
    private const val LAST_WIFI = "last_wifi.json"
    private const val LAST_MOBILE = "last_mobile.json"
    private const val SEED_ASSET = "seed_configs.txt"

    fun cacheDir(context: Context): File =
        File(context.filesDir, "config_store").also { if (!it.exists()) it.mkdirs() }

    fun saveRawList(context: Context, configs: List<String>) {
        try {
            File(cacheDir(context), CACHE_FILE).writeText(configs.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    fun loadRawList(context: Context): List<String> {
        return try {
            val file = File(cacheDir(context), CACHE_FILE)
            if (!file.exists()) emptyList()
            else file.readLines().map { it.trim() }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadSeedFromAssets(context: Context): List<String> {
        return try {
            context.assets.open(SEED_ASSET).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveWorking(
        context: Context,
        profile: NetworkProfileMode,
        configs: List<ConfigWithPing>
    ) {
        val name = if (profile == NetworkProfileMode.MOBILE) LAST_MOBILE else LAST_WIFI
        try {
            val arr = JSONArray()
            configs.take(200).forEach { c ->
                arr.put(
                    JSONObject()
                        .put("url", c.url)
                        .put("ping", c.pingMs)
                        .put("protocol", c.protocol)
                        .put("remark", c.remark)
                        .put("host", c.host)
                        .put("port", c.port)
                )
            }
            File(cacheDir(context), name).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun loadWorking(context: Context, profile: NetworkProfileMode): List<ConfigWithPing> {
        val name = if (profile == NetworkProfileMode.MOBILE) LAST_MOBILE else LAST_WIFI
        return try {
            val file = File(cacheDir(context), name)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ConfigWithPing(
                            url = o.getString("url"),
                            pingMs = o.getInt("ping"),
                            protocol = o.optString("protocol"),
                            remark = o.optString("remark"),
                            host = o.optString("host"),
                            port = o.optInt("port")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getFavorites(context: Context): MutableSet<String> {
        return try {
            val file = File(cacheDir(context), FAVORITES)
            if (!file.exists()) return mutableSetOf()
            val arr = JSONArray(file.readText())
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    fun saveFavorites(context: Context, favorites: Set<String>) {
        try {
            val arr = JSONArray()
            favorites.forEach { arr.put(it) }
            File(cacheDir(context), FAVORITES).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun toggleFavorite(context: Context, url: String): Boolean {
        val set = getFavorites(context)
        val added = if (set.contains(url)) {
            set.remove(url)
            false
        } else {
            set.add(url)
            true
        }
        saveFavorites(context, set)
        return added
    }

    fun isFavorite(context: Context, url: String): Boolean =
        getFavorites(context).contains(url)
}
