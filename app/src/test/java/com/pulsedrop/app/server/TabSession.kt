package com.pulsedrop.app.server

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * One browser tab, as the server sees it: an address, a credential, a body — pushed straight into
 * `serve()` instead of over a socket.
 *
 * [RoomHttp] is the right harness for anything about the wire itself, and it is honest about its
 * one limit: every client connects from loopback, so all of them arrive as `127.0.0.1`. Ownership
 * can still be tested that way — plant a record with a foreign address — but *"may this caller read
 * that object"* cannot, because the caller's address is the other half of the question and three
 * tabs all have the same one. A scope rule tested where every client is identical passes without
 * the rule: the very first version of these tests was a socket suite, and it said so only after a
 * probe was written to assert the opposite.
 *
 * `HTTPSession` is an inner class of NanoHTTPD and cannot be built from outside it, but `serve()`
 * takes the `IHTTPSession` interface, so this implements that. Nothing listens on a port for the
 * length of a run.
 */
class TabSession(
    val path: String,
    val verb: Method,
    /** The address NanoHTTPD will report for the caller. */
    val ip: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val json: String? = null,
    val filePart: Pair<String, String>? = null,
    /** Overrides the declared length only; the bytes a real socket would send are `json`'s. */
    val declareContentLength: Long? = null
) : IHTTPSession {

    private val tempFile: File? = filePart?.let { (name, contents) ->
        File.createTempFile("tab-", name).apply { writeText(contents) }
    }

    /** Set when the route reached `parseBody` — a refusal that read the body refused nothing. */
    var bodyRead = false
        private set

    override fun getUri() = path
    override fun getMethod() = verb
    override fun getRemoteIpAddress() = ip
    override fun getRemoteHostName() = ip
    override fun getHeaders(): Map<String, String> =
        requestHeaders + mapOf(
            "content-length" to (declareContentLength ?: json?.toByteArray()?.size?.toLong() ?: 0L).toString()
        )
    override fun getParms() = query
    override fun getParameters(): Map<String, List<String>> =
        query.mapValues { listOf(it.value) } +
            (filePart?.let { mapOf(it.first to listOf(tempFile!!.absolutePath)) } ?: emptyMap())

    override fun getQueryParameterString() = query.entries.joinToString("&") { "${it.key}=${it.value}" }

    override fun parseBody(store: MutableMap<String, String>) {
        bodyRead = true
        json?.let { store["postData"] = it }
        filePart?.let { store[it.first] = tempFile!!.absolutePath }
    }

    override fun getCookies() = null
    override fun getInputStream(): InputStream = ByteArrayInputStream((json ?: "").toByteArray())
    override fun execute() {}

    fun sendTo(server: PulseDropServer): TabAnswer = TabAnswer(server.serve(this))
}

/** What a tab gets back, with the two things worth asserting on a response: its body and its headers. */
class TabAnswer(response: Response) {
    val status: Int = response.status.requestStatus
    val body: String = response.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
    val headers: Map<String, String?> = mapOf(
        "content-disposition" to response.getHeader("Content-Disposition"),
        "x-content-type-options" to response.getHeader("X-Content-Type-Options"),
        "cache-control" to response.getHeader("Cache-Control")
    )
    val mimeType: String? = response.mimeType
}
