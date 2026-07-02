package com.example.androidllm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A file's indexing status: path, chunk count, and last-modified time recorded at index. */
data class DocFileInfo(
    val path: String,
    val chunks: Int,
    val mtime: Long,
)

@Dao
interface DocDao {

    @Insert
    suspend fun insertAll(chunks: List<DocChunkEntity>)

    @Query("DELETE FROM doc_chunks WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM doc_chunks")
    suspend fun clear()

    @Query("SELECT * FROM doc_chunks")
    suspend fun allChunks(): List<DocChunkEntity>

    @Query("SELECT COUNT(*) FROM doc_chunks")
    fun observeChunkCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT path) FROM doc_chunks")
    fun observeFileCount(): Flow<Int>

    /** Recorded mtime for a path (max over its chunks), or null if not indexed. */
    @Query("SELECT MAX(mtime) FROM doc_chunks WHERE path = :path")
    suspend fun indexedMtime(path: String): Long?

    @Query("SELECT path, COUNT(*) AS chunks, MAX(mtime) AS mtime FROM doc_chunks GROUP BY path ORDER BY path")
    fun observeFiles(): Flow<List<DocFileInfo>>
}
