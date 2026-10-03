package com.whitedns.vpn

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class TorControlConnectionTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hmac(key: String, bytes: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(), "HmacSHA256")); doFinal(bytes)
    }
    private fun exercise(validServer: Boolean): List<String> {
        val cookie = ByteArray(32) { 7 }
        val serverNonce = ByteArray(32) { 9 }
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { listener ->
                val commands = executor.submit<List<String>> {
                    listener.accept().use { connection ->
                        connection.soTimeout = 5000
                        val input = connection.getInputStream().bufferedReader()
                        val output = connection.getOutputStream().bufferedWriter()
                        val challenge = input.readLine()
                        val nonce = unhex(challenge.substringAfterLast(' '))
                        val data = cookie + nonce + serverNonce
                        val serverHash = if (validServer) hmac("Tor safe cookie authentication server-to-controller hash", data) else ByteArray(32)
                        output.write("250 AUTHCHALLENGE SERVERHASH=" + hex(serverHash) + " SERVERNONCE=" + hex(serverNonce) + "\r\n"); output.flush()
                        val authenticate = input.readLine()
                        if (!validServer) { assertNull(authenticate); return@submit listOf(challenge) }
                        assertEquals("AUTHENTICATE " + hex(hmac("Tor safe cookie authentication controller-to-server hash", data)), authenticate)
                        output.write("250 OK\r\n"); output.flush()
                        val ownership = input.readLine()
                        assertEquals("TAKEOWNERSHIP", ownership)
                        output.write("250 OK\r\n"); output.flush()
                        listOf(challenge, authenticate, ownership)
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 5000
                    if (validServer) TorControlConnection.attach(socket, cookie)
                    else assertThrows(IllegalStateException::class.java) { TorControlConnection.attach(socket, cookie) }
                }
                return commands.get(5, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun authenticatesControllerBeforeTakingOwnership() { assertEquals(3, exercise(true).size) }
    @Test fun forgedControllerNeverReceivesClientAuthentication() { assertEquals(1, exercise(false).size) }
}
