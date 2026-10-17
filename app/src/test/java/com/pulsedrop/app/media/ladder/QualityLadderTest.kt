package com.pulsedrop.app.media.ladder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder is transcribed from docs/Protocol-V2.md, so it is pinned against the spec's own
 * numbers here. If someone edits the doc and not the code, this fails — which is the only thing
 * standing between "the spec says 1080p30" and a build that quietly never asks the encoder for it.
 */
class QualityLadderTest {

    @Test
    fun ladderMatchesTheSpecifiedTiers() {
        val actual = QualityLadder.tiers.map {
            Triple(Triple(it.width, it.height, it.fps), it.bitrateBps, it.label)
        }
        assertEquals(
            listOf(
                Triple(Triple(1920, 1080, 30), 6_000_000, "1080p30"),
                Triple(Triple(1920, 1080, 30), 4_000_000, "1080p30/4M"),
                Triple(Triple(1920, 1080, 15), 3_000_000, "1080p15"),
                Triple(Triple(1280, 720, 30), 3_000_000, "720p30"),
                Triple(Triple(1280, 720, 15), 2_000_000, "720p15"),
                Triple(Triple(960, 540, 15), 1_500_000, "540p15"),
                Triple(Triple(640, 360, 15), 800_000, "360p15")
            ),
            actual
        )
    }

    @Test
    fun topOfLadderIsTheTenEightyPThirtyTarget() {
        val best = QualityLadder.tiers.first()
        assertEquals(1920, best.width)
        assertEquals(1080, best.height)
        assertEquals(30, best.fps)
        assertEquals(6_000_000, best.bitrateBps)
        assertSame(best, QualityLadder.BEST)
    }

    @Test
    fun frameIntervalFollowsFps() {
        assertEquals(33, QualityLadder.BEST.frameMs)
        assertEquals(66, QualityLadder.tiers[2].frameMs)
    }

    @Test
    fun conferenceCeilingIs720p30() {
        val cap = QualityLadder.CONFERENCE_CEILING
        assertEquals(1280, cap.width)
        assertEquals(720, cap.height)
        assertEquals(30, cap.fps)
        assertEquals(cap, QualityLadder.startFor(conference = true))
        assertEquals(QualityLadder.BEST, QualityLadder.startFor(conference = false))
    }

    @Test
    fun stepsWalkTheLadderOneRungAtATime() {
        val tiers = QualityLadder.tiers
        assertEquals(tiers[1], QualityLadder.stepDown(tiers[0]))
        assertEquals(tiers[0], QualityLadder.stepUp(tiers[1]))
        assertNull(QualityLadder.stepDown(tiers.last()))
        assertNull(QualityLadder.stepUp(tiers.first()))
    }

    @Test
    fun everyLowerRungNeedsNoMorePixelsAndNoMoreBandwidth() {
        QualityLadder.tiers.zipWithNext { better, worse ->
            assertSame("stepDown must advance exactly one rung", worse, QualityLadder.stepDown(better))
            assertTrue(
                "${worse.label} is below ${better.label} but asks for more pixels",
                QualityLadder.pixels(better) >= QualityLadder.pixels(worse)
            )
            assertTrue(
                "${worse.label} is below ${better.label} but asks for more bandwidth",
                worse.bitrateBps <= better.bitrateBps
            )
        }
    }

    /**
     * The order of the ladder is its whole design: fps gives way before resolution does, because
     * a face at 15 fps still reads as a face and a face at 360p does not. Pinned so a reordering
     * that looks harmless — trading pixels first — is caught as the spec change it is.
     */
    @Test
    fun frameRateDropsBeforeResolutionDoes() {
        val order = QualityLadder.tiers.map { Triple(it.height, it.fps, it.bitrateBps) }
        assertEquals(1080, order[0].first)
        assertEquals(1080, order[1].first)
        assertEquals(15, order[2].second)
        assertEquals("1080p15: still full height, fps cut first", 1080, order[2].first)
        assertEquals("the first resolution cut comes after an fps cut", 720, order[3].first)
        assertEquals(30, order[3].second)
    }

    @Test
    fun clampLeavesALightEnoughTierAloneAndPullsBackAHeavyOne() {
        val cap = QualityLadder.CONFERENCE_CEILING
        val fiveForty = QualityLadder.tiers.last { it.height == 540 }
        assertSame(fiveForty, QualityLadder.clampTo(fiveForty, cap))
        assertSame(cap, QualityLadder.clampTo(QualityLadder.BEST, cap))
    }

