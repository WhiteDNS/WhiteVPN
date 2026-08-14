package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferSpeedFormatterTest {
    @Test
    fun clampsNegativeSpeedToZeroBytes() {
        assertEquals("0 B/s", formatTransferSpeed(-1L))
    }

    @Test
    fun formatsByteSpeeds() {
        assertEquals("512 B/s", formatTransferSpeed(512L))
    }

    @Test
    fun formatsKilobyteSpeeds() {
        assertEquals("2 KB/s", formatTransferSpeed(2_048L))
    }

    @Test
    fun formatsMegabyteSpeeds() {
        assertEquals("1.5 MB/s", formatTransferSpeed(1_572_864L))
    }
}
