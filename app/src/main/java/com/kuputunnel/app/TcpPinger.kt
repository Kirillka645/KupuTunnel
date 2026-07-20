package com.kuputunnel.app

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Быстрый TCP-пинг узла (connect RTT).
 * Это «жив ли порт» — без полного handshake VLESS/Reality.
 * Для фильтрации мёртвых нод из публичных списков этого достаточно.
 */
object TcpPinger {

    data class Result(
        val ok: Boolean,
        val rttMs: Int,
        val error: String? = null
    )

    fun ping(host: String, port: Int, timeoutMs: Int): Result {
        if (host.isBlank() || port !in 1..65535) {
            return Result(false, -1, "bad_host")
        }
        val start = System.currentTimeMillis()
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs.coerceIn(400, 5000))
            val rtt = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            Result(true, rtt)
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - start).toInt()
            Result(false, elapsed.coerceAtLeast(-1), e.message ?: "timeout")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
