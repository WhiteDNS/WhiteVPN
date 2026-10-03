package com.whitedns.vpn

import com.jcraft.jsch.UserInfo
import com.whitedns.vpn.engines.models.SshProfile
import com.whitedns.vpn.engines.partner.SshController
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real SSH server exercises authentication, TOFU persistence and direct-tcpip cleanup. */
class SshControllerIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun server(): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"; port = 0
        keyPairProvider = KeyPairProvider.wrap(keyPair())
        setPasswordAuthenticator { username, password, _ -> username == "fixture" && password == "test-password" }
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        start()
    }
    private class Trust(private val accepted: Boolean) : UserInfo {
        var prompts = 0
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String) = false
        override fun promptPassphrase(message: String) = false
        override fun showMessage(message: String) = Unit
        override fun promptYesNo(message: String): Boolean = SshHostKeyTrustPolicy.confirm(message) { prompts++; accepted }
    }
    private fun controller(server: SshServer, known: File, trust: Trust, password: String = "test-password"): SshController {
        val port = ServerSocket(0).use { it.localPort }
        return SshController(SshProfile("127.0.0.1", server.port, "fixture", "password", password), known, trust, "auto", false, port)
    }
    @Test fun acceptedFirstUsePersistsIdentityAndForwardsTcp() {
        val server = server()
        val executor = Executors.newSingleThreadExecutor()
        val known = temp.newFile("known-hosts")
        val first = Trust(true)
        val controller = controller(server, known, first)
        try {
            val port = controller.start()
            assertTrue(port > 0); assertEquals(1, first.prompts); assertTrue(known.length() > 0)
            ServerSocket(0).use { target ->
                val echo = executor.submit { target.accept().use { peer -> peer.getOutputStream().write(peer.getInputStream().read()) } }
                Socket("127.0.0.1", port).use { proxy ->
                    proxy.soTimeout = 5000
                    val input = proxy.getInputStream(); val output = proxy.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0)); output.flush()
                    assertEquals(5, input.read()); assertEquals(0, input.read())
                    val domain = "localhost".toByteArray()
                    output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte())); output.write(domain)
                    output.write(byteArrayOf((target.localPort shr 8).toByte(), target.localPort.toByte())); output.flush()
                    val reply = ByteArray(10); java.io.DataInputStream(input).readFully(reply)
                    assertEquals(0, reply[1].toInt())
                    output.write(42); output.flush(); assertEquals(42, input.read())
                }
                echo.get(5, TimeUnit.SECONDS)
            }
            assertTrue(controller.stop()); assertFalse(controller.isRunning)
            assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", port).close() }
            repeat(10) {
                val trustAgain = Trust(false)
                val next = controller(server, known, trustAgain)
                try { assertTrue(next.start() > 0); assertEquals(0, trustAgain.prompts) } finally { assertTrue(next.stop()) }
            }
        } finally { controller.stop(); server.stop(true); executor.shutdownNow() }
    }
    @Test fun changedKeyIsRejectedEvenWhenUserWouldAcceptFirstUse() {
        val server = server(); val known = temp.newFile("known-hosts-changed")
        val original = controller(server, known, Trust(true))
        try {
            assertTrue(original.start() > 0); assertTrue(original.stop())
            server.keyPairProvider = KeyPairProvider.wrap(keyPair())
            val trust = Trust(true); val changed = controller(server, known, trust)
            try { assertEquals(-1, changed.start()); assertEquals(0, trust.prompts); assertFalse(changed.isRunning) }
            finally { assertTrue(changed.stop()) }
        } finally { original.stop(); server.stop(true) }
    }
    @Test fun rejectedCredentialsAndFirstUseLeaveNoRunningSession() {
        val server = server()
        try {
            val bad = controller(server, temp.newFile("bad-password"), Trust(true), "wrong")
            try { assertEquals(-1, bad.start()); assertFalse(bad.isRunning) } finally { assertTrue(bad.stop()) }
            val denied = controller(server, temp.newFile("denied-key"), Trust(false))
            try { assertEquals(-1, denied.start()); assertFalse(denied.isRunning) } finally { assertTrue(denied.stop()) }
        } finally { server.stop(true) }
    }
}
