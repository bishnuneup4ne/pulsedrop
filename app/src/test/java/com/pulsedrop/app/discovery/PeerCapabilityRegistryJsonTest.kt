package com.pulsedrop.app.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json is Android-owned, so these run under Robolectric like every other JSON test here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PeerCapabilityRegistryJsonTest {

    @Test
    fun capsFromJsonToleratesMissingAndBlank() {
        assertNull(PeerCapabilityRegistry.capsFromJson(null))
        assertEquals(
            listOf("h264"),
            PeerCapabilityRegistry.capsFromJson(JSONArray(listOf("h264", "")))
        )
    }

    @Test
    fun mergeIsAdditiveAcrossAnnouncements() {
        val r = PeerCapabilityRegistry()
        val key = r.keyForIp("10.0.0.5")
        r.record(key, "2.1.0", PeerCapabilityRegistry.capsFromJson(JSONArray(listOf("h264"))))
        r.record(key, "2.1.0", PeerCapabilityRegistry.capsFromJson(JSONArray(listOf("conf2"))))
        assertEquals(setOf("h264", "conf2"), r.capsFor(key))
    }
}
