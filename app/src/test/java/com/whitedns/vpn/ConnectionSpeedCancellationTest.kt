package com.whitedns.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionSpeedCancellationTest {
    @Test
    fun stoppingOneDownloadClosesItsConnectionWhileAnotherFinishes() = runBlocking {
        withTimeout(5_000) {
            val first = BlockingDownload()
            val second = BlockingDownload()
            val canceled = async { MihomoRuntimeHealth.measureDownloadSpeed(first, 1_024) }
            val completed = async { MihomoRuntimeHealth.measureDownloadSpeed(second, 1_024) }
            first.reading.await()
            second.reading.await()

            withTimeout(1_500) { canceled.cancelAndJoin() }
            assertTrue(first.disconnected.get())
            assertFalse(second.disconnected.get())
            assertTrue(completed.isActive)

            second.release.countDown()
            assertNotNull(completed.await())
            assertTrue(second.disconnected.get())
        }
    }

    private class BlockingDownload : HttpURLConnection(URL("https://example.test/")) {
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val disconnected = AtomicBoolean()

        override fun getResponseCode() = HTTP_OK
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int = error("Use the bulk read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reading.complete(Unit)
                // Model a socket that ignores interrupts and requires disconnect.
                while (release.count > 0) {
                    try {
                        check(release.await(3, TimeUnit.SECONDS)) { "Download did not close" }
                    } catch (_: InterruptedException) {
                        // The cancellation handler must close this connection.
                    }
                }
                return if (disconnected.get()) -1 else length
            }
        }

        override fun disconnect() {
            disconnected.set(true)
            release.countDown()
        }

        override fun connect() = Unit
        override fun usingProxy() = true
    }
}
