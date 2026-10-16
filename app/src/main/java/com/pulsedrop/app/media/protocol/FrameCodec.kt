package com.pulsedrop.app.media.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream

const val HEADER_SIZE = 5
const val MAX_PAYLOAD = 4 * 1024 * 1024

/** 16 kHz mono PCM16, 100 ms chunks. */
const val TYPE_AUDIO = 1
/** JPEG camera frame. */
const val TYPE_CAMERA = 2
/** JPEG screen-share frame. */
const val TYPE_SCREEN = 3
/** In-band JSON "I started/stopped sharing" notice; peers ignore unknown types. */
const val TYPE_SCREEN_STATE = 4
/** Conference relay: `[1B srcLen][src][pcm]`. */
const val TYPE_CONF_AUDIO = 5
/** Conference relay: `[1B srcLen][src][1B kind][jpeg]`, kind 2 = screen, else camera. */
const val TYPE_CONF_VIDEO = 6
/** Conference roster as JSON, pushed by the host whenever it changes. */
const val TYPE_CONF_STATE = 7
/** v2: in-band JSON control (keyframe-request, caps, stats-ping). Old peers ignore it. */
const val TYPE_CONTROL = 8
/** v2: H.264 access unit; sent only when both ends advertise `h264`. */
const val TYPE_H264 = 9
/** v2: conference-relayed H.264: `[1B srcLen][src][access unit]`. */
const val TYPE_CONF_H264 = 10

/**
 * Sole owner of the media wire format: `[1B type][4B BE payload length][payload]`.
 *
 * Types 1..7 are frozen for all time — builds going back to v1.7 must keep reading what
 * newer builds send. Peers ignore unknown types, so new semantics are added as new type
 * numbers, never by redefining old ones. See docs/Protocol-V2.md.
 */
object FrameCodec {

    data class Frame(val type: Int, val payload: ByteArray)

    fun encode(type: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        require(n <= MAX_PAYLOAD) { "payload of $n bytes exceeds the $MAX_PAYLOAD frame cap" }
        val frame = ByteArray(HEADER_SIZE + n)
        frame[0] = type.toByte()
        frame[1] = (n ushr 24).toByte()
        frame[2] = (n ushr 16).toByte()
        frame[3] = (n ushr 8).toByte()
        frame[4] = n.toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, n)
        return frame
    }

    /** Returns false when the write failed, so the caller can drop just that connection. */
    fun writeTo(out: OutputStream, type: Int, payload: ByteArray): Boolean {
        return try {
            synchronized(out) {
                val n = payload.size
                out.write(type)
                out.write(n ushr 24)
                out.write(n ushr 16)
                out.write(n ushr 8)
                out.write(n)
                out.write(payload)
                out.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Read one frame from a stream. Returns null at end-of-stream or on a header that can
     * only mean corruption, which callers treat as "this connection is done".
     */
    fun readFrame(input: DataInputStream): Frame? {
        val type = input.read()
        if (type == -1) return null
        val len = input.readInt()
        if (len <= 0 || len > MAX_PAYLOAD) return null
        val payload = ByteArray(len)
        input.readFully(payload)
        return Frame(type, payload)
    }

    /**
     * Reassembles frames out of arbitrary byte batches, holding a trailing partial frame
     * until the next batch completes it. Same contract CallEngine's web uplink always had.
     */
    class BatchParser {
        private val pending = ByteArrayOutputStream(64 * 1024)

        @Synchronized
        fun feed(bytes: ByteArray): List<Frame> {
            if (bytes.isNotEmpty()) pending.write(bytes)
            val buf = pending.toByteArray()
            val frames = ArrayList<Frame>(4)
            var i = 0
            while (i + HEADER_SIZE <= buf.size) {
                val type = buf[i].toInt() and 0xFF
                val len = ((buf[i + 1].toInt() and 0xFF) shl 24) or
                    ((buf[i + 2].toInt() and 0xFF) shl 16) or
                    ((buf[i + 3].toInt() and 0xFF) shl 8) or
                    (buf[i + 4].toInt() and 0xFF)
                if (len <= 0 || len > MAX_PAYLOAD) { i = buf.size; break }
                if (i + HEADER_SIZE + len > buf.size) break
                frames.add(Frame(type, buf.copyOfRange(i + HEADER_SIZE, i + HEADER_SIZE + len)))
                i += HEADER_SIZE + len
            }
            pending.reset()
            if (i < buf.size) pending.write(buf, i, buf.size - i)
            return frames
        }

        @Synchronized
        fun reset() {
            pending.reset()
        }
    }
}
