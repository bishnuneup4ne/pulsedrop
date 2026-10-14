package com.pulsedrop.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pulsedrop.app.MainActivity

class ServerService : Service() {

    private var currentServerUrl: String = "http://127.0.0.1:8080"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        // The system can stop a dataSync service on its own schedule (Android 15 quota).
        // Never keep serving from the background without the foreground notification.
        stopHostedServer()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            // Stop the HTTP server too: leaving it running behind a dead notification means
            // Android kills the process at an arbitrary time and the server dies mid-transfer.
            stopHostedServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_UPDATE_PROGRESS) {
            val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "File"
            val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
            val speed = intent.getStringExtra(EXTRA_SPEED) ?: "0 KB/s"
            val isFinished = intent.getBooleanExtra(EXTRA_IS_FINISHED, false)
            val isFailed = intent.getBooleanExtra(EXTRA_IS_FAILED, false)

            val notification = buildProgressNotification(fileName, progress, speed, isFinished, isFailed)
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
            return START_STICKY
        }

        currentServerUrl = intent?.getStringExtra(EXTRA_URL) ?: currentServerUrl
        val notification = buildIdleNotification(currentServerUrl)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PulseDrop Transfer Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the PulseDrop local file server active and tracks transfers"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildIdleNotification(serverUrl: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PulseDrop server is running")
            .setContentText("Listening on $serverUrl")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun buildProgressNotification(
        fileName: String,
        progress: Int,
        speed: String,
        isFinished: Boolean,
        isFailed: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            isFailed -> "Transfer Failed: $fileName"
            isFinished -> "Transfer Complete: $fileName"
            else -> "Transferring: $fileName"
        }

        val content = when {
            isFailed -> "Connection dropped or interrupted"
            isFinished -> "100% • Successfully transferred"
            else -> "$progress% • $speed"
        }

        val icon = when {
            isFinished -> android.R.drawable.stat_sys_download_done
            else -> android.R.drawable.stat_sys_upload
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setContentIntent(openPendingIntent)
            .setOngoing(!isFinished && !isFailed)

        if (!isFinished && !isFailed) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "webdrop_server_channel"

        /**
         * The running server. It is owned here, not by the ViewModel, so a ViewModel
         * recreation can reattach instead of failing to bind a port someone else holds.
         */
        @Volatile
        var hostedServer: PulseDropServer? = null

        fun stopHostedServer() {
            val server = hostedServer
            hostedServer = null
            try {
                server?.stop()
            } catch (e: Exception) {
                // already stopped
            }
        }

        const val NOTIFICATION_ID = 4040
        const val ACTION_START = "com.pulsedrop.app.webdrop.START_SERVER"
        const val ACTION_STOP = "com.pulsedrop.app.webdrop.STOP_SERVER"
        const val ACTION_UPDATE_PROGRESS = "com.pulsedrop.app.webdrop.UPDATE_PROGRESS"

        const val EXTRA_URL = "extra_server_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_IS_FINISHED = "extra_is_finished"
        const val EXTRA_IS_FAILED = "extra_is_failed"

        fun startService(context: Context, serverUrl: String) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, serverUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(
            context: Context,
            fileName: String,
            progress: Int,
            speed: String,
            isFinished: Boolean = false,
            isFailed: Boolean = false
        ) {
            try {
                val intent = Intent(context, ServerService::class.java).apply {
                    action = ACTION_UPDATE_PROGRESS
                    putExtra(EXTRA_FILE_NAME, fileName)
                    putExtra(EXTRA_PROGRESS, progress)
                    putExtra(EXTRA_SPEED, speed)
                    putExtra(EXTRA_IS_FINISHED, isFinished)
                    putExtra(EXTRA_IS_FAILED, isFailed)
                }
                context.startService(intent)
            } catch (e: Exception) {
                // If service is stopped, ignore progress notification
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
