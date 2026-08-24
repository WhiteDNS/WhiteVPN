package com.whitedns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubscriptionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SubscriptionStore(context)
    private val subscriptionId = "delay-bulk-${System.nanoTime()}"
    private val profiles = listOf(
        profile("first", "first-$subscriptionId"),
        profile("second", "second-$subscriptionId"),
        profile("invalid", "invalid-$subscriptionId"),
    )

    @After
    fun cleanUp() {
        store.deleteConnectionDelayRecords(
            subscriptionId,
            profiles.mapTo(mutableSetOf(), ConnectionProfile::fingerprint),
        )
    }

    @Test
    fun bulkSaveKeepsLatestRecordsNormalizesValuesAndReplacesAtomically() {
        val first = profiles[0]
        val second = profiles[1]
        val invalid = profiles[2]
        store.saveConnectionDelayRecord(
            record(first, delayMs = 30, testedAt = 300L, speedKbps = 3_000),
        )

        store.saveConnectionDelayRecords(
            listOf(
                record(first, delayMs = null, testedAt = 200L, status = ConnectionDelayStatus.Failure),
                record(second, delayMs = 80, testedAt = 100L),
                record(second, delayMs = 50, testedAt = 200L, speedKbps = 2_000),
                record(invalid, delayMs = 0, testedAt = 200L, speedKbps = 1_000),
                record(first, delayMs = 10, testedAt = 0L),
            ),
        )

        val records = store.readConnectionDelayRecords(
            subscriptionId = subscriptionId,
            profiles = profiles,
            nowMs = 400L,
            ttlMs = 1_000L,
        ).associateBy(ConnectionDelayRecord::fingerprint)

        assertEquals(3, records.size)
        assertEquals(30, records.getValue(first.fingerprint).delayMs)
        assertEquals(300L, records.getValue(first.fingerprint).testedAt)
        assertEquals(50, records.getValue(second.fingerprint).delayMs)
        assertEquals(2_000, records.getValue(second.fingerprint).speedKbps)
        assertEquals(ConnectionDelayStatus.Failure, records.getValue(invalid.fingerprint).status)
        assertNull(records.getValue(invalid.fingerprint).delayMs)
        assertNull(records.getValue(invalid.fingerprint).speedKbps)

        val cacheFile = File(context.filesDir, "profile-delay-cache.json")
        JSONArray(cacheFile.readText())
        assertFalse(File(context.filesDir, "profile-delay-cache.json.tmp").exists())
    }

    private fun record(
        profile: ConnectionProfile,
        delayMs: Int?,
        testedAt: Long,
        status: ConnectionDelayStatus = ConnectionDelayStatus.Success,
        speedKbps: Int? = null,
    ) = ConnectionDelayRecord(
        subscriptionId = subscriptionId,
        fingerprint = profile.fingerprint,
        delayMs = delayMs,
        status = status,
        testedAt = testedAt,
        speedKbps = speedKbps,
    )

    private fun profile(tag: String, fingerprint: String) = ConnectionProfile(
        tag = tag,
        type = "vless",
        server = "$tag.example.com",
        port = 443,
        transport = "",
        validationHost = "$tag.example.com",
        fingerprint = fingerprint,
    )
}
