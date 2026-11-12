package com.nodelix.coreos.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PrivateLanDnsTest {
    @Test
    fun `accepts only private addresses from DNS answer`() {
        val lan = InetAddress.getByName("192.168.1.20")
        val overlay = InetAddress.getByName("100.101.102.103")
        val dns = PrivateLanDns(Dns { listOf(lan, overlay) })
        assertEquals(listOf(lan, overlay), dns.lookup("nodelix.local"))
    }

    @Test
    fun `rejects DNS answer containing public address to prevent rebinding`() {
        val lan = InetAddress.getByName("192.168.1.20")
        val public = InetAddress.getByName("8.8.8.8")
        val dns = PrivateLanDns(Dns { listOf(lan, public) })
        assertThrows(UnknownHostException::class.java) { dns.lookup("nodelix.local") }
    }

    @Test
    fun `rejects empty DNS answer`() {
        val dns = PrivateLanDns(Dns { emptyList() })
        assertThrows(UnknownHostException::class.java) { dns.lookup("nodelix.local") }
    }
}
