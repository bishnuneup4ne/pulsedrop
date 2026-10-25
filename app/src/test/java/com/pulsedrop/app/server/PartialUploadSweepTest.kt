package com.pulsedrop.app.server

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a process killed mid-upload leaves behind, and what the next start is allowed to remove.
 *
 * `handleFileUpload` copies into `.<uuid>.part` and only deletes it in a `finally`; a kill that skips
 * the `finally` leaves the landing file forever. Nothing lists that folder — the browser list and the
 * Receive screen both read the in-memory registry — so the leftover is invisible and permanent,
 * quietly spending storage the phone may need. `start()` sweeps it before the listener exists, when
 * nothing can be uploading.
 *
 * The sweep is deliberately narrow. Only a leading-dot UUID-shaped `.part` is removed:
 * `sanitizeFileName` trims dots, so a received file cannot be named that way, but a user's own
 * `movie.part` or `.nomedia` can exist and must survive. A test that only proved "old parts go away"
 * would pass just as well against a sweep that deleted the whole folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PartialUploadSweepTest {

    private var server: PulseDropServer? = null

    @After
    fun stopServer() {
        server?.stop()
    }

    private fun receivedDir(): File =
        File(
            (RoomHttp.appContext().getExternalFilesDir(null) ?: RoomHttp.appContext().filesDir),
            PulseDropServer.RECEIVED_DIR_NAME
        )

    private fun seed(name: String, dir: File = receivedDir()): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText("half a file") }
    }

    private fun seedPartial(dir: File = receivedDir()): File = seed(".${UUID.randomUUID()}.part", dir)

    /** The real thing: the same constructor and startup path the app uses. */
    private fun startServer(): PulseDropServer {
        val port = RoomHttp.freePort()
        val started = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = RoomHttp.appContext(),
            initialDeviceName = "Test Phone",
            initialPinCode = "424242",
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        started.start()
        RoomHttp.awaitListening(port)
        server = started
        return started
    }

    @Test
    fun `landing files left by a killed process are removed at startup`() {
        val inRoot = seedPartial()
        val nested = receivedDir().resolve("sub/folder").apply { mkdirs() }
        val inSubdir = seedPartial(nested)

        startServer()

        assertFalse("root landing file should be gone", inRoot.exists())
        assertFalse("nested landing file should be gone", inSubdir.exists())
    }

    @Test
    fun `finished uploads and ordinary dot files survive the sweep`() {
        val finished = seed("holiday.jpg")
        val extensionless = seed("notes")
        val dotFile = seed(".nomedia")
        val partExtension = seed("movie.part")
        val disguised = seed(".${UUID.randomUUID()}.part.txt")

        startServer()

        listOf(finished, extensionless, dotFile, partExtension, disguised).forEach {
            assertTrue("${it.name} should survive", it.exists())
        }
    }

    @Test
    fun `a landing file without the uuid shape is left alone`() {
        // Anything other than our own naming is indistinguishable from a received file, and
        // deleting a delivered file to clean up after ourselves would be the worse trade.
        val nameish = seed(".part")
        val notUuid = seed(".not-a-uuid.part")

        startServer()

        assertTrue(nameish.exists())
        assertTrue(notUuid.exists())
    }
}
