package com.pulsedrop.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pulsedrop.app.data.AppDatabase
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.server.ServerService
import com.pulsedrop.app.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class TransferManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val database = AppDatabase.getDatabase(context)
    private val historyDao = database.transferHistoryDao()

    private val _activeTransfers = MutableStateFlow<Map<String, TransferItem>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, TransferItem>> = _activeTransfers.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    fun onTransferStarted(
        transferId: String,
        fileName: String,
        fileSize: Long,
        direction: TransferDirection,
        remoteDevice: String,
        clientIp: String = ""
    ) {
        val item = TransferItem(
            id = transferId,
            fileName = fileName,
            fileSize = fileSize,
            bytesTransferred = 0L,
            speedBytesPerSec = 0L,
            direction = direction,
            status = TransferStatus.IN_PROGRESS,
            progress = 0f,
            remoteDevice = remoteDevice,
            clientIp = clientIp
        )
        _activeTransfers.update { it + (transferId to item) }
        ServerService.updateProgress(context, fileName, 0, "0 KB/s")
    }

    fun onTransferProgress(
        transferId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        _activeTransfers.update { map ->
            val current = map[transferId] ?: return@update map
            val progress = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
            val percent = (progress * 100).toInt()
            val speedStr = NetworkUtils.formatSpeed(speedBytesPerSec)
            ServerService.updateProgress(context, current.fileName, percent, speedStr)
            map + (transferId to current.copy(
                bytesTransferred = bytesTransferred,
                speedBytesPerSec = speedBytesPerSec,
                progress = progress
            ))
        }
    }

    fun onTransferCompleted(
        transferId: String,
        fileName: String,
        fileSize: Long,
        localFilePath: String?,
        direction: TransferDirection,
        remoteDevice: String,
        clientIp: String = ""
    ) {
        ServerService.updateProgress(context, fileName, 100, "Done", isFinished = true)
        _activeTransfers.update { map ->
            val current = map[transferId] ?: return@update map
            map + (transferId to current.copy(
                status = TransferStatus.COMPLETED,
                progress = 1f,
                bytesTransferred = fileSize,
                localFilePath = localFilePath,
                clientIp = clientIp
            ))
        }

        // Persist to Room history
        scope.launch(Dispatchers.IO) {
            val entity = TransferHistoryEntity(
                id = transferId,
                fileName = fileName,
                fileSize = fileSize,
                direction = direction.name,
                status = TransferStatus.COMPLETED.name,
                remoteDevice = remoteDevice,
                clientIp = clientIp,
                localFilePath = localFilePath,
                mimeType = null,
                timestamp = System.currentTimeMillis()
            )
            try {
                historyDao.insertTransfer(entity)
            } catch (e: Exception) {
                // History is best-effort: a full or corrupted DB must not crash a finished transfer.
                e.printStackTrace()
            }
        }
    }

    fun onTransferFailed(
        transferId: String,
        fileName: String,
        error: String,
        direction: TransferDirection
    ) {
        ServerService.updateProgress(context, fileName, 0, "Error", isFailed = true)
        var failedRow: TransferItem? = null
        _activeTransfers.update { map ->
            val current = map[transferId] ?: return@update map
            failedRow = current.copy(
                status = TransferStatus.FAILED,
                errorMessage = error
            )
            map + (transferId to failedRow!!)
        }
        // A failure that only lives in memory is a failure the user cannot review after a
        // restart; the Transfers page's Failed tab reads from history, so record it there.
        val row = failedRow ?: return
        scope.launch(Dispatchers.IO) {
            val entity = TransferHistoryEntity(
                id = transferId,
                fileName = fileName,
                fileSize = row.fileSize,
                direction = direction.name,
                status = TransferStatus.FAILED.name,
                remoteDevice = row.remoteDevice,
                clientIp = row.clientIp,
                localFilePath = row.localFilePath,
                mimeType = null,
                timestamp = System.currentTimeMillis(),
                errorMessage = error
            )
            try {
                historyDao.insertTransfer(entity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearFinishedTransfers() {
        _activeTransfers.update { map ->
            map.filterValues { it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.QUEUED }
        }
    }

    /**
     * Drops one finished row from the live list. This is dismissal of the UI record only —
     * running work is never touched, and no file on disk is deleted either way.
     */
    fun dismissFinished(transferId: String): Boolean {
        var removed = false
        _activeTransfers.update { map ->
            val item = map[transferId]
            if (item != null && item.status != TransferStatus.IN_PROGRESS && item.status != TransferStatus.QUEUED) {
                removed = true
                map - transferId
            } else {
                map
            }
        }
        return removed
    }

    /**
     * Sends local file URI to a remote peer via HTTP multipart upload.
     */
    fun sendFileToPeer(
        peer: PeerDevice,
        uri: Uri,
        pin: String? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val transferId = UUID.randomUUID().toString()

        // The ContentResolver metadata query is IPC + disk work, so it runs off the caller thread.
        scope.launch(Dispatchers.IO) {
            val fileMetadata = try {
                getUriMetadata(uri)
            } catch (e: Exception) {
                onTransferFailed(transferId, "file", e.message ?: "Cannot read file", TransferDirection.OUTGOING)
                withContext(Dispatchers.Main) { onComplete(false, e.message) }
                return@launch
            }
            val fileName = fileMetadata.first
            val fileSize = fileMetadata.second

            onTransferStarted(transferId, fileName, fileSize, TransferDirection.OUTGOING, peer.name)

            try {
                val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
                val url = "http://${peer.ip}:${peer.port}/upload?filename=$encodedName"

                val countingBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = fileSize

                    override fun writeTo(sink: BufferedSink) {
                        var inputStream: InputStream? = null
                        try {
                            inputStream = context.contentResolver.openInputStream(uri)
                                ?: throw IllegalStateException("Cannot open uri")
                            val source = inputStream.source()
                            val buffer = okio.Buffer()
                            var totalBytesRead = 0L
                            var lastTime = System.currentTimeMillis()
                            var lastBytes = 0L

                            while (true) {
                                val read = source.read(buffer, 64 * 1024)
                                if (read == -1L) break
                                sink.write(buffer, read)
                                totalBytesRead += read

                                val now = System.currentTimeMillis()
                                val diff = now - lastTime
                                if (diff >= 300 || totalBytesRead == fileSize) {
                                    val speed = if (diff > 0) ((totalBytesRead - lastBytes) * 1000L) / diff else 0L
                                    onTransferProgress(transferId, totalBytesRead, fileSize, speed)
                                    lastTime = now
                                    lastBytes = totalBytesRead
                                }
                            }
                        } finally {
                            inputStream?.close()
                        }
                    }
                }

                val multipartBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, countingBody)

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(multipartBuilder.build())

                if (!pin.isNullOrBlank()) {
                    requestBuilder.addHeader("X-Pulse-Auth", pin)
                }

                val response = okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        onTransferCompleted(transferId, fileName, fileSize, null, TransferDirection.OUTGOING, peer.name)
                        withContext(Dispatchers.Main) {
                            onComplete(true, null)
                        }
                    } else {
                        val msg = "Server responded with code ${resp.code}"
                        onTransferFailed(transferId, fileName, msg, TransferDirection.OUTGOING)
                        withContext(Dispatchers.Main) {
                            onComplete(false, msg)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onTransferFailed(transferId, fileName, e.message ?: "Failed", TransferDirection.OUTGOING)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.message)
                }
            }
        }
    }

    /**
     * Sends an already-encrypted cipher file to a paired peer.
     * The receiver's server decrypts it with the shared pairing key,
     * so the bytes on the wire are end-to-end encrypted.
     */
    fun sendPairEncryptedFile(
        peer: PeerDevice,
        pid: String,
        cipherFile: java.io.File,
        originalName: String,
        plainSize: Long,
        proofFor: (contentLength: Long) -> String? = { null },
        onComplete: (Boolean, String?) -> Unit
    ) {
        val transferId = java.util.UUID.randomUUID().toString()
        val cipherSize = cipherFile.length()

        onTransferStarted(transferId, originalName, plainSize, TransferDirection.OUTGOING, "Paired • ${peer.name}")

        scope.launch(Dispatchers.IO) {
            try {
                val encodedName = java.net.URLEncoder.encode(originalName, "UTF-8")
                val url = "http://${peer.ip}:${peer.port}/upload?filename=$encodedName"

                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = cipherSize

                    override fun writeTo(sink: BufferedSink) {
                        java.io.FileInputStream(cipherFile).use { fis ->
                            val source = fis.source()
                            val buffer = okio.Buffer()
                            var total = 0L
                            var lastTime = System.currentTimeMillis()
                            var lastBytes = 0L
                            while (true) {
                                val read = source.read(buffer, 64 * 1024)
                                if (read == -1L) break
                                sink.write(buffer, read)
                                total += read
                                val now = System.currentTimeMillis()
                                val diff = now - lastTime
                                if (diff >= 300 || total == cipherSize) {
                                    val speed = if (diff > 0) ((total - lastBytes) * 1000L) / diff else 0L
                                    onTransferProgress(transferId, total, cipherSize, speed)
                                    lastTime = now
                                    lastBytes = total
                                }
                            }
                        }
                    }
                }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", originalName, body)
                    .build()

                // The proof signs the exact wire length, so it can only be made once the
                // multipart framing is known.
                val proof = proofFor(multipart.contentLength())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Pair-Id", pid)
                    .addHeader("X-Pair-Crypt", "1")
                    .apply { if (proof != null) addHeader("X-Pair-Auth", proof) }
                    .post(multipart)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        onTransferCompleted(transferId, originalName, plainSize, null, TransferDirection.OUTGOING, "Paired • ${peer.name}")
                        withContext(Dispatchers.Main) { onComplete(true, null) }
                    } else {
                        val msg = if (response.code == 401) {
                            "${peer.name} rejected the pairing key - re-pair from the radar"
                        } else {
                            "Peer responded with code ${response.code}"
                        }
                        onTransferFailed(transferId, originalName, msg, TransferDirection.OUTGOING)
                        withContext(Dispatchers.Main) { onComplete(false, msg) }
                    }
                }
            } catch (e: Exception) {
                onTransferFailed(transferId, originalName, e.message ?: "Failed", TransferDirection.OUTGOING)
                withContext(Dispatchers.Main) { onComplete(false, e.message) }
            } finally {
                try { cipherFile.delete() } catch (e: Exception) { }
            }
        }
    }

    private fun getUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = -1L

        if (uri.scheme == "content") {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) name = it.getString(nameIndex) ?: name
                        if (sizeIndex != -1) size = it.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            name = uri.lastPathSegment ?: name
        }

        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    size = it.statSize
                }
            } catch (e: Exception) {
                size = 0L
            }
        }

        return Pair(name, size.coerceAtLeast(0L))
    }
}
