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
