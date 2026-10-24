package com.pulsedrop.app.server

/**
 * What a route demands of its caller, declared once and consulted once.
 *
 * The dispatcher used to answer this question at three different points: some routes ran before
 * any check, some called `isAuthorized` inside their own branch, and one global check covered
 * everything below it. Tier C was an accident of position: a route added above that line was
 * public whether its author meant it or not. This table replaces the position with a lookup, so a
 * new route has no default at all — [forRequest] answers for it, and the only way that answer can
 * be wrong is if this table is wrong, where it can be read.
 *
 * The table is also what [AuthorizationPolicyTest] checks against `docs/Authorization-Matrix.md`.
 * Every rule names the row that documents it; a rule whose row disappears fails the build.
 */
internal enum class RouteAccess {
    /** No phone credential. The route is public by design and says why at its handler. */
    PUBLIC,

    /** The only thing the route can do is revoke the credential it was handed. */
    SELF,

    /** PIN-exempt but key-gated inside the handler: pairing, calls and relay mail. */
    PAIRING,

    /** A live session, or the PIN itself, in `X-Pulse-Auth`. Nothing read from a URL. */
    SESSION,

    /**
     * A session header, or a URL ticket for the exact object this request names. Tickets exist
     * only because `<img>`, `<a download>` and `EventSource` cannot attach a header.
     */
    SESSION_OR_TICKET;

    /** True when the route itself is the authority — no further credential is read here. */
    val isGuardedUpstream: Boolean
        get() = this != SESSION && this != SESSION_OR_TICKET
}

/**
 * One path family and what each verb on it requires.
 *
 * [fallback] is deliberately not PUBLIC: a method the route does not implement still has to pass
 * the credential check before it can reach the 404, exactly as the old global check did. Otherwise
 * `PUT /upload` would become a free probe of which paths exist.
 */
internal class RouteRule(
    val path: String,
    val prefix: Boolean,
    private val byMethod: Map<String, RouteAccess>,
    private val fallback: RouteAccess,
    /** A substring of the matrix row that documents this rule; null only for the default. */
    val matrixRow: String?
) {
    fun accessFor(method: String): RouteAccess = byMethod[method] ?: fallback

    /** The requirement this table states for [method], or null when it falls back. */
    fun explicitAccess(method: String): RouteAccess? = byMethod[method]

    fun matches(uri: String): Boolean = if (prefix) uri.startsWith(path) else uri == path
}

internal object AuthorizationPolicy {

    /**
     * Exact paths first, then prefixes in table order. `/api/call/web` is an exact rule and wins
     * over the `/api/call/` pairing prefix that would otherwise swallow it.
     */
    val rules: List<RouteRule> = listOf(
        exact("/", RouteAccess.SESSION, "`/`, `/index.html`", "GET" to RouteAccess.PUBLIC),
        exact("/index.html", RouteAccess.SESSION, "`/`, `/index.html`", "GET" to RouteAccess.PUBLIC),
        exact("/api/info", RouteAccess.SESSION, "`/api/info`", "GET" to RouteAccess.PUBLIC),
        exact("/api/typing", RouteAccess.SESSION, "`/api/typing`", "GET" to RouteAccess.PUBLIC),
        exact("/api/auth", RouteAccess.SESSION, "`/api/auth`", "POST" to RouteAccess.PUBLIC),
        exact("/api/auth/logout", RouteAccess.SESSION, "`/api/auth/logout`", "POST" to RouteAccess.SELF),
        exact("/api/ticket", RouteAccess.SESSION, "`/api/ticket`", "POST" to RouteAccess.SESSION),
        exact("/api/threads", RouteAccess.SESSION, "`/api/threads`"),
        exact("/api/events", RouteAccess.SESSION, "`/api/events`", "GET" to RouteAccess.SESSION_OR_TICKET),
        exact("/api/call/web", RouteAccess.SESSION, "`/api/call/web`"),
        exact("/api/webcall/down", RouteAccess.SESSION, "`/api/webcall/down`"),
        exact("/api/webcall/up", RouteAccess.SESSION, "`/api/webcall/up`"),
        exact("/api/peers", RouteAccess.SESSION, "`/api/peers`"),
        exact("/api/files", RouteAccess.SESSION, "`/api/files`"),
        exact("/api/delete", RouteAccess.SESSION, "`/api/delete`"),
        exact("/api/messages", RouteAccess.SESSION, "`/api/messages`"),
        exact("/download-folder", RouteAccess.SESSION, "`/download-folder?path=`",
            "GET" to RouteAccess.SESSION_OR_TICKET),
        exact("/upload", RouteAccess.SESSION, "`/upload`"),
        exact("/chatupload", RouteAccess.SESSION, "`/chatupload`"),

        prefix("/fonts/", RouteAccess.SESSION, "`/fonts/google-sans-", "GET" to RouteAccess.PUBLIC),
        prefix("/api/pair/", RouteAccess.SESSION, "`/api/pair/request`",
            "POST" to RouteAccess.PAIRING),
        prefix("/api/call/", RouteAccess.SESSION, "`/api/call/invite`",
            "POST" to RouteAccess.PAIRING),
        prefix("/api/relay/", RouteAccess.SESSION, "`/api/relay/post`",
            "POST" to RouteAccess.PAIRING),
        prefix("/download/", RouteAccess.SESSION, "`/download/{id}`",
            "GET" to RouteAccess.SESSION_OR_TICKET),
        prefix("/chatfile/", RouteAccess.SESSION, "`/chatfile/{id}`",
            "GET" to RouteAccess.SESSION_OR_TICKET),
        prefix("/preview/", RouteAccess.SESSION, "`/preview/{id}`",
            "GET" to RouteAccess.SESSION_OR_TICKET)
    )

    /**
     * A path this phone does not serve still passes the credential check, then 404s. The caller
     * cannot tell "not found" from "not yet authenticated", which is the one good half of the old
     * scheme and is kept deliberately.
     */
    private val defaultRule = RouteRule("*", prefix = true, emptyMap(), RouteAccess.SESSION, null)

    fun forRequest(uri: String, method: String): RouteRule {
        rules.firstOrNull { !it.prefix && it.matches(uri) }?.let { return it }
        rules.firstOrNull { it.prefix && it.matches(uri) }?.let { return it }
        return defaultRule
    }

    private fun exact(
        path: String,
        fallback: RouteAccess,
        matrixRow: String?,
        vararg byMethod: Pair<String, RouteAccess>
    ) = RouteRule(
        path = path,
        prefix = false,
        byMethod = byMethod.toMap(),
        fallback = fallback,
        matrixRow = matrixRow
    )

    private fun prefix(
        path: String,
        fallback: RouteAccess,
        matrixRow: String?,
        vararg byMethod: Pair<String, RouteAccess>
    ) = RouteRule(
        path = path,
        prefix = true,
        byMethod = byMethod.toMap(),
        fallback = fallback,
        matrixRow = matrixRow
    )
}
