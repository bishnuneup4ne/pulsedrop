package com.pulsedrop.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.pulsedrop.app.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

class PeerDiscoveryManager(
    private val context: Context,
    private val selfId: String = UUID.randomUUID().toString()
) {
    private val _nearbyPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val nearbyPeers: StateFlow<List<PeerDevice>> = _nearbyPeers.asStateFlow()

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var pruneJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val peersMap = mutableMapOf<String, PeerDevice>()

    // receive()/send() block a Kotlin thread and ignore coroutine cancellation, so the
    // sockets are kept to force them out when discovery stops.
    @Volatile private var broadcastSocket: DatagramSocket? = null
    @Volatile private var listenSocket: DatagramSocket? = null

    fun startDiscovery(
        deviceName: String,
        currentIp: String,
        serverPort: Int,
        requiresPin: Boolean,
        scope: CoroutineScope
    ) {
        stopDiscovery()

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("PulseDropMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 1. Broadcast job: sends presence announcement every few seconds
        broadcastJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().also { broadcastSocket = it }
                socket.broadcast = true

                while (isActive) {
                    try {
                        val payload = JSONObject().apply {
                            put("id", selfId)
                            put("name", deviceName)
                            put("ip", currentIp)
                            put("port", serverPort)
                            put("pin", requiresPin)
                            put("time", System.currentTimeMillis())
                        }.toString().toByteArray()

                        val broadcastIp = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(payload, payload.size, broadcastIp, DISCOVERY_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        // ignore network interface transient send errors
                    }
                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
                if (broadcastSocket === socket) broadcastSocket = null
            }
        }

        // 2. Listen job: receives presence announcements from nearby PulseDrop instances
        listenJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                // SO_REUSEADDR must be set before binding, so bind explicitly after creating
                // the unbound socket.
                socket = DatagramSocket(null).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = LISTEN_POLL_MS
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                listenSocket = socket
                val buffer = ByteArray(2048)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val dataStr = String(packet.data, 0, packet.length)
                        val json = JSONObject(dataStr)
                        val peerId = json.optString("id", "")
                        val peerName = json.optString("name", "PulseDrop Peer")
                        val peerIp = json.optString("ip", packet.address.hostAddress ?: "")
                        val peerPort = json.optInt("port", 8080)
                        val peerPin = json.optBoolean("pin", false)

                        // Do not list ourselves
                        if (peerId != selfId && peerIp != currentIp && peerId.isNotBlank()) {
                            val peer = PeerDevice(
                                id = peerId,
                                name = peerName,
                                ip = peerIp,
                                port = peerPort,
                                requiresPin = peerPin,
                                lastSeen = System.currentTimeMillis()
                            )
                            synchronized(peersMap) {
                                peersMap[peerId] = peer
                            }
                            cleanAndEmitPeers()
                        }
                    } catch (e: Exception) {
                        // socket timeout (periodic cancellation checkpoint) or transient read error
                        if (socket.isClosed) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
                if (listenSocket === socket) listenSocket = null
            }
        }

        // 3. Cleanup stale peers periodic job
        pruneJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PEER_PRUNE_INTERVAL_MS)
                cleanAndEmitPeers()
            }
        }
    }

    private fun cleanAndEmitPeers() {
        val now = System.currentTimeMillis()
        synchronized(peersMap) {
            val iterator = peersMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastSeen > PEER_TIMEOUT_MS) {
                    iterator.remove()
                }
            }
            _nearbyPeers.value = peersMap.values.toList()
        }
    }

    fun stopDiscovery() {
        broadcastJob?.cancel()
        broadcastJob = null
        listenJob?.cancel()
        listenJob = null
        pruneJob?.cancel()
        pruneJob = null
        try { broadcastSocket?.close() } catch (e: Exception) { }
        try { listenSocket?.close() } catch (e: Exception) { }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        multicastLock = null
    }

    companion object {
        const val DISCOVERY_PORT = 8888
        const val PEER_TIMEOUT_MS = 25000L
        private const val BROADCAST_INTERVAL_MS = 5000L
        private const val PEER_PRUNE_INTERVAL_MS = 8000L
        private const val LISTEN_POLL_MS = 1000
    }
}
