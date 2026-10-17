package com.pulsedrop.app.security

import android.content.Context
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec

/**
 * The store of who we are paired with: the pairings and groups themselves, their derived
 * secrets, and where each peer currently lives on the LAN.
 *
 * Persistence is a JSON array in SharedPreferences for now; Phase 2 moves it to Room behind
 * this same interface, so nothing above this class will notice.
 */
class PairRepository(
    private val context: Context,
    private val events: MutableStateFlow<String?>
) {

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    private val _groups = MutableStateFlow<List<ChatGroup>>(emptyList())
    val groups: StateFlow<List<ChatGroup>> = _groups.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secretCache = mutableMapOf<String, SecretKeySpec>()

    /** A peer that refused a direct connection recently is not worth re-dialling per message. */
    private val unreachableUntil = HashMap<String, Long>()

    init {
        load()
    }

    // ---- identity ------------------------------------------------------------

    fun myPubB64(): String = Crypto.publicKeyB64(context)
    fun myDeviceId(): String = Crypto.deviceId(myPubB64())
    fun deviceIdOf(pairing: PairedDevice): String = Crypto.deviceId(pairing.pubB64)

    fun secretKey(p: PairedDevice): SecretKeySpec = synchronized(secretCache) {
        secretCache.getOrPut(p.pid) { Crypto.sharedKeyFor(context, p.pubB64, p.kdfVersion) }
    }

    /**
     * Whether a paired device has announced a release at least [major].[minor]. A device that
     * paired before versions were exchanged reports nothing, and nothing is treated honestly:
     * as older than anything we could ask of it.
     */
    fun peerAtLeast(pairing: PairedDevice, major: Int, minor: Int): Boolean =
        versionAtLeast(pairing.appVersion, major, minor)

    companion object {
        private const val PREFS = "localdrop_pairs"
        private const val UNREACHABLE_MS = 90_000L

        /** Same rule for a bare version string: unknown or missing counts as oldest. */
        fun versionAtLeast(version: String, major: Int, minor: Int): Boolean {
            val parts = version.trim().split('.')
            val a = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val b = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return a > major || (a == major && b >= minor)
        }
    }

    // ---- lookups --------------------------------------------------------------

    fun findPairing(pid: String): PairedDevice? = _paired.value.firstOrNull { it.pid == pid }

    /**
     * Which pairing connects us to the device with this id. Used to pick the key a relay
     * request must be signed with, and to seal traffic for a peer we are not hosting.
     */
    fun findPairingByDeviceId(deviceId: String): PairedDevice? =
        _paired.value.firstOrNull { deviceIdOf(it) == deviceId }

    fun pairForPeer(peer: PeerDevice): PairedDevice? =
        _paired.value.firstOrNull { it.originIp == peer.ip || it.pid == peer.id }

    // ---- mutations --------------------------------------------------------------

    /**
     * Stores a pairing and returns the previously-trusted device it appears to displace, if any.
     * Re-pairing under a new identity is exactly what a downgrade looks like from here, so
     * callers can say so out loud instead of silently replacing the trusted key.
     */
    fun addPairing(device: PairedDevice): PairedDevice? {
        val clash = _paired.value.firstOrNull {
            it.pid != device.pid && (
                it.originIp == device.originIp ||
                    it.name.equals(device.name, ignoreCase = true)
                )
        }
        _paired.update { list ->
            if (list.any { it.pid == device.pid }) {
                list.map { if (it.pid == device.pid) device else it }
            } else {
                list + device
            }
        }
        save()
        return clash
    }

    fun removePairing(pid: String): PairedDevice? {
        val p = findPairing(pid) ?: return null
        _paired.update { list -> list.filterNot { it.pid == pid } }
        // A group cannot hold an unpaired device — drop it and retire empty groups.
        _groups.update { list ->
            list.map { g -> g.copy(memberPids = g.memberPids.filterNot { it == pid }) }
                .filter { it.memberPids.size >= 2 }
        }
        save()
        saveGroups()
        synchronized(secretCache) { secretCache.remove(pid) }
        return p
    }

    fun setVerified(pid: String, verified: Boolean) {
        val current = findPairing(pid) ?: return
        if (current.verified == verified) return
        _paired.update { list -> list.map { if (it.pid == pid) it.copy(verified = verified) else it } }
        save()
    }

    /**
     * A peer's LAN address changes with DHCP. Any traffic that already proved it holds the
     * pairwise key tells us where that device is now, so chat and sends keep working.
     */
    fun notePeerAddress(pid: String, ip: String) {
        if (ip.isBlank() || ip.startsWith("127.") || ip == "::1" || ip == "0.0.0.0") return
        val current = findPairing(pid) ?: return
        if (current.originIp == ip) return
        _paired.update { list -> list.map { if (it.pid == pid) it.copy(originIp = ip) else it } }
        save()
    }

    // ---- reachability bookkeeping -----------------------------------------------

    fun isDirectlyReachable(pid: String): Boolean =
        (unreachableUntil[pid] ?: 0L) <= System.currentTimeMillis()

    fun markUnreachable(pid: String) {
        synchronized(unreachableUntil) {
            val now = System.currentTimeMillis()
            unreachableUntil.entries.filter { it.value <= now }.forEach { unreachableUntil.remove(it.key) }
            while (unreachableUntil.size > 32) unreachableUntil.remove(unreachableUntil.keys.first())
            unreachableUntil[pid] = now + UNREACHABLE_MS
        }
    }

    fun clearUnreachable(pid: String) {
        synchronized(unreachableUntil) { unreachableUntil.remove(pid) }
    }

    // ---- groups -------------------------------------------------------------------

    fun addGroup(group: ChatGroup) {
        _groups.update { it + group }
        saveGroups()
        events.value = "Group \"${group.name}\" created with ${group.memberPids.size} devices"
    }

    fun removeGroup(id: String): ChatGroup? {
        val g = _groups.value.firstOrNull { it.id == id } ?: return null
        _groups.update { list -> list.filterNot { it.id == id } }
        saveGroups()
        events.value = "Group \"${g.name}\" removed"
        return g
    }

    fun findGroup(id: String): ChatGroup? = _groups.value.firstOrNull { it.id == id }

    // ---- persistence (until Room takes over) -----------------------------------------

    private fun load() {
        val raw = prefs.getString("pairs", "[]") ?: "[]"
        val list = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PairedDevice(
                    pid = o.getString("pid"),
                    name = o.getString("name"),
                    pubB64 = o.getString("pub"),
                    originIp = o.optString("ip"),
                    originPort = o.optInt("port", 8080),
                    createdAt = o.optLong("created", System.currentTimeMillis()),
                    verified = o.optBoolean("verified", false),
                    appVersion = o.optString("appVersion", ""),
                    kdfVersion = o.optInt("kdf", Kdf.V1)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        _paired.value = list
        _groups.value = try {
            val arr = JSONArray(prefs.getString("groups", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val members = o.getJSONArray("members")
                ChatGroup(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    memberPids = (0 until members.length()).map { members.getString(it) },
                    createdAt = o.optLong("created", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveGroups() {
        val arr = JSONArray()
        _groups.value.forEach { g ->
            arr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("members", JSONArray(g.memberPids))
                    .put("created", g.createdAt)
            )
        }
        prefs.edit().putString("groups", arr.toString()).apply()
    }

    private fun save() {
        val arr = JSONArray()
        _paired.value.forEach { p ->
            arr.put(
                JSONObject()
                    .put("pid", p.pid)
                    .put("name", p.name)
                    .put("pub", p.pubB64)
                    .put("ip", p.originIp)
                    .put("port", p.originPort)
                    .put("created", p.createdAt)
                    .put("verified", p.verified)
                    .put("appVersion", p.appVersion)
                    .put("kdf", p.kdfVersion)
            )
        }
        prefs.edit().putString("pairs", arr.toString()).apply()
    }
}
