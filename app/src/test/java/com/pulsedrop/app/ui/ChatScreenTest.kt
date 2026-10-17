package com.pulsedrop.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.ui.screens.ChatScreen
import com.pulsedrop.app.ui.screens.coldIpThread
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat list is derived from stored messages, and that is exactly the promise that broke
 * "message an IP directly": picking a device that had never said anything selected a thread that did
 * not exist, so the screen bounced straight back to the inbox and the feature looked dead from the
 * phone. These tests drive the `+` flow a finger uses, and check the thread that has to survive it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun peer(ip: String, name: String = "Redmi 22120RN86G") = PeerDevice(
        id = "peer-$ip",
        name = name,
        ip = ip,
        port = 8080,
        requiresPin = false,
        appKind = "LocalDrop",
        hasServer = true
    )

    private class Hooks {
        val selections = mutableListOf<String?>()
    }

    private fun render(
        hooks: Hooks,
        selectedThreadId: String?,
        lanPeers: List<PeerDevice> = listOf(peer("192.168.1.55")),
        messages: List<ClipMessage> = emptyList()
    ) {
        compose.setContent {
            PulseDropTheme {
                Box {
                    ChatScreen(
                        messages = messages,
                        myName = "This phone",
                        typingUsers = emptyList(),
                        pairedDevices = emptyList<PairedDevice>(),
                        groups = emptyList<ChatGroup>(),
                        lanPeers = lanPeers,
                        lastReadAt = emptyMap(),
                        selectedThreadId = selectedThreadId,
                        onSelectThread = { hooks.selections.add(it) },
                        onStartCall = { _, _ -> },
                        onStartGroupCall = { _, _ -> },
                        onStartBrowserCall = { _, _, _ -> },
                        onCallEveryone = { _, _, _, _ -> },
                        onSend = { _, _, _ -> },
                        onDelete = { _, _ -> },
                        onReact = { _, _ -> },
                        onEdit = { _, _ -> },
                        onSearchHistory = { emptyList() },
                        onTyping = { },
                        onAttach = { },
                        resolveAttachment = { null },
                        onCreateGroup = { _, _ -> },
                        onDeleteGroup = { },
                        onMarkRead = { }
                    )
                }
            }
        }
    }

    @Test
    fun thePlusButtonReachesTheDirectIpDialog() {
        val hooks = Hooks()
        render(hooks, selectedThreadId = null)
        compose.onNodeWithTag("chat_new_btn").performClick()
        compose.onNodeWithText("Message an IP directly").performClick()
        compose.onNodeWithTag("ip_peer_192.168.1.55").assertIsDisplayed()
    }

    @Test
    fun choosingADiscoveredPeerOpensAThreadWithNoHistoryYet() {
        val hooks = Hooks()
        render(hooks, selectedThreadId = null)
        compose.onNodeWithTag("chat_new_btn").performClick()
        compose.onNodeWithText("Message an IP directly").performClick()
        compose.onNodeWithTag("ip_peer_192.168.1.55").performClick()

        assertEquals(listOf<String?>("ip:192.168.1.55"), hooks.selections)
    }

    @Test
    fun aThreadNobodyHasSpokenInStillOpensInsteadOfBouncingBack() {
        val hooks = Hooks()
        render(hooks, selectedThreadId = "ip:192.168.1.55")
        compose.waitForIdle()

        assertTrue(
            "an unopenable thread must not be reported as closed: " + hooks.selections,
            hooks.selections.none { it == null }
        )
        // The conversation, not the inbox: back button plus the direct-thread subtitle.
        compose.onNodeWithTag("chat_back_btn").assertIsDisplayed()
        compose.onNodeWithText("Redmi 22120RN86G").assertIsDisplayed()
        compose.onNodeWithText("Direct thread", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTypedAddressWithNoPeerBehindItIsStillAThread() {
        // The dialog's manual field is how someone messages a device discovery missed, so the
        // address itself has to be enough to open it.
        val thread = coldIpThread("ip:10.0.0.9", emptyList())
        assertNotNull(thread)
        assertEquals("10.0.0.9", thread!!.title)
        assertEquals("ip:10.0.0.9", thread.id)
        assertTrue(thread.messages.isEmpty())
    }

    @Test
    fun onlyDirectIpThreadsAreSynthesised() {
        assertNull(coldIpThread(null, listOf(peer("192.168.1.55"))))
        assertNull("a paired thread always exists, so it never needed this", coldIpThread("pair:abc", emptyList()))
        assertNull("a group thread likewise", coldIpThread("group:7", emptyList()))
        assertNull("an address the user has not finished typing is not a peer", coldIpThread("ip:", emptyList()))
    }
}
