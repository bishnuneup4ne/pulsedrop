package com.pulsedrop.app.data

import com.pulsedrop.app.model.ChatAttachment
import com.pulsedrop.app.model.ClipMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The chat history this device keeps, on top of [ChatMessageDao].
 *
 * The three shapes involved do not agree and should not be forced to: [ClipMessage] is what a call
 * site has in hand and what a bubble draws, [ChatMessageEntity] is what the file holds, and the DAO
 * speaks only the second. This is the one place that translates, so that adding a column does not
 * mean touching every path a message can arrive by.
 */
class ChatStore(private val dao: ChatMessageDao) {

    /** Every thread's newest messages, oldest first - the order the inbox appends in. */
    fun recent(limit: Int): Flow<List<ClipMessage>> =
        dao.recent(limit).map { rows -> rows.asReversed().map(ChatMessageEntity::toClip) }

    /** One thread, oldest first, so a caller can draw it without re-sorting. */
    fun thread(threadId: String?, limit: Int = PAGE): Flow<List<ClipMessage>> =
        dao.messagesIn(threadId, limit, 0).map { rows -> rows.asReversed().map(ChatMessageEntity::toClip) }

    fun conversations(): Flow<List<ConversationEntity>> = dao.conversations()

    /**
     * Stores one message, its index row and its thread's summary together.
     *
     * [countsAsUnread] is the caller's answer to "did this arrive from somebody else" rather than
     * something read off a flag here: the same message reaches a phone over the pair and over a
     * relay, and the DAO's dedup by id is what stops the badge counting the delivery twice.
     */
    suspend fun record(message: ClipMessage, displayName: String, countsAsUnread: Boolean) {
        dao.record(message.toEntity(), displayName, countsAsUnread)
    }

    suspend fun find(id: String): ClipMessage? = dao.findById(id)?.toClip()

    /** Soft delete: the bubble stays, the words and the index entry leave. */
    suspend fun strikeOut(id: String) = dao.strikeOut(id)

    /** One reaction on a message, or null to clear it. [byName] is who put it there. */
    suspend fun react(id: String, reaction: String?, byName: String) =
        dao.setReaction(id, reaction, reaction?.let { byName })

    /**
     * Rewrites a message's text and re-indexes it.
     *
     * Refuses an edit of a row this device did not write, and of one already struck out: an edit
     * signal names a message id, and an id alone does not say whose words those are.
     */
    suspend fun editIfMine(id: String, text: String, at: Long, byDeviceId: String): Boolean {
        val row = dao.findById(id) ?: return false
        if (row.deleted || row.senderDeviceId != byDeviceId) return false
        dao.edit(id, text, at)
        return true
    }

    /**
     * Full-text search, oldest-hit-last.
     *
     * Goes through the FTS index rather than a substring scan, which is what lets a search over the
     * whole history stay off the composition path.
     */
    suspend fun search(query: String, limit: Int = PAGE): List<SearchHit> {
        val match = sanitizeQuery(query) ?: return emptyList()
        return dao.search(match, limit).map { row -> SearchHit(row.toClip(), snippet(row.text, query)) }
    }

    /** The newest timestamp in a thread, or null when it holds nothing. */
    suspend fun newestIn(threadId: String?): Long? = dao.newestIn(threadId)

    suspend fun markThreadRead(threadId: String?, through: Long) =
        dao.markThreadRead(threadId ?: ChatMessageDao.ROOM_KEY, through)

    /** Marks our own delivered messages in [threadId] as read on the peer, at [at]. */
    suspend fun acknowledgeRead(threadId: String?, through: Long, myDeviceId: String, at: Long): Int {
        val ids = dao.unreadSentIds(threadId, through, myDeviceId)
        if (ids.isNotEmpty()) dao.markReadOnPeer(ids, at)
        return ids.size
    }

    companion object {
        const val PAGE = 200

        /**
         * Turns what the user typed into a safe MATCH expression.
         *
         * `MATCH` is a query language, not a substring operator: a bare quote, a stray `*` or the word
         * `NOT` in the text is a syntax error, which would throw instead of finding nothing. Splitting
         * on everything that is not a letter or digit does two jobs at once - it drops the punctuation
         * the parser would choke on, and it produces the same tokens the FTS tokenizer stored, so
         * `don't` is looked up the way it was indexed (`don` `t`) rather than as a word that is not in
         * the index. Each token is quoted so it is data; the last one is left bare with a `*` after it,
         * which is what makes a prefix search work - except when the word is one of the four operators,
         * which stay quoted or they stop being words.
         */
        internal fun sanitizeQuery(raw: String): String? {
            val words = raw.split(Regex("""[^\p{L}\p{N}_]+"""))
                .filter { it.isNotEmpty() }
                .take(8)
            if (words.isEmpty()) return null
            val last = words.last()
            val tail = if (last.lowercase() in OPERATORS) "\"$last\"" else "$last*"
            val head = words.dropLast(1).joinToString(" ") { "\"$it\"" }
            return if (head.isEmpty()) tail else "$head $tail"
        }

        /** Reserved in the MATCH grammar, so a search for one has to be quoted to mean itself. */
        private val OPERATORS = setOf("and", "or", "not", "near")

        /** The matching words around the query, for showing under a search result. */
        private fun snippet(text: String, query: String): String {
            val needle = query.trim().lowercase()
            val at = if (needle.isEmpty()) -1 else text.lowercase().indexOf(needle)
            return if (at < 0) text.take(120)
            else text.substring(maxOf(0, at - 40), minOf(text.length, at + needle.length + 80))
        }
    }
}

/** A search result with the words around the match, which the row itself cannot know. */
data class SearchHit(val message: ClipMessage, val snippet: String)

internal fun ChatMessageEntity.toClip() = ClipMessage(
    id = id,
    text = text,
    sender = sender,
    clientIp = clientIp,
    timestamp = timestamp,
    encrypted = encrypted,
    threadId = threadId,
    attachment = attachmentId?.let {
        ChatAttachment(
            id = it,
            name = attachmentName.orEmpty(),
            size = attachmentSize,
            mime = attachmentMime.orEmpty()
        )
    },
    replyTo = replyTo,
    replySender = replySender,
    replyText = replyText,
    deleted = deleted,
    reaction = reaction,
    reactionBy = reactionSender,
    editedAt = editedAt,
    readAt = readAt
)

internal fun ClipMessage.toEntity(senderDeviceId: String = "") = ChatMessageEntity(
    id = id,
    threadId = threadId,
    sender = sender,
    senderDeviceId = senderDeviceId,
    clientIp = clientIp,
    text = text,
    timestamp = timestamp,
    encrypted = encrypted,
    attachmentId = attachment?.id,
    attachmentName = attachment?.name,
    attachmentSize = attachment?.size ?: 0L,
    attachmentMime = attachment?.mime,
    replyTo = replyTo,
    replySender = replySender,
    replyText = replyText,
    deleted = deleted
)
