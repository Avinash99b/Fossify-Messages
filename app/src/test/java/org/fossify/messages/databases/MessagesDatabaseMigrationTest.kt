package org.fossify.messages.databases

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MessagesDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "ai-migration-test.db"

    @Test
    fun migratesUpstreamV11AndPreservesRows() {
        createV11Database()

        val room = openMigratedDatabase()
        try {
            room.openHelper.writableDatabase.query(
                "SELECT ai_notification_reasoning, ai_category FROM messages WHERE id = 10"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            room.openHelper.writableDatabase.query(
                "SELECT ai_category FROM conversations WHERE thread_id = 1"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
            room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            room.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migratesInterimV11ThatAlreadyHasReasoningColumn() {
        createV11Database(alreadyHasReasoning = true)

        val room = openMigratedDatabase()
        try {
            room.openHelper.writableDatabase.query(
                "SELECT ai_category FROM messages WHERE id = 10"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
        } finally {
            room.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun openMigratedDatabase(): MessagesDatabase {
        return Room.databaseBuilder(context, MessagesDatabase::class.java, databaseName)
            .addMigrations(MessagesDatabase.MIGRATION_11_12)
            .build()
    }

    private fun createV11Database(alreadyHasReasoning: Boolean = false) {
        context.deleteDatabase(databaseName)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            V11_SCHEMA.forEach(db::execSQL)
            if (alreadyHasReasoning) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN " +
                        "ai_notification_reasoning TEXT NOT NULL DEFAULT ''"
                )
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                    "VALUES (42, '$V11_IDENTITY_HASH')"
            )
            db.execSQL(
                "INSERT INTO conversations " +
                    "(thread_id, snippet, date, read, title, photo_uri, is_group_conversation, " +
                    "phone_number, is_scheduled, uses_custom_title, archived, unread_count) " +
                    "VALUES (1, 'hi', 1, 0, 'Alice', '', 0, '123', 0, 0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id, body, type, status, participants, date, read, thread_id, is_mms, " +
                    "attachment, sender_phone_number, sender_name, sender_photo_uri, " +
                    "subscription_id, is_scheduled) " +
                    "VALUES (10, 'hello', 1, 0, '[]', 1, 0, 1, 0, NULL, '123', 'Alice', '', -1, 0)"
            )
            db.version = 11
        } finally {
            db.close()
        }
    }

    private companion object {
        const val V11_IDENTITY_HASH = "f9077f0a48a1e07dc2ddb02393e34f98"

        val V11_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `conversations` (`thread_id` INTEGER NOT NULL, " +
                "`snippet` TEXT NOT NULL, `date` INTEGER NOT NULL, `read` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `photo_uri` TEXT NOT NULL, " +
                "`is_group_conversation` INTEGER NOT NULL, `phone_number` TEXT NOT NULL, " +
                "`is_scheduled` INTEGER NOT NULL, `uses_custom_title` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, " +
                "PRIMARY KEY(`thread_id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_thread_id` " +
                "ON `conversations` (`thread_id`)",
            "CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "`message_id` INTEGER NOT NULL, `uri_string` TEXT NOT NULL, " +
                "`mimetype` TEXT NOT NULL, `width` INTEGER NOT NULL, " +
                "`height` INTEGER NOT NULL, `filename` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attachments_message_id` " +
                "ON `attachments` (`message_id`)",
            "CREATE TABLE IF NOT EXISTS `message_attachments` (`id` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, `attachments` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER NOT NULL, " +
                "`body` TEXT NOT NULL, `type` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                "`participants` TEXT NOT NULL, `date` INTEGER NOT NULL, `read` INTEGER NOT NULL, " +
                "`thread_id` INTEGER NOT NULL, `is_mms` INTEGER NOT NULL, `attachment` TEXT, " +
                "`sender_phone_number` TEXT NOT NULL, `sender_name` TEXT NOT NULL, " +
                "`sender_photo_uri` TEXT NOT NULL, `subscription_id` INTEGER NOT NULL, " +
                "`is_scheduled` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_messages_thread_id_date` " +
                "ON `messages` (`thread_id`, `date`)",
            "CREATE TABLE IF NOT EXISTS `recycle_bin_messages` (`id` INTEGER NOT NULL, " +
                "`deleted_ts` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recycle_bin_messages_id` " +
                "ON `recycle_bin_messages` (`id`)",
            "CREATE TABLE IF NOT EXISTS `drafts` (`thread_id` INTEGER NOT NULL, " +
                "`body` TEXT NOT NULL, `date` INTEGER NOT NULL, PRIMARY KEY(`thread_id`))",
        )
    }
}
