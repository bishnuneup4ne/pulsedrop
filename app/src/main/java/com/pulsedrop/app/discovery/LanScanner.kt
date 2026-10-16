package com.pulsedrop.app.discovery

import com.pulsedrop.app.graph.AppGraph
import com.pulsedrop.app.model.PeerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Sweeps the local /24 for devices hosting a PulseDrop server, so a phone whose
 * app is backgrounded — or a PC with the page open — is still discovered.
 *
 * The sweep is two-phase on purpose: a raw TCP handshake is ~free and knocks out
 * the ~250 hosts that have nothing listening, and only the survivors pay for an
 * HTTP identity probe. Doing all of it over OkHttp meant 1270 connection objects
 * per pass, which is what pinned the CPU.
 */
object LanScanner {

    private const val SCAN_PORT = 8080
    private const val TCP_TIMEOUT_MS = 120
    private const val MAX_CONCURRENT_PROBES = 48

    private val scanClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(700, TimeUnit.MILLISECONDS)
            .readTimeout(900, TimeUnit.MILLISECONDS)
            .callTimeout(1500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun scanSubnet(selfIp: String): List<PeerDevice> = withContext(Dispatchers.IO) {
        val parts = selfIp.split(".")
        if (parts.size != 4) return@withContext emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."

        val hosts = (1..254).map { prefix + it }.filter { it != selfIp }
        val gate = Semaphore(MAX_CONCURRENT_PROBES)
        val open = coroutineScope {
            hosts.map { host ->
                async {
                    gate.withPermit { if (isPortOpen(host, SCAN_PORT)) host else null }
                }
            }.awaitAll().filterNotNull()
        }
        if (open.isEmpty()) return@withContext emptyList()

        coroutineScope {
            open.map { host ->
                async { probe(host) }
            }.awaitAll().filterNotNull()
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun probe(ip: String): PeerDevice? {
        return try {
            scanClient.newCall(
                Request.Builder().url("http://$ip:$SCAN_PORT/api/info").get().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val name = json.optString("deviceName")
                    .ifBlank { json.optString("name") }
                    .ifBlank { return null }
                val version = json.optString("version")
                AppGraph.peerCaps.record(
                    AppGraph.peerCaps.keyForIp(ip),
                    version,
                    PeerCapabilityRegistry.capsFromJson(json.optJSONArray("caps"))
                )
                PeerDevice(
                    id = "scan_${ip}_$SCAN_PORT",
                    name = name,
                    ip = ip,
                    port = SCAN_PORT,
                    requiresPin = json.optBoolean("requiresPin", false),
                    appKind = if (version.startsWith("2")) "LocalDrop" else "web"
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
