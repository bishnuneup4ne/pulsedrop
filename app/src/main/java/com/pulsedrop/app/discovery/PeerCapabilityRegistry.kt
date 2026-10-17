package com.pulsedrop.app.discovery

import com.pulsedrop.app.media.h264.HardwareCodec

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * What each peer can do, merged from the three places a capability is announced:
 * the `/api/info` probe, the discovery UDP beacon, and the pairing handshake.
 *
 * Absence is never a guess in the caller's favour: a peer with no announced version is
 * older than anything we could ask of it, and gets no caps. Announcements only ever
 * *add* caps — one beacon lost mid-sweep must not downgrade a peer mid-call.
 */
class PeerCapabilityRegistry {

    private data class Seen(val version: String, val caps: Set<String>)

    private val peers = ConcurrentHashMap<String, Seen>()

    fun keyForIp(ip: String) = "ip/$ip"
    fun keyForPid(pid: String) = "pid/$pid"

    /** Merges one announcement. Blank version and null caps leave the old record intact. */
    fun record(key: String, version: String?, caps: Collection<String>?) {
        peers.merge(key, Seen(version.orEmpty(), caps?.toSet() ?: emptySet())) { old, new ->
            Seen(
                version = new.version.ifBlank { old.version },
                caps = old.caps + new.caps
            )
        }
    }

    fun forget(key: String) {
        peers.remove(key)
    }

    fun capsFor(key: String): Set<String> = peers[key]?.caps ?: emptySet()

    fun versionOf(key: String): String = peers[key]?.version ?: ""

    fun has(key: String, cap: String): Boolean = cap in capsFor(key)

    /** Same contract as the pairing handshake's version gate: unknown version is oldest. */
    fun atLeast(key: String, major: Int, minor: Int): Boolean {
        val parts = versionOf(key).trim().split('.')
        val a = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val b = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return a > major || (a == major && b >= minor)
    }

    companion object {
        const val CAP_H264 = "h264"
        const val CAP_BINARY_UPLINK = "binary-uplink"
        const val CAP_KDF_V2 = "kdf-v2"
        const val CAP_CHAT_EXT1 = "chat-ext1"
        const val CAP_CONF2 = "conf2"

        /**
         * This device can *watch* a shared screen that arrives encoded. A promise about the decoder and
         * the view, not about the encoder: which is why it is asked for before a peer starts sending
         * frame type 11, and why a device that merely has a fast encoder does not claim it.
         */
        const val CAP_SCREEN_H264 = "screen-h264"

        /**
         * What this device may claim about itself, for `/api/info` and the discovery beacon.
         *
         * A cap is a promise about hardware, not about which build is installed: a phone that lists
         * `h264` but has no hardware AVC encoder makes its peer drop the JPEG lane too, and both ends
         * then encode in software at once. So the encoder question is answered here, at the only place
         * the announcement is assembled, rather than by a version check.
         *
         * `screen-h264` is answered off the *decoder*, because a shared screen is only worth encoding if
         * the far end has a surface to present it on; a phone that can encode a share it cannot display
         * would simply be unable to watch anybody else's.
         *
         * `conf2` is deliberately absent. Multi-source H.264 needs one decode surface per conference
         * tile and the grid draws Bitmaps, so a group call stays on the JPEG lane the spec prescribes
         * for a mixed-version one.
         *
         * `chat-ext1` is the one cap that is not a hardware promise, and it is here because the thing
         * it promises - reactions, edits and read receipts on a paired thread - is a wire format this
         * build either speaks or does not. It is unconditional for that reason, and it is the only
         * cap a peer without an encoder still answers to.
         */
        fun localCaps(): List<String> = buildList {
            if (HardwareCodec.hasHardwareAvcEncoder()) add(CAP_H264)
            if (HardwareCodec.hasHardwareAvcDecoder()) add(CAP_SCREEN_H264)
            add(CAP_CHAT_EXT1)
        }

        /** Whether a peer is a PulseDrop phone (pair-capable) rather than a plain web page. */
        fun isLocalDropVersion(version: String): Boolean = version.startsWith("2")

        /** Tolerant read of the additive `"caps"` array; pre-2.1 builds have none. */
        fun capsFromJson(json: JSONArray?): Collection<String>? {
            if (json == null) return null
            return (0 until json.length()).mapNotNull { json.optString(it).takeIf { c -> c.isNotBlank() } }
        }
    }
}
