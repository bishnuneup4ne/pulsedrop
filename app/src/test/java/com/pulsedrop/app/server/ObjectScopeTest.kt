package com.pulsedrop.app.server

import android.net.Uri
import com.pulsedrop.app.model.ChatAttachment
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Who may act on a call, a folder, or a body size — as opposed to merely being allowed in.
 *
 * Authorization in this server is a single yes/no for the whole room: type the PIN and every
 * endpoint answering to that PIN is yours. That is the wrong question for four routes. `/api/call/web`
 * hands the live call id to every authorized tab, and `end`, `down` and `up` all accepted it from
 * whoever asked, so a second tab could hang up, listen to, or speak into a call between the phone
 * and someone else's browser. `accept` and `decline` checked ownership all along; the other three
 * simply never did.
 *
 * Every client here is `127.0.0.1`, which is what makes the ownership tests possible: the call
 * record is planted with a foreign address, standing in for the other visitor's tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ObjectScopeTest {

    private val pin = "424242"
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String

    @Before
    fun startServer() {
        val port = RoomHttp.freePort()
        server = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = RoomHttp.appContext(),
            initialDeviceName = "Test Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        server.start()
        RoomHttp.awaitListening(port)
        http = RoomHttp(port)
        token = http.requireLogin(pin)
        // `/upload` refuses a file it cannot fit. Robolectric's StatFs reports an empty
        // filesystem by default, so every upload would fail on space rather than on the rule
        // under test; give the received directory a plausible amount of free space.
        val received = receivedDir().apply { mkdirs() }
        org.robolectric.shadows.ShadowStatFs.registerStats(received.absolutePath, 512, 2_000_000, 4_000_000)
    }

    @After
    fun stopServer() {
        server.webCall = null
        server.stop()
        org.robolectric.shadows.ShadowStatFs.reset()
    }

    private fun auth() = mapOf(
        "X-Pulse-Auth" to token,
        "Content-Type" to "application/json; charset=utf-8"
    )

    /** A call between this phone and a tab at `peerIp`, which is not the tab sending requests. */
    private fun plantCall(cid: String, peerIp: String, state: String = "accepted") {
        server.webCall = WebCall(cid = cid, name = "Other tab", ip = peerIp, video = false, state = state)
    }

    // -- S-4: hanging up someone else's call ---------------------------------

    @Test
    fun `a tab that does not own the call cannot end it`() {
        plantCall("call-1", "10.1.2.3")
        val answer = http.call("POST", "/api/call/web", auth(), "{\"action\":\"end\",\"cid\":\"call-1\"}")
        assertEquals("only the owner may hang up", 400, answer.status)
        assertTrue("and it says which rule stopped it", answer.body.contains("Not your call"))
        assertEquals("the call must still be running", "accepted", server.webCall?.state)
    }

    @Test
    fun `a blank cid no longer ends whatever call happens to be running`() {
        plantCall("call-1", "10.1.2.3")
        val answer = http.call("POST", "/api/call/web", auth(), "{\"action\":\"end\"}")
        assertEquals(400, answer.status)
        assertEquals(
            "the old code treated a missing cid as a licence to end anything",
            "accepted", server.webCall?.state
        )
    }

    @Test
    fun `a wrong cid is refused and leaves the live call alone`() {
        plantCall("call-1", "10.1.2.3")
        val answer = http.call("POST", "/api/call/web", auth(), "{\"action\":\"end\",\"cid\":\"guess\"}")
        assertEquals(400, answer.status)
        assertEquals("accepted", server.webCall?.state)
    }

    @Test
    fun `the owner still hangs up its own call`() {
        // This test's client is loopback, so the record must name loopback to be its own.
        plantCall("call-1", "127.0.0.1")
        val answer = http.call("POST", "/api/call/web", auth(), "{\"action\":\"end\",\"cid\":\"call-1\"}")
        assertEquals(200, answer.status)
        assertEquals("ended", server.webCall?.state)
    }

    @Test
    fun `ending a call that is not running is no longer reported as success`() {
        server.webCall = null
        val answer = http.call("POST", "/api/call/web", auth(), "{\"action\":\"end\",\"cid\":\"call-1\"}")
        assertEquals("the old code answered 200 true whether or not anything ended", 400, answer.status)
        assertTrue(answer.body.contains("No open call"))
    }

    // -- S-4: the media legs --------------------------------------------------

    @Test
    fun `the down leg goes only to the tab the call belongs to`() {
        plantCall("call-1", "10.1.2.3")
        val answer = http.call("GET", "/api/webcall/down?cid=call-1", mapOf("X-Pulse-Auth" to token))
        assertEquals(400, answer.status)
        assertTrue(
            "this body is the live conversation; cid alone was the whole problem",
            answer.body.contains("Not your call")
        )
    }

    @Test
    fun `a stranger cannot subscribe to the call by guessing nothing`() {
        // The cid is published to every authorized tab by GET /api/call/web, so the cid check on
        // its own is not a boundary. Pin that the disclosure and the leg are separate questions.
        plantCall("call-1", "10.1.2.3")
        val state = http.call("GET", "/api/call/web", mapOf("X-Pulse-Auth" to token))
        assertEquals(200, state.status)
        assertTrue("the cid really is handed out", state.body.contains("call-1"))
        assertTrue("but the tab is told it is not the caller's screen", !state.body.contains("\"self\":true"))
    }

    // -- R-1: bodies that lie about their size -------------------------------

    @Test
    fun `an oversized frame batch is refused before its body is read`() {
        plantCall("call-1", "127.0.0.1")
        val answer = http.call(
            "POST", "/api/webcall/up", auth(),
            body = "A".repeat(1024),
            declareContentLength = 400_000_000
        )
        assertEquals(
            "one frame batch is capped, and 400 MB is not one",
            413, answer.status
        )
    }

    @Test
    fun `an oversized signaling post is refused before its body is read`() {
        val answer = http.call(
            "POST", "/api/call/web", auth(),
            body = "A".repeat(1024),
            declareContentLength = 64 * 1024 * 1024
        )
        assertEquals(413, answer.status)
    }

    @Test
    fun `a normal sized action still gets through the ceiling`() {
        val answer = http.call("POST", "/api/call/web", auth(), "{\"action\":\"ring\",\"name\":\"Tab\"}")
        assertTrue(
            "the limit must not be so tight that dialling breaks: " + answer.body,
            answer.body.contains("\"success\":true")
        )
        server.webCall = null
    }

    // -- S-5: a received folder is somebody's drop area ----------------------

    private fun receivedDir(): File =
        File(
            (RoomHttp.appContext().getExternalFilesDir(null) ?: RoomHttp.appContext().filesDir),
            PulseDropServer.RECEIVED_DIR_NAME
        )

    @Test
    fun `a file you addressed to another visitor is still yours to take back`() {
        // The cross-visitor read this rule exists to stop needs two different addresses, and every
        // client here is loopback — the requester is always also the recorded uploader, so the
        // forbid branch cannot be reached from this test. What can be proved, and is the risk that
        // mattered most of getting wrong, is that adding the rule does not lock an uploader out of
        // the folder it made, including a file it addressed to somebody else.
        val up = http.postMultipart(
            "/upload?path=Private%2Fa.txt&to=10.9.9.9", mapOf("a.txt" to "not theirs either"), token
        )
        assertEquals("upload must have worked for this to prove anything: " + up.body, 200, up.status)
        assertTrue(receivedDir().resolve("Private/a.txt").isFile)

        val answer = http.call("GET", "/download-folder?path=Private", mapOf("X-Pulse-Auth" to token))
        assertEquals(200, answer.status)
        assertTrue(answer.headers.contains("application/zip"))
    }

    @Test
    fun `my own upload comes back marked as mine`() {
        // `isMine` compared raw strings before, and a dual-stack socket records the peer as
        // ::ffff:127.0.0.1 while the recorded uploader was 127.0.0.1 — so the client could be told
        // its own file belonged to somebody else. It now goes through the same normalization as
        // the authorization decision beside it.
        val up = http.postMultipart("/upload?path=Mine%2Fonly.txt", mapOf("only.txt" to "mine"), token)
        assertEquals(200, up.status)
        val list = http.call("GET", "/api/files", mapOf("X-Pulse-Auth" to token))
        assertEquals(200, list.status)
        assertTrue("a room-visible upload is listed", list.body.contains("only.txt"))
        assertTrue("and the uploader is told so", list.body.contains("\"isMine\":true"))
    }

    @Test
    fun `a folder the room can see stays downloadable`() {
        val up = http.postMultipart("/upload?path=Shared%2Fb.txt", mapOf("b.txt" to "for everyone"), token)
        assertEquals(200, up.status)

        val answer = http.call("GET", "/download-folder?path=Shared", mapOf("X-Pulse-Auth" to token))
        assertEquals("bounding the zip must not break the honest case", 200, answer.status)
        assertTrue(answer.headers.contains("application/zip"))
    }

    @Test
    fun `an oversized folder is refused without writing a zip`() {
        // Not uploaded, so nothing in the file list scopes it: this reaches the size ceiling
        // directly, which is the one that protected no storage at all before.
        val folder = receivedDir().resolve("Huge").apply { mkdirs() }
        repeat(2_001) { File(folder, "f$it.txt").createNewFile() }
        assertTrue("the test fixture needs its folder", folder.isDirectory)
        try {
            val answer = http.call("GET", "/download-folder?path=Huge", mapOf("X-Pulse-Auth" to token))
            assertEquals("2000 entries is the ceiling", 413, answer.status)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `a folder outside the received directory is still not readable`() {
        val answer = http.call(
            "GET", "/download-folder?path=..%2F..%2F..", mapOf("X-Pulse-Auth" to token)
        )
        assertTrue(
            "traversal stays closed while ownership is added",
            answer.status == 404 || answer.status == 403
        )
    }

    // -- A chat attachment is somebody's bytes too ---------------------------

    /**
     * An attachment written by the **phone**, which is the only way to make one this tab did not
     * upload: `/chatupload` always records loopback as its uploader, and loopback is also the
     * requester, so the uploader rule alone would wave everything through.
     */
    private fun phoneAttachment(name: String, bytes: ByteArray): ChatAttachment? {
        val uri = Uri.parse("content://pulsedrop.test/$name")
        shadowOf(RoomHttp.appContext().contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return server.storeChatAttachment(uri, name)
    }

    private fun chatFile(id: String) =
        http.call("GET", "/chatfile/$id", mapOf("X-Pulse-Auth" to token))

    @Test
    fun `an attachment the phone has not sent yet is not the room's to read`() {
        // The hole this closes: `/chatfile/{id}` checked nothing at all, while `/download/{id}` and
        // `/preview/{id}` each asked who owned the file. Any tab in the room holding the id had the
        // bytes -- including a file still sitting unsent in the phone's own composer.
        val attachment = phoneAttachment("draft.txt", "not posted anywhere".toByteArray())
        assertNotNull("the fixture must actually have written a file", attachment)
        val answer = chatFile(attachment!!.id)
        assertEquals("unsent means not yours", 404, answer.status)
    }

    @Test
    fun `a chat attachment posted to the room is readable by the room`() {
        val attachment = phoneAttachment("room.txt", "for everyone".toByteArray())!!
        server.addMessage("look", "Phone", clientIp = null, threadId = null, attachment = attachment)
        val answer = chatFile(attachment.id)
        assertEquals("a message with no thread is the open room", 200, answer.status)
        assertTrue(answer.body.contains("for everyone"))
    }

    @Test
    fun `an attachment sent into another tab's thread is not mine`() {
        val attachment = phoneAttachment("theirs.txt", "addressed elsewhere".toByteArray())!!
        // The phone wrote this into somebody else's address book. This test's tab is loopback, and
        // the book is 10.9.9.9's, so the bytes must stay closed -- which is the cross-client read
        // that `/download/` already refused and this route never did.
        server.addMessage(
            "psst", "Phone", clientIp = "10.9.9.9", threadId = "ip:10.9.9.9", attachment = attachment
        )
        assertEquals(404, chatFile(attachment.id).status)
    }

    @Test
    fun `a thread the reader belongs to grants its attachment`() {
        val attachment = phoneAttachment("mine.txt", "this one is mine".toByteArray())!!
        server.addMessage(
            "hello", "Other tab", clientIp = "10.9.9.9", threadId = "ip:127.0.0.1", attachment = attachment
        )
        // The phone wrote to this tab's book from another address: the thread key names the reader.
        assertEquals(200, chatFile(attachment.id).status)
    }

    @Test
    fun `a group the phone opened to browsers grants its attachments`() {
        val attachment = phoneAttachment("group.txt", "shared in the group".toByteArray())!!
        server.webThreads = listOf(WebThread("group:demo", "Demo", "group", listOf()))
        server.addMessage("hi", "Phone", clientIp = null, threadId = "group:demo", attachment = attachment)
        assertEquals(200, chatFile(attachment.id).status)
        server.webThreads = emptyList()
    }

    @Test
    fun `the tab that uploaded an attachment can preview it before sending`() {
        // The grant has to be one-way: a composer shows the file it just picked, and that message
        // does not exist yet. Refusing it would break the UI the same release that adds the check.
        val up = http.postMultipart(
            "/chatupload?filename=unsent.txt", mapOf("file" to "typed but not sent"), token
        )
        assertEquals(200, up.status)
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(up.body)?.groupValues?.get(1)
            ?: throw AssertionError("the upload must hand back an attachment id: ${up.body}")
        assertEquals(200, chatFile(id).status)
    }
}
