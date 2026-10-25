package com.pulsedrop.app.server

import fi.iki.elonen.NanoHTTPD.Method
import java.io.File
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/**
 * Baseline numbers for the host half of PulseDrop, taken on the machine that runs the tests.
 *
 * This is a probe, not a gate: it prints measurements and asserts only that the thing being
 * measured happened at all. It is skipped unless `PULSEDROP_PROBE=true` in the environment, so an
 * ordinary `./gradlew test` run neither pays for it nor turns its timings into a flaky failure.
 *
 * What it can and cannot say. Everything here is the *server*: handler duration, payload size,
 * socket throughput, event fan-out. The browser's main thread, a phone's camera, a MediaCodec
 * block and a real Wi-Fi link are not on this machine and are not measured by anything below —
 * a figure from here is a figure about NanoHTTPD on this CPU, and a regression on a phone can
 * exist with every number here unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostBaselineProbe {

    private val pin = "424242"
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String
    private var port = 0
    private val lines = mutableListOf<String>()

    @Before
    fun startServer() {
        // An environment variable rather than a system property: the test JVM is forked, and `-D`
        // on the command line goes to Gradle's own JVM, not to the one that runs this.
        assumeTrue("probe runs only with PULSEDROP_PROBE=true", System.getenv("PULSEDROP_PROBE") == "true")
        port = RoomHttp.freePort()
        server = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = RoomHttp.appContext(),
            initialDeviceName = "Probe Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        server.start()
        RoomHttp.awaitListening(port)
        http = RoomHttp(port)
        token = http.requireLogin(pin)
        val cache = RoomHttp.appContext().cacheDir.apply { mkdirs() }
        receivedDir().mkdirs()
        // A phone-sized amount of free space, not the tiny figure the ceiling tests register: a
        // probe that measures throughput must not be measuring the storage guard saying no.
        ShadowStatFs.registerStats(cache.absolutePath, 4096, 30_000_000, 30_000_000)
        ShadowStatFs.registerStats(receivedDir().absolutePath, 4096, 30_000_000, 30_000_000)
    }

    @After
    fun stopAndReport() {
        if (!::server.isInitialized) return
        server.stop()
        ShadowStatFs.reset()
        val out = System.getenv("PULSEDROP_PROBE_OUT")
        if (out != null) File(out).appendText(lines.joinToString("\n") + "\n")
        lines.forEach { println(it) }
    }

    private fun receivedDir(): File =
        File(
            (RoomHttp.appContext().getExternalFilesDir(null) ?: RoomHttp.appContext().filesDir),
            PulseDropServer.RECEIVED_DIR_NAME
        )

    private fun report(section: String, metric: String, value: String) {
        lines += "PROBE\t$section\t$metric\t$value"
    }

    /** Handler-only request: no socket, so the number is the route's own work. */
    private fun callInProcess(
        path: String,
        verb: Method = Method.GET,
        json: String? = null
    ): Triple<Long, Int, Int> {
        val start = System.nanoTime()
        val answer = TabSession(
            path, verb, "127.0.0.1",
            requestHeaders = mapOf("x-pulse-auth" to token, "content-type" to "application/json"),
            json = json
        ).sendTo(server)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return Triple(elapsedMs, answer.body.toByteArray().size, answer.status)
    }

    /**
     * A poll that actually reached the route.
     *
     * `serve()` keeps a token bucket per address (120 burst, 30/s) and every client here is
     * loopback, so a probe that measures a thousand polls in a row is otherwise measuring the
     * flood guard's 45-byte rejection — which is a real number about the flood guard and no
     * number at all about the route it replaced.
     */
    private fun polledSamples(path: String, samples: Int): Pair<LongArray, Int> {
        val times = LongArray(samples)
        var bytes = 0
        var taken = 0
        var guard = 0
        while (taken < samples && guard++ < 400) {
            val (ms, size, status) = callInProcess(path)
            if (status == 429) {
                Thread.sleep(50)
                continue
            }
            times[taken++] = ms
            bytes = size
        }
        return times.copyOf(taken) to bytes
    }

    private fun percentile(samples: LongArray, p: Double): Long {
        if (samples.isEmpty()) return -1
        val sorted = samples.sortedArray()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    @Test
    fun documentPayloadAndHandlerCost() {
        val sizes = mutableListOf<Int>()
        val times = LongArray(20)
        for (i in 0 until 20) {
            val (ms, bytes, _) = callInProcess("/")
            times[i] = ms
            if (i == 0) sizes += bytes
        }
        report("document", "bytes", sizes.first().toString())
        report("document", "handler_p50_ms", percentile(times, 0.50).toString())
        report("document", "handler_p95_ms", percentile(times, 0.95).toString())
    }

    /**
     * `/api/messages` is polled on a timer and serialises the whole visible history every time, so
     * the cost of an idle tab grows with how much that tab has ever said.
     */
    @Test
    fun messagePollScalesWithHistory() {
        for (target in listOf(1, 200, 1000, 2000)) {
            var seeded = countMessages()
            while (seeded < target) {
                val answer = TabSession(
                    "/api/messages", Method.POST, "127.0.0.1",
                    requestHeaders = mapOf("x-pulse-auth" to token, "content-type" to "application/json"),
                    json = "{\"text\":\"seed $seeded padding-padding-padding-padding-padding\",\"sender\":\"prober\"}"
                ).sendTo(server)
                // The same flood guard: a rejected POST stores nothing, and counting it as a seed
                // would silently measure a smaller history than the row claims.
                if (answer.status == 429) Thread.sleep(40)
                else if (answer.status == 200) seeded++
            }
            val (samples, bytes) = polledSamples("/api/messages", 15)
            report("messages", "stored_$target bytes", bytes.toString())
            report("messages", "stored_$target p50_ms", percentile(samples, 0.50).toString())
            report("messages", "stored_$target p95_ms", percentile(samples, 0.95).toString())
            report("messages", "stored_$target polls_measured", samples.size.toString())
        }
    }

    private fun countMessages(): Int =
        Regex("\"id\":").findAll(http.call("GET", "/api/messages", mapOf("x-pulse-auth" to token)).body).count()

    @Test
    fun transferThroughputAndInterference() {
        val megabytes = 32L
        val total = megabytes * 1024 * 1024

        val uploadStart = System.currentTimeMillis()
        val uploaded = http.streamBody("/upload?path=probe.bin", token, total)
        val uploadMs = System.currentTimeMillis() - uploadStart
        assertTrue("the probe upload must land: ${uploaded.body}", uploaded.status == 200)
        report("transfer", "upload_32MiB_ms", uploadMs.toString())
        report("transfer", "upload_32MiB_MBps", (total * 1000 / 1024 / 1024 / uploadMs.coerceAtLeast(1)).toString())

        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .find(http.call("GET", "/api/files", mapOf("x-pulse-auth" to token)).body)
            ?.groupValues?.get(1) ?: error("probe file never appeared in /api/files")

        val heapBefore = usedHeap()
        val downloadStart = System.currentTimeMillis()
        val received = downloadBytes("/download/$id")
        val downloadMs = System.currentTimeMillis() - downloadStart
        report("transfer", "download_32MiB_ms", downloadMs.toString())
        report("transfer", "download_32MiB_MBps", (received * 1000 / 1024 / 1024 / downloadMs.coerceAtLeast(1)).toString())
        report("transfer", "download_heap_growth_MiB", ((usedHeap() - heapBefore) / 1024 / 1024).toString())
        report("transfer", "download_bytes_client_read", received.toString())

        // The PRD's Phase Four requirement in one line: does a bulk transfer move messaging latency?
        // Both sides are measured over a real socket, because the point of the comparison is that
        // an idle route and a busy route are timed by the same clock and the same path.
        val idle = LongArray(15)
        for (i in 0 until 15) {
            idle[i] = elapsed { http.call("GET", "/api/messages", mapOf("x-pulse-auth" to token)) }
        }
        val inflight = LongArray(15)
        val writer = Thread { runCatching { http.streamBody("/upload?path=probe2.bin", token, total) } }
        writer.isDaemon = true
        writer.start()
        for (i in 0 until 15) {
            inflight[i] = elapsed { http.call("GET", "/api/messages", mapOf("x-pulse-auth" to token)) }
        }
        writer.join(120_000)
        report("interference", "messages_idle_p50_ms", percentile(idle, 0.50).toString())
        report("interference", "messages_idle_p95_ms", percentile(idle, 0.95).toString())
        report("interference", "messages_during_upload_p50_ms", percentile(inflight, 0.50).toString())
        report("interference", "messages_during_upload_p95_ms", percentile(inflight, 0.95).toString())
    }

    private fun elapsed(block: () -> Any?): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun downloadBytes(path: String): Long {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 120_000
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nx-pulse-auth: $token\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            var read = 0L
            val buffer = ByteArray(64 * 1024)
            var headerDone = false
            // Skip headers, then count body bytes.
            var pending = ByteArray(0)
            while (!headerDone) {
                val n = input.read()
                if (n == -1) break
                pending += n.toByte()
                if (pending.size > 4 && String(pending, pending.size - 4, 4) == "\r\n\r\n") headerDone = true
            }
            read += pending.size - 4
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                read += n
            }
            return read
        }
    }

    /** Read a tab until it stops answering, so the next timing measures a fresh delivery. */
    private fun drain(stream: LiveStream) {
        while (stream.nextFrame(250) != null) { }
    }

    /**
     * Room traffic from somewhere other than loopback.
     *
     * A tab is not doorbelled about a message it posted itself, and every socket a probe opens is
     * 127.0.0.1, so posting over HTTP would time a delivery the server correctly never makes. The
     * traffic a streaming tab exists to hear about is traffic that arrived from elsewhere.
     */
    private fun arrive(text: String) {
        server.addMessage(text, "another device", clientIp = "10.0.0.9")
    }

    /** Milliseconds from that traffic arriving to this tab being told, or -1 if it never was. */
    private fun deliveryMs(stream: LiveStream): Long {
        val start = System.nanoTime()
        arrive("delivery probe")
        return if (stream.nextFrame(5_000) != null) (System.nanoTime() - start) / 1_000_000 else -1L
    }

    /**
     * Event delivery: how long a doorbell takes to reach a tab, whether one tab that stopped
     * reading drags the others, and what a full house does to a tab that already has a stream.
     */
    @Test
    fun eventFanoutAndStalledSubscriber() {
        fun open(): LiveStream = RoomHttp.live(port, "/api/events", token)
        fun percentileOf(values: List<Long>, p: Double) = percentile(values.toLongArray(), p)

        val one = open()
        val clean = ArrayList<Long>()
        var cleanMissed = 0
        repeat(10) {
            val ms = deliveryMs(one)
            if (ms < 0) cleanMissed++ else clean += ms
        }
        report("events", "one_reader_p50_ms", percentileOf(clean, 0.50).toString())
        report("events", "one_reader_p95_ms", percentileOf(clean, 0.95).toString())
        report("events", "one_reader_missed", cleanMissed.toString())

        // Three readers that keep draining, one that stopped, and the first tab again. A stalled
        // reader's queue is bounded and drops the newest frame rather than blocking the producer, so
        // the question is whether the tabs that are reading feel it anyway.
        val live = listOf(open(), open(), open())
        val stalled = open()
        val readers = live + one
        val floodStart = System.nanoTime()
        repeat(200) { arrive("flood") }
        report("events", "push_200_events_to_5_tabs_ms", ((System.nanoTime() - floodStart) / 1_000_000).toString())
        readers.forEach { drain(it) }

        val worst = ArrayList<Long>()
        var stallMissed = 0
        repeat(5) {
            val start = System.nanoTime()
            arrive("after stall")
            val frames = readers.map { it.nextFrame(5_000) }
            val ms = (System.nanoTime() - start) / 1_000_000
            if (frames.none { it == null }) worst += ms else stallMissed++
        }
        report("events", "four_readers_with_one_stalled_p50_ms", percentileOf(worst, 0.50).toString())
        report("events", "four_readers_with_one_stalled_p95_ms", percentileOf(worst, 0.95).toString())
        report("events", "four_readers_with_one_stalled_missed", stallMissed.toString())
        readers.forEach { it.close() }
        stalled.close()

        // A full house: eight seats held, a ninth asking, and what that does to the oldest seat. The
        // assertions that this must behave one way live in EventStreamAdmissionTest; here it is
        // recorded, including how many of the eight the earlier closings left room for.
        val held = (1..8).map { open() }
        val ninth = open()
        val first = held.first()
        arrive("ninth check")
        val firstFrame = first.nextFrame(3_000)
        report("events", "eight_seats_opened", held.count { it.statusCode() == 200 }.toString())
        report("events", "ninth_status", ninth.statusCode().toString())
        report(
            "events", "first_stream_after_ninth",
            if (firstFrame?.startsWith("event: message") == true) "still delivered" else "cut off"
        )
        (held + ninth).forEach { it.close() }
        assertTrue("at least one doorbell was delivered", clean.isNotEmpty())
    }

    @Test
    fun reconnectionCostWhenEveryTabRefetches() {
        // One tab reconnecting is a full refetch; the count of requests a tab needs to catch up
        // after a gap is what a reconnect storm multiplies.
        val routes = listOf("/api/messages", "/api/threads", "/api/files", "/api/peers", "/api/info", "/api/typing")
        routes.forEach { route ->
            val (ms, bytes, _) = callInProcess(route)
            report("reconnect", "$route bytes", bytes.toString())
            report("reconnect", "$route handler_ms", ms.toString())
        }
        val all = elapsed { routes.forEach { callInProcess(it) } }
        report("reconnect", "full_refetch_round_trip_ms", all.toString())
        report("reconnect", "requests_per_reconnect", routes.size.toString())
    }
}
