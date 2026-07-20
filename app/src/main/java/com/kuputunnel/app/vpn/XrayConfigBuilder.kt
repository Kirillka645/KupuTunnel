package com.kuputunnel.app.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Share-link → Xray JSON для AndroidLibXrayLite.
 *
 * Важно:
 * - stats + policy обязательны (иначе GetFeature(stats) → panic в lib)
 * - protocol tun + env xray.tun.fd (ставит startLoop)
 * - без geoip/geosite в routing
 * - server IP/domain → direct (anti-loop)
 */
object XrayConfigBuilder {

    data class Built(
        val json: String,
        val protocol: String,
        val remark: String,
        val host: String,
        val port: Int,
        val serverIps: List<String>
    )

    private val PRIVATE_CIDRS = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4"
    )

    fun build(shareLink: String, socksPort: Int = 10808, useTun: Boolean = true): Built? {
        val link = shareLink.trim()
        val lower = link.lowercase()
        val outbound = when {
            lower.startsWith("vless://") -> vlessOutbound(link)
            lower.startsWith("vmess://") -> vmessOutbound(link)
            lower.startsWith("trojan://") -> trojanOutbound(link)
            lower.startsWith("ss://") -> ssOutbound(link)
            lower.startsWith("socks://") || lower.startsWith("socks5://") -> socksOutbound(link)
            else -> null
        } ?: return null

        val serverIps = resolveHostIps(outbound.host)

        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        // Обязательно для AndroidLibXrayLite — иначе panic на statsManager
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject()
                .put(
                    "levels",
                    JSONObject().put(
                        "8",
                        JSONObject()
                            .put("connIdle", 300)
                            .put("handshake", 8)
                            .put("uplinkOnly", 2)
                            .put("downlinkOnly", 5)
                    )
                )
                .put(
                    "system",
                    JSONObject()
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true)
                )
        )

        val inbounds = JSONArray()
        inbounds.put(
            JSONObject()
                .put("tag", "socks-in")
                .put("port", socksPort)
                .put("listen", "127.0.0.1")
                .put("protocol", "socks")
                .put(
                    "settings",
                    JSONObject()
                        .put("auth", "noauth")
                        .put("udp", true)
                        .put("userLevel", 8)
                )
                .put(
                    "sniffing",
                    JSONObject()
                        .put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                        .put("routeOnly", false)
                )
        )
        if (useTun) {
            // Android: fd приходит через env xray.tun.fd (ставит Libv2ray.startLoop)
            inbounds.put(
                JSONObject()
                    .put("tag", "tun-in")
                    .put("port", 0)
                    .put("protocol", "tun")
                    .put(
                        "settings",
                        JSONObject()
                            .put("name", "xray0")
                            .put("mtu", 1500)
                            .put("userLevel", 8)
                    )
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                            .put("routeOnly", false)
                    )
            )
        }
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(outbound.outbound)
        outbounds.put(
            JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", "UseIP"))
        )
        outbounds.put(
            JSONObject()
                .put("tag", "block")
                .put("protocol", "blackhole")
                .put("settings", JSONObject())
        )
        root.put("outbounds", outbounds)

        val rules = JSONArray()

        // private → direct
        val priv = JSONArray()
        PRIVATE_CIDRS.forEach { priv.put(it) }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("ip", priv)
                .put("outboundTag", "direct")
        )

        // server IP → direct (anti routing-loop)
        if (serverIps.isNotEmpty()) {
            val sip = JSONArray()
            serverIps.forEach { sip.put(it) }
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", sip)
                    .put("outboundTag", "direct")
            )
        }
        if (!isIpLiteral(outbound.host)) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("domain", JSONArray().put("full:${outbound.host}"))
                    .put("outboundTag", "direct")
            )
        }

        // default → proxy
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("network", "tcp,udp")
                .put("outboundTag", "proxy")
        )
        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules)
        )

        root.put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put("1.1.1.1")
                        .put("8.8.8.8")
                        .put("localhost")
                )
                .put("queryStrategy", "UseIPv4")
                .put("disableCache", false)
        )

        return Built(
            json = root.toString(),
            protocol = outbound.protocol,
            remark = outbound.remark,
            host = outbound.host,
            port = outbound.port,
            serverIps = serverIps
        )
    }

    private fun resolveHostIps(host: String): List<String> {
        if (isIpLiteral(host)) return listOf(host)
        return try {
            InetAddress.getAllByName(host)
                .mapNotNull { it.hostAddress }
                .filter { !it.contains(":") }
                .distinct()
                .take(6)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    private data class Out(
        val outbound: JSONObject,
        val protocol: String,
        val remark: String,
        val host: String,
        val port: Int
    )

    private fun vlessOutbound(link: String): Out? {
        val uri = safeUri(link) ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val uuid = uri.userInfo ?: return null
        val q = queryMap(uri)
        val remark = fragment(uri)
        val network = normalizeNetwork(q["type"] ?: "tcp")
        val security = (q["security"] ?: "none").ifBlank { "none" }
        val flow = q["flow"]

        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", q["encryption"] ?: "none")
            .put("level", 8)
        if (!flow.isNullOrBlank() && flow != "none") {
            user.put("flow", flow)
        }

        val net = if (!flow.isNullOrBlank() && flow.contains("vision")) "tcp" else network

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", streamSettings(net, security, q))
            .put("mux", JSONObject().put("enabled", false).put("concurrency", -1))

        return Out(outbound, "VLESS", remark, host, port)
    }

    private fun vmessOutbound(link: String): Out? {
        val b64 = link.removePrefix("vmess://").removePrefix("VMESS://").trim()
        val json = decodeBase64ToString(b64) ?: return null
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val host = o.optString("add").ifBlank { o.optString("host") }
        val port = o.optInt("port", 0)
        val uuid = o.optString("id")
        if (host.isBlank() || port !in 1..65535 || uuid.isBlank()) return null
        val remark = o.optString("ps").ifBlank { host }
        val network = normalizeNetwork(o.optString("net").ifBlank { "tcp" })
        val tls = o.optString("tls")
        val q = mapOf(
            "type" to network,
            "security" to if (tls.equals("tls", true) || tls.equals("reality", true)) tls.lowercase() else "none",
            "path" to o.optString("path").ifBlank { "/" },
            "host" to o.optString("host"),
            "sni" to o.optString("sni").ifBlank { o.optString("host") },
            "alpn" to o.optString("alpn"),
            "fp" to o.optString("fp"),
            "headerType" to o.optString("type"),
            "pbk" to o.optString("pbk"),
            "sid" to o.optString("sid"),
            "spx" to o.optString("spx")
        )
        val user = JSONObject()
            .put("id", uuid)
            .put("alterId", o.optInt("aid", 0))
            .put("security", o.optString("scy").ifBlank { "auto" })
            .put("level", 8)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", streamSettings(network, q["security"]!!, q))
            .put("mux", JSONObject().put("enabled", false))
        return Out(outbound, "VMESS", remark, host, port)
    }

    private fun trojanOutbound(link: String): Out? {
        val uri = safeUri(link) ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val password = uri.userInfo ?: return null
        val q = queryMap(uri)
        val network = normalizeNetwork(q["type"] ?: "tcp")
        val security = (q["security"] ?: "tls").ifBlank { "tls" }
        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("password", password)
            .put("level", 8)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", streamSettings(network, security, q))
            .put("mux", JSONObject().put("enabled", false))
        return Out(outbound, "TROJAN", fragment(uri), host, port)
    }

    private fun ssOutbound(link: String): Out? {
        val raw = link.removePrefix("ss://").removePrefix("SS://")
        val remark = if ("#" in raw) decode(raw.substringAfterLast("#")) else ""
        val main = raw.substringBefore("#")
        val host: String
        val port: Int
        val method: String
        val password: String
        if ("@" in main) {
            val userInfoPart = main.substringBeforeLast("@")
            val hostPort = main.substringAfterLast("@")
            host = hostPort.substringBeforeLast(":").trim('[', ']')
            port = hostPort.substringAfterLast(":").filter { it.isDigit() }.toIntOrNull() ?: return null
            val decodedUser =
                if (userInfoPart.contains(":")) userInfoPart
                else decodeBase64ToString(userInfoPart) ?: userInfoPart
            method = decodedUser.substringBefore(":")
            password = decodedUser.substringAfter(":", "")
        } else {
            val decoded = decodeBase64ToString(main) ?: return null
            if ("@" !in decoded) return null
            method = decoded.substringBefore("@").substringBefore(":")
            password = decoded.substringBefore("@").substringAfter(":")
            val hostPort = decoded.substringAfter("@")
            host = hostPort.substringBeforeLast(":").trim('[', ']')
            port = hostPort.substringAfterLast(":").filter { it.isDigit() }.toIntOrNull() ?: return null
        }
        if (host.isBlank() || port !in 1..65535) return null
        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("method", method)
            .put("password", password)
            .put("level", 8)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", JSONObject().put("network", "tcp"))
        return Out(outbound, "SS", remark.ifBlank { host }, host, port)
    }

    private fun socksOutbound(link: String): Out? {
        val normalized = link.replace("socks5://", "socks://", ignoreCase = true)
        val uri = safeUri(normalized) ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 1080
        val server = JSONObject().put("address", host).put("port", port).put("level", 8)
        val ui = uri.userInfo
        if (!ui.isNullOrBlank()) {
            server.put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put("user", ui.substringBefore(":"))
                        .put("pass", ui.substringAfter(":", ""))
                        .put("level", 8)
                )
            )
        }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return Out(outbound, "SOCKS", fragment(uri).ifBlank { host }, host, port)
    }

    private fun normalizeNetwork(raw: String): String = when (raw.lowercase()) {
        "h2", "http" -> "h2"
        "raw", "tcp" -> "tcp"
        "websocket" -> "ws"
        "xhttp", "splithttp" -> "xhttp"
        else -> raw.lowercase()
    }

    private fun streamSettings(network: String, security: String, q: Map<String, String>): JSONObject {
        val stream = JSONObject()
        val net = normalizeNetwork(network)
        stream.put("network", net)
        stream.put("security", security.lowercase().ifBlank { "none" })

        when (net) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", q["path"] ?: "/")
                    .put("headers", JSONObject().put("Host", q["host"] ?: q["sni"] ?: ""))
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", q["serviceName"] ?: q["path"] ?: "")
                    .put("multiMode", (q["mode"] ?: "") == "multi")
            )
            "h2" -> stream.put(
                "httpSettings",
                JSONObject()
                    .put("path", q["path"] ?: "/")
                    .put("host", JSONArray().put(q["host"] ?: q["sni"] ?: ""))
            )
            "xhttp" -> stream.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", q["path"] ?: "/")
                    .put("host", q["host"] ?: q["sni"] ?: "")
                    .put("mode", q["mode"] ?: "auto")
            )
            else -> stream.put(
                "tcpSettings",
                JSONObject().put("header", JSONObject().put("type", "none"))
            )
        }

        when (security.lowercase()) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", q["sni"] ?: q["host"] ?: "")
                    .put("allowInsecure", q["allowInsecure"] == "1" || q["insecure"] == "1")
                q["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotBlank() }?.let {
                    val arr = JSONArray()
                    it.split(",").forEach { p -> arr.put(p.trim()) }
                    tls.put("alpn", arr)
                }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                stream.put(
                    "realitySettings",
                    JSONObject()
                        .put("show", false)
                        .put("serverName", q["sni"] ?: q["host"] ?: "")
                        .put("fingerprint", q["fp"]?.ifBlank { null } ?: "chrome")
                        .put("publicKey", q["pbk"] ?: "")
                        .put("shortId", q["sid"] ?: "")
                        .put("spiderX", q["spx"] ?: "/")
                )
            }
        }

        // sockopt: не привязываем mark — используем addDisallowedApplication
        stream.put(
            "sockopt",
            JSONObject()
                .put("tcpKeepAliveInterval", 30)
                .put("tcpNoDelay", true)
        )
        return stream
    }

    private fun safeUri(link: String): URI? = try {
        URI(link.replace(" ", "%20"))
    } catch (_: Exception) {
        null
    }

    private fun queryMap(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        return raw.split("&").mapNotNull { part ->
            val k = part.substringBefore("=")
            if (k.isBlank()) null
            else k to decode(part.substringAfter("=", ""))
        }.toMap()
    }

    private fun fragment(uri: URI): String = try {
        decode(uri.rawFragment ?: uri.fragment ?: "")
    } catch (_: Exception) {
        ""
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        s
    }

    private fun decodeBase64ToString(b64: String): String? = try {
        val cleaned = b64.trim().replace(Regex("\\s"), "")
        val pad = "=".repeat((4 - cleaned.length % 4) % 4)
        val bytes = try {
            Base64.decode(cleaned + pad, Base64.DEFAULT)
        } catch (_: Exception) {
            Base64.decode(
                cleaned.replace('-', '+').replace('_', '/') + pad,
                Base64.DEFAULT
            )
        }
        String(bytes, StandardCharsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
