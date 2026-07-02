package com.example.androidllm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatEntity::class, MessageEntity::class, ScheduleEntity::class,
        DocChunkEntity::class, MemoryEntity::class, MemoryChunkEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun docDao(): DocDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        /** v1 → v2: add the schedules table for proactive briefings (keeps existing chats). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `hour` INTEGER NOT NULL,
                        `minute` INTEGER NOT NULL,
                        `daysMask` INTEGER NOT NULL DEFAULT 0,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `toolsEnabled` INTEGER NOT NULL DEFAULT 1,
                        `lastRunAt` INTEGER,
                        `nextRunAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3: add the doc_chunks vector store for on-device RAG. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `doc_chunks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `path` TEXT NOT NULL,
                        `ord` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `mtime` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_doc_chunks_path` ON `doc_chunks` (`path`)")
            }
        }

        /** v3 → v4: add the memories + memory_chunks tables for Ambient Memory. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sourceApp` TEXT,
                        `uri` TEXT,
                        `title` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `pinned` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_chunks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `memoryId` INTEGER NOT NULL,
                        `ord` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        FOREIGN KEY(`memoryId`) REFERENCES `memories`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chunks_memoryId` ON `memory_chunks` (`memoryId`)")
            }
        }

        fun get(context: Context): ChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "androidllm-chats.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
