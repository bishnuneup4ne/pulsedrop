package com.pulsedrop.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransferHistoryEntity::class,
        ChatMessageEntity::class,
        ChatMessageFts::class,
        ConversationEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** The error column arrived at v3; history is the user's record and must survive it. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfer_history ADD COLUMN errorMessage TEXT")
            }
        }

        /**
         * Chat history arrived at v4, and this adds nothing but new tables.
         *
         * The statements are copied from `app/schemas/.../4.json` rather than written by hand: Room
         * compares every column, index and the FTS table's own definition against its expected schema
         * after migrating, and a hand-typed guess fails at runtime on the first device that upgrades.
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `chat_message` (`id` TEXT NOT NULL, `threadId` TEXT, " +
                        "`sender` TEXT NOT NULL, `senderDeviceId` TEXT NOT NULL, `clientIp` TEXT, " +
                        "`text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`encrypted` INTEGER NOT NULL, " +
                        "`attachmentId` TEXT, `attachmentName` TEXT, `attachmentSize` INTEGER NOT NULL, " +
                        "`attachmentMime` TEXT, `replyTo` TEXT, `replySender` TEXT, `replyText` TEXT, " +
                        "`deleted` INTEGER NOT NULL, `reaction` TEXT, `reactionSender` TEXT, " +
                        "`readAt` INTEGER, `editedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX `index_chat_message_threadId_timestamp` " +
                        "ON `chat_message` (`threadId`, `timestamp`)"
                )
                db.execSQL(
                    "CREATE INDEX `index_chat_message_attachmentId` ON `chat_message` (`attachmentId`)"
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE `chat_message_fts` USING FTS4(" +
                        "`message_id` TEXT NOT NULL, `sender` TEXT NOT NULL, `body` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE `conversation` (`threadId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`lastMessageAt` INTEGER NOT NULL, `lastSenderId` TEXT NOT NULL, " +
                        "`lastSender` TEXT NOT NULL, `lastText` TEXT NOT NULL, " +
                        "`unreadCount` INTEGER NOT NULL, `lastReadAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`threadId`))"
                )
            }
        }

        /**
         * Opens the database, migrating forward when an older one is on the disk.
         *
         * There is deliberately no destructive fallback here. It used to be
         * `fallbackToDestructiveMigration(dropAllTables = true)`, which meant that adding an entity
         * without registering a migration wiped `transfer_history` - the user's record of every send
         * and receive - silently, on the device, with nothing in the build to say so. A missed
         * migration now throws on open instead, which is the correct failure: loud, on the developer's
         * bench, before anyone's history is involved.
         *
         * The oldest version this path can bring forward is 2; every published release shipped at 2 or
         * 3, and a database older than that is wiped by Room's own version check rather than quietly
         * rebuilt.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        /**
         * The same database without the process-wide singleton. A test that wants to watch an old
         * file be upgraded needs its own copy, and one file per process is the opposite of that.
         */
        internal fun buildDatabase(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DB_NAME
        )
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()

        internal const val DB_NAME = "webdrop_database"
    }
}
