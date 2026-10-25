package com.pulsedrop.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pulsedrop.app.server.WebCall
import com.pulsedrop.app.ui.screens.WebCallScreen
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What this screen is allowed to claim about the line.
 *
 * The call itself does the talking about transport: a browser tab will not hand over a camera on an
 * insecure page, so a call reaching this sheet means TLS is up. That made the old footer's "over the
 * secure link" *technically* true, and still the wrong thing to say, because the certificate is the
 * phone's own and the browser said so. A user taught that a certificate warning means "secure link
 * blocked" is a user trained to click through the one warning that would tell them somebody else is
 * answering for the phone. So the footer names which half it is vouching for — encryption, not
 * identity — and these tests keep it from drifting back to a word that covers both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebCallScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun acceptedSheet() {
        compose.setContent {
            PulseDropTheme {
                WebCallScreen(
                    call = WebCall(
                        cid = "cid-1", name = "Laptop", ip = "192.0.2.14",
                        video = true, state = "accepted"
                    ),
                    onAccept = {}, onDecline = {}, onHangUp = {}
                )
            }
        }
    }

    @Test
    fun `the answered sheet says what the certificate does and does not prove`() {
        acceptedSheet()
        compose.waitForIdle()

        compose.onNodeWithTag("web_call_security_note").assertExists()
        val note = compose.onNodeWithTag("web_call_security_note")
            .fetchSemanticsNode().let { node ->
                val text = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                text.joinToString(" ")
            }
        assertTrue("the note stopped saying the certificate is the phone's own: $note",
            note.contains("own certificate"))
        assertTrue("the note no longer says what the warning means: $note",
            note.contains("nobody vouched"))
        assertTrue("the note no longer says the call is encrypted at all: $note",
            note.contains("encrypted"))
    }

    @Test
    fun `the answered sheet does not claim a trusted link`() {
        acceptedSheet()
        compose.waitForIdle()

        // The word the screen used to lean on. "Secure" covers encryption *and* identity, and this
        // phone can only promise the first half.
        compose.onNodeWithText("secure link", substring = true).assertDoesNotExist()
        // The state the wording above has to stay consistent with.
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("Answered").assertDoesNotExist()
    }
}
