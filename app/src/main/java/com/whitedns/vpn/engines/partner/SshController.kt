// Adapted from ZedSecure, revision 2c01da26bed98b883503850df1b7f793d28f7f54.
package com.whitedns.vpn.engines.partner

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.whitedns.vpn.engines.models.SshProfile
import java.io.File
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

class SshController(
    private val profile: SshProfile,
    private val knownHosts: File,
    private val userInfo: com.jcraft.jsch.UserInfo,
    private val cipher: String,
    private val compression: Boolean,
    private val listenPort: Int,

    private val maxListenPort: Int = listenPort + 9,
    private val listenHost: String = "127.0.0.1",

    private val proxySocksHost: String? = null,
    private val proxySocksPort: Int = 0,
    private val proxySocksUser: String = "",
    private val proxySocksPass: String = "",
) {
    private var session: Session? = null
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val workers = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
    private fun worker(name: String, block: () -> Unit): Thread = Thread({
        try { block() } finally { workers.remove(Thread.currentThread()) }
    }, name).apply { isDaemon = true; workers.add(this); start() }

    val isRunning: Boolean get() = running.get() && session?.isConnected == true

    @Volatile var lastError: String? = null

    fun start(): Int {
        lastError = null
        return try {
            JSch.setConfig("CheckKexes", "")
            JSch.setConfig("kex", KEX_ORDER)
            val jsch = JSch()
            jsch.setKnownHosts(knownHosts.absolutePath)
            if (profile.authType == SshProfile.AUTH_KEY && profile.privateKey.isNotBlank()) {
                jsch.addIdentity(
                    "zed", profile.privateKey.toByteArray(), null,
                    profile.keyPassphrase.takeIf { it.isNotBlank() }?.toByteArray(),
                )
            }
            val s = jsch.getSession(profile.username, profile.host, profile.port)
            s.userInfo = userInfo
            if (proxySocksHost != null && proxySocksPort > 0) {
                val proxy = com.jcraft.jsch.ProxySOCKS5(proxySocksHost, proxySocksPort)
                if (proxySocksUser.isNotBlank()) proxy.setUserPasswd(proxySocksUser, proxySocksPass)
                s.setProxy(proxy)
            }
            s.setConfig(sessionProps())
            if (profile.authType == SshProfile.AUTH_KEY) {
                s.setConfig("PreferredAuthentications", "publickey")
            } else {
                s.setPassword(profile.password)
                s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            }
            session = s
            s.connect(CONNECT_TIMEOUT_MS)

            var bound: ServerSocket? = null
            var boundPort = -1
            for (candidate in listenPort..maxListenPort) {
                val attempt = ServerSocket()
                attempt.reuseAddress = true
                try {
                    attempt.bind(InetSocketAddress(listenHost, candidate))
                    bound = attempt
                    boundPort = candidate
                    break
                } catch (_: Exception) {
                    runCatching { attempt.close() }
                }
            }
            val srv = bound ?: throw IllegalStateException(
                "no free local port in $listenPort..$maxListenPort for the SSH SOCKS server",
            )
            server = srv
            running.set(true)
            worker("ssh-socks-accept") { acceptLoop(srv) }
            LogBus.append("I/$TAG SSH connected to ${profile.host}:${profile.port}, SOCKS on $listenHost:$boundPort")
            boundPort
        } catch (e: Exception) {
            lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            LogBus.append("E/$TAG SSH failed to start: $lastError")
            stop()
            -1
        }
    }

    fun stop(): Boolean {
        running.set(false)
        runCatching { server?.close() }
        server = null
        runCatching { session?.disconnect() }
        session = null
        clients.forEach { runCatching { it.close() } }
        val deadline = System.nanoTime() + 5_000_000_000L
        workers.toTypedArray().filter { it !== Thread.currentThread() }.forEach { thread ->
            thread.interrupt()
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining > 0) thread.join(remaining)
        }
        return workers.none { it !== Thread.currentThread() && it.isAlive } && clients.isEmpty()
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            val client = try {
                listener.accept()
            } catch (e: Exception) {
                if (running.get()) continue else return
            }
            if (!running.get()) { client.close(); return }
            clients.add(client)
            worker("ssh-socks") { serveClient(client) }
        }
    }

    private class Target(val host: String, val port: Int)

    private class Tunnel(val channel: ChannelDirectTCPIP, val input: InputStream, val output: OutputStream)

    private fun serveClient(client: Socket) {
        try {
            client.use { socket ->
                socket.tcpNoDelay = true
                val fromClient = socket.getInputStream()
                val toClient = socket.getOutputStream()
                val target = readConnectRequest(fromClient, toClient) ?: return
                val tunnel = openTunnel(target) ?: run {
                    toClient.socksReply(REPLY_REFUSED)
                    return
                }
                toClient.socksReply(REPLY_SUCCEEDED)
                splice(fromClient, toClient, tunnel)
            }
        } catch (e: Exception) {
            if (running.get()) LogBus.append("D/$TAG SOCKS client ended")
        } finally { clients.remove(client) }
    }

    private fun readConnectRequest(input: InputStream, output: OutputStream): Target? {
        if (input.read() != SOCKS_VERSION) return null
        val methodCount = input.read()
        if (methodCount < 0) return null
        val methods = input.readBytes(methodCount)
        if (methods.none { it.toInt() == METHOD_NO_AUTH }) { output.sendAll(byteArrayOf(5, -1)); return null }
        output.sendAll(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH.toByte()))

        if (input.read() != SOCKS_VERSION) return null
        val command = input.read()
        input.read()
        val host = when (input.read()) {
            ADDR_IPV4 -> InetAddress.getByAddress(input.readBytes(4)).hostAddress
            ADDR_IPV6 -> InetAddress.getByAddress(input.readBytes(16)).hostAddress
            ADDR_DOMAIN -> {
                val length = input.read()
                if (length <= 0) return null
                String(input.readBytes(length), Charsets.US_ASCII)
            }
            else -> {
                output.socksReply(REPLY_ADDRESS_TYPE_UNSUPPORTED)
                return null
            }
        }
        val portBytes = input.readBytes(2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        if (command != CMD_CONNECT) {
            output.socksReply(REPLY_COMMAND_UNSUPPORTED)
            return null
        }
        return Target(host, port)
    }

    private fun openTunnel(target: Target): Tunnel? {
        val ssh = session ?: return null
        val channel = runCatching { ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP }.getOrNull()
            ?: return null
        channel.setHost(target.host)
        channel.setPort(target.port)

        val input = channel.inputStream
        val output = channel.outputStream
        return try {
            channel.connect(CONNECT_TIMEOUT_MS)
            Tunnel(channel, input, output)
        } catch (e: Exception) {
            runCatching { channel.disconnect() }
            null
        }
    }

    private fun splice(fromClient: InputStream, toClient: OutputStream, tunnel: Tunnel) {
        val upstream = worker("ssh-socks-up") {
            runCatching { fromClient.pumpInto(tunnel.output) }
            runCatching { tunnel.output.close() }
        }
        runCatching { tunnel.input.pumpInto(toClient) }
        runCatching { tunnel.channel.disconnect() }
        upstream.interrupt()
    }

    private fun sessionProps(): Properties = Properties().apply {
        put("StrictHostKeyChecking", "ask")
        if (cipher != "auto" && cipher.isNotBlank()) {
            put("cipher.s2c", cipher); put("cipher.c2s", cipher)
        } else {
            put("cipher.s2c", AUTO_CIPHER_ORDER); put("cipher.c2s", AUTO_CIPHER_ORDER)
        }
        if (compression) {
            put("compression.s2c", "zlib@openssh.com,zlib,none")
            put("compression.c2s", "zlib@openssh.com,zlib,none")
        } else {
            put("compression.s2c", "none"); put("compression.c2s", "none")
        }
    }

    private fun InputStream.readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(bytes, filled, count - filled)
            if (read < 0) throw EOFException()
            filled += read
        }
        return bytes
    }

    private fun InputStream.pumpInto(sink: OutputStream) {
        val buffer = ByteArray(PUMP_BUFFER)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            sink.write(buffer, 0, read)
            sink.flush()
        }
    }

    private fun OutputStream.sendAll(bytes: ByteArray) {
        write(bytes)
        flush()
    }

    private fun OutputStream.socksReply(status: Int) {
        val reply = ByteArray(10)
        reply[0] = SOCKS_VERSION.toByte()
        reply[1] = status.toByte()
        reply[3] = ADDR_IPV4.toByte()
        sendAll(reply)
    }

    private companion object {
        const val TAG = "Ssh"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val PUMP_BUFFER = 16384

        const val SOCKS_VERSION = 0x05
        const val METHOD_NO_AUTH = 0x00
        const val CMD_CONNECT = 0x01
        const val ADDR_IPV4 = 0x01
        const val ADDR_DOMAIN = 0x03
        const val ADDR_IPV6 = 0x04
        const val REPLY_SUCCEEDED = 0x00
        const val REPLY_REFUSED = 0x05
        const val REPLY_COMMAND_UNSUPPORTED = 0x07
        const val REPLY_ADDRESS_TYPE_UNSUPPORTED = 0x08
        const val AUTO_CIPHER_ORDER =
            "aes128-gcm@openssh.com,chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes256-ctr"
        const val KEX_ORDER =
            "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384," +
                "ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512," +
                "diffie-hellman-group18-sha512,diffie-hellman-group14-sha256"
    }
}
