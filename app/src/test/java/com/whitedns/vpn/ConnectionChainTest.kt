package com.whitedns.vpn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionChainTest {
    @Test
    fun baseCannotBeOffAndBrokenFixedReferencesAreNormalized() {
        val settings = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.fixed("", "missing"),
            base = ConnectionChainHop.off(),
            after = ConnectionChainHop.fixed("sub", ""),
        ).normalized()

        assertEquals(ConnectionChainHopMode.Off, settings.before.mode)
        assertEquals(ConnectionChainHopMode.Automatic, settings.base.mode)
        assertEquals(ConnectionChainHopMode.Off, settings.after.mode)
        assertFalse(settings.isActive)
    }

    @Test
    fun preservesExistingDialerClosureAndAttachesTheNextHopToItsRoot() {
        val first = source(
            "first",
            proxy("transport", "vless", udp = true) +
                proxy("tunnel", "wireguard", udp = true, dialer = "transport"),
        )
        val second = source("second", proxy("exit", "vless"))
        val plan = plans(
            fixedSettings(first.ref("tunnel"), second.ref("exit")),
            first,
            second,
        ).single()

        assertTrue(plan.rawYaml.contains("name: 'WhiteDNS Chain 1.1'"))
        assertTrue(plan.rawYaml.contains("name: 'WhiteDNS Chain 1.2'"))
        assertTrue(plan.rawYaml.contains("name: 'WhiteDNS Chain 2.1'"))
        assertTrue(plan.rawYaml.contains("dialer-proxy: 'WhiteDNS Chain 1.1'"))
        assertTrue(plan.rawYaml.contains("dialer-proxy: 'WhiteDNS Chain 1.2'"))
        assertEquals("WhiteDNS Chain 2.1", plan.finalRuntimeName)
    }

    @Test
    fun requiresUdpOnlyWhenTheDownstreamTransportNeedsIt() {
        val tcpOnly = source("tcp", proxy("tcp", "vless"))
        val udp = source("udp", proxy("udp", "vless", udp = true))
        val wireguard = source("wg", proxy("wg", "wireguard", udp = true))

        assertThrows(IOException::class.java) {
            plans(fixedSettings(tcpOnly.ref("tcp"), wireguard.ref("wg")), tcpOnly, wireguard)
        }
        assertEquals(
            1,
            plans(fixedSettings(udp.ref("udp"), wireguard.ref("wg")), udp, wireguard).size,
        )

        val ordinaryExit = source("ordinary", proxy("ordinary", "trojan"))
        assertEquals(
            1,
            plans(fixedSettings(tcpOnly.ref("tcp"), ordinaryExit.ref("ordinary")), tcpOnly, ordinaryExit).size,
        )
    }

    @Test
    fun protocolFlagsUseThePlannerUdpDialerRule() {
        val source = source(
            "protocols",
            proxy("tcp", "vless") +
                proxy("udp", "vless", udp = true) +
                proxy("hy2", "hysteria2") +
                proxy("wireguard-no-udp", "wireguard"),
        )

        val support = ConnectionChainPlanner.udpSupportByFingerprint(source.snapshot)

        assertEquals(false, support[source.ref("tcp").fingerprint])
        assertEquals(true, support[source.ref("udp").fingerprint])
        assertEquals(true, support[source.ref("hy2").fingerprint])
        assertEquals(false, support[source.ref("wireguard-no-udp").fingerprint])
    }

    @Test
    fun selectedCompatibilityExplainsFixedConnectionFailures() {
        val tcp = source("tcp", proxy("tcp", "vless"))
        val udp = source("udp", proxy("udp", "vless", udp = true))
        val wireguard = source("wg", proxy("wg", "wireguard", udp = true))
        val shared = source(
            "shared",
            proxy("transport", "vless", udp = true) +
                proxy("tunnel", "wireguard", udp = true, dialer = "transport"),
        )

        assertEquals(
            ConnectionChainCompatibilityIssue.DownstreamRequiresUdpCapableUpstream,
            ConnectionChainPlanner.selectedCompatibilityIssue(
                fixedBaseAfterSettings(tcp.ref("tcp"), wireguard.ref("wg")),
                listOf(tcp, wireguard),
            ),
        )
        assertNull(
            ConnectionChainPlanner.selectedCompatibilityIssue(
                fixedBaseAfterSettings(udp.ref("udp"), wireguard.ref("wg")),
                listOf(udp, wireguard),
            ),
        )
        assertEquals(
            ConnectionChainCompatibilityIssue.SameConnection,
            ConnectionChainPlanner.selectedCompatibilityIssue(
                fixedBaseAfterSettings(tcp.ref("tcp"), tcp.ref("tcp")),
                listOf(tcp),
            ),
        )
        assertEquals(
            ConnectionChainCompatibilityIssue.SharedProxyDependency,
            ConnectionChainPlanner.selectedCompatibilityIssue(
                fixedBaseAfterSettings(shared.ref("transport"), shared.ref("tunnel")),
                listOf(shared),
            ),
        )
    }

    @Test
    fun wireguardWithoutUdpIsNotAcceptedAsAnUdpDialer() {
        val upstream = source("up", proxy("up", "wireguard"))
        val downstream = source("down", proxy("down", "wireguard", udp = true))

        assertThrows(IOException::class.java) {
            plans(fixedSettings(upstream.ref("up"), downstream.ref("down")), upstream, downstream)
        }
    }

    @Test
    fun nativeUdpRelayRequiresAnUdpCapableUpstream() {
        val tcpOnly = source("tcp", proxy("tcp", "vless"))
        val udp = source("udp", proxy("udp", "vless", udp = true))
        val shadowsocks = source("ss", proxy("ss", "ss", udp = true))

        assertThrows(IOException::class.java) {
            plans(fixedSettings(tcpOnly.ref("tcp"), shadowsocks.ref("ss")), tcpOnly, shadowsocks)
        }
        assertEquals(
            1,
            plans(fixedSettings(udp.ref("udp"), shadowsocks.ref("ss")), udp, shadowsocks).size,
        )
    }

    @Test
    fun tcpOpenVpnAndHttp2MasqueCanUseATcpOnlyUpstream() {
        val upstream = source("upstream", proxy("tcp", "vless"))
        val openVpnTcp = source("openvpn-tcp", proxy("ovpn", "openvpn", proto = "tcp"))
        val masqueH2 = source("masque-h2", proxy("masque", "masque", network = "h2"))
        val openVpnUdp = source("openvpn-udp", proxy("ovpn-udp", "openvpn"))

        assertEquals(
            1,
            plans(fixedSettings(upstream.ref("tcp"), openVpnTcp.ref("ovpn")), upstream, openVpnTcp).size,
        )
        assertEquals(
            1,
            plans(fixedSettings(upstream.ref("tcp"), masqueH2.ref("masque")), upstream, masqueH2).size,
        )
        assertThrows(IOException::class.java) {
            plans(fixedSettings(upstream.ref("tcp"), openVpnUdp.ref("ovpn-udp")), upstream, openVpnUdp)
        }
    }

    @Test
    fun rejectsAnIncompatibleDependencyInsideAnImportedChain() {
        val imported = source(
            "imported",
            proxy("tcp", "vless") +
                proxy("wg-over-tcp", "wireguard", udp = true, dialer = "tcp"),
        )
        val exit = source("exit", proxy("exit", "vless"))

        assertThrows(IOException::class.java) {
            plans(fixedSettings(imported.ref("wg-over-tcp"), exit.ref("exit")), imported, exit)
        }
    }

    @Test
    fun doesNotAddAProxyThatIsAlreadyInsideAnotherSelectedHop() {
        val imported = source(
            "imported",
            proxy("transport", "vless", udp = true) +
                proxy("tunnel", "wireguard", udp = true, dialer = "transport"),
        )

        assertThrows(IOException::class.java) {
            plans(
                fixedSettings(imported.ref("transport"), imported.ref("tunnel")),
                imported,
            )
        }
    }

    @Test
    fun fixedSelectionSurvivesItsLatestFailedTest() {
        val first = source("first", proxy("first", "vless"))
        val second = source("second", proxy("second", "vless"))
        val failed = ConnectionDelayRecord(
            subscriptionId = first.subscriptionId,
            fingerprint = first.ref("first").fingerprint,
            delayMs = null,
            status = ConnectionDelayStatus.Failure,
            testedAt = 1,
        )

        assertEquals(
            1,
            ConnectionChainPlanner.plans(
                fixedSettings(first.ref("first"), second.ref("second")),
                listOf(first, second),
                listOf(failed),
            ).size,
        )
    }

    @Test
    fun automaticSearchCanReachACompatibleCandidateAfterTheFirstSix() {
        val upstream = source(
            "upstream",
            (1..7).joinToString("\n") { index ->
                proxy("candidate-%02d".format(index), "vless", udp = index == 7).trimEnd()
            } + "\n",
        )
        val downstream = source("downstream", proxy("wireguard", "wireguard", udp = true))
        val settings = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.automatic(),
            base = ConnectionChainHop.fixed(
                downstream.subscriptionId,
                downstream.ref("wireguard").fingerprint,
            ),
        )

        val plan = plans(settings, upstream, downstream).first()

        assertEquals("candidate-07", plan.hops.first().candidate.profile.tag)
    }

    @Test
    fun compatibilitySearchDoesNotLoseAHighRankUdpCandidate() {
        val source = source(
            "large",
            (1..320).joinToString("\n") { index ->
                proxy("tcp-%03d".format(index), "vless").trimEnd()
            } + "\n" +
                proxy("udp-last", "vless", udp = true) +
                proxy("wireguard", "wireguard", udp = true),
        )
        val wireguard = source.ref("wireguard")
        val settings = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.automatic(),
            base = ConnectionChainHop.automatic(),
            after = ConnectionChainHop.fixed(wireguard.subscriptionId, wireguard.fingerprint),
        )
        val records = source.snapshot.catalog.profiles.mapNotNull { profile ->
            when {
                profile.tag.startsWith("tcp-") -> ConnectionDelayRecord(
                    subscriptionId = source.subscriptionId,
                    fingerprint = profile.fingerprint,
                    delayMs = 1,
                    status = ConnectionDelayStatus.Success,
                    testedAt = 1,
                )
                profile.tag == "wireguard" -> ConnectionDelayRecord(
                    subscriptionId = source.subscriptionId,
                    fingerprint = profile.fingerprint,
                    delayMs = null,
                    status = ConnectionDelayStatus.Failure,
                    testedAt = 1,
                )
                else -> null
            }
        }

        val plan = ConnectionChainPlanner.plans(settings, listOf(source), records).first()

        assertEquals("udp-last", plan.hops[1].candidate.profile.tag)
    }

    @Test
    fun automaticAttemptsDoNotAllDependOnTheFirstCandidate() {
        val source = source(
            "automatic",
            (1..6).joinToString("\n") { index ->
                proxy("candidate-%02d".format(index), "vless").trimEnd()
            } + "\n",
        )
        val settings = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.automatic(),
            base = ConnectionChainHop.automatic(),
            after = ConnectionChainHop.automatic(),
        )

        val plans = plans(settings, source)

        assertTrue(plans.any { plan ->
            plan.hops.none { it.candidate.profile.tag == "candidate-01" }
        })
    }

    @Test
    fun automaticRetriesDiversifySharedPhysicalDependencies() {
        val source = source(
            "dependencies",
            proxy("entry", "vless") +
                proxy("shared", "vless") +
                (1..13).joinToString("\n") { index ->
                    proxy("shared-root-%02d".format(index), "vless", dialer = "shared").trimEnd()
                } + "\n" +
                proxy("healthy", "vless") +
                proxy("exit", "vless"),
        )
        val settings = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.fixed(
                source.subscriptionId,
                source.ref("entry").fingerprint,
            ),
            base = ConnectionChainHop.automatic(),
            after = ConnectionChainHop.fixed(
                source.subscriptionId,
                source.ref("exit").fingerprint,
            ),
        )
        val records = source.snapshot.catalog.profiles.mapNotNull { profile ->
            when {
                profile.tag.startsWith("shared-root-") -> ConnectionDelayRecord(
                    subscriptionId = source.subscriptionId,
                    fingerprint = profile.fingerprint,
                    delayMs = 1,
                    status = ConnectionDelayStatus.Success,
                    testedAt = 1,
                )
                profile.tag in setOf("entry", "shared", "exit") -> ConnectionDelayRecord(
                    subscriptionId = source.subscriptionId,
                    fingerprint = profile.fingerprint,
                    delayMs = null,
                    status = ConnectionDelayStatus.Failure,
                    testedAt = 1,
                )
                else -> null
            }
        }

        val plans = ConnectionChainPlanner.plans(settings, listOf(source), records)

        assertTrue(plans.first().hops[1].candidate.profile.tag.startsWith("shared-root-"))
        assertTrue(plans.any { it.hops[1].candidate.profile.tag == "healthy" })
    }

    @Test
    fun fixedFutureHopPrunesNonAdjacentDependencyConflictsBeforeTheSearchLimit() {
        val source = source(
            "large-conflict",
            proxy("shared", "vless") +
                (1..1001).joinToString("\n") { index ->
                    proxy("bad-%04d".format(index), "vless", dialer = "shared").trimEnd()
                } + "\n" +
                (1..1000).joinToString("\n") { index ->
                    proxy("independent-%04d".format(index), "vless").trimEnd()
                } + "\n" +
                proxy("after", "vless", dialer = "shared"),
        )
        val after = source.ref("after")
        val settings = ConnectionChainSettings(
            enabled = true,
            before = ConnectionChainHop.automatic(),
            base = ConnectionChainHop.automatic(),
            after = ConnectionChainHop.fixed(after.subscriptionId, after.fingerprint),
        )
        val records = source.snapshot.catalog.profiles.map { profile ->
            ConnectionDelayRecord(
                subscriptionId = source.subscriptionId,
                fingerprint = profile.fingerprint,
                delayMs = if (profile.tag.startsWith("bad-")) 1 else 2,
                status = if (profile.tag in setOf("shared", "after")) {
                    ConnectionDelayStatus.Failure
                } else {
                    ConnectionDelayStatus.Success
                },
                testedAt = 1,
            )
        }

        val plan = ConnectionChainPlanner.plans(settings, listOf(source), records).first()

        assertTrue(plan.hops.take(2).all { it.candidate.profile.tag.startsWith("independent-") })
    }

    @Test
    fun namespacesSameNamedConnectionsFromDifferentSubscriptions() {
        val first = source("one", proxy("same", "vless", server = "one.example"))
        val second = source("two", proxy("same", "vless", server = "two.example"))
        val plan = plans(
            fixedSettings(first.ref("same"), second.ref("same")),
            first,
            second,
        ).single()

        assertTrue(plan.rawYaml.contains("name: 'WhiteDNS Chain 1.1'"))
        assertTrue(plan.rawYaml.contains("name: 'WhiteDNS Chain 2.1'"))
        assertEquals(2, plan.hops.map { it.candidate.ref }.distinct().size)
    }

    @Test
    fun rejectsDuplicateSourceProxyNames() {
        assertThrows(IOException::class.java) {
            MihomoProxyBlockParser.parse(
                "proxies:\n" + proxy("same", "vless") + proxy("same", "trojan"),
            )
        }
    }

    @Test
    fun pickerEligibilityExcludesUnsupportedGroupDialers() {
        val source = source("group", proxy("through-group", "vless", dialer = "Main"))

        assertTrue(ConnectionChainPlanner.selectableFingerprints(source.snapshot).isEmpty())
    }

    @Test
    fun pickerEligibilityDecodesDoubleQuotedUnicodeNames() {
        val source = source(
            "escaped",
            "  - name: \"\\uD83C\\uDDF0\\uD83C\\uDDF7 Seoul\"\n" +
                "    type: vless\n" +
                "    server: seoul.example\n" +
                "    port: 443\n",
        )

        assertEquals(1, ConnectionChainPlanner.selectableFingerprints(source.snapshot).size)
    }

    @Test
    fun pickerEligibilityPreservesHashInsideQuotedNames() {
        val source = source(
            "numbered",
            "  - name: 'US #1'\n" +
                "    type: vless\n" +
                "    server: numbered.example\n" +
                "    port: 443\n",
        )

        assertEquals(1, ConnectionChainPlanner.selectableFingerprints(source.snapshot).size)
    }

    @Test
    fun listItemsRemainSelectableWhenNameIsNotTheFirstField() {
        val shaped = source(
            "shape",
            "  - client-fingerprint: chrome\n" +
                "    name: late-name\n" +
                "    network: ws\n" +
                "    port: 443\n" +
                "    server: late.example\n" +
                "    tls: true\n" +
                "    type: vless\n",
        )
        val exit = source("exit", proxy("exit", "vless"))

        assertEquals(1, ConnectionChainPlanner.selectableFingerprints(shaped.snapshot).size)
        assertTrue(
            plans(fixedSettings(shaped.ref("late-name"), exit.ref("exit")), shaped, exit)
                .single()
                .rawYaml
                .contains("name: 'WhiteDNS Chain 1.1'"),
        )
    }

    @Test
    fun ipv6DependencyIsRejectedWhenTheDefaultNetworkCannotReachIpv6() {
        val imported = source(
            "imported",
            proxy("ipv6-transport", "vless", server = "[2606:4700:4700::1111]") +
                proxy("root", "vless", dialer = "ipv6-transport"),
        )
        val exit = source("exit", proxy("exit", "vless"))

        assertThrows(IOException::class.java) {
            ConnectionChainPlanner.plans(
                fixedSettings(imported.ref("root"), exit.ref("exit")),
                listOf(imported, exit),
                emptyList(),
                allowIpv6Literals = false,
            )
        }
    }

    private fun plans(
        settings: ConnectionChainSettings,
        vararg sources: ConnectionChainSource,
    ) = ConnectionChainPlanner.plans(settings, sources.toList(), emptyList())

    private fun fixedSettings(
        before: ConnectionChainProfileRef,
        base: ConnectionChainProfileRef,
    ) = ConnectionChainSettings(
        enabled = true,
        before = ConnectionChainHop.fixed(before.subscriptionId, before.fingerprint),
        base = ConnectionChainHop.fixed(base.subscriptionId, base.fingerprint),
    )

    private fun fixedBaseAfterSettings(
        base: ConnectionChainProfileRef,
        after: ConnectionChainProfileRef,
    ) = ConnectionChainSettings(
        enabled = true,
        base = ConnectionChainHop.fixed(base.subscriptionId, base.fingerprint),
        after = ConnectionChainHop.fixed(after.subscriptionId, after.fingerprint),
    )

    private fun source(id: String, proxies: String): ConnectionChainSource = ConnectionChainSource(
        subscriptionId = id,
        subscriptionName = id,
        snapshot = MihomoConfigParser.parse(
            "proxies:\n${proxies.trimEnd()}\n" +
                "proxy-groups:\n" +
                "  - name: Main\n" +
                "    type: select\n" +
                "    proxies:\n" +
                "      - DIRECT\n" +
                "rules:\n" +
                "  - MATCH,Main\n",
        ),
    )

    private fun ConnectionChainSource.ref(tag: String): ConnectionChainProfileRef {
        val profile = snapshot.catalog.profiles.single { it.tag == tag }
        return ConnectionChainProfileRef(subscriptionId, profile.fingerprint)
    }

    private fun proxy(
        name: String,
        type: String,
        udp: Boolean = false,
        dialer: String? = null,
        server: String = "$name.example",
        network: String? = null,
        proto: String? = null,
    ): String = buildString {
        append("  - name: '").append(name).append("'\n")
        append("    type: ").append(type).append('\n')
        append("    server: ").append(server).append('\n')
        append("    port: 443\n")
        if (network != null) append("    network: ").append(network).append('\n')
        if (proto != null) append("    proto: ").append(proto).append('\n')
        if (udp) append("    udp: true\n")
        if (dialer != null) append("    dialer-proxy: '").append(dialer).append("'\n")
    }
}
