package com.pulsedrop.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chat message as it is kept on this device.
 *
 * [id] is the wire id the sender generated, so the same message arriving twice - from the pair and
 * from a relay, or after a reconnect - lands on one row instead of two bubbles. A tombstone is not a
 * row removal: [deleted] stays here with the text blanked, because a thread that renumbers when
 * someone deletes a message is a thread where "the third one from the bottom" stops meaning anything.
 */
@Entity(
    tableName = "chat_message",
    indices = [Index(value = ["threadId", "timestamp"]), Index(value = ["attachmentId"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    /** null = the room everyone is in; otherwise `pair:<pid>`, `group:<id>` or `ip:<addr>`. */
    val threadId: String?,
    val sender: String,
    /** Stable identity of the device that wrote this, blank for a browser with no pairing. */
    val senderDeviceId: String = "",
    /**
     * The address the writer came from, kept because a thread id does not carry it.
     *
     * An `ip:<addr>` thread is keyed on *this* phone's address - a browser tab dials the phone, not
     * the reverse - so the peer tab to ring back is only recoverable from whoever wrote the other
     * side. Without this column a reopened thread knows its own suffix and nothing else.
     */
    val clientIp: String? = null,
    val text: String,
    val timestamp: Long,
    val encrypted: Boolean = false,
    val attachmentId: String? = null,
    val attachmentName: String? = null,
    val attachmentSize: Long = 0L,
    val attachmentMime: String? = null,
    /** The quote, as a snapshot taken when this reply was written - never a lookup. */
    val replyTo: String? = null,
    val replySender: String? = null,
    val replyText: String? = null,
    val deleted: Boolean = false,
    /** One reaction on the message, and who put it there. Absent is null, not empty. */
    val reaction: String? = null,
    val reactionSender: String? = null,
    /** Set when the peer acknowledged reading this; null while it has not been read there. */
    val readAt: Long? = null,
    /** Editing keeps the id and the timestamp; this says the text underneath changed. */
    val editedAt: Long? = null
)

/**
 * The searchable copy of [ChatMessageEntity].
 *
 * FTS4 needs its own table because it tokenises text into an inverted index and cannot be a regular
 * column set, and this one is standalone rather than `content = chat_message`: an external-content
 * index has to join on an integer rowid, and message ids are the sender's strings. The cost is that
 * the DAO writes both tables in one transaction, which `ChatMessageDao.record` does.
 */
@Fts4
@Entity(tableName = "chat_message_fts")
data class ChatMessageFts(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "body") val body: String
)

/**
 * A conversation as the list shows it: what to call it, the newest thing in it, and what is unread.
 *
 * Derived from the messages, but stored rather than computed: a `GROUP BY` over the whole history on
 * every list draw is the slow way to get the same numbers, and unread counts need a per-thread
 * watermark that outlives the messages it was measured against.
 */
@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "threadId") val threadId: String,
    val displayName: String,
    val lastMessageAt: Long = 0L,
    val lastSenderId: String = "",
    val lastSender: String = "",
    val lastText: String = "",
    val unreadCount: Int = 0,
    /** Messages in this thread with a timestamp at or below this are read here. */
    val lastReadAt: Long = 0L
)
