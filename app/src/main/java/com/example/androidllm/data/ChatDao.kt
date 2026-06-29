package com.example.androidllm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Insert
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateChat(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchChat(id: Long, updatedAt: Long)

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChat(id: Long): ChatEntity?

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesOnce(chatId: Long): List<MessageEntity>

    /**
     * Chat list with last-message snippet. When [query] is blank, returns every chat;
     * otherwise returns chats whose title or any message content matches the query.
     */
    @Query(
        """
        SELECT c.id AS id,
               c.title AS title,
               c.updatedAt AS updatedAt,
               (SELECT m.content FROM messages m
                 WHERE m.chatId = c.id
                 ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS snippet
        FROM chats c
        WHERE (:query = ''
               OR c.title LIKE '%' || :query || '%'
               OR c.id IN (SELECT DISTINCT chatId FROM messages
                           WHERE content LIKE '%' || :query || '%'))
        ORDER BY c.updatedAt DESC
        """
    )
    fun observeChatList(query: String): Flow<List<ChatListItem>>
}
