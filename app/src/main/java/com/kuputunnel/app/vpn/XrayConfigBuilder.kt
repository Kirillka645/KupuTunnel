package com.kuputunnel.app.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Share-link → Xray JSON (как в Happ: VLESS Reality / VMess / Trojan / SS / Socks).
 * Архитектура ядра как у Hiddify: TUN inbound + proxy outbound + routing.
 */
object XrayConfigBuilder {

    data class Built(
        val json: String,
        val protocol: String,
        val remark: String,
        val host: String,
        val port: Int
    )

    fun build(shareLink: String, socksPort: Int = 10808): Built? {
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

        val root = JSONObject()
        root.put("stats", JSONObject())
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put(
            "policy",
            JSONObject()
                .put(
                    "levels",
                    JSONObject().put(
                        "8",
                        JSONObject()
                            .put("handshake", 4)
                            .put("connIdle", 300)
                            .put("uplinkOnly", 1)
                            .put("downlinkOnly", 1)
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
        // SOCKS (fallback / apps)
        inbounds.put(
            JSONObject()
                .put("tag", "socks")
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
                )
        )
        // TUN — fd приходит через xray.tun.fd (AndroidLibXrayLite)
        inbounds.put(
            JSONObject()
                .put("tag", "tun")
                .put("protocol", "tun")
                .put(
                    "settings",
                    JSONObject()
                        .put("name", "kupu0")
                        .put("MTU", 1500)
                        .put("userLevel", 8)
                )
                .put(
                    "sniffing",
                    JSONObject()
                        .put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                )
        )
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
                .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
        )
        root.put("outbounds", outbounds)

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put(
                    "rules",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "field")
                                .put("outboundTag", "direct")
                                .put("ip", JSONArray().put("geoip:private"))
                        )
                        .put(
                            JSONObject()
                                .put("type", "field")
                                .put("outboundTag", "proxy")
                                .put("network", "tcp,udp")
                        )
                )
        )

        root.put(
            "dns",
            JSONObject()
                .put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
                .put("queryStrategy", "UseIP")
        )

        return Built(
            json = root.toString(),
            protocol = outbound.protocol,
            remark = outbound.remark,
            host = outbound.host,
            port = outbound.port
        )
    }

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
        val encryption = q["encryption"] ?: "none"
        val flow = q["flow"]
        val network = (q["type"] ?: "tcp").ifBlank { "tcp" }
        val security = (q["security"] ?: "none").ifBlank { "none" }

        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", encryption)
            .put("level", 8)
        if (!flow.isNullOrBlank()) user.put("flow", flow)

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
            .put("streamSettings", streamSettings(network, security, q))
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
        val securityUser = o.optString("scy").ifBlank { "auto" }
        val network = o.optString("net").ifBlank { "tcp" }
        val tls = o.optString("tls")
        val sni = o.optString("sni").ifBlank { o.optString("host") }
        val path = o.optString("path").ifBlank { "/" }
        val hostHeader = o.optString("host")
        val alpn = o.optString("alpn")
        val fp = o.optString("fp")

        val q = mutableMapOf(
            "type" to network,
            "security" to if (tls.equals("tls", true)) "tls" else "none",
            "path" to path,
            "host" to hostHeader,
            "sni" to sni,
            "alpn" to alpn,
            "fp" to fp,
            "headerType" to o.optString("type")
        )

        val user = JSONObject()
            .put("id", uuid)
            .put("alterId", o.optInt("aid", 0))
            .put("security", securityUser)
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
        val remark = fragment(uri)
        val network = (q["type"] ?: "tcp").ifBlank { "tcp" }
        val security = (q["security"] ?: "tls").ifBlank { "tls" }

        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("password", password)
            .put("level", 8)
        if (!q["flow"].isNullOrBlank()) server.put("flow", q["flow"])

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", streamSettings(network, security, q))
            .put("mux", JSONObject().put("enabled", false))

        return Out(outbound, "TROJAN", remark, host, port)
    }

    private fun ssOutbound(link: String): Out? {
        // ss://base64(method:password)@host:port#remark
        // ss://method:password@host:port
        val raw = link.removePrefix("ss://").removePrefix("SS://")
        val remark = if ("#" in raw) {
            decode(raw.substringAfterLast("#"))
        } else ""
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
            val decodedUser = if (userInfoPart.contains(":")) {
                userInfoPart
            } else {
                decodeBase64ToString(userInfoPart) ?: userInfoPart
            }
            method = decodedUser.substringBefore(":")
            password = decodedUser.substringAfter(":", "")
        } else {
            val decoded = decodeBase64ToString(main) ?: return null
            // method:password@host:port
            if ("@" !in decoded) return null
            val user = decoded.substringBefore("@")
            val hostPort = decoded.substringAfter("@")
            method = user.substringBefore(":")
            password = user.substringAfter(":")
            host = hostPort.substringBeforeLast(":").trim('[', ']')
            port = hostPort.substringAfterLast(":").filter { it.isDigit() }.toIntOrNull() ?: return null
        }
        if (host.isBlank() || port !in 1..65535 || method.isBlank()) return null

        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("method", method)
            .put("password", password)
            .put("level", 8)
            .put("ota", false)

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", JSONObject().put("network", "tcp"))
            .put("mux", JSONObject().put("enabled", false))

        return Out(outbound, "SS", remark.ifBlank { host }, host, port)
    }

    private fun socksOutbound(link: String): Out? {
        // socks://user:pass@host:port  or socks5://host:port
        val normalized = link
            .replace("socks5://", "socks://", ignoreCase = true)
            .replace("socks4://", "socks://", ignoreCase = true)
        val uri = safeUri(normalized) ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 1080
        val remark = fragment(uri).ifBlank { host }
        val userInfo = uri.userInfo

        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("level", 8)
        if (!userInfo.isNullOrBlank()) {
            val user = userInfo.substringBefore(":")
            val pass = userInfo.substringAfter(":", "")
            server.put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put("user", user)
                        .put("pass", pass)
                        .put("level", 8)
                )
            )
        }

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", JSONObject().put("network", "tcp"))
            .put("mux", JSONObject().put("enabled", false))

        return Out(outbound, "SOCKS", remark, host, port)
    }

    private fun streamSettings(
        network: String,
        security: String,
        q: Map<String, String>
    ): JSONObject {
        val stream = JSONObject()
        val net = when (network.lowercase()) {
            "h2", "http" -> "h2"
            "raw" -> "tcp"
            else -> network.lowercase()
        }
        stream.put("network", net)
        stream.put("security", security.lowercase())

        when (net) {
            "ws" -> {
                val ws = JSONObject()
                    .put("path", q["path"] ?: "/")
                    .put(
                        "headers",
                        JSONObject().put("Host", q["host"] ?: q["sni"] ?: "")
                    )
                stream.put("wsSettings", ws)
            }
            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject()
                        .put("serviceName", q["serviceName"] ?: q["path"] ?: "")
                        .put("multiMode", (q["mode"] ?: "") == "multi")
                )
            }
            "h2" -> {
                stream.put(
                    "httpSettings",
                    JSONObject()
                        .put("path", q["path"] ?: "/")
                        .put(
                            "host",
                            JSONArray().put(q["host"] ?: q["sni"] ?: "")
                        )
                )
            }
            "xhttp", "splithttp" -> {
                val xhttp = JSONObject()
                    .put("path", q["path"] ?: "/")
                    .put("host", q["host"] ?: q["sni"] ?: "")
                    .put("mode", q["mode"] ?: "auto")
                stream.put("xhttpSettings", xhttp)
                // some cores use splithttpSettings
                stream.put("splithttpSettings", xhttp)
            }
            "tcp", "raw" -> {
                val headerType = q["headerType"] ?: "none"
                if (headerType == "http") {
                    stream.put(
                        "tcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject()
                                .put("type", "http")
                                .put(
                                    "request",
                                    JSONObject()
                                        .put("path", JSONArray().put(q["path"] ?: "/"))
                                        .put(
                                            "headers",
                                            JSONObject().put(
                                                "Host",
                                                JSONArray().put(q["host"] ?: q["sni"] ?: "")
                                            )
                                        )
                                )
                        )
                    )
                } else {
                    stream.put(
                        "tcpSettings",
                        JSONObject().put("header", JSONObject().put("type", "none"))
                    )
                }
            }
        }

        when (security.lowercase()) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", q["sni"] ?: q["host"] ?: "")
                    .put("allowInsecure", q["allowInsecure"] == "1" || q["insecure"] == "1")
                val fp = q["fp"]
                if (!fp.isNullOrBlank()) tls.put("fingerprint", fp)
                val alpn = q["alpn"]
                if (!alpn.isNullOrBlank()) {
                    tls.put(
                        "alpn",
                        JSONArray().apply {
                            alpn.split(",").forEach { put(it.trim()) }
                        }
                    )
                }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                    .put("serverName", q["sni"] ?: q["host"] ?: "")
                    .put("fingerprint", q["fp"] ?: "chrome")
                    .put("publicKey", q["pbk"] ?: "")
                    .put("shortId", q["sid"] ?: "")
                    .put("spiderX", q["spx"] ?: "/")
                stream.put("realitySettings", reality)
            }
        }
        return stream
    }

    private fun safeUri(link: String): URI? {
        return try {
            // encode illegal spaces in fragment etc.
            val fixed = link.replace(" ", "%20")
            URI(fixed)
        } catch (_: Exception) {
            try {
                val scheme = link.substringBefore("://")
                val rest = link.substringAfter("://")
                URI("$scheme://${rest.replace("#", "%23").replace(" ", "%20")}")
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun queryMap(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        raw.split("&").forEach { part ->
            val k = part.substringBefore("=")
            val v = part.substringAfter("=", "")
            if (k.isNotBlank()) map[k] = decode(v)
        }
        return map
    }

    private fun fragment(uri: URI): String {
        return try {
            decode(uri.rawFragment ?: uri.fragment ?: "")
        } catch (_: Exception) {
            uri.fragment.orEmpty()
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        s
    }

    private fun decodeBase64ToString(b64: String): String? {
        return try {
            val cleaned = b64.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            val pad = "=".repeat((4 - cleaned.length % 4) % 4)
            val bytes = try {
                Base64.decode(cleaned + pad, Base64.DEFAULT)
            } catch (_: Exception) {
                val urlSafe = cleaned.replace('-', '+').replace('_', '/')
                val pad2 = "=".repeat((4 - urlSafe.length % 4) % 4)
                Base64.decode(urlSafe + pad2, Base64.DEFAULT)
            }
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
