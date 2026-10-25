package com.pulsedrop.app.server

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who gets an event stream when the phone has run out.
 *
 * One stream is one handler thread held open for the life of the tab, so the listener caps how many
 * it takes. That cap has two ways to answer the tab asking for the ninth seat: refuse it, or cut
 * somebody already streaming. Cutting was the original behaviour and it is the worse of the two —
 * the evicted tab's reconnect evicts the next, and a room one tab too large ends up with every tab
 * intermittently deaf rather than one tab knowingly polling.
 *
 * The 2 s poll keeps running either way, so a refused tab is degraded and not blind. That is the
 * whole argument for the trade, and these tests are its shape: the newcomer is told in a way it can
 * act on, and nobody who already had a stream loses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EventStreamAdmissionTest {

    private val pin = "424242"

    /** `maxEventStreams` in the server, restated here because the route's answer depends on it. */
    private val maxStreams = 8
    private var port = 0
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String
    private val open = mutableListOf<LiveStream>()

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
        open.forEach { it.close() }
        server.stop()
    }

    /** A tab that asked for the stream, with the answer's status line and headers already read. */
    private fun stream(): LiveStream =
        RoomHttp.live(port, "/api/events", token).also { open += it }

    /**
     * Room traffic that arrived from somewhere other than this test's own address.
     *
     * A tab is deliberately not doorbelled about a message it posted itself, and every socket here
     * shares 127.0.0.1, so a POST would ring none of the streams under watch and the test would read
     * a working stream as a silent one. What every tab is supposed to hear about is what came from
     * elsewhere, which is what this produces.
     */
    private fun arrive(text: String) {
        server.addMessage(text, "another device", clientIp = "10.0.0.9")
    }

    @Test
    fun `the ninth tab is refused rather than taking somebody else's stream`() {
        val held = (1..maxStreams).map { stream() }
        held.forEach { assertTrue("every seat up to the cap must open a stream: ${it.statusLine}", it.statusCode() == 200) }

        val refusal = stream()
        assertTrue("the ninth stream was accepted: ${refusal.statusLine}", refusal.statusCode() == 503)
        assertTrue(
            "a refusal with no wait to honour is a retry storm:\n${refusal.headers}",
            refusal.headers.contains("Retry-After")
        )
        assertTrue(
            "a cacheable refusal outlives the traffic that caused it:\n${refusal.headers}",
            refusal.headers.contains("no-store")
        )

        // The eight that were already streaming are untouched — proved by delivering to the oldest,
        // which is the one an eviction policy cuts first.
        arrive("after the refusal")
        val frame = held.first().nextFrame()
        assertTrue(
            "the oldest tab went quiet when the ninth arrived: $frame",
            frame?.startsWith("event: message") == true
        )
    }

    /**
     * A tab that closes does not hand its seat back when the socket goes: the handler thread is
     * parked on its queue, and only the next write — a delivered event, or the heartbeat — learns the
     * reader is gone. Twenty seconds of that is a refused tab waiting twenty seconds for a seat that
     * is already free, so asking for a stream now probes the holders first and reaps whoever fails to
     * answer. This pins both halves: the seat comes back promptly, and the tab that waited is then
     * served.
     */
    @Test
    fun `a seat returns when its tab goes away`() {
        val held = (1..maxStreams).map { stream() }
        val refused = stream()
        assertTrue("expected the ninth to be refused, got ${refused.statusLine}", refused.statusCode() == 503)
        refused.close()
        open.remove(refused)

        val departed = held.first()
        departed.close()
        open.remove(departed)

        var accepted: LiveStream? = null
        var waited = 0
        while (accepted == null && waited < 15_000) {
            Thread.sleep(250); waited += 250
            val tryAgain = stream()
            if (tryAgain.statusCode() == 200) accepted = tryAgain else { tryAgain.close(); open.remove(tryAgain) }
        }
        val next = accepted ?: error("a freed seat never let the next tab stream after ${waited}ms")
        assertTrue(
            "a seat that was already free took ${waited}ms to be handed out",
            waited <= 5_000
        )

        arrive("for the tab that waited")
        assertTrue(
            "the tab that waited never received the next message",
            next.nextFrame()?.startsWith("event: message") == true
        )
    }
}
