package com.whitedns.vpn

import java.io.InputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Uses the SOCKS domain address explicitly, so isolated probes never resolve the target locally. */
internal object EngineSocksHandshake {
    fun connect(socket: Socket, host: String, port: Int) {
        val output = socket.getOutputStream(); val input = socket.getInputStream()
        output.write(byteArrayOf(5, 1, 0)); output.flush()
        check(input.read() == 5 && input.read() == 0) { "SOCKS authentication failed" }
        val domain = IDN.toASCII(host).toByteArray(Charsets.US_ASCII)
        require(domain.size in 1..255 && port in 1..65535)
        output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()))
        output.write(domain); output.write(byteArrayOf((port ushr 8).toByte(), port.toByte())); output.flush()
        check(input.read() == 5 && input.read() == 0 && input.read() == 0) { "SOCKS connection refused" }
        val length = when (input.read()) { 1 -> 4; 4 -> 16; 3 -> input.read().also { check(it >= 0) }; else -> error("Invalid SOCKS reply") }
        repeat(length + 2) { check(input.read() >= 0) { "Truncated SOCKS reply" } }
    }
}

internal object EngineSocksHttp {
    fun measure(port: Int, target: String, speed: Boolean, timeoutMs: Int = 10_000, expectedBytes: Long = 5_000_000L): String {
        val url = URL(target)
        require(url.protocol == "https")
        val start = System.nanoTime()
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            EngineSocksHandshake.connect(socket, url.host, if (url.port > 0) url.port else 443)
            val tls = SSLSocketFactory.getDefault().let { it as SSLSocketFactory }
                .createSocket(socket, url.host, if (url.port > 0) url.port else 443, true) as SSLSocket
            tls.use {
                tls.soTimeout = timeoutMs
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                val request = "GET " + url.file.ifBlank { "/" } + " HTTP/1.1\r\nHost: " + url.host +
                    "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
                tls.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
                val input = tls.getInputStream().buffered()
                val status = line(input).split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP response")
                check(status in 200..299) { "Routed HTTPS probe failed" }
                val headers = mutableMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val header = line(input)
                    headerBytes += header.length
                    check(headerBytes <= 65536) { "Oversized HTTP headers" }
                    if (header.isEmpty()) break
                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                }
                if (!speed) return ((System.nanoTime() - start) / 1_000_000).toString() + " ms"
                val expected = expectedBytes
                var received = 0L
                val buffer = ByteArray(16384)
                val chunked = headers["transfer-encoding"].orEmpty().contains("chunked", true)
                while (received < expected) {
                    val length = if (chunked) line(input).substringBefore(';').toLong(16) else expected - received
                    if (length == 0L) break
                    var remaining = length
                    while (remaining > 0 && received < expected) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining, expected - received).toInt())
                        check(count > 0) { "Incomplete throughput response" }
                        received += count; remaining -= count
                    }
                    if (chunked && received < expected) check(line(input).isEmpty()) { "Invalid HTTP chunk" }
                }
                check(received == expected) { "Incomplete throughput response" }
                return "%.2f Mbps".format(received * 8.0 * 1000 / (System.nanoTime() - start).coerceAtLeast(1))
            }
        }
    }
    private fun line(input: InputStream): String {
        val result = StringBuilder()
        while (result.length < 8192) {
            val char = input.read(); check(char >= 0) { "Truncated HTTP headers" }
            if (char == 10) return result.toString().removeSuffix("\r")
            result.append(char.toChar())
        }
        error("Oversized HTTP line")
    }
}
