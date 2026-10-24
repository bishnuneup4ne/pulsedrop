package com.pulsedrop.app.server

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import android.os.StatFs

/**
 * What a body may cost the phone, and what is left behind when it cannot be paid.
 *
 * NanoHTTPD 2.3.1 hands `serve()` the headers and leaves the body unread; the first thing that
 * touches it is the route's own `parseBody`, and from there the declared `Content-Length` is copied
 * whole — into a Java String for JSON, into a file in `cacheDir` for a multipart upload. So the only
 * ceiling that costs nothing to enforce is the one answered before `parseBody`, and it was answered
 * on two of the nine routes that read a body. The other seven would buffer whatever a socket was
 * pointed at, including `/api/typing`, which asks for no credential at all.
 *
 * A body that arrives under its declared length is a different problem, and these tests do not
 * pretend to cover it: `parseBody` reads exactly `Content-Length` bytes and stops, so the remainder
 * is left in the socket rather than written to storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadCeilingTest {

    private val pin = "424242"
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String
    private lateinit var cacheDir: File

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
        // Two volumes are measured here and they need separate numbers: `hasFreeSpaceFor` guards the
        // received folder, `uploadBudgetBytes` guards the cache folder the body is buffered in.
        // Robolectric reports an empty filesystem by default, which would fail every upload test on
        // space rather than on the rule it is about — and the cache volume is deliberately registered
        // *small*, because the point of most of these tests is a body the phone cannot spare room for.
        val received = receivedDir().apply { mkdirs() }
        ShadowStatFs.registerStats(received.absolutePath, 512, 2_000_000, 4_000_000)
        cacheDir = RoomHttp.appContext().cacheDir.apply { mkdirs() }
        ShadowStatFs.registerStats(cacheDir.absolutePath, 4096, 4_000, 8_000)
    }

    @After
    fun stopServer() {
        server.stop()
        ShadowStatFs.reset()
    }

    private fun receivedDir(): File =
        File(
            (RoomHttp.appContext().getExternalFilesDir(null) ?: RoomHttp.appContext().filesDir),
            PulseDropServer.RECEIVED_DIR_NAME
        )

    private fun partFiles(): List<File> =
        if (receivedDir().isDirectory) receivedDir().walkTopDown().filter { it.name.endsWith(".part") }.toList()
        else emptyList()

    /** A declared length no phone is being asked to spare room for right now. */
    private val tooBig = 2_000_000_000

    // -- the upload ceilings -------------------------------------------------

    @Test
    fun `an upload declaring more than the device can hold is refused without reading it`() {
        val answer = http.call(
            "POST", "/upload",
            mapOf("Content-Type" to "multipart/form-data; boundary=----x", "X-Pulse-Auth" to token),
            body = "a", declareContentLength = tooBig
        )
        assertEquals("free space minus headroom is the ceiling", 413, answer.status)
        assertTrue(answer.headers.contains("Cache-Control: no-store"))
        assertTrue("and none of it may be on storage", partFiles().isEmpty())
        assertFalse(receivedDir().resolve("a").exists())
    }

    @Test
    fun `the refusal is reached before the phone has held the body`() {
        // The declared length is above the cache volume here, so a server that reads the socket to
        // find out is a server that just wrote 40 MB into cacheDir. `streamedBytes` is what the
        // client managed to push before the phone hung up: the whole body means it read it all.
        val offered = 40L * 1024 * 1024
        val answer = http.streamBody("/upload", token, offered)
        assertEquals(413, answer.status)
        assertTrue(
            "the phone read " + http.streamedBytes + " of $offered; it should not have needed any",
            http.streamedBytes < offered
        )
        assertTrue("and none of it landed", partFiles().isEmpty())
    }

    @Test
    fun `an oversized chat attachment upload is refused the same way`() {
        val answer = http.call(
            "POST", "/chatupload",
            mapOf("Content-Type" to "multipart/form-data; boundary=----x", "X-Pulse-Auth" to token),
            body = "a", declareContentLength = tooBig
        )
        assertEquals(413, answer.status)
    }

    @Test
    fun `an upload that fits still arrives whole`() {
        val up = http.postMultipart("/upload?path=Kept%2Fone.txt", mapOf("one.txt" to "intact"), token)
        assertEquals(200, up.status)
        val landed = receivedDir().resolve("Kept/one.txt")
        assertTrue("the ceiling must not be so tight that transfers break", landed.isFile)
        assertEquals("intact", landed.readText())
    }

    // -- what a failed upload leaves behind ----------------------------------

    @Test
    fun `a completed upload leaves no half file in the received folder`() {
        val up = http.postMultipart("/upload?path=Kept%2Ftwo.txt", mapOf("two.txt" to "bytes"), token)
        assertEquals(200, up.status)
        assertTrue(receivedDir().resolve("Kept/two.txt").isFile)
        assertTrue(partFiles().toString() + " must be gone", partFiles().isEmpty())
    }

    @Test
    fun `an encrypted upload for a pairing this phone does not know writes nothing`() {
        // The old code decrypted straight onto the final name, so a refusal could leave a file there
        // and the room could be told a transfer had arrived.
        val up = http.postMultipart(
            "/upload?path=Ghost%2Fnever.txt", mapOf("never.txt" to "ciphertext"), token,
            mapOf("X-Pair-Crypt" to "1", "X-Pair-Id" to "no-such-pair")
        )
        assertTrue("the phone says why: " + up.body, up.body.contains("Unknown pairing id"))
        assertFalse(receivedDir().resolve("Ghost/never.txt").exists())
        assertTrue(partFiles().toString() + " must be gone", partFiles().isEmpty())
    }

    @Test
    fun `a failed upload does not describe itself with a filesystem path`() {
        // `/upload` used to answer 500 with `e.message`, and the messages from this depth name the
        // file that failed. The received directory is under the app's private storage, so that is a
        // map of the device handed to whoever sent the body.
        val up = http.postMultipart(
            "/upload?path=Ghost%2Fleak.txt", mapOf("leak.txt" to "ciphertext"), token,
            mapOf("X-Pair-Crypt" to "1", "X-Pair-Id" to "no-such-pair")
        )
        assertFalse("the reply must not locate the app's storage: " + up.body,
            up.body.contains(receivedDir().absolutePath))
    }

    // -- the routes that ask for no credential -------------------------------

    @Test
    fun `the login route refuses an oversized body and stays usable`() {
        val answer = http.call(
            "POST", "/api/auth", mapOf("Content-Type" to "application/json"),
            body = "{\"pin\":\"$pin\"}", declareContentLength = tooBig
        )
        assertEquals("a PIN is six digits; this is not a tight fit", 413, answer.status)
        // A ceiling that took the server down would look like a pass from here, so log in again.
        assertNotNull(http.login(pin))
    }

    @Test
    fun `presence ping of a gigabyte is refused by a route that takes no credential`() {
        // `/api/typing` answers to the room before any token is checked, so before this the only
        // thing between an unauthenticated socket and this phone's storage was the route's own
        // willingness to read the body.
        val answer = http.call(
            "POST", "/api/typing", mapOf("Content-Type" to "application/json"),
            body = "{\"sender\":\"x\"}", declareContentLength = tooBig
        )
        assertEquals(413, answer.status)
        assertFalse("and no presence was recorded", typingReports("x"))
    }

    @Test
    fun `a normal presence ping still gets through the ceiling`() {
        val answer = http.call(
            "POST", "/api/typing", mapOf("Content-Type" to "application/json"),
            "{\"sender\":\"Honest tab\",\"typing\":true}"
        )
        assertEquals(200, answer.status)
        assertTrue("the ceiling is a ceiling, not a block", answer.body.contains("success"))
        assertTrue(typingReports("Honest tab"))
    }

    private fun typingReports(sender: String): Boolean =
        http.call("GET", "/api/typing").body.contains(sender)

    // -- where the ceiling comes from ----------------------------------------

    @Test
    fun `the ceiling is the space the volume reports, less headroom`() {
        // Read back from `StatFs` rather than recomputing what the registration should have meant:
        // Robolectric's shadow derives `availableBytes` from its own block size, and a test that
        // hardcoded that arithmetic would be a test of the shadow.
        val dir = File(cacheDir, "measured").apply { mkdirs() }
        ShadowStatFs.registerStats(dir.absolutePath, 4096, 30_000_000, 30_000_000)
        val free = StatFs(dir.absolutePath).availableBytes
        assertTrue("the fixture needs a volume above the headroom, got $free", free > 128L * 1024 * 1024)
        assertEquals(free - 64L * 1024 * 1024, uploadBudgetBytes(dir))
        dir.delete()
    }

    @Test
    fun `a nearly full volume still gets a floor rather than nothing`() {
        val dir = File(cacheDir, "nearly-full").apply { mkdirs() }
        ShadowStatFs.registerStats(dir.absolutePath, 4096, 1_000, 2_000)
        assertTrue(
            "so the fixture is really exercising the floor",
            StatFs(dir.absolutePath).availableBytes < 64L * 1024 * 1024
        )
        assertEquals(32L * 1024 * 1024, uploadBudgetBytes(dir))
        dir.delete()
    }
}
