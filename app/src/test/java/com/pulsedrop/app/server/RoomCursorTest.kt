package com.pulsedrop.app.server

import fi.iki.elonen.NanoHTTPD.Method
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading the room again without reading it again.
 *
 * A tab polls, and a room that grew by one message used to answer with all of it — 517 KB of JSON at
 * the retention cap, every two seconds, forever. Compression and an `ETag` (#81) make an *unchanged*
 * read cheap; they cannot make a *changed* one cheap, because the phone still serialises every message
 * to know what the bytes it would send are. A cursor is what stops that: only what moved since this
 * tab last asked.
 *
 * The three ways a naive cursor is wrong are the three middle tests here. Ids are random UUIDs, so
 * they order nothing. Retention evicts from the front, so a position means a different message next
 * minute. And a retraction rewrites an entry where it stands — a reader that only watches the tail
 * would keep showing a message whose text the phone has already deleted, which is a correctness bug
 * wearing a performance label.
 *
 * The last three are the claims that were only ever written down: what a marker older than the
 * retained room answers with, whether a `304` (which carries no cursor, by design) can strand a tab
 * that then misses a message, and what an unreadable marker does to a reader that offered one. The
 * second of those is the interesting one, because it is this cursor meeting the validator from #81 —
 * two mechanisms shipped a batch apart, on the same route, with the same job of deciding what a tab
 * does not need to be sent twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomCursorTest {

    private val pin = "424242"
    private var port = 0
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String

    @Before
    fun startServer() {
        port = RoomHttp.freePort()
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
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun read(path: String, vararg headers: Pair<String, String>) =
        http.raw("GET", path, mapOf("x-pulse-auth" to token) + headers.toMap())

    /** The cursor this tab would send next, taken from the answer it just got. */
    private fun Wire.cursor(): String = requireNotNull(header("X-Room-Cursor")) {
        "the room answered with no cursor: ${header("Content-Type")} / $status"
    }

    private fun ids(body: String): List<String> = org.json.JSONArray(body).let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    @Test
    fun `a first read is the whole room and hands back a marker for the next one`() {
        val seeded = listOf(
            server.addMessage("one", "another device", clientIp = "10.0.0.9"),
            server.addMessage("two", "another device", clientIp = "10.0.0.9"),
            server.addMessage("three", "another device", clientIp = "10.0.0.9")
        )
        val first = read("/api/messages")
        assertEquals(200, first.status)
        assertEquals(seeded.map { it.id }, ids(first.text))
        assertNull("a read that offered no cursor was answered as a patch", first.header("X-Room-Delta"))
        assertTrue(first.cursor().split(":").size == 3)
    }

    @Test
    fun `a cursor is answered with only what moved`() {
        val older = server.addMessage("older", "another device", clientIp = "10.0.0.9")
        val cursor = read("/api/messages").cursor()

        val newer = server.addMessage("newer", "another device", clientIp = "10.0.0.9")
        val patch = read("/api/messages?since=$cursor")
        assertEquals("1", patch.header("X-Room-Delta"))
        assertEquals(listOf(newer.id), ids(patch.text))
        assertNotEqualsFragment(older.id, patch.text)
        assertTrue("the marker did not move on", patch.cursor() != cursor)
    }

    private fun assertNotEqualsFragment(id: String, body: String) {
        assertFalse("the patch redelivered a message the tab already had: $body", body.contains(id))
    }

    @Test
    fun `an unchanged room asked about with a cursor is an empty array`() {
        server.addMessage("nothing new", "another device", clientIp = "10.0.0.9")
        val cursor = read("/api/messages").cursor()
        val again = read("/api/messages?since=$cursor")
        assertEquals("1", again.header("X-Room-Delta"))
        assertEquals("[]", again.text)
    }

    /**
     * The case a tail-only cursor loses: the message the tab already holds is the one that changed,
     * and it sits in the middle of the list. A tab that never learned this would keep words on screen
     * that the phone has already retracted.
     */
    @Test
    fun `a retraction reaches a tab that already read the message`() {
        val kept = server.addMessage("kept", "another device", clientIp = "10.0.0.9")
        val retracted = server.addMessage("secret words", "another device", clientIp = "10.0.0.9")
        val last = server.addMessage("last", "another device", clientIp = "10.0.0.9")
        val cursor = read("/api/messages").cursor()

        server.markMessageDeleted(retracted.id)
        val patch = read("/api/messages?since=$cursor")
        assertEquals("1", patch.header("X-Room-Delta"))
        val sent = ids(patch.text)
        assertEquals(listOf(retracted.id), sent)
        assertNotEqualsFragment(kept.id, patch.text)
        assertNotEqualsFragment(last.id, patch.text)
        val body = org.json.JSONArray(patch.text).getJSONObject(0)
        assertTrue("the retraction arrived without its tombstone flag: $patch", body.getBoolean("deleted"))
        assertEquals("", body.getString("text"))
    }

    /**
     * A cursor describes one caller's view. Handing another address the same marker would answer it
     * from the first tab's place in the room, and what the second tab may see is not what the first
     * one could.
     */
    @Test
    fun `one address's cursor is not another address's`() {
        server.addMessage("room note", "another device", clientIp = "10.0.0.9")
        val cursor = TabSession("/api/messages", Method.GET, "10.0.0.9",
            mapOf("x-pulse-auth" to token)).sendTo(server).headers.getValue("x-room-cursor")
        assertNotNull(cursor)

        val other = TabSession("/api/messages", Method.GET, "10.0.0.8",
            mapOf("x-pulse-auth" to token), query = mapOf("since" to cursor!!)).sendTo(server)
        assertNull("a foreign cursor was honoured", other.headers["x-room-delta"])
        assertTrue(other.body.contains("room note"))
    }

    /**
     * Opening a group changes what a tab may read without changing the room's contents, so the marker
     * has to notice the rules moved as well as the messages. Without this, a tab would sit on a
     * cursor that excludes messages it is now allowed to see, until it reloaded.
     */
    @Test
    fun `a thread opened after a cursor forces a whole read rather than a patch`() {
        server.addMessage("in the room", "another device", clientIp = "10.0.0.9")
        server.addMessage("in the group", "another device", clientIp = "10.0.0.9", threadId = "group:demo")
        val before = TabSession("/api/messages", Method.GET, "127.0.0.1",
            mapOf("x-pulse-auth" to token)).sendTo(server)
        assertFalse(before.body.contains("in the group"))
        val cursor = before.headers.getValue("x-room-cursor")

        server.webThreads = listOf(WebThread("group:demo", "Demo", "group", listOf()))
        val after = TabSession("/api/messages", Method.GET, "127.0.0.1",
            mapOf("x-pulse-auth" to token), query = mapOf("since" to cursor!!)).sendTo(server)
        assertNull("a patch was answered under a visibility the cursor never saw", after.headers["x-room-delta"])
        assertTrue("the newly opened thread is still missing: ${after.body}", after.body.contains("in the group"))
        server.webThreads = emptyList()
    }

    /**
     * The marker rides in a URL query, and the rule for that route is that nothing secret may ever be
     * there. It is a place in a list plus a digest of addresses — no PIN, no token — and this is the
     * test that keeps it that way rather than a comment saying so.
     */
    @Test
    fun `the cursor carries no credential`() {
        server.addMessage("note", "another device", clientIp = "10.0.0.9")
        val cursor = read("/api/messages").cursor()
        val parts = cursor.split(":")
        assertEquals("the cursor is not three fields: $cursor", 3, parts.size)
        parts.forEach { field ->
            assertFalse("the cursor leaks the PIN", field.contains(pin))
            assertFalse("the cursor leaks the session token", field.contains(token))
        }
        assertTrue("the first two fields are not numbers: $cursor",
            parts[0].toLongOrNull() != null && parts[1].toLongOrNull() != null)
    }

    /**
     * The tab's half, asserted on the served text: a patch is folded into what the tab holds, and a
     * whole room replaces it. Reading a patch as a replacement would leave the screen showing only
     * the messages that moved — a room of two bubbles out of two hundred.
     */
    @Test
    fun `the tab folds a patch and replaces a whole room`() {
        val page = WebClientHtml.getHtml("Test Phone", true)
        val offer = page.indexOf("var url = roomCursor ?")
        val delta = page.indexOf("x-room-delta")
        assertTrue("the page never offers its cursor", offer in 0 until delta)
        assertTrue("the answer's patch flag is not read", delta > 0)
        assertTrue("a patch is not merged", page.contains("if (isDelta) mergeRoomPatch(msgs); else lastMsgs = msgs;"))
        assertTrue("a retraction would not replace the message it retracts",
            page.contains("lastMsgs[at[m.id]] = m;"))
        assertTrue("the marker survives a logout it has no right to survive",
            page.contains("roomCursor = '';"))
    }

    /**
     * A marker from further back than the room now reaches.
     *
     * Retention evicts from the front at 2,000 messages, so a tab that slept through that much
     * traffic holds a revision older than anything the phone still has. The claim in the backlog is
     * that this cannot *miss* a message — the filter simply keeps everything retained, and the tab
     * repeats what it already holds. Repeating is the cost; missing one would be the bug, and it is
     * the bug an offset-based cursor would have shipped silently.
     */
    @Test
    fun `a marker older than the retained room misses nothing`() {
        val first = server.addMessage("the oldest note", "another device", clientIp = "10.0.0.9")
        val stale = read("/api/messages").cursor()

        // Enough traffic to push the message the marker was minted against off the front.
        repeat(2001) { i ->
            server.addMessage("flood $i", "another device", clientIp = "10.0.0.9")
        }
        assertFalse("retention did not evict, so this test proves nothing",
            ids(read("/api/messages").text).contains(first.id))

        val patch = read("/api/messages?since=$stale")
        assertEquals("1", patch.header("X-Room-Delta"))
        val whole = ids(read("/api/messages").text)
        assertEquals("retention did not cap the room at 2000", 2000, whole.size)
        assertEquals(
            "the patch is not everything the phone still holds",
            whole.sorted(), ids(patch.text).sorted()
        )
    }

    /**
     * The two mechanisms from #81 and #83 together, which is the pair most likely to fight.
     *
     * A `304` carries no `X-Room-Cursor` — there is no new answer to stamp — so a tab that took the
     * 304 keeps the marker it already had. That is only safe while nothing moved, which is exactly
     * what a matching validator promises. The case worth asserting is the one after: the tab still
     * offers the *old* validator with its cursor, and the room has since grown, so the answer must be
     * a patch and not a `304` that hides a message behind bytes the tab never asked for.
     */
    @Test
    fun `an unchanged read that came back 304 does not strand the cursor`() {
        server.addMessage("before", "another device", clientIp = "10.0.0.9")
        val whole = read("/api/messages")
        val cursor = whole.cursor()
        val etag = requireNotNull(whole.etag) { "the room answered with no validator" }

        assertEquals(304, read("/api/messages", "If-None-Match" to etag).status)

        val newer = server.addMessage("after", "another device", clientIp = "10.0.0.9")
        val patch = read("/api/messages?since=$cursor", "If-None-Match" to etag)
        assertEquals("a changed room was answered 304 to a tab holding an old validator", 200, patch.status)
        assertEquals("1", patch.header("X-Room-Delta"))
        assertEquals(listOf(newer.id), ids(patch.text))
    }

    /**
     * A marker the phone cannot make sense of is not an error and not an empty room. Three shapes
     * reach this: nothing offered, junk offered, and a well-formed marker minted for a different
     * caller. All three answer the whole room without the patch flag, because the worst a server can
     * do to a reader it cannot trust is give it more than it needed.
     */
    @Test
    fun `a marker the phone cannot read is answered as the whole room`() {
        server.addMessage("room note", "another device", clientIp = "10.0.0.9")
        val mine = read("/api/messages").cursor()
        val foreignScope = "0:" + mine.split(":")[1] + ":" + "f".repeat(16)

        listOf("", "not-a-marker", "7:1834", "seven:eighteen:${mine.split(":")[2]}", foreignScope)
            .forEach { offered ->
                val answer = read(if (offered.isEmpty()) "/api/messages?since=" else "/api/messages?since=$offered")
                assertEquals("an unreadable marker was answered with $answer", 200, answer.status)
                assertNull("an unreadable marker was honoured as a patch", answer.header("X-Room-Delta"))
                assertTrue("the whole room was not in the answer", answer.text.contains("room note"))
            }
    }
}
