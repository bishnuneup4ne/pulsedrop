package com.pulsedrop.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Captures the screen for PulseDrop calls. Required foreground service type
 * for MediaProjection on modern Android; frames are pushed to CallEngine.
 */
class ProjectionCaptureService : Service() {

    companion object {
        @Volatile
        var engine: CallEngine? = null

        fun arm(context: Context) {
            // Android 14+ refuses a mediaProjection foreground claim until the user has handed
            // over a projection token, and answers with SecurityException. The claim therefore
            // happens in onStartCommand for ACTION_START, by which time the app is foreground
            // again. Below 14 there is no such restriction, and the slot must be taken before
            // the picker runs: Android 15 hands the result back only once the captured app is in
            // front, which leaves PulseDrop cached and frozen, and the service never reaches
            // startForeground() before ForegroundServiceDidNotStartInTimeException kills the
            // whole process.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            startWith(context, Intent(context, ProjectionCaptureService::class.java)
                .setAction(ACTION_ARM))
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ProjectionCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra("resultCode", resultCode)
                .putExtra("captureData", data)
            startWith(context, intent)
        }

        private fun startWith(context: Context, intent: Intent) {
            try {
                // startForegroundService only exists on API 26+; on older devices a plain
                // startService is fine because there is no background-start restriction.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                try { context.startService(intent) } catch (ex: Exception) { }
            }
        }

        fun stop(context: Context) {
            // stopService is always allowed from the background; startService for a service
            // that already died throws IllegalStateException and would swallow the teardown.
            try {
                context.stopService(Intent(context, ProjectionCaptureService::class.java))
            } catch (e: Exception) {
                // service already stopped
            }
        }

        private const val ACTION_START = "start"
        private const val ACTION_ARM = "arm"
        private const val ARM_TIMEOUT_MS = 60_000L
        private const val CHANNEL_ID = "localdrop_screen_share"
        private const val NOTIF_ID = 4455

        // JPEG over a LAN socket: 1920 on the long edge at q82 costs ~180-260 KB a frame, so at
        // 8 fps the uplink stays near 15 Mbps - inside hotspot capacity, and far above the
        // 1280/q78 budget that read as 480p once the call view was maximised.
        private const val MAX_EDGE = 1920
        private const val FRAME_QUALITY = 82
        private const val FRAME_INTERVAL_MS = 125L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var grabThread: Thread? = null
    @Volatile private var capW = 0
    @Volatile private var capH = 0
    @Volatile private var grabbing = false

    private val disarm = Runnable {
        if (projection == null) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_ARM) {
            handler.postDelayed(disarm, ARM_TIMEOUT_MS)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(disarm)
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("captureData")
        if (data != null) setup(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen share", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PulseDrop is sharing your screen")
            .setContentText("Tap end call to stop sharing.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun setup(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data) ?: return
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // The user stopped sharing from the system shade, or the session was
                    // revoked. Without this the service keeps a dead projection and the
                    // call UI still says "sharing".
                    Handler(Looper.getMainLooper()).post {
                        engine?.sharingStopped()
                        stopSelf()
                    }
                }
            }, null)

            if (!buildCapture()) {
                teardown()
                return
            }

            grabbing = true
            grabThread = Thread {
                while (grabbing) {
                    val image = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
                    if (image != null) {
                        try {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val rowStride = plane.rowStride
                            val pw = minOf(rowStride / 4, image.width)
                            val ph = image.height
                            val padded = ByteArray(rowStride * ph)
                            buffer.get(padded, 0, minOf(buffer.remaining(), padded.size))
                            val colors = IntArray(pw * ph)
                            for (y in 0 until ph) {
                                val rowBase = y * rowStride
                                for (x in 0 until pw) {
                                    val i = rowBase + x * 4
                                    if (i + 3 >= padded.size) break
                                    // Plane bytes are R,G,B,A; createBitmap wants the int as
                                    // A<<24|R<<16|G<<8|B. Packing them in memory order swaps red
                                    // and blue and is the green/magenta cast people reported.
                                    colors[y * pw + x] = ((padded[i].toInt() and 0xFF) shl 16) or
                                            ((padded[i + 1].toInt() and 0xFF) shl 8) or
                                            (padded[i + 2].toInt() and 0xFF) or
                                            ((padded[i + 3].toInt() and 0xFF) shl 24)
                                }
                            }
                            val full = Bitmap.createBitmap(colors, 0, pw, pw, ph, Bitmap.Config.ARGB_8888)
                            val scaled = if (full.width == capW && full.height == capH) full
                            else Bitmap.createScaledBitmap(full, capW, capH, true)
                            val bos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, bos)
                            engine?.pushScreenFrame(bos.toByteArray())
                            if (scaled !== full) scaled.recycle()
                            full.recycle()
                        } catch (e: Exception) {
                            // skip frame
                        }
                        image.close()
                    }
                    Thread.sleep(FRAME_INTERVAL_MS)
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            teardown()
        }
    }

    /**
     * A Service's `resources.displayMetrics` is the last configuration the app saw, so sharing
     * straight after a rotation (or from the maximised call view) built a display sized for the
     * old orientation: the frames came out letterboxed and read as 480p. The real display size
     * is asked for instead, and a config change rebuilds the capture at the new aspect.
     */
    private fun realDisplaySize(): IntArray {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            intArrayOf(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            intArrayOf(dm.widthPixels, dm.heightPixels)
        }
    }

    /** (Re)creates the ImageReader + VirtualDisplay for the current orientation. */
    private fun buildCapture(): Boolean {
        val proj = projection ?: return false
        val size = realDisplaySize()
        val dpi = resources.displayMetrics.densityDpi
        val scale = (MAX_EDGE.toFloat() / maxOf(size[0], size[1])).coerceAtMost(1f)
        val w = even((size[0] * scale).roundToInt())
        val h = even((size[1] * scale).roundToInt())
        val nextReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = display
        capW = w
        capH = h
        reader = nextReader
        display = proj.createVirtualDisplay(
            "PulseDropShare", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            nextReader.surface, null, null
        )
        old?.release()
        return true
    }

    private fun even(v: Int): Int = (v.coerceAtLeast(16) / 2) * 2

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) return
        handler.post {
            // Frames keep flowing from the old surface if the rebuild fails, which is better
            // than tearing the share down over an orientation flip.
            try { buildCapture() } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        grabbing = false
        try { grabThread?.join(500) } catch (e: Exception) { }
        grabThread = null
        try { display?.release() } catch (e: Exception) { }
        display = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
    }
}
