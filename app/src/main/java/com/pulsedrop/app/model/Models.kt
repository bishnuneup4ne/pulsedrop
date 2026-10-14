package com.pulsedrop.app.model

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val uriString: String,
    val targetClientIp: String? = null, // null means visible to all authorized clients, or specific IP
    val uploadedByIp: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
)

data class TransferItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val bytesTransferred: Long,
    val speedBytesPerSec: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val progress: Float, // 0.0f to 1.0f
    val remoteDevice: String,
    val clientIp: String = "",
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class PendingTransferRequest(
    val id: String,
    val clientIp: String,
    val clientName: String,
    val fileName: String,
    val fileSize: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class PeerDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val requiresPin: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    // Wire value, not branding: older builds compare on this exact string.
    val appKind: String? = null // "LocalDrop" when the host runs a PulseDrop server
)

data class PairedDevice(
    val pid: String, // symmetric pairing id
    val name: String,
    val pubB64: String,
    val originIp: String,
    val originPort: Int,
    val createdAt: Long = System.currentTimeMillis()
)

data class IncomingPairRequest(
    val reqId: String,
    val name: String,
    val pubB64: String,
    val myIp: String,
    val myPort: Int,
    val safetyCode: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class PendingPairRequest(
    val peer: PeerDevice,
    val reqId: String,
    val startedAt: Long = System.currentTimeMillis()
)

data class ServerSettings(
    val port: Int = 8080,
    val isPinRequired: Boolean = false,
    val pin: String = "1234",
    val deviceName: String = "PulseDrop Phone",
    val autoAcceptTransfers: Boolean = false,
    val allowWebUploads: Boolean = true
)

/**
 * A file that belongs to a chat message and lives in the chat store, never in the
 * hosted file list. Bytes are served inline by [id] so the clients can preview them.
 */
data class ChatAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String
)

data class ClipMessage(
    val id: String,
    val text: String,
    val sender: String,
    val clientIp: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val encrypted: Boolean = false,
    /** Conversation this belongs to: null = Everyone, "pair:<pid>", "group:<id>", "ip:<addr>". */
    val threadId: String? = null,
    val attachment: ChatAttachment? = null
)

/** A named cluster of paired devices that share one end-to-end encrypted room. */
data class ChatGroup(
    val id: String,
    val name: String,
    val memberPids: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)
