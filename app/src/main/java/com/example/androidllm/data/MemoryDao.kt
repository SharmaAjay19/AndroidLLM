package com.example.androidllm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A memory row joined with its chunk count (for the browser list). */
data class MemoryListItem(
    val id: Long,
    val type: String,
    val title: String,
    val text: String,
    val uri: String?,
    val createdAt: Long,
    val pinned: Boolean,
)

@Dao
interface MemoryDao {

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Insert
    suspend fun insertChunks(chunks: List<MemoryChunkEntity>)

    @Transaction
    suspend fun insertMemoryWithChunks(
        memory: MemoryEntity,
        chunksFor: (Long) -> List<MemoryChunkEntity>,
    ): Long {
        val id = insertMemory(memory)
        insertChunks(chunksFor(id))
        return id
    }

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clearAll()

    @Query("UPDATE memories SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("SELECT * FROM memory_chunks")
    suspend fun allChunks(): List<MemoryChunkEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getMemory(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories ORDER BY pinned DESC, createdAt DESC")
    suspend fun allMemoriesOnce(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT id, type, title, text, uri, createdAt, pinned FROM memories
        WHERE (:query = ''
               OR title LIKE '%' || :query || '%'
               OR text LIKE '%' || :query || '%')
        ORDER BY pinned DESC, createdAt DESC
        """
    )
    fun observeMemories(query: String): Flow<List<MemoryListItem>>
}
