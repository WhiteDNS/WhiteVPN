package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EngineSocksHandshakeTest {
    @Test fun probeSendsDomainToProxyWithoutLocalResolution() {
        ServerSocket(0).use { listener ->
            val request = CompletableFuture<String>()
            val worker = Thread {
                try {
                    listener.accept().use { server ->
                        server.soTimeout = 2000
                        val input = server.getInputStream(); val output = server.getOutputStream()
                        assertEquals(5, input.read()); assertEquals(1, input.read()); assertEquals(0, input.read())
                        output.write(byteArrayOf(5, 0)); output.flush()
                        assertEquals(5, input.read()); assertEquals(1, input.read()); assertEquals(0, input.read()); assertEquals(3, input.read())
                        val bytes = ByteArray(input.read())
                        for (index in bytes.indices) bytes[index] = input.read().toByte()
                        val port = input.read() * 256 + input.read()
                        request.complete(String(bytes, Charsets.US_ASCII) + ":" + port)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    }
                } catch (error: Throwable) { request.completeExceptionally(error) }
            }.apply { start() }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 2000
                EngineSocksHandshake.connect(socket, "no-local-resolution.invalid", 443)
            }
            assertEquals("no-local-resolution.invalid:443", request.get(3, TimeUnit.SECONDS))
            worker.join(3000); assertFalse(worker.isAlive)
        }
    }
    @Test fun rejectedSocksAuthenticationCannotProceed() {
        ServerSocket(0).use { listener ->
            val worker = Thread {
                listener.accept().use { server ->
                    server.soTimeout = 2000
                    repeat(3) { server.getInputStream().read() }
                    server.getOutputStream().apply { write(byteArrayOf(5, -1)); flush() }
                }
            }.apply { start() }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 2000
                assertThrows(IllegalStateException::class.java) { EngineSocksHandshake.connect(socket, "example.com", 443) }
            }
            worker.join(3000)
        }
    }
}
