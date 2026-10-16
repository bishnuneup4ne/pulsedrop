package com.pulsedrop.app.ui

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.ui.screens.HelpScreen
import com.pulsedrop.app.ui.screens.helpFaqs
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Help page is where a lying UI hurts most: an answer that does not match the code sends
 * people chasing problems that do not exist. These tests pin the answers to the truths they
 * were written from, and the buttons to real destinations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HelpScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var hotspot = 0
        var network = 0
        var security = 0
        var notifications = 0
        var issues = 0
        var update = 0
    }

    private fun screen(hooks: Hooks) {
        compose.setContent {
            PulseDropTheme {
                HelpScreen(
                    receiveDir = "/sdcard/Android/data/com.pulsedrop.app/files/WebDrop_Received",
                    onOpenHotspotGuide = { hooks.hotspot++ },
                    onOpenNetwork = { hooks.network++ },
                    onOpenSecurity = { hooks.security++ },
                    onOpenNotifications = { hooks.notifications++ },
                    onOpenGithubIssues = { hooks.issues++ },
                    onCheckUpdate = { hooks.update++ }
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    // ── Pure answers ───────────────────────────────────────────────────────

    @Test
    fun everyFaqAsksOneQuestionAndAnswersItOnce() {
        val faqs = helpFaqs("/tmp/received")
        assertEquals(8, faqs.size)
        assertEquals(faqs.map { it.question }.toSet().size, faqs.size)
        assertEquals(faqs.map { it.id }.toSet().size, faqs.size)
        assertTrue(faqs.all { it.answer.isNotBlank() })
    }

    @Test
    fun theEncryptionAnswerNeverOverstatesTheCallMedia() {
        val answer = helpFaqs("/tmp/received").first { it.id == "encryption" }.answer
        assertTrue(answer.contains("Call audio and video are not end-to-end encrypted"))
        assertTrue(answer.contains("end-to-end encrypted"))
    }

    @Test
    fun theReceivedAnswerCarriesTheRealFolder() {
        val answer = helpFaqs("/data/x/WebDrop_Received").first { it.id == "received" }.answer
        assertTrue(answer.contains("/data/x/WebDrop_Received"))
    }

    @Test
    fun noAnswerPromisesPushOrSilentUpdates() {
        val all = helpFaqs("/tmp/r").joinToString(" ") { it.answer }
        assertTrue(all.contains("no push server"))
        assertTrue(all.contains("Nothing updates silently"))
    }

    // ── Accordion behaviour ────────────────────────────────────────────────

    @Test
    fun openingOneAnswerClosesThePreviousOne() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("help_faq_send")
        compose.onNodeWithText("How do I send a file to a computer?").performClick()
        compose.onNodeWithTag("help_answer_send").assertExists()
        scrollTo("help_faq_pair")
        compose.onNodeWithText("How do I pair with another phone?").performClick()
        compose.onNodeWithTag("help_answer_pair").assertExists()
        scrollTo("help_faq_send")
        compose.onNodeWithTag("help_answer_send").assertDoesNotExist()
    }

    // ── Real destinations ──────────────────────────────────────────────────

    @Test
    fun everyLinkOpensThePageThatActuallyHandlesIt() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("help_link_hotspot")
        compose.onNodeWithTag("help_link_hotspot").performClick()
        compose.onNodeWithTag("help_link_network").performClick()
        compose.onNodeWithTag("help_link_security").performClick()
        compose.onNodeWithTag("help_link_notifications").performClick()
        assertEquals(1, hooks.hotspot)
        assertEquals(1, hooks.network)
        assertEquals(1, hooks.security)
        assertEquals(1, hooks.notifications)
    }

    @Test
    fun supportButtonsReachTheRealChannels() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("help_report")
        compose.onNodeWithTag("help_report").performClick()
        compose.onNodeWithTag("help_update").performClick()
        assertEquals(1, hooks.issues)
        assertEquals(1, hooks.update)
    }

    @Test
    fun theLimitsCardSaysWhatNoSupportCanDo() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("help_limits_card")
        compose.onNodeWithText("No remote access", substring = true).assertExists()
        compose.onNodeWithText("No deleted-file recovery", substring = true).assertExists()
    }
}
