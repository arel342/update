package com.nodelix.coreos.network

import com.nodelix.coreos.domain.model.ProtocolType
import com.nodelix.coreos.domain.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpOnlyProtocolMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy HTTPS profile is loaded as HTTP and obsolete certificate fields are ignored`() {
        val profile = json.decodeFromString<ServerProfile>(
            """{"name":"Legacy","host":"192.168.1.20","port":3000,"protocol":"HTTPS","allowLanCleartext":false,"trustedCaPem":"obsolete"}"""
        )
        assertEquals(ProtocolType.HTTP, profile.protocol)
        assertEquals("http://192.168.1.20:3000/", profile.baseUrl())
        assertEquals("ws://192.168.1.20:3000/", profile.webSocketUrl())
    }

    @Test
    fun `protocol serializer always persists HTTP`() {
        assertEquals("\"HTTP\"", json.encodeToString(ProtocolType.serializer(), ProtocolType.HTTP))
        assertTrue(ProtocolType.HTTP.scheme == "http")
        assertTrue(ProtocolType.HTTP.wsScheme == "ws")
    }
}
