package com.pulsedrop.app.media.protocol

/**
 * Caps for media that arrives over the embedded server's browser leg, plus the decode cost of a
 * JPEG from any leg.
 *
 * [MAX_PAYLOAD] is the wire format's frame cap, sized for one frame a paired phone generated. The
 * browser leg is a different boundary: a tab holding the room credential is exactly the client the
 * POST is reachable by, and it can declare a frame far larger than the client ever makes. These
 * numbers are several times the real thing — a camera frame is at most 1100 px wide at quality 0.72
 * and a shared screen 1600 px at 0.75 — while a 4 MiB declaration spread across many small batches
 * stops accumulating.
 */
object MediaLimits {

    /** One JPEG, H.264 access unit or relay wrapper from a browser leg. */
    const val MAX_FRAME_BYTES = 1024 * 1024

    /** 100 ms of 16 kHz mono PCM16 is 3 200 bytes; a whole second is still under this. */
    const val MAX_AUDIO_FRAME_BYTES = 64 * 1024

    /** Presence and control frames are small JSON documents. */
    const val MAX_CONTROL_FRAME_BYTES = 64 * 1024

    /** The shipped client sends at most three frames per flush, 24 after shedding a backlog. */
    const val MAX_FRAMES_PER_BATCH = 64

    /**
     * A JPEG's dimensions, not its bytes, decide what decoding costs: a 100 KB file can declare
     * 20 000 x 20 000 and ask for gigabytes. Screen capture is capped at a 1920 px edge (3.7 MPix
     * at square), 1080p camera is 2.1 MPix, so this spares real media while refusing a bomb.
     */
    const val MAX_JPEG_PIXELS = 4_000_000L

    fun frameAllowed(type: Int, size: Int): Boolean = when (type) {
        TYPE_AUDIO, TYPE_CONF_AUDIO -> size <= MAX_AUDIO_FRAME_BYTES
        TYPE_SCREEN_STATE, TYPE_CONTROL, TYPE_CONF_STATE -> size <= MAX_CONTROL_FRAME_BYTES
        else -> size <= MAX_FRAME_BYTES
    }

    fun jpegAllowed(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_JPEG_PIXELS
}
