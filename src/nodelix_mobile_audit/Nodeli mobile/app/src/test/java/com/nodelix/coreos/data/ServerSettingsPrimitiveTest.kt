package com.nodelix.coreos.data

import com.nodelix.coreos.data.repository.ServerSettingsRepositoryImpl
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `POST /api/settings` takes `{ "<group>": { "<field>": <value> } }`. Values typed as
 * numbers must never lose information: an all-digit API key such as "0123456789" has a
 * leading zero that a JSON number would silently drop, which would lock the app out of
 * the ESP32/ESP8266 device it is configuring.
 */
class ServerSettingsPrimitiveTest {

    private fun primitive(raw: String): JsonPrimitive =
        with(ServerSettingsRepositoryImpl.Companion) { raw.toSettingPrimitive() }

    @Test
    fun `loss free numbers and booleans keep their JSON type`() {
        assertEquals(JsonPrimitive(3000L), primitive("3000"))
        assertEquals(JsonPrimitive(1.5), primitive("1.5"))
        assertEquals(JsonPrimitive(true), primitive("true"))
        assertEquals(JsonPrimitive(false), primitive("false"))
    }

    @Test
    fun `numeric looking text values stay strings`() {
        assertEquals(JsonPrimitive("0123456789"), primitive("0123456789"))
        assertEquals(JsonPrimitive("0012"), primitive("0012"))
        assertEquals(JsonPrimitive("Lampu Teras"), primitive("Lampu Teras"))
        assertEquals(JsonPrimitive("nodelix.local"), primitive("nodelix.local"))
    }

    @Test
    fun `values are trimmed and an empty field stays an empty string`() {
        assertEquals(JsonPrimitive(80L), primitive(" 80 "))
        assertEquals("", primitive("").content)
    }
}
