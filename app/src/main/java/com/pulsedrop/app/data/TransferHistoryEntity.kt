package com.pulsedrop.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferHistoryEntity(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val direction: String, // INCOMING or OUTGOING
    val status: String,    // COMPLETED or FAILED
    val remoteDevice: String,
    val clientIp: String? = null,
    val localFilePath: String?,
    val mimeType: String?,
    val timestamp: Long
)
