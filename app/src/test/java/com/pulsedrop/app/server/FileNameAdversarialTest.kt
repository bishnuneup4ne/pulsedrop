package com.pulsedrop.app.server

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/**
 * What a caller-chosen file name can and cannot do to this phone.
 *
 * `?path=` on `/upload` is the only place in this server where a client picks the name of a file that
 * lands on the device, and the phone has no reason to trust the caller: the route sits behind the room
 * credential, but that credential is shared by every tab in the room, so "an attacker is anyone who
 * knows the PIN" is the honest threat model. The name then comes back out of three mouths — the
 * browser's file list, the `Content-Disposition` of a download, and the Receive screen on the phone —
 * which is why one sanitizer (`sanitizeFileName`, reached through `sanitizeRelativePath`) is worth
 * pinning harder than four copies of it would be.
 *
 * The invariants, in the order they matter:
 *
 *  * the bytes stay inside `WebDrop_Received`, whatever the query string says;
 *  * the saved name is a string the phone wrote, not one the caller's bytes were trusted into — no
 *    control characters, because a CR in a saved name is a response-splitting bug one download away;
 *  * the name is inert wherever it is read: markup and Unicode directional overrides are gone before
 *    anything can render them or be reordered by them;
 *  * an unfriendly name is transformed, not refused. A transfer that arrives is the product, so every
 *    test here reads the payload back as well as checking the name.
 *
 * Not decided by these tests: two clients naming the same file. Collisions still resolve by appending a
 * timestamp, and whether that should instead be a refusal is a response-shape question — see
 * `docs/operations/Issue-Backlog.md` R-2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileNameAdversarialTest {

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
        // Both volumes registered large: a name test that failed for want of disk would be measuring
        // the fixture. What a body may cost is `UploadCeilingTest`'s business.
        val received = receivedDir().apply { mkdirs() }
        ShadowStatFs.registerStats(received.absolutePath, 4096, 2_000_000, 4_000_000)
        val cache = RoomHttp.appContext().cacheDir.apply { mkdirs() }
        ShadowStatFs.registerStats(cache.absolutePath, 4096, 2_000_000, 4_000_000)
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

    private fun enc(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    private fun upload(rawPath: String, content: String = "payload"): RoomHttp.Answer =
        http.postMultipart("/upload?path=" + enc(rawPath), mapOf("file" to content), token)

    /**
     * The name the phone answers with is the name it saved under. Parsed as JSON rather than matched,
     * because `org.json` writes a saved `/` out as `\/` and a regex that does not know that returns a
     * name with backslashes in it — which then fails every assertion about the name it is supposed to
     * be checking.
     */
    private fun answeredName(answer: RoomHttp.Answer): String =
        try {
            JSONObject(answer.body).getString("name")
        } catch (e: Exception) {
            throw AssertionError("no name in ${answer.status} ${answer.body}")
        }

    private fun listed(): List<Pair<String, String>> =
        JSONArray(http.call("GET", "/api/files", mapOf("X-Pulse-Auth" to token)).body)
            .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .map { it.getString("id") to it.getString("name") }

    private fun idOf(name: String): String =
        listed().firstOrNull { it.second == name }?.first
            ?: throw AssertionError("the room list does not carry $name: " + listed().map { it.second })

    /** A name the phone wrote for itself: printable ASCII, one folder deep at most, inside the folder. */
    private fun assertInertAndKept(raw: String, payload: String) {
        val answer = upload(raw, payload)
        assertEquals("kept $raw rather than refusing it: " + answer.body, 200, answer.status)
        val name = answeredName(answer)
        assertTrue(
            "unprintable or non-ASCII survived in ${name.toList()}",
            name.all { it.code in 0x20..0x7E }
        )
        assertFalse("$raw produced an absolute name: $name", name.startsWith("/"))
        assertFalse("$raw left a traversal segment: $name", name.split("/").any { it == ".." || it == "." })
        val landed = File(receivedDir(), name)
        assertTrue(
            "$raw resolved outside its folder: ${landed.canonicalPath}",
            landed.canonicalPath.startsWith(receivedDir().canonicalPath + File.separator)
        )
        assertTrue("nothing landed for $raw under $name", landed.isFile)
        assertEquals(payload, landed.readText())
    }

    // -- staying inside the folder -------------------------------------------

    @Test
    fun `traversal in path= lands inside the received folder and nowhere else`() {
        // One shape per attempt: parents to an absolute target, the other separator, a leading slash,
        // a form that survives one decode, one that survives none, parents buried mid-path, the fullwidth
        // punctuation a normalising client might hand over, and a NUL splitting a traversal segment in
        // two. The last two matter because both defeat a filter that only looks for the ASCII `..` and
        // `/` pair — the character class answers them by excluding everything it does not name.
        for (raw in listOf(
                "../../../../etc/passwd",
                "..\\..\\..\\windows\\system.ini",
                "/etc/hosts",
                "/tmp/pulsedrop-escape.txt",
                "%2e%2e/%2e%2e/tmp/second.txt",
                "..%2f..%2fsecret.txt",
                "a/b/../../../../c/d.txt",
                "．．／．．／etc／passwd",
                "..\u0000../secret.txt",
                "....//....//etc/passwd"
            )) {
            assertInertAndKept(raw, "kept:" + raw.length)
        }
        assertFalse("?path= did not get to choose an absolute target",
            File("/tmp/pulsedrop-escape.txt").exists())
    }

    @Test
    fun `a dotfile name becomes a visible file rather than a hidden one`() {
        // Leading dots are trimmed, so no upload can plant a file whose meaning depends on being hidden,
        // and no caller can name a file into the shape this server uses while a transfer is still
        // landing (`.<uuid>.part`) — which is the other reason that shape carries no caller bytes.
        val name = answeredName(upload(".hidden", "shown"))
        assertEquals("hidden", name)
        assertTrue(File(receivedDir(), name).isFile)
        val trailing = answeredName(upload("trailing...", "trimmed"))
        assertEquals("trailing", trailing)
        val landingShape = answeredName(upload(".abc123.part", "shaped"))
        assertFalse("a caller could write the landing name itself: $landingShape",
            landingShape.endsWith(".part") && landingShape.startsWith("."))
    }

    @Test
    fun `a deep folder path is kept to four segments`() {
        val deep = (1..30).joinToString("/") { "dir$it" } + "/deep.txt"
        val name = answeredName(upload(deep, "shallow"))
        assertEquals("the tail of the structure is kept, the depth is not: $name", 4, name.split("/").size)
        assertTrue("the last four segments are the ones kept: $name",
            name.endsWith("dir28/dir29/dir30/deep.txt"))
        val landed = File(receivedDir(), name)
        assertTrue("the folder that fits is created: $name", landed.isFile)
        assertEquals("shallow", landed.readText())
    }

    // -- the name as it comes back off the wire ------------------------------

    @Test
    fun `a newline in path= cannot invent a response header`() {
        // The sanitizer is the only thing between a query string and `Content-Disposition`, which is
        // built from the saved name and written out by NanoHTTPD exactly as it was handed it. So the CR
        // is not decoration: if it survived, this is a split response on every download.
        val name = answeredName(upload("report\r\nSet-Cookie: stolen=1.txt", "flat"))
        assertFalse("a control character reached the saved name: ${name.toList()}",
            name.any { it == '\r' || it == '\n' })

        val answer = http.call("GET", "/download/" + idOf(name), mapOf("X-Pulse-Auth" to token))
        val lines = answer.headers.split("\r\n")
        val declared = lines.filter { it.contains(":") }.map { it.substringBefore(":").lowercase() }
        assertFalse("the reply declares a header nobody set: $declared",
            declared.any { it == "set-cookie" })
        assertEquals("one Content-Disposition, not one broken across lines",
            1, declared.count { it == "content-disposition" })
        assertEquals("the injected text belongs inside the name the phone wrote, nowhere else: " + lines,
            1, lines.count { it.contains("stolen") })
        assertEquals("flat", answer.body)
    }

    @Test
    fun `lone carriage returns, bare feeds and NULs are substituted too`() {
        for (raw in listOf("a\rb.txt", "a\nb.txt", "a\u0000b.txt", "a\u001f\u007fb.txt", "a\tb.txt")) {
            assertInertAndKept(raw, "kept")
        }
    }

    @Test
    fun `script payloads arrive as a name the browser list cannot execute`() {
        for (raw in listOf(
                "<script>alert(document.cookie)</script>.txt",
                "<svg onload=alert(1)>.svg",
                "\"><img src=x onerror=alert(1)>.png",
                "javascript:alert(1).txt"
            )) {
            assertInertAndKept(raw, "text")
        }
    }

    @Test
    fun `an uploaded html file is not served as a document`() {
        // `/preview/{id}` answers inline, so a name ending in `.html` is the one filename shape that
        // could turn the phone's own origin into the attacker's. The MIME type is guessed from the
        // saved extension by a table that has no HTML row, and nothing consults the uploaded bytes.
        val name = answeredName(upload("page.html", "<h1>not a document</h1>"))
        assertTrue("the extension is what identifies the file: $name", name.endsWith(".html"))
        val preview = http.call("GET", "/preview/" + idOf(name), mapOf("X-Pulse-Auth" to token))
        assertEquals(200, preview.status)
        assertFalse("preview serves an upload as HTML from this origin: " + preview.headers,
            preview.headers.contains("text/html"))
        assertTrue("and it is not the phone's guess either: " + preview.headers,
            preview.headers.contains("application/octet-stream"))
        assertEquals("<h1>not a document</h1>", preview.body)
    }

    @Test
    fun `a bidirectional override cannot reorder what the phone shows`() {
        // The classic shape: a name that reads `report.txt` to the eye and is an executable to the
        // shell. Unicode controls fall outside the allowed character class, so they never reach storage.
        for (raw in listOf(
                "Invoice\u202Egpj.exe",
                "photo\u202Dtxt.exe",
                "note\u2066iso.txt",
                "doc\u200Fmanifest.exe"
            )) {
            assertInertAndKept(raw, "bytes")
        }
    }

    // -- length and shape ----------------------------------------------------

    @Test
    fun `a 300 character name is truncated, not refused`() {
        val answer = upload("a".repeat(300) + ".txt", "still here")
        assertEquals("a long name is a long name, not an attack: " + answer.body, 200, answer.status)
        val name = answeredName(answer)
        assertTrue("expected a truncated name, got ${name.length} characters", name.length <= 120)
        assertTrue("the extension is what a receiver needs: $name", name.endsWith(".txt"))
        assertEquals("still here", File(receivedDir(), name).readText())
    }

    @Test
    fun `a name whose extension alone outruns the budget still lands`() {
        // The stem is kept as `120 - extension - 1` characters, which is negative for an extension
        // longer than the budget. A negative count is not a name, and must not cost the transfer.
        val answer = upload("a." + "b".repeat(300), "wide extension")
        assertEquals("an absurd extension must not break the upload: " + answer.body, 200, answer.status)
        val name = answeredName(answer)
        assertTrue("truncated to ${name.length}: $name", name.length <= 120)
        assertEquals("wide extension", File(receivedDir(), name).readText())
    }

    @Test
    fun `an empty or dot-only path still produces a real file`() {
        // Blank after sanitizing is the case where the name is thrown away entirely, so the phone has
        // to invent one. A name of `""` would be the folder itself, and `File(dir, "")` is a directory.
        for (raw in listOf("", "...", "..", "/", "   ", "//", ".", "./")) {
            val answer = upload(raw, "unnamed")
            assertEquals("$raw should get a generated name, not a failure: " + answer.body, 200, answer.status)
            val name = answeredName(answer)
            assertTrue("an empty name for $raw would be the folder itself", name.isNotBlank())
            val landed = File(receivedDir(), name)
            assertTrue("$name is not a file", landed.isFile)
            assertEquals("unnamed", landed.readText())
        }
    }

    @Test
    fun `windows reserved names are ordinary files on this platform`() {
        // There is no reserved-device-name problem on Android, and inventing one would cost real
        // transfers, since `nul` and `com1` are legal names here. Pinned so a future filter cannot
        // reject them without a compatibility note.
        for (raw in listOf("CON.txt", "nul", "com1.log", "LPT1", "aux.dat")) {
            val answer = upload(raw, "kept")
            assertEquals("reserved on Windows, ordinary here: $raw " + answer.body, 200, answer.status)
            val name = answeredName(answer)
            val landed = File(receivedDir(), name)
            assertTrue("$raw was renamed to $name", landed.isFile)
            assertEquals("kept", landed.readText())
        }
    }

    @Test
    fun `only the first of two path values is honoured`() {
        // `session.parameters` is a list and the handler takes the first entry. Which one is the point:
        // a client that sends two must not get a file it did not ask for, and must not get both.
        val answer = http.postMultipart(
            "/upload?path=" + enc("first.txt") + "&path=" + enc("second.txt"),
            mapOf("file" to "one"), token
        )
        assertEquals("first.txt", answeredName(answer))
        assertTrue(File(receivedDir(), "first.txt").isFile)
        assertFalse("the discarded value wrote a file too", File(receivedDir(), "second.txt").exists())
    }

    // -- names that collide --------------------------------------------------

    @Test
    fun `two different names sanitizing to one file keep both payloads`() {
        // `<` and `>` both become `_`, so `a<b.txt` and `a>b.txt` are the same file by the time they
        // reach the folder. The timestamp suffix is what stops the second from replacing the first.
        val first = answeredName(upload("a<b.txt", "left"))
        val second = answeredName(upload("a>b.txt", "right"))
        assertFalse("the second upload replaced the first", first == second)
        assertEquals("left", File(receivedDir(), first).readText())
        assertEquals("right", File(receivedDir(), second).readText())
    }

    @Test
    fun `two uploads of one name arriving at the same instant never mix their bytes`() {
        // Both requests read `finalFile.exists()` before either has written, so the name is decided
        // twice and the landing happens twice. Whether the second wins the name or a suffixed twin is
        // created is the open collision question (R-2); what must never happen is a file on storage that
        // is half of one send and half of the other, because that file is registered as a completed
        // transfer. It cannot: the landing name carries a uuid, so the two copies never share a path,
        // and `renameTo` within one directory replaces rather than interleaves.
        //
        // A race pin, not a proof — it only exercises the overlap if the two copies really land at once.
        val left = "L".repeat(256 * 1024)
        val right = "R".repeat(256 * 1024)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val answers = java.util.concurrent.ConcurrentLinkedQueue<RoomHttp.Answer>()
        val threads = listOf(left to "A", right to "B").map { (payload, label) ->
            Thread {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                answers.add(upload("race.txt", payload))
            }.apply { name = "name-race-$label"; start() }
        }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        threads.forEach { it.join(30_000) }

        val responses = answers.toList()
        assertEquals("both uploads answered", 2, responses.size)
        responses.forEach {
            assertEquals("a name collision must not cost a transfer: " + it.body, 200, it.status)
        }
        val onDisk = receivedDir().walkTopDown().filter { it.isFile }.toList()
        assertTrue("nothing landed: " + onDisk.map { it.name }, onDisk.isNotEmpty())
        for (f in onDisk) {
            val kinds = f.readText().toSet()
            assertTrue(
                "${f.name} holds ${kinds} — a mixture no client sent",
                kinds.size <= 1
            )
        }
        // Every name the room was told about has to be a file the room can actually read.
        for ((_, name) in listed()) {
            if (name == "race.txt" || name.startsWith("race_")) {
                assertTrue("the list names $name and the folder does not have it",
                    File(receivedDir(), name).isFile)
            }
        }
    }

    // -- the same sanitizer, elsewhere ---------------------------------------

    @Test
    fun `folder download cannot be walked out of the received directory`() {
        // `/download-folder?path=` shares `sanitizeRelativePath` with the upload and adds a canonical
        // containment check on top, so a hostile prefix has to be answered by one of the two — never
        // with a zip of the app's private storage, or of `WebDrop_Pending` beside it.
        upload("Kept/inside.txt", "member")
        for (raw in listOf("..", "../../..", "../WebDrop_Pending", "/data/data", "a/../../b", "/", "")) {
            val answer = http.call("GET", "/download-folder?path=" + enc(raw), mapOf("X-Pulse-Auth" to token))
            assertEquals("$raw answered ${answer.status}: " + answer.body.take(120), 404, answer.status)
            assertFalse("$raw came back as a zip", answer.body.startsWith("PK"))
        }
        val allowed = http.call("GET", "/download-folder?path=Kept", mapOf("X-Pulse-Auth" to token))
        assertEquals("the rule cannot forbid the folder it is standing in the way of", 200, allowed.status)
    }

    @Test
    fun `a symlink in the received folder is stopped by the containment check, not by the sanitizer`() {
        // The sanitizer cannot answer this one: `link` is a perfectly legal name. It is the second layer
        // that has to hold — `canonicalPath` resolves the link, so the prefix test sees a target outside
        // the folder and refuses. A client cannot create the link through `?path=`, because no separator
        // survives there, so this pins defence in depth against something that already has this phone's
        // filesystem rather than a remote hole. It has to be a link to a *directory*: a link to one file
        // fails `isDirectory` first and would prove nothing about the canonical check.
        val outsideDir = java.nio.file.Files.createTempDirectory("pulsedrop-outside").toFile()
        File(outsideDir, "not-yours.txt").writeText("private")
        val link = File(receivedDir(), "link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outsideDir.toPath())
        } catch (e: Exception) {
            // The host declined to make a link. That is a fixture problem, not a pass, and an
            // `Assume` says so instead of quietly turning this test green.
            org.junit.Assume.assumeNoException("this host will not make symlinks", e)
        }
        try {
            assertTrue("the fixture is really a folder through the link", link.isDirectory)
            val answer = http.call("GET", "/download-folder?path=link", mapOf("X-Pulse-Auth" to token))
            assertEquals("a link out of the folder is not the room's to zip: " + answer.body.take(80),
                404, answer.status)
            assertFalse("and it was not answered with the outside file", answer.body.contains("private"))
        } finally {
            link.delete()
            File(outsideDir, "not-yours.txt").delete()
            outsideDir.delete()
        }
    }

    @Test
    fun `a chat attachment name is one segment, whatever the caller called it`() {
        // `/chatupload` has no folder structure to keep, so it calls `sanitizeFileName` directly and
        // prefixes a uuid: the segment count is the whole traversal question, and the prefix is the
        // whole collision question, for attachments.
        for (raw in listOf("../../evil.txt", "C:\\windows\\evil.txt", "a/b/c.txt", "\r\nSet-Cookie: x=1.txt")) {
            val answer = http.postMultipart("/chatupload?filename=" + enc(raw), mapOf("file" to "bytes"), token)
            assertEquals("the attachment still arrives for $raw: " + answer.body, 200, answer.status)
            val name = JSONObject(answer.body).getJSONObject("attachment").getString("name")
            assertFalse("$raw produced a path for an attachment: $name", name.contains("/"))
            assertTrue("control characters survived in ${name.toList()}", name.all { it.code in 0x20..0x7E })
        }
        val chatDir = File(
            (RoomHttp.appContext().getExternalFilesDir(null) ?: RoomHttp.appContext().filesDir),
            "WebDrop_Chat"
        )
        val stored = chatDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("an attachment was stored: " + stored.map { it.name }, stored.isNotEmpty())
        assertTrue("every stored attachment is one uuid-prefixed segment: " + stored.map { it.name },
            stored.all { it.parentFile == chatDir && it.name.matches(Regex("[0-9a-f]{8}-.+")) })
    }

    @Test
    fun `no upload leaves a landing file behind`() {
        // Whatever the final name was, the landing file is gone once the route answers. The landing name
        // carries a uuid rather than the final name, so two sends of one path cannot share it.
        for (raw in listOf("plain.txt", "sub/dir.txt", "a".repeat(200) + ".txt", ".dotted")) {
            assertEquals(200, upload(raw, "bytes").status)
        }
        val leftovers = receivedDir().walkTopDown().filter { it.name.endsWith(".part") }.toList()
        assertTrue("a transfer still landing, or a corpse: " + leftovers.map { it.name },
            leftovers.isEmpty())
    }
}
