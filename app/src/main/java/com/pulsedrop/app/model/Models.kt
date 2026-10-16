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

/** One upload waiting on the phone for Accept or Decline. */
data class PendingUploadInfo(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val fromDevice: String
)

data class PeerDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val requiresPin: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    // Wire value, not branding: older builds compare on this exact string.
    val appKind: String? = null, // "LocalDrop" when the host runs a PulseDrop server
    /** False on a phone that is only a client — dialling it is a guaranteed miss. */
    val hasServer: Boolean = true,
    /** Stable identity of the peer, so a relay can be signed with the right pairing. */
    val deviceId: String = ""
)

data class PairedDevice(
    val pid: String, // symmetric pairing id
    val name: String,
    val pubB64: String,
    val originIp: String,
    val originPort: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** The user read the 6-digit safety code aloud on both devices and confirmed it matches. */
    val verified: Boolean = false,
    /** Release the peer ran when it paired; blank when an older app never said. */
    val appVersion: String = "",
    /** Key derivation for this pair: 1 is the frozen v1 formula, 2 HKDF. Set at pairing time
         *  by the acceptor, echoed to the requester, never renegotiated afterwards. */
    val kdfVersion: Int = 1
)

data class IncomingPairRequest(
    val reqId: String,
    val name: String,
    val pubB64: String,
    val myIp: String,
    val myPort: Int,
    val safetyCode: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** The requester runs no server, so the answer has to be polled back rather than dialled in. */
    val viaRelay: Boolean = false,
    val appVersion: String = ""
)

data class PendingPairRequest(
    val peer: PeerDevice,
    val reqId: String,
    val startedAt: Long = System.currentTimeMillis(),
    /** True when we are the client-only half of this handshake. */
    val viaRelay: Boolean = false
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
    val attachment: ChatAttachment? = null,
    /**
     * Quoted message. The sender and text are a snapshot taken when this reply was written, not a
     * lookup: a reply must still show what it answered after the original is deleted, and on a
     * relayed thread the original may not be on this device yet at all.
     */
    val replyTo: String? = null,
    val replySender: String? = null,
    val replyText: String? = null,
    /** Soft delete. The bubble stays as a marked placeholder so a thread does not renumber. */
    val deleted: Boolean = false
)

/** A named cluster of paired devices that share one end-to-end encrypted room. */
data class ChatGroup(
    val id: String,
    val name: String,
    val memberPids: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)
