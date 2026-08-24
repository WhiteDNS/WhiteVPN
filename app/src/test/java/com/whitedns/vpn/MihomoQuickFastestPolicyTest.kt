package com.whitedns.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoQuickFastestPolicyTest {
    @Test
    fun planUsesLiveUrlSpecificHistoryAndPreservesBlankFixedSelection() {
        val testUrl = "https://example.test/generate_204"
        val response = response(
            testUrl = testUrl,
            fixed = "",
            members = listOf(
                "Nested",
                "Slow",
                "Fast",
                "Fast",
                "Dead",
                "Stale",
                "Empty",
                "Generic",
                "Unknown",
                "Middle",
            ),
            proxies = mapOf(
                "Nested" to JSONObject().put("type", "Selector").put("all", JSONArray(listOf("Fast"))),
                "Slow" to leaf(testUrl, alive = true, delays = listOf(90)),
                "Fast" to leaf(testUrl, alive = true, delays = listOf(70, 10)),
                "Dead" to leaf(testUrl, alive = false, delays = listOf(1)),
                "Stale" to leaf(testUrl, alive = true, delays = listOf(2, 0)),
                "Empty" to leaf(testUrl, alive = true, delays = emptyList()),
                "Generic" to leaf("https://other.test/generate_204", alive = true, delays = listOf(1)),
                "Unknown" to leaf(testUrl, alive = true, delays = listOf(3)),
                "Middle" to leaf(testUrl, alive = true, delays = listOf(40)),
            ),
        )

        val plan = MihomoQuickFastestPolicy.plan(
            response = response,
            groupName = GROUP,
            availableProfileNames = setOf(
                "Nested",
                "Slow",
                "Fast",
                "Dead",
                "Stale",
                "Empty",
                "Generic",
                "Middle",
            ),
        )

        assertEquals("", plan?.originalFixed)
        assertEquals(listOf("Fast", "Middle", "Slow"), plan?.candidates?.map { it.name })
        assertEquals(listOf(10, 40, 90), plan?.candidates?.map { it.delayMs })
    }

    @Test
    fun planRequiresUrlTestCapabilitiesAndAtLeastTwoCandidates() {
        val valid = response(
            fixed = "Node A",
            members = listOf("Node A", "Node B"),
            proxies = mapOf(
                "Node A" to leaf(TEST_URL, alive = true, delays = listOf(10)),
                "Node B" to leaf(TEST_URL, alive = true, delays = listOf(20)),
            ),
        )
        val profiles = setOf("Node A", "Node B")

        assertTrue(MihomoQuickFastestPolicy.hasRequiredCapabilities(valid, GROUP))
        val plan = MihomoQuickFastestPolicy.plan(valid, GROUP, profiles)
        assertEquals("Node A", plan?.originalFixed)
        assertEquals(listOf("Node A", "Node B"), plan?.candidates?.map { it.name })
        assertFalse(
            MihomoQuickFastestPolicy.hasRequiredCapabilities(
                JSONObject(valid.toString()).apply {
                    getJSONObject("proxies").getJSONObject(GROUP).remove("fixed")
                },
                GROUP,
            ),
        )
        assertNull(
            MihomoQuickFastestPolicy.plan(
                JSONObject(valid.toString()).apply {
                    getJSONObject("proxies").getJSONObject(GROUP).remove("fixed")
                },
                GROUP,
                profiles,
            ),
        )
        assertNull(
            MihomoQuickFastestPolicy.plan(
                JSONObject(valid.toString()).apply {
                    getJSONObject("proxies").getJSONObject(GROUP).remove("testUrl")
                },
                GROUP,
                profiles,
            ),
        )
        assertNull(
            MihomoQuickFastestPolicy.plan(
                JSONObject(valid.toString()).apply {
                    getJSONObject("proxies").getJSONObject(GROUP).put("type", "Fallback")
                },
                GROUP,
                profiles,
            ),
        )
        assertNull(MihomoQuickFastestPolicy.plan(valid, GROUP, setOf("Node A")))
    }

    @Test
    fun planCapsOneThousandProfilesAtThreeLowestDelays() {
        val proxies = linkedMapOf<String, JSONObject>()
        val members = (0 until 1_000).map { index ->
            "Node $index".also { name ->
                proxies[name] = leaf(TEST_URL, alive = true, delays = listOf(1_000 - index))
            }
        }

        val plan = MihomoQuickFastestPolicy.plan(
            response = response(members = members, proxies = proxies),
            groupName = GROUP,
            availableProfileNames = members.toSet(),
        )

        assertEquals(listOf("Node 999", "Node 998", "Node 997"), plan?.candidates?.map { it.name })
    }

    @Test
    fun winnerUsesEightyPercentBandThenDelaySpeedAndOrder() {
        val fastest = measurement("Fastest", delayMs = 80, order = 0, speedKbps = 1_000)
        val boundary = measurement("Boundary", delayMs = 20, order = 1, speedKbps = 800)
        val belowBand = measurement("Below", delayMs = 1, order = 2, speedKbps = 799)

        assertEquals(
            boundary,
            MihomoQuickFastestPolicy.winner(listOf(fastest, boundary, belowBand)),
        )
        assertEquals(
            fastest,
            MihomoQuickFastestPolicy.winner(
                listOf(fastest, measurement("Too slow", 1, 1, 790)),
            ),
        )
        assertEquals(
            "First",
            MihomoQuickFastestPolicy.winner(
                listOf(
                    measurement("Second", 10, 2, 900),
                    measurement("First", 10, 1, 900),
                ),
            )?.candidate?.name,
        )
        assertNull(MihomoQuickFastestPolicy.winner(listOf(fastest)))
    }

    @Test
    fun pinnedVerificationRequiresFixedNowAndActiveRootPath() {
        val response = response(
            fixed = "Winner",
            now = "Winner",
            members = listOf("Winner", "Other"),
            proxies = mapOf(
                "Winner" to leaf(TEST_URL, alive = true, delays = listOf(10)),
                "Other" to leaf(TEST_URL, alive = true, delays = listOf(20)),
                "Traffic" to JSONObject()
                    .put("type", "Selector")
                    .put("now", GROUP)
                    .put("all", JSONArray(listOf(GROUP))),
            ),
        )

        assertTrue(MihomoQuickFastestPolicy.isPinnedActive(response, "Traffic", GROUP, "Winner"))
        assertFalse(
            MihomoQuickFastestPolicy.isPinnedActive(
                JSONObject(response.toString()).apply {
                    getJSONObject("proxies").getJSONObject(GROUP).put("fixed", "")
                },
                "Traffic",
                GROUP,
                "Winner",
            ),
        )
        assertFalse(
            MihomoQuickFastestPolicy.isPinnedActive(
                JSONObject(response.toString()).apply {
                    getJSONObject("proxies").getJSONObject(GROUP).put("now", "Other")
                },
                "Traffic",
                GROUP,
                "Winner",
            ),
        )
        assertFalse(MihomoQuickFastestPolicy.isPinnedActive(response, "Missing", GROUP, "Winner"))
    }

    @Test
    fun completeSpeedRejectsPartialAndInvalidMeasurements() {
        assertEquals(8_000, ConnectionSpeed.completeKbps(1_000_000, 1_000_000, 1_000_000_000))
        assertNull(ConnectionSpeed.completeKbps(999_999, 1_000_000, 1_000_000_000))
        assertNull(ConnectionSpeed.completeKbps(1_000_000, 1_000_000, 0))
    }

    private fun response(
        testUrl: String = TEST_URL,
        fixed: String = "",
        now: String = "",
        members: List<String>,
        proxies: Map<String, JSONObject>,
    ): JSONObject {
        val allProxies = JSONObject()
        proxies.forEach(allProxies::put)
        allProxies.put(
            GROUP,
            JSONObject()
                .put("type", "URLTest")
                .put("fixed", fixed)
                .put("now", now)
                .put("all", JSONArray(members))
                .put("testUrl", testUrl),
        )
        return JSONObject().put("proxies", allProxies)
    }

    private fun leaf(testUrl: String, alive: Boolean, delays: List<Int>): JSONObject {
        return JSONObject()
            .put("type", "Vless")
            .put(
                "extra",
                JSONObject().put(
                    testUrl,
                    JSONObject()
                        .put("alive", alive)
                        .put(
                            "history",
                            JSONArray(delays.map { delay -> JSONObject().put("delay", delay) }),
                        ),
                ),
            )
    }

    private fun measurement(name: String, delayMs: Int, order: Int, speedKbps: Int) =
        MihomoQuickFastestMeasurement(
            MihomoQuickFastestCandidate(name, delayMs, order),
            speedKbps,
        )

    private companion object {
        const val GROUP = "Auto Select"
        const val TEST_URL = "https://example.test/generate_204"
    }
}
