package com.pulsedrop.app.relay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Store-and-forward mail for devices that pair with this host but keep no listener of their own.
 *
 * A phone only needs to start its server to *be* the host; every other paired device can send and
 * receive through here instead, which is what makes one server enough for a whole room. Each
 * envelope is already sealed with the sender and receiver's own pairwise key, so the host carries
 * bytes it cannot read and is never a party to the conversation — it is a postbox, not a server in
 * the trust sense. That is also why nothing here indexes message text or file contents.
 *
 * Mail is held until the addressee fetches and explicitly acknowledges it, so a device that
 * crashes mid-poll does not silently lose the messages it had just been handed.
 */
class RelayMailbox(
    context: Context,
    internal var leaseMs: Long = IN_FLIGHT_MS,
    /** A host carries a conversation for a day, not forever: a phone that never returns is forgotten. */
    internal var maxCarriageMs: Long = TTL_MS,
    /** Budget for the whole host, so carrying for a large room cannot exhaust the phone's storage. */
    internal var maxCarriageBytes: Long = MAX_TOTAL_BYTES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private class Envelope(
        val eid: String,
        val to: String,
        val json: JSONObject,
        val postedAt: Long,
        val bytes: Int
    )

    private val file = File(context.filesDir, "relay_mailbox.json")
    private val boxes = LinkedHashMap<String, ArrayDeque<Envelope>>()
    /** Handed out but not yet acknowledged; kept so an interrupted poll can fetch again. */
    private val inFlight = HashMap<String, Long>()
    private var carriedBytes = 0L

    @Volatile
    private var dirty = false

    fun stats(): JSONObject = synchronized(boxes) {
        val count = boxes.values.sumOf { it.size }
        JSONObject().put("devices", boxes.keys.count { boxes[it]?.isNotEmpty() == true })
            .put("messages", count)
            .put("bytes", carriedBytes)
    }

    /**
     * Queues one sealed envelope. Returns the id the addressee will acknowledge, or null when the
     * address is unusable. A device that never comes back for its mail is bounded per mailbox and
     * by the host's overall budget, oldest mail going first either way.
     */
    fun post(to: String, body: JSONObject): String? {
        if (to.isBlank() || to.length > 32) return null
        val eid = UUID.randomUUID().toString().take(12)
        val now = nowMs()
        val json = JSONObject(body.toString())
            .put("eid", eid)
            .put("to", to)
            .put("postedAt", now)
        val envelope = Envelope(eid = eid, to = to, json = json, postedAt = now, bytes = json.toString().length)
        synchronized(boxes) {
            pruneLocked()
            val queue = boxes.getOrPut(to) { ArrayDeque() }
            if (queue.size >= MAX_PER_DEVICE) dropFirstLocked(queue)
            queue.addLast(envelope)
            carriedBytes += envelope.bytes
            while (carriedBytes > maxCarriageBytes) {
                val oldest = boxes.values.filter { it.isNotEmpty() }.minByOrNull { it.first().postedAt }
                    ?: break
                dropFirstLocked(oldest)
            }
            dirty = true
        }
        return eid
    }

    /** Everything waiting for [me], oldest first. Nothing is removed until it is acknowledged. */
    fun pending(me: String, limit: Int = PULL_LIMIT): List<JSONObject> {
        if (me.isBlank()) return emptyList()
        val now = nowMs()
        return synchronized(boxes) {
            pruneLocked()
            val queue = boxes[me] ?: return@synchronized emptyList()
            queue.asSequence().filter { available(it.eid, now) }
                .take(limit)
                .map { it.json }
                .toList()
                .also { picked ->
                    // A lease, not a deletion: if the device dies before ack the envelope comes back.
                    picked.forEach { inFlight[it.optString("eid")] = now + leaseMs }
                }
        }
    }

    /** Free, or leased to a poll whose lease has run out. */
    private fun available(eid: String, now: Long): Boolean {
        val lease = inFlight[eid] ?: return true
        return lease <= now
    }

    fun ack(me: String, eids: List<String>) {
        if (eids.isEmpty()) return
        val wanted = eids.toHashSet()
        synchronized(boxes) {
            val queue = boxes[me]
            if (queue != null) {
                val taken = queue.filter { wanted.contains(it.eid) }
                if (taken.isNotEmpty()) {
                    queue.removeAll { wanted.contains(it.eid) }
                    carriedBytes -= taken.sumOf { it.bytes }
                    if (queue.isEmpty()) boxes.remove(me)
                }
            }
            wanted.forEach { inFlight.remove(it) }
            dirty = true
        }
    }

    private fun dropFirstLocked(queue: ArrayDeque<Envelope>) {
        val dropped = queue.removeFirst()
        carriedBytes -= dropped.bytes
        inFlight.remove(dropped.eid)
    }

    private fun pruneLocked() {
        val now = nowMs()
        inFlight.entries.filter { it.value <= now }.map { it.key }.forEach { inFlight.remove(it) }
        val emptied = ArrayList<String>()
        for ((device, queue) in boxes) {
            while (queue.isNotEmpty() && now - queue.first().postedAt > maxCarriageMs) dropFirstLocked(queue)
            if (queue.isEmpty()) emptied.add(device)
        }
        emptied.forEach { boxes.remove(it) }
    }

    /** Called when the host shuts down; the queue must survive the next start. */
    fun flush() {
        val snapshot = synchronized(boxes) {
            if (!dirty) return
            val arr = JSONArray()
            for (queue in boxes.values) for (e in queue) arr.put(e.json)
            arr
        }
        try {
            file.writeText(snapshot.toString())
            synchronized(boxes) { dirty = false }
        } catch (e: Exception) {
            // Losing carried mail is bad, but never let it take the server down with it.
        }
    }

    fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            synchronized(boxes) {
                val now = nowMs()
                boxes.clear()
                inFlight.clear()
                carriedBytes = 0L
                for (i in 0 until arr.length()) {
                    val json = arr.optJSONObject(i) ?: continue
                    val to = json.optString("to")
                    if (to.isBlank()) continue
                    val postedAt = json.optLong("postedAt", now)
                    if (now - postedAt > maxCarriageMs) continue
                    val bytes = json.toString().length
                    carriedBytes += bytes
                    boxes.getOrPut(to) { ArrayDeque() }.addLast(
                        Envelope(json.optString("eid"), to, json, postedAt, bytes)
                    )
                }
            }
        } catch (e: Exception) {
            // A corrupt spool is worth less than the pairings that made it; start clean.
            try { file.delete() } catch (e: Exception) { }
        }
    }

    companion object {
        const val MAX_PER_DEVICE = 400
        private const val PULL_LIMIT = 64
        private const val TTL_MS = 24 * 60 * 60 * 1000L
        private const val IN_FLIGHT_MS = 45_000L
        /** Bound on how much mail the whole host will hold, so a room cannot exhaust storage. */
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    }
}
