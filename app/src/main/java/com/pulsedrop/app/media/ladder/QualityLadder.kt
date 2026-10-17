package com.pulsedrop.app.media.ladder

/**
 * One runnable camera configuration: what the sensor is asked for, what the encoder is set to,
 * and what the peer is told it is receiving.
 *
 * Bundled as one value because the three must never disagree. Setting a preview to 1920x1080
 * while the encoder is configured for 1280x720 produces a scaled blur, and reporting fps from
 * a number other than the encoder's own clock is how a stats overlay ends up lying.
 */
data class QualityTier(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    /** Short name for the stats overlay and the `tier` field of a control frame. */
    val label: String,
    /**
     * Position in [QualityLadder.tiers], carried so a re-cast tier can still find its rung.
     *
     * [QualityLadder.fitTo] rewrites width and height to what the sensor delivers, which means the
     * value the engine holds is no longer equal to any entry in the ladder — and a ladder step looked
     * up by equality then silently returns "no such rung", so congestion has nowhere to go and the call
     * sits at a rung it cannot feed.
     */
    val rung: Int = -1
) {
    /** The JPEG lane's frame throttle: how long a frame is held for, in ms. */
    val frameMs: Int get() = 1000 / fps

    fun describe(): String = "${width}x$height@$fps"
}

/**
 * The sender's quality ladder, transcribed from the v2 spec (docs/Protocol-V2.md, "Quality
 * tiers"). Ordered best to worst; a sender starts at the top and steps down as the uplink
 * fails it, never up past the tier that just collapsed.
 *
 * The shape of the ladder is the point: 1080p30 needs 6 Mbps, which is more than a hotspot
 * reliably carries alongside a screen share, so the escape rungs drop fps *before* resolution
 * twice (30 -> 15 at the same size) and only then trade pixels. Dropping resolution first is
 * the wrong order for a face on a small screen — motion stops reading as stutter.
 */
object QualityLadder {

    /**
     * Order matters: index 0 is best. The two 1080p30 rungs differ only in bitrate, which is
     * the first thing a congested link gives up; the 1080p15 rung is next, so the picture stays
     * sharp before it gets small.
     */
    val tiers: List<QualityTier> = listOf(
        QualityTier(1920, 1080, 30, 6_000_000, "1080p30"),
        QualityTier(1920, 1080, 30, 4_000_000, "1080p30/4M"),
        QualityTier(1920, 1080, 15, 3_000_000, "1080p15"),
        QualityTier(1280, 720, 30, 3_000_000, "720p30"),
        QualityTier(1280, 720, 15, 2_000_000, "720p15"),
        QualityTier(960, 540, 15, 1_500_000, "540p15"),
        QualityTier(640, 360, 15, 800_000, "360p15")
    ).mapIndexed { i, tier -> tier.copy(rung = i) }

    /**
     * Top of the ladder, and what a healthy point-to-point call aims for. A view of [tiers]
     * rather than its own value, so the two cannot drift apart.
     */
    val BEST: QualityTier = tiers[0]

    /**
     * A conference caps at 720p30 (spec: "Conferences cap at 720p30 by default"). The host
     * re-encodes nothing — it relays each member's stream to every other member — so the cap
     * exists to keep the host's uplink from being (n-1) copies of one member's bitrate.
     */
    val CONFERENCE_CEILING = tiers.first { it.label == "720p30" }

    /** The starting tier for a call of the given shape. */
    fun startFor(conference: Boolean): QualityTier = if (conference) CONFERENCE_CEILING else BEST

    /**
     * One rung down, or null at the bottom. Returning null rather than the last tier keeps the
     * caller from thrashing: once the floor is reached the only remaining lever is the JPEG lane,
     * which is a different decision, not another step.
     */
    fun stepDown(current: QualityTier): QualityTier? {
        val i = indexOf(current)
        if (i < 0) return null
        return tiers.getOrNull(i + 1)
    }

    /** One rung up, for when a congested link has clearly recovered. */
    fun stepUp(current: QualityTier): QualityTier? {
        val i = indexOf(current)
        if (i <= 0) return null
        return tiers[i - 1]
    }

    private fun indexOf(tier: QualityTier): Int =
        if (tier.rung >= 0) tier.rung else tiers.indexOf(tier)

    /**
     * The best tier that fits a conference ceiling, used when a call becomes a group mid-flight
     * and the sender has to give pixels up without negotiating a reconnect.
     */
    fun clampTo(tier: QualityTier, ceiling: QualityTier): QualityTier =
        if (pixels(tier) <= pixels(ceiling) && tier.fps <= ceiling.fps) tier else ceiling

    fun pixels(tier: QualityTier): Long = tier.width.toLong() * tier.height

    /**
     * Re-casts a tier onto the geometry the sensor actually offers, keeping the rung's frame rate and
     * bitrate.
     *
     * Needed because a tier's resolution is a request, not a guarantee: the legacy Camera API exposes
     * *preview* sizes far smaller than the still-capture sizes, and many phones stop their preview at
     * 1280x720 or 1440x1080 no matter what they can photograph. Configuring the encoder for 1920x1080
     * and feeding it a 1280x720 frame does not fail — it stretches, which is worse, because the call
     * looks like it is running at the top of the ladder while every face in it is wrong.
     */
    fun fitTo(tier: QualityTier, width: Int, height: Int): QualityTier =
        if (width == tier.width && height == tier.height) tier
        else tier.copy(
            width = width,
            height = height,
            // The rung's name says pixels, so it goes with the pixels. A tier re-cast onto a 1280x720
            // sensor still labeled "1080p30" is how the overlay ends up advertising a resolution no
            // frame ever had.
            label = "${width}x$height@${tier.fps}"
        )

    /**
     * The largest preview size that fits under a tier, preferring one that matches its aspect.
     *
     * Returns null when the sensor offers nothing at all under the tier, which the caller treats as
     * "this device cannot do the lane" rather than picking the smallest size and calling it a success.
     */
    fun bestPreviewUnder(
        tier: QualityTier,
        sizes: List<Pair<Int, Int>>
    ): Pair<Int, Int>? {
        val budget = pixels(tier)
        val candidates = sizes.filter { (w, h) -> w.toLong() * h <= budget }
        if (candidates.isEmpty()) return null
        val targetAspect = tier.width.toFloat() / tier.height
        return candidates
            .sortedByDescending { it.first.toLong() * it.second }
            .firstOrNull { kotlin.math.abs(it.first.toFloat() / it.second - targetAspect) < 0.12f }
            ?: candidates.maxByOrNull { it.first.toLong() * it.second }
    }
}
