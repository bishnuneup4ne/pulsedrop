package com.pulsedrop.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferHistoryEntity>>

    @Query("SELECT * FROM transfer_history WHERE clientIp = :clientIp ORDER BY timestamp DESC")
    fun getTransfersByIp(clientIp: String): Flow<List<TransferHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferHistoryEntity)

    @Query("DELETE FROM transfer_history")
    suspend fun clearHistory()

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: String)
}
