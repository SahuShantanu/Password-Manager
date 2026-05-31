package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries WHERE isDeleted = 0 ORDER BY modifiedDate DESC")
    fun getAllEntries(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE isDeleted = 1 ORDER BY deletedDate DESC")
    fun getDeletedEntries(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: Int): VaultEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: VaultEntry): Long

    @Update
    suspend fun updateEntry(entry: VaultEntry)

    @Delete
    suspend fun deleteEntry(entry: VaultEntry)

    @Query("DELETE FROM vault_entries WHERE isDeleted = 1")
    suspend fun clearRecycleBin()

    @Query("DELETE FROM vault_entries WHERE isDeleted = 1 AND deletedDate < :thresholdTimestamp")
    suspend fun purgeOldDeletedEntries(thresholdTimestamp: Long)
}
