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
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Swiping PulseDrop away in Recents must not take the server with it: the user stops
     * serving from the notification's Stop action or the in-app toggle, never by closing the
     * window. A foreground service already outlives the task, so there is nothing to do here
     * beyond making that intent explicit and never calling stopSelf().
     */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    /**
     * A dozing phone stops answering the network and a transfer stalls mid-file, but a lock
     * held for the server's whole lifetime would eat the night's battery. So the app asks for
     * CPU only while a transfer or a call is live, and the timeout is a safety net.
     */
    private fun setKeepAwake(keep: Boolean) {
        if (keep) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PulseDrop::server")
                .apply { acquire(60 * 60 * 1000L) }
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    override fun onDestroy() {
        setKeepAwake(false)
        // Without the foreground notification the system is free to kill the process at an
        // arbitrary time - but only tear down the server this instance actually brought up.
        // Toggling the server off and on destroys the old instance asynchronously, after the
        // replacement is already published, and stopping that one leaves the UI showing
        // "Live" over a closed socket.
        if (owner === this) {
            owner = null
            stopHostedServer()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // START_STICKY redelivery after the system killed us. The server died with the
            // process, so don't post a "running" notification over a closed port: only stay
            // up when a live server actually exists in this process.
            if (hostedServer?.isAlive == true) {
                val notification = buildIdleNotification(currentServerUrl)
                startForeground(NOTIFICATION_ID, notification)
                owner = this
                return START_STICKY
            }
            // This redelivery counts as a foreground-service start, so backing out still has
            // to answer with startForeground() first — stopSelf() alone makes the system kill
            // the process ten seconds later with ForegroundServiceDidNotStartInTimeException.
            startForeground(NOTIFICATION_ID, buildIdleNotification(currentServerUrl))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_KEEP_AWAKE) {
            setKeepAwake(intent?.getBooleanExtra(EXTRA_KEEP_AWAKE, false) == true)
            return START_STICKY
        }

        if (action == ACTION_STOP) {
            // Stop the HTTP server too: leaving it running behind a dead notification means
            // Android kills the process at an arbitrary time and the server dies mid-transfer.
            userStopped = true
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
        userStopped = false
        val notification = buildIdleNotification(currentServerUrl)
        startForeground(NOTIFICATION_ID, notification)
        owner = this

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
        val openIntent = Intent(this, MainActivity::class.java).apply {
            // A completed receipt is a file: tapping the notification lands on My Files, where it lives.
            if (isFinished && !isFailed) putExtra("com.pulsedrop.app.SECTION", "my_files")
        }
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

        /** True when the last shutdown came from the notification's Stop action. */
        @Volatile
        var userStopped = false

        /** The service instance currently responsible for [hostedServer]; see onDestroy. */
        @Volatile
        private var owner: ServerService? = null

        /**
         * Publish a freshly started server. Ownership deliberately goes to null here: the
         * instance that is about to be destroyed must not treat this server as its own.
         */
        fun publishServer(server: PulseDropServer) {
            hostedServer = server
            owner = null
        }

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
        const val ACTION_KEEP_AWAKE = "com.pulsedrop.app.webdrop.KEEP_AWAKE"

        const val EXTRA_KEEP_AWAKE = "extra_keep_awake"

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

        /** Hold the CPU while a transfer or a call is live; idle is left to doze. */
        fun keepAwake(context: Context, keep: Boolean) {
            try {
                val intent = Intent(context, ServerService::class.java).apply {
                    action = ACTION_KEEP_AWAKE
                    putExtra(EXTRA_KEEP_AWAKE, keep)
                }
                context.startService(intent)
            } catch (e: Exception) {
                // Not serving right now; nothing to keep awake.
            }
        }

        fun stopService(context: Context) {            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
