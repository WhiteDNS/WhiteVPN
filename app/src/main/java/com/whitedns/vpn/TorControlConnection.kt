package com.whitedns.vpn

import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticated ownership makes Tor exit when the application process loses its socket. */
internal object TorControlConnection {
    private const val SERVER_KEY = "Tor safe cookie authentication server-to-controller hash"
    private const val CLIENT_KEY = "Tor safe cookie authentication controller-to-server hash"
    fun attach(socket: Socket, cookie: ByteArray) {
        require(cookie.size == 32) { "Invalid Tor authentication cookie" }
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
        fun command(value: String): String {
            output.write(value + "\r\n"); output.flush()
            return input.readLine()?.takeIf { it.length <= 4096 } ?: error("Tor controller closed")
        }
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
        val challenge = command("AUTHCHALLENGE SAFECOOKIE " + hex(nonce))
        val parsed = Regex("250 AUTHCHALLENGE SERVERHASH=([0-9a-fA-F]{64}) SERVERNONCE=([0-9a-fA-F]{64})").matchEntire(challenge)
            ?: error("Invalid Tor authentication challenge")
        val serverHash = unhex(parsed.groupValues[1])
        val serverNonce = unhex(parsed.groupValues[2])
        val data = cookie + nonce + serverNonce
        check(MessageDigest.isEqual(serverHash, hmac(SERVER_KEY, data))) { "Tor controller identity mismatch" }
        check(command("AUTHENTICATE " + hex(hmac(CLIENT_KEY, data))) == "250 OK") { "Tor controller authentication failed" }
        check(command("TAKEOWNERSHIP") == "250 OK") { "Tor ownership was refused" }
    }
    private fun hmac(key: String, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "HmacSHA256")); doFinal(data)
    }
    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
