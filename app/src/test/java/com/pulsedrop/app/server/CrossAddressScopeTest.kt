package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.SharedFile
import fi.iki.elonen.NanoHTTPD.Method
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scope rules with three callers that are actually three callers.
 *
 * [ObjectScopeTest] proves ownership by planting a record with somebody else's address, which is
 * the only thing a loopback-only harness can do. This file asks the questions that need two
 * addresses on both sides of the rule — may *this* tab read *that* object — and each test sends the
 * same request from two tabs and compares the answers.
 *
 * The statuses asserted here are the ones the server gives today, including its choice to answer
 * 404 rather than 403 for an object that exists but is not the caller's; where that is a decision
 * rather than a fact, the test accepts either and says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrossAddressScopeTest {

    private val pin = "424242"
    private val tabA = "127.0.0.1"
    private val tabB = "127.0.0.2"
    private val tabC = "127.0.0.3"

    private lateinit var server: PulseDropServer
    private lateinit var token: String

    @Before
    fun openRoom() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // port 0, never started: `serve()` is public and takes the interface, so the route chain
        // runs in full without a listener anybody on the LAN could reach.
        server = PulseDropServer(
            port = 0,
            bindAddress = tabA,
            context = context,
            initialDeviceName = "Test Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        token = login(tabA)
    }

    private fun auth() = mapOf("x-pulse-auth" to token)

    private fun login(from: String): String {
        val answer = TabSession(
            "/api/auth", Method.POST, from,
            requestHeaders = mapOf("content-type" to "application/json"),
            json = "{\"pin\":\"$pin\"}"
        ).sendTo(server)
        assertEquals("a correct PIN must return a session", 200, answer.status)
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(answer.body)?.groupValues?.get(1)
            ?: throw AssertionError("no token in the login answer: ${answer.body}")
    }

    private fun postMessage(from: String, json: String) = TabSession(
        "/api/messages", Method.POST, from,
        requestHeaders = auth() + mapOf("content-type" to "application/json"), json = json
    ).sendTo(server)

    private fun messages(from: String) =
        TabSession("/api/messages", Method.GET, from, requestHeaders = auth()).sendTo(server).body

    private fun files(from: String) =
        TabSession("/api/files", Method.GET, from, requestHeaders = auth()).sendTo(server).body

    /** Upload bytes as one tab and return the attachment id the server minted for them. */
    private fun upload(from: String, name: String, contents: String): String {
        val answer = TabSession(
            "/chatupload", Method.POST, from,
            requestHeaders = auth() + mapOf("content-type" to "multipart/form-data; boundary=x"),
            query = mapOf("filename" to name),
            filePart = "file" to contents
        ).sendTo(server)
        assertEquals("the upload must succeed: ${answer.body}", 200, answer.status)
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(answer.body)?.groupValues?.get(1)
            ?: throw AssertionError("the upload returned no attachment id: ${answer.body}")
    }

    // -- the harness itself ---------------------------------------------------

    @Test
    fun `three tabs are three tabs`() {
        postMessage(tabB, "{\"text\":\"written-from-b\"}")
        assertTrue(
            "a caller the server cannot tell apart makes every scope test below vacuous",
            messages(tabB).contains(tabB) && !messages(tabC).contains("written-from-b$tabB")
        )
        assertTrue(messages(tabB).contains("written-from-b"))
    }

    // -- chat attachments -----------------------------------------------------

    @Test
    fun `an attachment one tab uploaded is not another tab's to read`() {
        val id = upload(tabA, "note.txt", "private bytes")

        val owner = TabSession("/chatfile/$id", Method.GET, tabA, requestHeaders = auth()).sendTo(server)
        assertEquals("the uploader reads its own bytes", 200, owner.status)
        assertTrue(owner.body.contains("private bytes"))

        val other = TabSession("/chatfile/$id", Method.GET, tabB, requestHeaders = auth()).sendTo(server)
        assertTrue(
            "a second tab holding the id, the session and the PIN must be refused, got ${other.status}",
            other.status == 403 || other.status == 404
        )
        assertFalse("and the refusal must not carry the bytes", other.body.contains("private bytes"))
    }

    @Test
    fun `publishing a message is what makes an attachment readable`() {
        val id = upload(tabA, "room.txt", "room bytes")
        assertEquals(
            "the owner may attach what it uploaded", 200,
            postMessage(tabA, "{\"text\":\"here you go\",\"attachmentId\":\"$id\"}").status
        )
        assertTrue(
            "now the room can read it",
            TabSession("/chatfile/$id", Method.GET, tabC, requestHeaders = auth()).sendTo(server).status == 200
        )
    }

    // -- hosted files ---------------------------------------------------------

    /** Host a file the way a directed send does, without needing an upload to go through first. */
    private fun hostDirected(name: String, contents: String, to: String?, by: String?): String {
        val file = File.createTempFile("hosted-", "-$name").apply { writeText(contents) }
        val id = UUID.randomUUID().toString()
        server.setSharedFiles(
            listOf(SharedFile(id, name, file.length(), "text/plain", file.absolutePath, to, by))
        )
        return id
    }

    @Test
    fun `a file aimed at one client is not a third client's to download`() {
        val id = hostDirected("private.txt", "for b only", to = tabB, by = tabA)

        assertTrue("the addressee sees the file", files(tabB).contains(id))
        assertTrue("the sender sees the file", files(tabA).contains(id))
        assertFalse("a bystander does not", files(tabC).contains(id))

        val bystander = TabSession("/download/$id", Method.GET, tabC, requestHeaders = auth()).sendTo(server)
        assertTrue("the bystander is refused, not served", bystander.status in 403..404)
        assertFalse("and the body is not the file", bystander.body.contains("for b only"))

        val addressee = TabSession("/download/$id", Method.GET, tabB, requestHeaders = auth()).sendTo(server)
        assertEquals("the addressee is served", 200, addressee.status)
        assertTrue(addressee.body.contains("for b only"))
    }

    @Test
    fun `a room file is a room file`() {
        val id = hostDirected("shared.txt", "for everyone", to = null, by = tabA)
        listOf(tabA, tabB, tabC).forEach {
            assertEquals("$it may read an undirected file", 200,
                TabSession("/download/$id", Method.GET, it, requestHeaders = auth()).sendTo(server).status)
        }
    }

    // -- conversations --------------------------------------------------------

    @Test
    fun `a direct thread stays between its two addresses`() {
        assertEquals(200, postMessage(tabA, "{\"text\":\"between us\",\"to\":\"$tabB\"}").status)

        assertTrue("the addressee reads the thread", messages(tabB).contains("between us"))
        assertTrue("the writer reads it back", messages(tabA).contains("between us"))
        assertFalse("a third tab does not", messages(tabC).contains("between us"))
    }

    @Test
    fun `a mapped v6 address is the same tab as its v4 form`() {
        postMessage(tabB, "{\"text\":\"from-v4\",\"to\":\"$tabB\"}")
        assertTrue(
            "the same laptop asked over a different address family must not be told to leave",
            messages("::ffff:$tabB").contains("from-v4")
        )
    }
}