    /**
     * A mis-sized tier would blow the 4 MiB frame cap mid-call rather than at startup, so the
     * arithmetic that keeps it out is checked here: a whole second of a tier's own bitrate is the
     * slack any single frame could conceivably need.
     */
    /**
     * A phone that cannot preview at 1920x1080 must not be encoded as if it could. The rung's frame
     * rate and bitrate carry over; only the geometry follows the sensor.
     */
    @Test
    fun fitKeepsTheRungAndChangesOnlyTheGeometry() {
        val fitted = QualityLadder.fitTo(QualityLadder.BEST, 1280, 720)
        assertEquals(1280, fitted.width)
        assertEquals(720, fitted.height)
        assertEquals(30, fitted.fps)
        assertEquals(6_000_000, fitted.bitrateBps)
        assertSame(QualityLadder.BEST, QualityLadder.fitTo(QualityLadder.BEST, 1920, 1080))
    }

    @Test
    fun previewSearchTakesTheLargestSizeUnderTheRung() {
        val sizes = listOf(640 to 480, 1280 to 720, 1920 to 1080, 320 to 240)
        assertEquals(
            1280 to 720,
            QualityLadder.bestPreviewUnder(QualityLadder.CONFERENCE_CEILING, sizes)
        )
        assertEquals(
            1920 to 1080,
            QualityLadder.bestPreviewUnder(QualityLadder.BEST, sizes)
        )
    }

    /**
     * A sensor that can only do a squat 4:3 preview still gets the biggest one it has rather than a
     * 16:9 letterbox, and an empty list is the only thing that means "no lane here".
     */
    @Test
    fun previewSearchPrefersTheTiersAspectButFallsBackToSize() {
        val fourThree = listOf(1024 to 768, 640 to 480)
        assertEquals(1024 to 768, QualityLadder.bestPreviewUnder(QualityLadder.BEST, fourThree))
        assertEquals(320 to 240, QualityLadder.bestPreviewUnder(QualityLadder.BEST, listOf(320 to 240)))
        assertNull(QualityLadder.bestPreviewUnder(QualityLadder.BEST, emptyList()))
    }

    /**
     * A tier re-cast onto the sensor's real size is no longer equal to any rung in the ladder, and the
     * engine holds *that* value for the whole call. Looking the rung up by equality therefore returns
     * "not on the ladder" at the exact moment a congested link needs a lower one — the call would sit
     * at a rung it cannot feed with no escape. Pinned here because the symptom is a stutter, not an error.
     */
    @Test
    fun aFittedTierStillKnowsItsRung() {
        val fitted = QualityLadder.fitTo(QualityLadder.BEST, 1280, 720)
        assertEquals(1280, fitted.width)
        assertEquals("the rung's frame rate and bitrate survive the re-cast", 30, fitted.fps)
        assertEquals(6_000_000, fitted.bitrateBps)
        val down = QualityLadder.stepDown(fitted)
        assertEquals(4_000_000, down?.bitrateBps)
        assertEquals(30, down?.fps)
        // And all the way to the floor from here, one rung at a time.
        var tier = down
        var steps = 0
        while (tier != null && steps < 10) {
            tier = QualityLadder.stepDown(tier)
            steps++
        }
        assertNull(tier)
        // Six rungs left below the one we stepped onto, and then the floor says "no more".
        assertEquals(QualityLadder.tiers.size - 1, steps)
    }

    /** The name has to follow the pixels, or a 720p sensor is advertised as 1080p30 all call. */
    @Test
    fun fittingATierRelabelsIt() {
        assertEquals("1280x720@30", QualityLadder.fitTo(QualityLadder.BEST, 1280, 720).label)
        assertSame(QualityLadder.BEST, QualityLadder.fitTo(QualityLadder.BEST, 1920, 1080))
    }

    /** A tier that never came from the ladder has no rung, and must not be read as the top of it. */
    @Test
    fun anOffLadderTierHasNowhereToStep() {
        val stranger = QualityTier(640, 480, 24, 900_000, "odd")
        assertNull(QualityLadder.stepDown(stranger))
        assertNull(QualityLadder.stepUp(stranger))
    }

    @Test
    fun everyTierFitsTheFrameCapWithASecondsWorthOfSlack() {
        val cap = 4L * 1024 * 1024
        QualityLadder.tiers.forEach { tier ->
            val worstFrameBytes = tier.bitrateBps.toLong() / 8L
            assertTrue("${tier.label} cannot fit the frame cap", worstFrameBytes < cap)
        }
    }
}
