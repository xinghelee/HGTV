// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Tries IPv4 addresses before IPv6 ones. On networks that hand out global IPv6 addresses but drop
 * IPv6 traffic (seen on a HiNet home network), the system order puts IPv6 first and every request
 * waits out the connect timeout before reaching a working IPv4 route. IPv6 stays as a fallback, so
 * IPv6-only hosts still connect.
 */
object PreferIpv4Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = order(Dns.SYSTEM.lookup(hostname))

    internal fun order(addresses: List<InetAddress>): List<InetAddress> =
        addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}

/** Every client in the app starts here so all requests share the same address preference. */
object HttpClients {
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().dns(PreferIpv4Dns)
}
