package com.pulsedrop.app.ui

import com.pulsedrop.app.ui.screens.IncomingCallCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A call from a paired phone and a call from a browser tab are the same event, so they have to look
 * like the same event. Nothing but reading the sources can prove that, because the two paths start
 * in two different files and can drift apart again in any later change. These tests pin the ring to
 * one screen: both entry points must draw it, and neither may keep buttons of its own.
 */
class RingingUiParityTest {

    private val uiDir = listOf(
        File("src/main/java/com/pulsedrop/app/ui"),
        File("../app/src/main/java/com/pulsedrop/app/ui")
    ).firstOrNull { it.isDirectory }
        ?: error("the ui sources are not reachable from " + File(".").absolutePath)

    private fun source(relative: String): String {
        val file = File(uiDir, relative)
        assertTrue("$relative is missing", file.isFile)
        return file.readText()
    }

    private val mainScreen = source("MainScreen.kt")
    private val webCallScreen = source("screens/WebCallScreen.kt")
    private val stage = source("screens/IncomingCallStage.kt")

    /** The body of the block that answers an app-originated call, from its guard to its close. */
    private fun phoneRing(): String {
        val start = mainScreen.indexOf("if (incoming != null) {")
        assertTrue("MainScreen no longer rings for an incoming call", start >= 0)
        val end = mainScreen.indexOf("\n    }\n", start)
        assertTrue("the incoming-call block does not close", end > start)
        return mainScreen.substring(start, end)
    }

    @Test
    fun `both call paths ring with the one shared screen`() {
        assertTrue("the phone-side ring does not use the shared screen", "IncomingCallStage(" in phoneRing())
        assertTrue("the browser-side ring does not use the shared screen", "IncomingCallStage(" in webCallScreen)
    }

    @Test
    fun `the phone no longer answers a call through a stock alert dialog`() {
        val ring = phoneRing()
        assertFalse("the ring is a Material dialog again: " + ring, "AlertDialog(" in ring)
        // A dialog that keeps the platform width would letterbox the full-bleed screen inside a
        // grey card, which is the very difference this change removed.
        assertTrue("the ring is not full-bleed", "usePlatformDefaultWidth = false" in ring)
    }

    @Test
    fun `only the shared screen builds the answer buttons`() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "IncomingCallStage.kt" }
            .filter { "accept_call_btn" in it.readText() }
            .map { it.name }
            .toList()
        assertTrue("files that draw their own accept button: $offenders", offenders.isEmpty())
        assertTrue("the shared screen does not default to the tag the app already used",
            stage.contains("\"accept_call_btn\""))
    }

    @Test
    fun `the browser keeps its own tags so its tests still find the buttons`() {
        listOf("web_call_accept_btn", "web_call_decline_btn", "web_call_cancel_btn", "web_call_end_btn")
            .forEach { assertTrue("$it is gone from the browser sheet", webCallScreen.contains("\"$it\"")) }
    }

    @Test
    fun `a card describes a ring and nothing else`() {
        val card = IncomingCallCard(name = "Asha", detail = "Video call · 192.168.1.9", hint = "hint")
        assertEquals("Ringing…", card.ringingLabel)
        assertEquals("Accept", card.acceptLabel)
        assertEquals("Decline", card.declineLabel)
        assertEquals("accept_call_btn", card.acceptTag)
        assertEquals("decline_call_btn", card.declineTag)
        assertNull(card.groupNote)
    }

    @Test
    fun `a call this end dialled has no accept button to press`() {
        // Cancelling our own dial is the one ring where answering is not this phone's choice.
        val card = IncomingCallCard(
            name = "Browser",
            detail = "Voice call · 192.168.1.20",
            hint = "Waiting for the browser to answer.",
            acceptLabel = null,
            declineLabel = "Cancel",
            declineTag = "web_call_cancel_btn"
        )
        assertNull(card.acceptLabel)
        assertEquals("Cancel", card.declineLabel)
    }

    @Test
    fun `the shared screen shows every part of the card it is handed`() {
        listOf("card.name", "card.detail", "card.groupNote", "card.ringingLabel", "card.hint")
            .forEach { assertTrue("the ring ignores $it", stage.contains(it)) }
        assertTrue("the ring must hide the accept button when there is none", stage.contains("card.acceptLabel"))
    }
}
