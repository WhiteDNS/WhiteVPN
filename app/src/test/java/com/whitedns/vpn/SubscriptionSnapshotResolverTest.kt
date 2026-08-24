package com.whitedns.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SubscriptionSnapshotResolverTest {
    @Test
    fun freshSnapshotResolvesWithoutLoadingItsSource() = runBlocking {
        val cachedYaml = subscriptionYaml("Cached Node")
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = cachedYaml,
                fetchedAt = 900L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = { error("fresh cache must not load its source") },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val resolution = resolver.resolve("subscription-1")

        assertEquals(SubscriptionSnapshotOrigin.FreshCache, resolution.origin)
        assertEquals(cachedYaml, resolution.snapshot.rawConfig)
        assertEquals(900L, resolution.snapshot.catalog.fetchedAt)
        assertEquals("Cached Node", resolution.snapshot.catalog.profiles.single().tag)
    }

    @Test
    fun staleSnapshotRefreshesAndPersistsCanonicalYaml() = runBlocking {
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = subscriptionYaml("Stale Node"),
                fetchedAt = 100L,
            ),
        )
        val refreshedYaml = subscriptionYaml("Refreshed Node")
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = { refreshedYaml },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val resolution = resolver.resolve("subscription-1")

        assertEquals(SubscriptionSnapshotOrigin.Refreshed, resolution.origin)
        assertEquals("Refreshed Node", resolution.snapshot.catalog.profiles.single().tag)
        assertEquals(1_000L, resolution.snapshot.catalog.fetchedAt)
        assertEquals(refreshedYaml, persistence.read("subscription-1")?.cachedYaml)
        assertEquals(1_000L, persistence.read("subscription-1")?.fetchedAt)
    }

    @Test
    fun failedStaleRefreshReturnsLastKnownGoodSnapshot() = runBlocking {
        val staleYaml = subscriptionYaml("Last Known Good")
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = staleYaml,
                fetchedAt = 100L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = { throw java.io.IOException("sanitized source failure") },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val resolution = resolver.resolve("subscription-1")

        assertEquals(SubscriptionSnapshotOrigin.LastKnownGood, resolution.origin)
        assertEquals(staleYaml, resolution.snapshot.rawConfig)
        assertEquals(100L, resolution.snapshot.catalog.fetchedAt)
        assertEquals("Last Known Good", resolution.snapshot.catalog.profiles.single().tag)
        assertEquals("sanitized source failure", persistence.recordedFailure?.message)
    }

    @Test
    fun forceRefreshPropagatesFailureAndPreservesTheCache() = runBlocking {
        val cachedYaml = subscriptionYaml("Cached Node")
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = cachedYaml,
                fetchedAt = 900L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = { throw java.io.IOException("sanitized source failure") },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val error = runCatching {
            resolver.resolve("subscription-1", SubscriptionRefreshPolicy.Force)
        }.exceptionOrNull()

        assertEquals(IOException::class.java, error?.javaClass)
        assertEquals(cachedYaml, persistence.read("subscription-1")?.cachedYaml)
        assertEquals(900L, persistence.read("subscription-1")?.fetchedAt)
    }

    @Test
    fun inlineSourceResolvesWithoutACachedSnapshot() = runBlocking {
        val inlineYaml = subscriptionYaml("Inline Node")
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.Inline(inlineYaml),
                cachedYaml = null,
                fetchedAt = 0L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val resolution = resolver.resolve("subscription-1")

        assertEquals(SubscriptionSnapshotOrigin.Refreshed, resolution.origin)
        assertEquals("Inline Node", resolution.snapshot.catalog.profiles.single().tag)
        assertEquals(inlineYaml, persistence.read("subscription-1")?.cachedYaml)
    }

    @Test
    fun explicitSourceKindWinsAndLegacyRecordsAreInferred() {
        assertEquals(
            UserSubscriptionSourceKind.Inline,
            UserSubscriptionSourceKind.fromWireName("inline", "https://example.com/subscription"),
        )
        assertEquals(
            UserSubscriptionSourceKind.Https,
            UserSubscriptionSourceKind.fromWireName(null, "https://example.com/subscription"),
        )
        assertEquals(
            UserSubscriptionSourceKind.Inline,
            UserSubscriptionSourceKind.fromWireName(null, subscriptionYaml("Inline Node")),
        )
    }

    @Test
    fun remoteSourceRejectsNonHttpsUrls() = runBlocking {
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("http://example.com/subscription"),
                cachedYaml = null,
                fetchedAt = 0L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val error = runCatching { resolver.resolve("subscription-1") }.exceptionOrNull()

        assertEquals(IOException::class.java, error?.javaClass)
        assertEquals("Subscription Source must use HTTPS", error?.cause?.message)
    }

    @Test
    fun cachedReadRejectsInvalidYamlWithoutLoadingTheSource() {
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = "proxy-groups: [corrupt",
                fetchedAt = 900L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = { error("cached-only read must not load its source") },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        assertEquals(null, resolver.cached("subscription-1"))
    }

    @Test
    fun cancellationNeverFallsBackToCachedContent() = runBlocking {
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = subscriptionYaml("Cached Node"),
                fetchedAt = 100L,
            ),
        )
        val loading = CompletableDeferred<Unit>()
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = {
                loading.complete(Unit)
                CompletableDeferred<String>().await()
            },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val refresh = launch { resolver.resolve("subscription-1") }
        loading.await()
        refresh.cancelAndJoin()

        assertEquals(0, persistence.saveCount)
        assertEquals("Cached Node", persistence.read("subscription-1")?.cachedYaml?.let {
            MihomoConfigParser.parse(it, 100L).catalog.profiles.single().tag
        })
    }

    @Test
    fun invalidFreshCacheIsRefreshed() = runBlocking {
        val refreshedYaml = subscriptionYaml("Recovered Node")
        val persistence = InMemorySubscriptionSnapshotPersistence(
            SubscriptionSnapshotEntry(
                source = SubscriptionSource.RemoteHttps("https://example.com/subscription"),
                cachedYaml = "proxy-groups: [corrupt",
                fetchedAt = 900L,
            ),
        )
        val resolver = SubscriptionSnapshotResolver(
            persistence = persistence,
            loadSource = { refreshedYaml },
            nowMs = { 1_000L },
            freshnessMs = 200L,
        )

        val resolution = resolver.resolve("subscription-1")

        assertEquals(SubscriptionSnapshotOrigin.Refreshed, resolution.origin)
        assertEquals("Recovered Node", resolution.snapshot.catalog.profiles.single().tag)
        assertEquals(refreshedYaml, persistence.read("subscription-1")?.cachedYaml)
    }

    @Test
    fun failedMetadataWriteRestoresPreviousSnapshot() {
        val directory = Files.createTempDirectory("subscription-snapshot-").toFile()
        val snapshot = File(directory, "subscription.yaml").apply { writeText("previous") }
        try {
            assertThrows(IOException::class.java) {
                replaceSubscriptionSnapshot(snapshot, "replacement") {
                    throw IOException("metadata write failed")
                }
            }

            assertEquals("previous", snapshot.readText())
        } finally {
            snapshot.delete()
            assertFalse(directory.exists() && !directory.delete())
        }
    }

    private class InMemorySubscriptionSnapshotPersistence(
        entry: SubscriptionSnapshotEntry,
    ) : SubscriptionSnapshotPersistence {
        private val entries = mutableMapOf("subscription-1" to entry)
        var saveCount = 0
            private set
        var recordedFailure: Throwable? = null
            private set

        override fun read(id: String): SubscriptionSnapshotEntry? = entries[id]

        override fun save(id: String, subscription: CompiledSubscription) {
            saveCount += 1
            val previous = checkNotNull(entries[id])
            entries[id] = previous.copy(
                cachedYaml = subscription.snapshot.rawConfig,
                fetchedAt = subscription.snapshot.catalog.fetchedAt,
            )
        }

        override fun recordFailure(id: String, error: Throwable) {
            recordedFailure = error
        }
    }

    private fun subscriptionYaml(name: String): String = """
        proxies:
          - name: '$name'
            type: vless
            server: 203.0.113.10
            port: 443
        proxy-groups:
          - name: WhiteDNS Proxy
            type: select
            proxies:
              - '$name'
    """.trimIndent()
}
