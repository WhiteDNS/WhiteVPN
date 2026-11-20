package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class BackendOwnershipTest {
    @Test fun unconfirmedShutdownBlocksReplacement() {
        val owners = BackendOwnership(); val first = BackendLease(); val next = BackendLease()
        assertTrue(owners.claim(first))
        assertFalse(owners.claim(next))
        assertTrue(owners.accepts(first))
        assertFalse(owners.confirmRelease(next))
        assertFalse(owners.vacant())
    }
    @Test fun staleCallbacksAndLateShutdownCannotAffectReplacement() {
        val owners = BackendOwnership(); val old = BackendLease(); val replacement = BackendLease()
        assertTrue(owners.claim(old)); assertTrue(owners.confirmRelease(old)); assertTrue(owners.claim(replacement))
        assertFalse(owners.accepts(old)); assertFalse(owners.confirmRelease(old)); assertTrue(owners.accepts(replacement))
    }
    @Test fun concurrentStartupClaimsExactlyOneLease() {
        val owners = BackendOwnership(); val gate = CountDownLatch(1); val wins = AtomicInteger()
        val threads = List(16) { Thread { gate.await(); if (owners.claim(BackendLease())) wins.incrementAndGet() }.apply { start() } }
        gate.countDown(); threads.forEach { it.join() }
        assertEquals(1, wins.get())
    }
    @Test fun cancelledStartupCanReleaseItsOwnLease() {
        val owners = BackendOwnership(); val lease = BackendLease()
        assertTrue(owners.claim(lease)); assertTrue(owners.confirmRelease(lease)); assertTrue(owners.vacant())
    }
}
