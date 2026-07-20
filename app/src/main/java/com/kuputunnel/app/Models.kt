package com.kuputunnel.app

import java.io.Serializable

enum class ConfigStatus : Serializable {
    AVAILABLE,
    UNAVAILABLE
}

data class ConfigWithPing(
    val url: String,
    val pingMs: Int,
    val profileLabel: String = "",
    val protocol: String = "",
    val remark: String = "",
    val host: String = "",
    val port: Int = 0,
    val status: ConfigStatus = ConfigStatus.AVAILABLE,
    val statusText: String = "Доступен"
) : Serializable

data class NodeInfo(
    val protocol: String,
    val host: String,
    val port: Int,
    val remark: String
)

data class FetchResult(
    val configs: List<String>,
    val sourceHits: Map<String, Int>,
    val usedMirrors: List<String>,
    val fromCache: Boolean = false,
    val fromSeed: Boolean = false
)
