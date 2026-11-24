// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetAddress

class PreferIpv4DnsTest {
    private fun v6(last: Int) = InetAddress.getByAddress(ByteArray(15) { if (it == 0) 0x20 else 0 } + last.toByte())
    private fun v4(last: Int) = InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte()))

    @Test fun ipv4ComesFirstAndKeepsItsOrder() {
        val ordered = PreferIpv4Dns.order(listOf(v6(1), v4(1), v6(2), v4(2)))
        assertEquals(listOf(v4(1), v4(2), v6(1), v6(2)), ordered)
    }

    @Test fun ipv6OnlyHostsStillResolve() {
        assertEquals(listOf(v6(1), v6(2)), PreferIpv4Dns.order(listOf(v6(1), v6(2))))
    }

    @Test fun everySharedClientUsesTheIpv4FirstResolver() {
        assertSame(PreferIpv4Dns, HttpClients.builder().build().dns)
        assertSame(PreferIpv4Dns, ContentRepository().http.dns)
    }
}
