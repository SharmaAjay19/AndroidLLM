package com.example.androidllm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String, // "user" or "assistant"
    val content: String,
    val createdAt: Long,
)

/** Row used to render the chat list: chat header plus a snippet of its last message. */
data class ChatListItem(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
    @ColumnInfo(name = "snippet") val snippet: String?,
)

/**
 * A saved prompt that runs automatically on a schedule (proactive briefings).
 *
 * [daysMask] is a bitmask of weekdays: bit0 = Sunday … bit6 = Saturday. A value of 0 means
 * "every day". [hour]/[minute] are local wall-clock time. When [deliverAsChat] is true the
 * result is saved as a chat (always) and a notification is posted that opens it.
 */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val daysMask: Int = 0,
    val enabled: Boolean = true,
    val toolsEnabled: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
)

/**
 * One embedded chunk of a workspace document, for on-device RAG. [embedding] is an
 * L2-normalized float vector serialized little-endian (see [com.example.androidllm.Rag]).
 * [mtime] is the source file's last-modified time so unchanged files can be skipped on reindex.
 */
@Entity(
    tableName = "doc_chunks",
    indices = [Index("path")]
)
data class DocChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val ord: Int,
    val text: String,
    val embedding: ByteArray,
    val mtime: Long,
)

/**
 * A saved memory ("second brain" item): text/URL/image the user shared, dictated, or clipped.
 * The full text is embedded into one or more [MemoryChunkEntity] rows for semantic recall.
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,          // "text" | "url" | "image" | "voice"
    val sourceApp: String?,    // package that shared it, if known
    val uri: String?,          // original content URI / link, for tap-through
    val title: String,
    val text: String,
    val createdAt: Long,
    val pinned: Boolean = false,
)

/** An embedded chunk of a [MemoryEntity] (L2-normalized vector, little-endian blob). */
@Entity(
    tableName = "memory_chunks",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("memoryId")]
)
data class MemoryChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryId: Long,
    val ord: Int,
    val text: String,
    val embedding: ByteArray,
)
