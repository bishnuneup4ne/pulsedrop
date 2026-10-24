package com.pulsedrop.app.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route table is the source of truth for who may call what, so this file holds it to the two
 * properties that make it worth reading: every row is documented, and the answer for a route whose
 * verb was not anticipated is still "credential first".
 *
 * The documentation half is not ceremony. The matrix is what a reviewer reads before adding a
 * route; a rule with no row there is a rule nobody agreed to, and the build says so instead of a
 * future reader finding out from a 401 in the field.
 */
class AuthorizationPolicyTest {

    @Test
    fun `every rule names a row that exists in the authorization matrix`() {
        val doc = matrix()
        val undocumented = AuthorizationPolicy.rules.filter { rule ->
            rule.matrixRow == null || rule.matrixRow !in doc
        }
        assertTrue(
            "these rules have no row in docs/Authorization-Matrix.md: " +
                undocumented.joinToString { it.path },
            undocumented.isEmpty()
        )
    }

    @Test
    fun `an unlisted verb on a known path still needs a credential`() {
        // The old chain let a method fall through to the next branch; a route that answered before
        // the global check therefore stayed public for every verb it did not implement. The table
        // makes the fallback explicit, and it is SESSION for every family.
        AuthorizationPolicy.rules.forEach { rule ->
            listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { verb ->
                if (rule.explicitAccess(verb) == null) {
                    assertEquals(
                        "${rule.path} answers $verb without a credential",
                        RouteAccess.SESSION,
                        rule.accessFor(verb)
                    )
                }
            }
        }
    }

    @Test
    fun `an unknown path is authenticated before it is not found`() {
        val rule = AuthorizationPolicy.forRequest("/api/does-not-exist", "GET")
        assertEquals(RouteAccess.SESSION, rule.accessFor("GET"))
    }

    @Test
    fun `an exact rule wins over a prefix that would swallow it`() {
        // `/api/call/web` is a browser route; `/api/call/` is the key-gated pairing family.
        assertEquals(RouteAccess.SESSION, AuthorizationPolicy.forRequest("/api/call/web", "POST").accessFor("POST"))
        assertEquals(RouteAccess.PAIRING, AuthorizationPolicy.forRequest("/api/call/invite", "POST").accessFor("POST"))
    }

    @Test
    fun `the public routes are public only for the verb that serves them`() {
        val cases = listOf(
            Triple("/", "GET", RouteAccess.PUBLIC),
            Triple("/", "POST", RouteAccess.SESSION),
            Triple("/api/info", "GET", RouteAccess.PUBLIC),
            Triple("/api/info", "POST", RouteAccess.SESSION),
            Triple("/api/typing", "GET", RouteAccess.PUBLIC),
            // P-2: reading presence is open; writing it can impersonate or evict.
            Triple("/api/typing", "POST", RouteAccess.SESSION),
            Triple("/api/auth", "POST", RouteAccess.PUBLIC),
            Triple("/api/auth", "GET", RouteAccess.SESSION),
            Triple("/api/auth/logout", "POST", RouteAccess.SELF)
        )
        cases.forEach { (path, verb, expected) ->
            assertEquals("$verb $path", expected, AuthorizationPolicy.forRequest(path, verb).accessFor(verb))
        }
    }

    @Test
    fun `only the five URL-bearing families accept a ticket`() {
        val ticketed = AuthorizationPolicy.rules
            .filter { rule -> listOf("GET", "POST", "PUT", "DELETE").any { rule.accessFor(it) == RouteAccess.SESSION_OR_TICKET } }
            .map { it.path }
            .toSet()
        assertEquals(
            setOf("/api/events", "/download/", "/download-folder", "/chatfile/", "/preview/"),
            ticketed
        )
    }

    @Test
    fun `a ticket is never accepted on a state-changing verb`() {
        AuthorizationPolicy.rules.forEach { rule ->
            listOf("POST", "PUT", "DELETE", "PATCH").forEach { verb ->
                assertNotEquals(
                    "${rule.path} would accept a URL ticket for $verb",
                    RouteAccess.SESSION_OR_TICKET,
                    rule.accessFor(verb)
                )
            }
        }
    }

    /** The matrix lives at the repository root; Gradle's working directory may be either side. */
    private fun matrix(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/Authorization-Matrix.md")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("docs/Authorization-Matrix.md not found above ${System.getProperty("user.dir")}")
    }
}
