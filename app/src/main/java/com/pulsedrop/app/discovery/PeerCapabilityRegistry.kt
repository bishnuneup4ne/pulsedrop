package com.pulsedrop.app.discovery

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

        /** Whether a peer is a PulseDrop phone (pair-capable) rather than a plain web page. */
        fun isLocalDropVersion(version: String): Boolean = version.startsWith("2")

        /** Tolerant read of the additive `"caps"` array; pre-2.1 builds have none. */
        fun capsFromJson(json: JSONArray?): Collection<String>? {
            if (json == null) return null
            return (0 until json.length()).mapNotNull { json.optString(it).takeIf { c -> c.isNotBlank() } }
        }
    }
}
