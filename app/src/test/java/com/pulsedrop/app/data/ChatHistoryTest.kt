package com.pulsedrop.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatStore] as the app's chat history: what survives a restart, what a search can reach, and what
 * a user's own text is allowed to do to the query parser.
 *
 * The DAO has its own tests; these cover the layer above it, which is the one that decides whether a
 * message is kept at all. The in-memory list it replaced lost history on process death and searched
 * only inside the newest 2000 messages, so both are asserted here rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatHistoryTest {

    private lateinit var context: Context
    private var opened: AppDatabase? = null
    private lateinit var store: ChatStore

    @Before
    fun freshDatabase() {
        context = ApplicationProvider.getApplicationContext()
        dbFiles().forEach { it.delete() }
        opened = AppDatabase.buildDatabase(context)
        store = ChatStore(opened!!.chatMessageDao())
    }

    @After
    fun closeTheDatabase() {
        opened?.close()
        opened = null
    }

    private fun dbFiles() = listOf(
        context.getDatabasePath(AppDatabase.DB_NAME),
        File(context.getDatabasePath(AppDatabase.DB_NAME).path + "-wal"),
        File(context.getDatabasePath(AppDatabase.DB_NAME).path + "-journal")
    )

    private fun clip(
        id: String,
        thread: String? = "pair:aya",
        text: String = "hello",
        at: Long = 1_700_000_000_000L,
        sender: String = "Aya",
        clientIp: String? = null
    ) = com.pulsedrop.app.model.ClipMessage(
        id = id,
        text = text,
        sender = sender,
        clientIp = clientIp,
        timestamp = at,
        threadId = thread
    )

    @Test
    fun historyComesBackAfterTheProcessDies() = runBlocking {
        store.record(clip("m1", text = "the passcode is 4417"), "Aya", countsAsUnread = true)

        opened?.close()
        opened = AppDatabase.buildDatabase(context)
        store = ChatStore(opened!!.chatMessageDao())

        val kept = store.recent(50).first()
        assertEquals(listOf("m1"), kept.map { it.id })
        assertEquals("the passcode is 4417", kept.first().text)
    }

    @Test
    fun aMessageThatArrivesOnTwoPathsBadgesOnce() = runBlocking {
        val msg = clip("m1", text = "sharing my screen now")
        store.record(msg, "Aya", countsAsUnread = true)
        store.record(msg, "Aya", countsAsUnread = true)

        assertEquals(1, opened!!.chatMessageDao().messageCount())
        assertEquals(1, store.conversations().first().first().unreadCount)
    }

    @Test
    fun theThreadOrderIsOldestFirstSoBubblesDrawInPlace() = runBlocking {
        store.record(clip("old", text = "earlier", at = 1L), "Aya", true)
        store.record(clip("new", text = "later", at = 9L), "Aya", true)

        assertEquals(listOf("old", "new"), store.thread("pair:aya").first().map { it.id })
        assertEquals(listOf("old", "new"), store.recent(50).first().map { it.id })
    }

    @Test
    fun searchReachesHistoryThatAlreadyLeftTheWindow() = runBlocking {
        // Older than the newest 5 the UI asks for, which is exactly what a list scan cannot find.
        store.record(clip("m1", text = "invoice for the printer repair", at = 1L), "Aya", true)
        repeat(5) { i -> store.record(clip("n$i", text = "noise $i", at = 100L + i), "Aya", true) }

        val hits = store.search("printer", limit = 5)

        assertEquals(listOf("m1"), hits.map { it.message.id })
        assertTrue(hits.first().snippet.contains("printer"))
    }

    @Test
    fun punctuationInTheQueryIsWordsRatherThanSyntax() = runBlocking {
        store.record(clip("m1", text = "send the file please"), "Aya", true)

        // None of these may throw: an unbalanced quote, the operators as plain words, a bare wildcard,
        // and whitespace. A syntax error here is a crash on a keystroke, which is not a search result.
        assertTrue(store.search("\"", limit = 5).isEmpty())
        assertTrue(store.search("and or not", limit = 5).isEmpty())
        assertTrue(store.search("*", limit = 5).isEmpty())
        assertTrue(store.search("   ", limit = 5).isEmpty())
        assertEquals(listOf("m1"), store.search("send*", limit = 5).map { it.message.id })
    }

    @Test
    fun theQueryBecomesTheWordsNotThePunctuationAroundThem() {
        assertEquals("send*", ChatStore.sanitizeQuery("send"))
        assertEquals("\"can\" you*", ChatStore.sanitizeQuery("can you"))
        // Quoted so the word is itself and not the operator it happens to be.
        assertEquals("\"not\"", ChatStore.sanitizeQuery("not"))
        assertEquals("\"and\" \"or\" \"NOT\"", ChatStore.sanitizeQuery("and or NOT"))
        // Contractions are indexed as two tokens, so the query has to split the same way.
        assertEquals("\"don\" t*", ChatStore.sanitizeQuery("don't"))
        assertEquals(null, ChatStore.sanitizeQuery("  --  "))
    }

    @Test
    fun aPrefixFindsTheWordItStarts() = runBlocking {
        store.record(clip("m1", text = "reconnecting through the relay"), "Aya", true)

        assertEquals(listOf("m1"), store.search("reconn", limit = 5).map { it.message.id })
    }

    @Test
    fun attachmentAndOriginAddressRoundTrip() = runBlocking {
        val withFile = clip("m1", text = "", thread = "ip:192.168.1.9", clientIp = "192.168.1.9")
            .copy(attachment = com.pulsedrop.app.model.ChatAttachment("a1", "notes.pdf", 2048L, "application/pdf"))
        store.record(withFile, "Browser", countsAsUnread = true)

        val back = store.find("m1")!!
        assertEquals("192.168.1.9", back.clientIp)
        assertEquals("a1", back.attachment?.id)
        assertEquals("notes.pdf", back.attachment?.name)
        assertEquals(2048L, back.attachment?.size)
        assertEquals("application/pdf", back.attachment?.mime)
    }

    @Test
    fun aQuoteSurvivesTheOriginalBeingDeleted() = runBlocking {
        val quoted = clip("m1", text = "can you send the charger")
        store.record(quoted, "Aya", true)
        store.record(
            quoted.copy(id = "m2", text = "yes, in the drawer", replyTo = "m1", replySender = "Aya", replyText = "can you send the charger"),
            "Aya", true
        )

        store.strikeOut("m1")

        val reply = store.find("m2")!!
        assertEquals("can you send the charger", reply.replyText)
        assertTrue(store.find("m1")!!.deleted)
    }

    @Test
    fun markingReadClearsTheBadgeAndPersistsTheWatermark() = runBlocking {
        store.record(clip("m1", text = "one", at = 1L), "Aya", countsAsUnread = true)
        store.record(clip("m2", text = "two", at = 2L), "Aya", countsAsUnread = true)
        val newest = store.newestIn("pair:aya")!!

        store.markThreadRead("pair:aya", newest)

        assertEquals(0, store.conversations().first().first().unreadCount)
        assertEquals(newest, store.conversations().first().first().lastReadAt)
    }
}
