package com.pulsedrop.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The chat history store.
 *
 * Every read is a `Flow` and every write is bounded: the UI subscribes to a page of a thread rather
 * than pulling the whole history into memory, and a message that arrives on two paths at once - the
 * pair directly, and a relay speaking for a device with no server - is written once, keyed by the
 * sender's own message id.
 */
@Dao
interface ChatMessageDao {

    /** One page of a thread, newest first. A null [threadId] is the room every device shares. */
    @Query(
        "SELECT * FROM chat_message WHERE threadId IS :threadId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset"
    )
    fun messagesIn(threadId: String?, limit: Int, offset: Int): Flow<List<ChatMessageEntity>>

    /**
     * The newest messages of every thread, newest first.
     *
     * The inbox groups threads client-side, so it needs one subscription rather than one per thread;
     * [messagesIn] is the right query once a pane opens a single thread, and paging there is what
     * keeps a long history off the composition path.
     */
    @Query("SELECT * FROM chat_message ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE id = :id")
    suspend fun findById(id: String): ChatMessageEntity?

    /** The newest message in a thread, or null when it holds nothing. A read watermark needs this. */
    @Query("SELECT max(timestamp) FROM chat_message WHERE threadId IS :threadId")
    suspend fun newestIn(threadId: String?): Long?

    /**
     * Full-text search across every thread. `chat_message_fts MATCH` is the FTS operator, not a
     * column comparison: it consults the inverted index, so prefix queries (`send*`) work without a
     * scan of the history.
     */
    @Query(
        "SELECT m.* FROM chat_message AS m " +
            "INNER JOIN chat_message_fts AS f ON m.id = f.message_id " +
            "WHERE chat_message_fts MATCH :query " +
            "ORDER BY m.timestamp DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: ChatMessageFts)

    /**
     * Writes a message, its searchable copy and the thread's summary as one unit.
     *
     * The index row is deleted and re-inserted rather than updated in place: an FTS table is cheap to
     * rewrite and awkward to mutate, and this path serves an edited message as often as a new one.
     *
     * Unread is counted only for a message this device has not seen. The same id can arrive twice -
     * once from the pair and once relayed for a device with no server of its own - and a badge that
     * counts deliveries rather than messages is a badge that never clears.
     */
    @Transaction
    suspend fun record(message: ChatMessageEntity, displayName: String, countsAsUnread: Boolean) {
        val previous = findById(message.id)
        val row = if (previous == null) message else message.copy(
            reaction = message.reaction ?: previous.reaction,
            reactionSender = message.reactionSender ?: previous.reactionSender,
            readAt = message.readAt ?: previous.readAt,
            editedAt = message.editedAt ?: previous.editedAt,
            // A tombstone outranks a copy of the words it struck out: a redelivery from a second path
            // must not bring a deleted message back.
            deleted = message.deleted || previous.deleted
        )
        insertMessage(row)
        deleteFtsFor(row.id)
        insertFts(ChatMessageFts(row.id, row.sender, row.text))
        bumpConversation(row, displayName, countsAsUnread && previous == null)
    }

    @Query("DELETE FROM chat_message_fts WHERE message_id = :id")
    suspend fun deleteFtsFor(id: String)

    /**
     * Marks a thread read up to [through]. Unread here is a watermark, not a per-message flag: the
     * peer says "I have seen everything up to this one" and everything at or before it stops counting.
     * Message-level [ChatMessageEntity.readAt] is the mirror image - our own messages the peer has
     * read - and is set by the caller that knows which ones those are.
     */
    @Query("UPDATE conversation SET lastReadAt = :through, unreadCount = 0 WHERE threadId = :threadId")
    suspend fun markThreadRead(threadId: String, through: Long)

    @Query("UPDATE chat_message SET reaction = :reaction, reactionSender = :by WHERE id = :id")
    suspend fun setReaction(id: String, reaction: String?, by: String?)

    /**
     * Applies an edit and re-indexes the words it replaced.
     *
     * The two have to move together: an index still holding the old sentence would keep finding a
     * message for text it no longer contains, and search results are the last place a stale copy of a
     * message should turn up.
     */
    @Transaction
    suspend fun edit(id: String, text: String, at: Long) {
        stampEdit(id, text, at)
        reindex(id) ?: return
    }

    @Query("UPDATE chat_message SET text = :text, editedAt = :at WHERE id = :id")
    suspend fun stampEdit(id: String, text: String, at: Long)

    /**
     * Soft delete: the row stays so the thread keeps its shape, the words leave it - and leave the
     * index with it, or a struck-out message stays searchable by the text it no longer has.
     */
    @Transaction
    suspend fun strikeOut(id: String) {
        markDeleted(id)
        deleteFtsFor(id)
    }

    @Query("UPDATE chat_message SET deleted = 1, text = '', reaction = NULL WHERE id = :id")
    suspend fun markDeleted(id: String)

    /** Puts a message's current text back into the index; null when the row is gone. */
    @Transaction
    suspend fun reindex(id: String): ChatMessageEntity? {
        val row = findById(id) ?: return null
        deleteFtsFor(id)
        insertFts(ChatMessageFts(id, row.sender, row.text))
        return row
    }

    @Query("SELECT id FROM chat_message WHERE threadId IS :threadId AND timestamp <= :through " +
        "AND senderDeviceId = :mine AND readAt IS NULL AND deleted = 0")
    suspend fun unreadSentIds(threadId: String?, through: Long, mine: String): List<String>

    @Query("UPDATE chat_message SET readAt = :at WHERE id IN (:ids)")
    suspend fun markReadOnPeer(ids: List<String>, at: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversation ORDER BY lastMessageAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE threadId = :threadId")
    suspend fun findConversation(threadId: String): ConversationEntity?

    @Query("SELECT COUNT(*) FROM chat_message")
    suspend fun messageCount(): Int

    private suspend fun bumpConversation(
        message: ChatMessageEntity,
        displayName: String,
        countsAsUnread: Boolean
    ) {
        val key = message.threadId ?: ROOM_KEY
        val existing = findConversation(key) ?: return insertConversation(
            ConversationEntity(
                threadId = key,
                displayName = displayName.ifBlank { key },
                lastMessageAt = message.timestamp,
                lastSenderId = message.senderDeviceId,
                lastSender = message.sender,
                lastText = message.text,
                unreadCount = if (countsAsUnread) 1 else 0
            )
        )
        val newer = message.timestamp >= existing.lastMessageAt
        updateConversation(
            existing.copy(
                displayName = displayName.ifBlank { existing.displayName },
                lastMessageAt = if (newer) message.timestamp else existing.lastMessageAt,
                lastSenderId = if (newer) message.senderDeviceId else existing.lastSenderId,
                lastSender = if (newer) message.sender else existing.lastSender,
                lastText = if (newer) message.text else existing.lastText,
                unreadCount = existing.unreadCount + if (countsAsUnread) 1 else 0
            )
        )
    }

    companion object {
        /** The room has no thread id; the conversation list still needs a key to hang it on. */
        const val ROOM_KEY = "room"
    }
}
