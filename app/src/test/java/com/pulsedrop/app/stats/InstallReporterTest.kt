package com.pulsedrop.app.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole point of the count is that a device is in it once, and only for an install that really
 * happened. Each of those is a way this could quietly go wrong, so each gets a case: a build that
 * has nowhere to report, an upgrade that must not be counted at all, a network that refused the
 * first try, and a retry that must reuse the same id rather than mint a second one.
 *
 * The transport is always a fake here, so no test of this file can reach the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstallReporterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deadScope = CoroutineScope(Job().apply { cancel() })

    private val fresh = InstallStamps(firstInstall = 1_000L, lastUpdate = 1_000L)
    private val upgraded = InstallStamps(firstInstall = 1_000L, lastUpdate = 2_000L)

    private val sent = mutableListOf<Pair<String, String>>()

    /** Records what would have gone out and reports whatever the case asked for. */
    private var accept = true

    private fun reporter(endpoint: String = "https://stats.test/functions/v1/app?action=record") =
        InstallReporter(context, deadScope, endpoint = endpoint, version = "1.7.0") { url, body ->
            sent += url to body
            accept
        }

    /** The page's own validators, so the two implementations cannot drift apart unnoticed. */
    private val installIdShape =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val versionShape = Regex("^[0-9]+\\.[0-9]+(?:\\.[0-9]+){0,2}$")

    private fun prefs() = context.getSharedPreferences(InstallReporter.PREFS, Context.MODE_PRIVATE)

    @Before
    fun clear() {
        sent.clear()
        accept = true
        prefs().edit().clear().commit()
    }

    @Test
    fun `a build with nowhere to report stays out of the way entirely`() {
        assertEquals(InstallOutcome.NOT_CONFIGURED, reporter(endpoint = "").announceOnce(fresh))
        assertTrue("nothing was said, so nothing was sent", sent.isEmpty())
        assertFalse("no verdict may be recorded while the endpoint is blank",
            prefs().contains(InstallReporter.KEY_VERDICT))
    }

    @Test
    fun `a fresh install is announced once, and the body is what the page accepts`() {
        assertEquals(InstallOutcome.ANNOUNCED, reporter().announceOnce(fresh))
        assertEquals(1, sent.size)
        val (url, body) = sent.single()
        assertTrue("the record action is the only way in", url.contains("action=record"))
        val id = Regex("\"install_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val version = Regex("\"version\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        assertTrue("a uuid the page will validate, got $id", installIdShape.matches(id))
        assertEquals("1.7.0", version)
        assertTrue(versionShape.matches(version))

        // Asked again on the next launch: settled, and not one more request.
        assertEquals(InstallOutcome.SETTLED, reporter().announceOnce(fresh))
        assertEquals("an install already announced must never be announced twice", 1, sent.size)
    }

    @Test
    fun `an upgrade is never counted, and is not asked about again`() {
        assertEquals(InstallOutcome.UPGRADE, reporter().announceOnce(upgraded))
        assertTrue("an update must not reach the counter at all", sent.isEmpty())
        assertEquals(InstallReporter.UPGRADE, prefs().getString(InstallReporter.KEY_VERDICT, ""))
        assertEquals(InstallOutcome.SETTLED, reporter().announceOnce(upgraded))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a refused announcement keeps its id and tries again`() {
        accept = false
        assertEquals(InstallOutcome.DEFERRED, reporter().announceOnce(fresh))
        assertEquals(1, sent.size)
        assertFalse("a refusal is not a verdict", prefs().contains(InstallReporter.KEY_VERDICT))
        val firstId = Regex("\"install_id\":\"([^\"]+)\"").find(sent.single().second)!!.groupValues[1]

        accept = true
        assertEquals(InstallOutcome.ANNOUNCED, reporter().announceOnce(fresh))
        val secondId = Regex("\"install_id\":\"([^\"]+)\"").find(sent[1].second)!!.groupValues[1]
        assertEquals("a retry must reuse the same install, not mint a second device", firstId, secondId)
        assertEquals(InstallReporter.ANNOUNCED, prefs().getString(InstallReporter.KEY_VERDICT, ""))
    }

    @Test
    fun `a package whose stamps cannot be read is left for another time`() {
        assertEquals(InstallOutcome.NO_PACKAGE, reporter().announceOnce(null))
        assertTrue(sent.isEmpty())
        assertEquals(InstallOutcome.ANNOUNCED, reporter().announceOnce(fresh))
    }

    @Test
    fun `only a package that has never been replaced counts as an install`() {
        assertTrue(InstallReporter.isFreshInstall(InstallStamps(5L, 5L)))
        assertTrue("a stamp that moved backwards is a restore, not an upgrade",
            InstallReporter.isFreshInstall(InstallStamps(6L, 5L)))
        assertFalse(InstallReporter.isFreshInstall(InstallStamps(5L, 6L)))
    }

    @Test
    fun `the body is exactly two fields and nothing about the device`() {
        val body = InstallReporter.announcement("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f", "1.7")
        assertEquals("{\"install_id\":\"0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f\",\"version\":\"1.7\"}", body)
        assertFalse("no name, no address, no identifier of the phone",
            body.contains("device") || body.contains("Build.") || body.contains("android"))
    }
}
