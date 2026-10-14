package com.pulsedrop.app.call

/**
 * Wire format for the frames a group-call bridge forwards between legs. A member's single leg
 * needs no tagging (the bridge already knows who sent it), but once a frame is relayed on to a
 * third party it has to carry its origin, or every tile shows the same speaker.
 *
 * audio: [1b srcLen][src utf8][pcm]
 * video: [1b srcLen][src utf8][1b kind][jpeg]   kind 1 = camera, 2 = screen
 */
internal object ConfFrame {
    const val KIND_CAMERA = 1
    const val KIND_SCREEN = 2

    private fun tag(src: ByteArray, payload: ByteArray, extra: Int): ByteArray {
        val body = ByteArray(1 + src.size + extra + payload.size)
        body[0] = src.size.toByte()
        System.arraycopy(src, 0, body, 1, src.size)
        System.arraycopy(payload, 0, body, 1 + src.size + extra, payload.size)
        return body
    }

    fun audio(srcPid: String, pcm: ByteArray): ByteArray? {
        val src = srcPid.toByteArray(Charsets.UTF_8)
        if (src.isEmpty() || src.size > 255) return null
        return tag(src, pcm, 0)
    }

    fun video(srcPid: String, jpeg: ByteArray, kind: Int): ByteArray? {
        val src = srcPid.toByteArray(Charsets.UTF_8)
        if (src.isEmpty() || src.size > 255) return null
        val body = tag(src, jpeg, 1)
        body[1 + src.size] = kind.toByte()
        return body
    }

    /** The origin id and the offset just past it, or null if the frame is truncated. */
    private fun readSrc(payload: ByteArray): Pair<String, Int>? {
        if (payload.isEmpty()) return null
        val len = payload[0].toInt() and 0xFF
        if (len <= 0 || payload.size < 1 + len) return null
        return Pair(String(payload, 1, len, Charsets.UTF_8), 1 + len)
    }

    fun readAudio(payload: ByteArray): Pair<String, ByteArray>? {
        val (src, off) = readSrc(payload) ?: return null
        return Pair(src, payload.copyOfRange(off, payload.size))
    }

    /** Origin id and jpeg bytes; the kind byte is consumed but not returned. */
    fun readVideo(payload: ByteArray): Pair<String, ByteArray>? {
        val (src, off) = readSrc(payload) ?: return null
        if (payload.size < off + 1) return null
        return Pair(src, payload.copyOfRange(off + 1, payload.size))
    }

    fun kind(payload: ByteArray): Int {
        if (payload.isEmpty()) return KIND_CAMERA
        val len = payload[0].toInt() and 0xFF
        val at = 1 + len
        return if (at < payload.size) payload[at].toInt() and 0xFF else KIND_CAMERA
    }
}
