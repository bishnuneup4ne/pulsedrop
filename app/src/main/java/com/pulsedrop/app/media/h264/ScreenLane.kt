package com.pulsedrop.app.media.h264

/**
 * Whether a shared screen may ride the H.264 lane, as one pure decision.
 *
 * Kept out of the engine because four independent things have to be true, and a fifth has to be
 * refused. Reading them as one expression is the only way to check that each one is answered by
 * evidence rather than by hope — every condition below is a peer or a piece of hardware that says no
 * silently, and a screen share that goes black because one of them was assumed is the failure this
 * lane was built to avoid.
 */
object ScreenLane {

    enum class Lane {
        /** Per-frame JPEG out of the `ImageReader`. What every build has always done, and still does alone. */
        JPEG,

        /** The display rendered straight into a hardware encoder, one access unit per frame. */
        ENCODED
    }

    /**
     * @param peerPromisesDecode the peer announced `screen-h264`, meaning it both reads frame type 11 and
     * owns a hardware AVC decoder plus a surface to present the picture onto. Absence is not a
     * rounding error: a peer that does not know type 11 discards every frame of the share and still
     * believes, correctly, that the other side is sharing.
     * @param hardwareEncoder this device's AVC encoder is a hardware block. A software block asked for
     * 1080p30 of live display falls behind the screen it is capturing, which is worse than the JPEG
     * share it replaced.
     * @param conference the call is a hosted group call. The bridge relays frames without transcoding
     * and the grid draws one Bitmap per tile, so an encoded share would need a decode surface per seat
     * — which is the same reason `conf2` is unclaimed, and why a group call keeps the JPEG share.
     * @param width @param height the capture geometry, which the caller has already scaled to the
     * display. Even numbers only: 4:2:0 has no place to put a luma dimension of one odd pixel.
     */
    fun choose(
        peerPromisesDecode: Boolean,
        hardwareEncoder: Boolean,
        conference: Boolean,
        width: Int,
        height: Int
    ): Lane {
        val usable = peerPromisesDecode && hardwareEncoder && !conference &&
            width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0
        return if (usable) Lane.ENCODED else Lane.JPEG
    }
}
