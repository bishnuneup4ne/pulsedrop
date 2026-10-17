package com.pulsedrop.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upgrade path and the chat store, on a real SQLite file.
 *
 * The old builder carried `fallbackToDestructiveMigration(dropAllTables = true)`, so a missing
 * migration dropped every table and reopened - a silent wipe of the user's transfer log on the first
 * device to upgrade. These tests are the reason that call is gone: the first one puts a v3 database
 * on the disk and asserts the rows walk into v4 intact, which is also what Room's own schema check
 * does to a hand-written migration that guessed wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatStoreTest {

    private lateinit var context: Context
    private var opened: AppDatabase? = null

    @Before
    fun removeAnyDatabaseFromAPreviousTest() {
        context = ApplicationProvider.getApplicationContext()
        dbFiles().forEach { it.delete() }
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

    /** A database exactly as release 1.7 left it: the transfer table, no chat, user_version 3. */
    private fun writeAV3Database(vararg transfers: Pair<String, String>) {
        val file = context.getDatabasePath(AppDatabase.DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `transfer_history` (`id` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                    "`fileSize` INTEGER NOT NULL, `direction` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`remoteDevice` TEXT NOT NULL, `clientIp` TEXT, `localFilePath` TEXT, " +
                    "`mimeType` TEXT, `timestamp` INTEGER NOT NULL, `errorMessage` TEXT, " +
                    "PRIMARY KEY(`id`))"
            )
            transfers.forEach { (id, name) ->
                db.insert(
                    "transfer_history",
                    null,
                    ContentValues().apply {
                        put("id", id)
                        put("fileName", name)
                        put("fileSize", 1024L)
                        put("direction", "INCOMING")
                        put("status", "COMPLETED")
                        put("remoteDevice", "Kitchen PC")
                        put("localFilePath", "/sdcard/Download/$name")
                        put("timestamp", 1_700_000_000_000L)
                    }
                )
            }
            db.execSQL("PRAGMA user_version = 3")
        }
    }

    private fun open(): AppDatabase = AppDatabase.buildDatabase(context).also { opened = it }

    @Test
    fun upgradingFromThreeKeepsEveryTransferRow() {
        writeAV3Database("t1" to "IMG_0001.jpg", "t2" to "report.pdf")

        val db = open()
        val rows = runBlocking { db.transferHistoryDao().getAllTransfers().first() }

        assertEquals(2, rows.size)
        assertEquals(setOf("IMG_0001.jpg", "report.pdf"), rows.map { it.fileName }.toSet())
        assertEquals("Kitchen PC", rows.first().remoteDevice)
    }

    @Test
    fun aMessageThatArrivesTwiceIsStoredOnce() = runBlocking {
        val dao = open().chatMessageDao()
        val hello = message("m1", text = "the file is on the desktop")
        dao.record(hello, displayName = "Aya", countsAsUnread = true)
        dao.record(hello, displayName = "Aya", countsAsUnread = true)

        assertEquals(1, dao.messageCount())
        assertEquals(1, dao.messagesIn("pair:aya", limit = 10, offset = 0).first().size)
        assertEquals(1, dao.findConversation("pair:aya")?.unreadCount)
    }

    @Test
    fun aRedeliveredCopyDoesNotUndoWhatThePeerAlreadySaid() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", text = "thanks for the pictures"), "Aya", true)
        dao.setReaction("m1", reaction = "heart", by = "Aya")
        dao.markReadOnPeer(listOf("m1"), at = 3L)

        dao.record(message("m1", text = "thanks for the pictures"), "Aya", true)

        val row = dao.findById("m1")
        assertEquals("heart", row?.reaction)
        assertEquals(3L, row?.readAt)
    }

    @Test
    fun searchFindsTheWordsRatherThanTheIds() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", text = "can you send the charger"), "Aya", true)
        dao.record(message("m2", text = "meeting at nine"), "Aya", true)

        val hits = dao.search("charger", limit = 10)
        assertEquals(listOf("m1"), hits.map { it.id })
    }

    @Test
    fun editingAMessageRewritesWhatSearchKnows() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", text = "meeting at nine"), "Aya", true)

        dao.edit("m1", "meeting at ten", at = 2L)

        assertEquals(listOf("m1"), dao.search("ten", limit = 10).map { it.id })
        assertTrue(dao.search("nine", limit = 10).isEmpty())
        assertEquals(2L, dao.findById("m1")?.editedAt)
    }

    @Test
    fun deletingLeavesTheBubbleAndTakesTheText() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", text = "something regrettable"), "Aya", true)

        dao.strikeOut("m1")

        val row = dao.findById("m1")
        assertNotNull(row)
        assertTrue(row!!.deleted)
        assertEquals("", row.text)
        assertTrue(dao.search("regrettable", limit = 10).isEmpty())
    }

    @Test
    fun unreadIsOneWatermarkPerThread() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", text = "one"), "Aya", countsAsUnread = true)
        dao.record(message("m2", text = "two"), "Aya", countsAsUnread = true)

        assertEquals(2, dao.findConversation("pair:aya")?.unreadCount)

        dao.markThreadRead("pair:aya", through = 5L)
        assertEquals(0, dao.findConversation("pair:aya")?.unreadCount)
        assertEquals(5L, dao.findConversation("pair:aya")?.lastReadAt)
    }

    @Test
    fun theConversationListFollowsTheNewestMessage() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", thread = "pair:aya", text = "older", at = 1L), "Aya", true)
        dao.record(message("m2", thread = "ip:192.168.1.9", text = "newer", at = 9L), "Browser", true)

        val list = dao.conversations().first()
        assertEquals(listOf("ip:192.168.1.9", "pair:aya"), list.map { it.threadId })
        assertEquals("newer", list.first().lastText)
    }

    @Test
    fun theRoomThreadIsStoredUnderOneKey() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", thread = null, text = "anyone there"), "Room", true)

        assertEquals(1, dao.messagesIn(null, limit = 10, offset = 0).first().size)
        assertNotNull(dao.findConversation(ChatMessageDao.ROOM_KEY))
    }

    @Test
    fun aSentMessageBecomesReadOnlyOnThePeerThatAcknowledgesIt() = runBlocking {
        val dao = open().chatMessageDao()
        dao.record(message("m1", text = "written by me", at = 1L, mine = "me"), "Aya", false)
        dao.record(message("m2", text = "written by you", at = 2L, mine = "you"), "Aya", true)

        assertNull(dao.findById("m1")?.readAt)
        val mine = dao.unreadSentIds(threadId = "pair:aya", through = 3L, mine = "me")
        assertEquals(listOf("m1"), mine)

        dao.markReadOnPeer(mine, at = 3L)
        assertEquals(3L, dao.findById("m1")?.readAt)
        assertEquals(emptyList<String>(), dao.unreadSentIds("pair:aya", 3L, "me"))
    }

    private fun message(
        id: String,
        thread: String? = "pair:aya",
        text: String,
        at: Long = 1_700_000_000_000L,
        mine: String = ""
    ) = ChatMessageEntity(
        id = id,
        threadId = thread,
        sender = "Aya",
        senderDeviceId = mine,
        text = text,
        timestamp = at
    )
}
