package com.pulsedrop.app.relay

import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.security.PairManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One device's side of the relay: it talks to a host it is paired with instead of running a server.
 *
 * This is what removes the requirement that every phone in a room host its own server. A pair
 * request goes out, the answer is polled back on a capability the request itself created, and from
 * then on chat arrives by polling with a proof that we hold the key we share with that host. Mail
 * addressed to somebody else is still sealed with *their* key, so the host carries it unread.
 */
class RelayClient(
    private val pairManager: PairManager,
    private val scope: CoroutineScope
) {

    class Host(val ip: String, val port: Int, val deviceId: String, val name: String)

    private val _attached = MutableStateFlow<Host?>(null)
    val attached: StateFlow<Host?> = _attached.asStateFlow()

    private val _lastSync = MutableStateFlow(0L)
    /** 0 until the first successful pull, so the UI can say "not yet reached" instead of guessing. */
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null

    /** Eids handed to us but not yet confirmed, carried into the next pull. */
    private val received = ArrayList<String>()

    /**
     * The host has to be identified by its device id, which `/api/info` publishes, so that the
     * right pairing is picked to sign with when a phone is paired with several hosts.
     */
    fun attach(ip: String, port: Int): Boolean {
        val info = try {
            client.newCall(
                Request.Builder().url("http://$ip:$port/api/info").get().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return false
                JSONObject(resp.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            return false
        }
        val deviceId = info.optString("deviceId")
        if (deviceId.isBlank()) return false
        val host = Host(ip, port, deviceId, info.optString("deviceName", "PulseDrop host"))
        // A second attach to the same host must not stack pollers.
        if (_attached.value?.deviceId == deviceId) return true
        _attached.value = host
        startPolling()
        return true
    }

    fun detach() {
        pollJob?.cancel()
        pollJob = null
        _attached.value = null
        synchronized(received) { received.clear() }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            var misses = 0
            while (isActive) {
                val host = _attached.value ?: break
                val handled = try {
                    poll(host)
                } catch (e: Exception) {
                    0
                }
                if (handled >= 0) {
                    misses = 0
                    _lastSync.value = System.currentTimeMillis()
                } else {
                    misses++
                    // A host that stops answering is a host that has gone away; do not sit on it.
                    if (misses >= RELAY_HOST_GIVEUP) {
                        detach()
                        break
                    }
                }
                delay(if (handled > 0) POLL_ACTIVE_MS else if (misses > 0) POLL_RETRY_MS else POLL_IDLE_MS)
            }
        }
    }

    /**
     * Fetches mail for us, acknowledging what the previous round delivered. Returns the number of
     * messages routed, or -1 when the host did not answer.
     */
    private fun poll(host: Host): Int {
        val acks = synchronized(received) {
            if (received.isEmpty()) JSONArray() else JSONArray(ArrayList(received).also { received.clear() })
        }
        val body = JSONObject().put("role", ROLE_PULL).put("ack", acks)
        val response = post(host, "/api/relay/pull", body) ?: return -1
        val messages = response.optJSONArray("messages") ?: JSONArray()
        var handled = 0
        for (i in 0 until messages.length()) {
            val envelope = messages.optJSONObject(i) ?: continue
            val eid = envelope.optString("eid")
            if (envelope.optString("kind") == KIND_SIGNAL) {
                if (pairManager.handleRelaySignal(envelope)) handled++
            } else if (pairManager.handleRelayMessage(envelope)) {
                handled++
            }
            if (eid.isNotBlank()) synchronized(received) { received.add(eid) }
        }
        return handled
    }

    /**
     * Posts one already-sealed envelope for [pairing] to the host's mailbox. The bytes were
     * encrypted with the recipient's own key before they got here, not with the host's, which is
     * the whole reason a carrier cannot read what it is carrying.
     */
    fun send(pairing: PairedDevice, ivB64: String, ctB64: String): Boolean {
        val host = _attached.value ?: return false
        val to = pairManager.deviceIdOf(pairing)
        if (to == host.deviceId) return false // the host is the recipient; it gets mail directly
        val body = JSONObject()
            .put("role", ROLE_POST)
            .put("kind", KIND_MESSAGE)
            .put("to", to)
            .put("pid", pairing.pid)
            .put("iv", ivB64)
            .put("ct", ctB64)
        return post(host, "/api/relay/post", body)?.optBoolean("queued", false) == true
    }

    /**
     * Relay-only pairing handshake. The request advertised that there was nothing to dial back,
     * so the answer is polled on the request id — a random uuid only we hold. That id is the whole
     * authorization at this stage: no key exists yet between these two devices and none is claimed.
     */
    fun pollPairAnswer(ip: String, port: Int, reqId: String): JSONObject? =
        post(Host(ip, port, "", ""), "/api/relay/pair-poll", JSONObject().put("reqId", reqId))

    private fun post(host: Host, path: String, body: JSONObject): JSONObject? {
        val auth = pairManager.relayAuth(host.deviceId, body.optString("role", ""))
        if (path == "/api/relay/post" || path == "/api/relay/pull") {
            if (auth == null) return null
            body.put("pid", auth.first).put("proof", auth.second)
        }
        return try {
            client.newCall(
                Request.Builder()
                    .url("http://${host.ip}:${host.port}$path")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val ROLE_POST = "post"
        const val ROLE_PULL = "pull"
        const val KIND_MESSAGE = "message"
        const val KIND_SIGNAL = "signal"
        private const val POLL_ACTIVE_MS = 1_500L
        private const val POLL_IDLE_MS = 6_000L
        private const val POLL_RETRY_MS = 12_000L
        private const val RELAY_HOST_GIVEUP = 6
    }
}
