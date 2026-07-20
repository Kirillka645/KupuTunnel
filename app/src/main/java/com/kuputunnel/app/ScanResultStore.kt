package com.kuputunnel.app

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Результаты скана на диске — НЕ через Intent
 * (иначе TransactionTooLargeException → краш → стартовый экран).
 */
object ScanResultStore {
    private const val LAST_SCAN = "last_scan.json"
    const val MAX_ITEMS = 60

    @Volatile
    var memory: List<ConfigWithPing> = emptyList()
        private set

    @Volatile
    var title: String = "Узлы"
        private set

    @Volatile
    var autoConnect: Boolean = false

    fun save(
        context: Context,
        list: List<ConfigWithPing>,
        title: String,
        autoConnect: Boolean = false
    ) {
        val trimmed = list.sortedBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }.take(MAX_ITEMS)
        this.memory = trimmed
        this.title = title
        this.autoConnect = autoConnect
        try {
            val arr = JSONArray()
            trimmed.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("url", c.url)
                        .put("ping", c.pingMs)
                        .put("protocol", c.protocol)
                        .put("remark", c.remark.take(120))
                        .put("host", c.host)
                        .put("port", c.port)
                )
            }
            val root = JSONObject()
                .put("title", title)
                .put("auto", autoConnect)
                .put("items", arr)
            File(dir(context), LAST_SCAN).writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun load(context: Context): List<ConfigWithPing> {
        if (memory.isNotEmpty()) return memory
        return try {
            val f = File(dir(context), LAST_SCAN)
            if (!f.exists()) return emptyList()
            val root = JSONObject(f.readText())
            title = root.optString("title", "Узлы")
            autoConnect = root.optBoolean("auto", false)
            val arr = root.getJSONArray("items")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ConfigWithPing(
                            url = o.getString("url"),
                            pingMs = o.optInt("ping"),
                            protocol = o.optString("protocol"),
                            remark = o.optString("remark"),
                            host = o.optString("host"),
                            port = o.optInt("port")
                        )
                    )
                }
            }.also { memory = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun consumeAutoConnect(): Boolean {
        val v = autoConnect
        autoConnect = false
        return v
    }

    private fun dir(context: Context) =
        File(context.filesDir, "config_store").also { if (!it.exists()) it.mkdirs() }
}
