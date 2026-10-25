package com.pulsedrop.app.ui.components

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.pulsedrop.app.model.ChatAttachment
import com.pulsedrop.app.ui.theme.PulseStageColors
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun openExternally(context: android.content.Context, file: File, mime: String, label: String) {
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, label)) }
}

/** What kind of viewer a file needs. */
enum class PreviewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg")
private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "webm", "avi", "3gp")
private val AUDIO_EXT = setOf("mp3", "wav", "flac", "m4a", "ogg", "aac")
private val TEXT_EXT = setOf(
    "txt", "md", "json", "js", "ts", "py", "kt", "java", "html", "css", "sh",
    "xml", "csv", "log", "yml", "yaml", "ini", "conf", "gradle", "properties"
)

fun previewKindOf(name: String): PreviewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXT -> PreviewKind.IMAGE
        ext in VIDEO_EXT -> PreviewKind.VIDEO
        ext in AUDIO_EXT -> PreviewKind.AUDIO
        ext == "pdf" -> PreviewKind.PDF
        ext in TEXT_EXT -> PreviewKind.TEXT
        else -> PreviewKind.OTHER
    }
}

fun formatAttachmentSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.roundToInt()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}

/**
 * Full-screen viewer for a chat attachment: pinch/wheel zoom, pan, rotate, page
 * through a PDF, play video and audio, or read text. Anything it cannot render
 * inline offers an "open with" escape hatch.
 */
@Composable
fun AttachmentPreview(
    attachment: ChatAttachment,
    file: File?,
    onDismiss: () -> Unit
) {
    val stage = PulseStageColors
    val transform = remember { StageTransform() }
    val barPadding = WindowInsets.systemBars.asPaddingValues()
    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = stage.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = barPadding.calculateTopPadding(),
                        bottom = barPadding.calculateBottomPadding()
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("preview_close_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close preview", tint = stage.textPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.name,
                            color = stage.textPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatAttachmentSize(attachment.size) + "  ·  " +
                                attachment.name.substringAfterLast('.', "file").uppercase(),
                            color = stage.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        file == null -> PreviewMessage("This file is no longer on the device.")
                        !file.isFile -> PreviewMessage("This file is no longer on the device.")
                        else -> when (previewKindOf(attachment.name)) {
                            PreviewKind.IMAGE -> ZoomableImage(file, transform)
                            PreviewKind.VIDEO -> VideoStage(file)
                            PreviewKind.AUDIO -> AudioStage(file)
                            PreviewKind.PDF -> PdfStage(file, transform)
                            PreviewKind.TEXT -> TextStage(file)
                            PreviewKind.OTHER -> OtherStage(file, attachment)
                        }
                    }
                }

                if (file != null && file.isFile &&
                    previewKindOf(attachment.name) != PreviewKind.VIDEO &&
                    previewKindOf(attachment.name) != PreviewKind.AUDIO &&
                    previewKindOf(attachment.name) != PreviewKind.OTHER
                ) {
                    PreviewToolbar(transform, file, attachment)
                } else {
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

private class StageTransform {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var rotation by mutableFloatStateOf(0f)
        private set

    fun zoomBy(factor: Float) {
        scale = (scale * factor).coerceIn(0.2f, 16f)
        if (scale <= 1.001f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    fun pan(dx: Float, dy: Float) {
        if (scale > 1f) {
            offsetX += dx
            offsetY += dy
        }
    }

    fun rotate() { rotation = (rotation + 90f) % 360f }

    fun fit() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        rotation = 0f
    }
}

@Composable
private fun ZoomableStage(
    transform: StageTransform,
    content: @Composable (Modifier) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(transform) {
                detectTransformGestures { _, pan, zoom, _ ->
                    transform.zoomBy(zoom)
                    transform.pan(pan.x, pan.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content(Modifier.graphicsLayer {
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.offsetX
            translationY = transform.offsetY
            rotationZ = transform.rotation
        })
    }
}

@Composable
private fun ZoomableImage(file: File, transform: StageTransform) {
    var failed by remember(file) { mutableStateOf(false) }
    if (failed) {
        PreviewMessage("This image could not be opened.")
        return
    }
    ZoomableStage(transform) { modifier ->
        AsyncImage(
            model = file,
            contentDescription = "Image preview",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag("preview_image"),
            onError = { failed = true }
        )
    }
}

@Composable
private fun VideoStage(file: File) {
    var failed by remember(file) { mutableStateOf(false) }
    if (failed) {
        PreviewMessage("This video format is not supported by the device player.")
        return
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnErrorListener { _, _, _ -> failed = true; true }
                    setOnPreparedListener { start() }
                    setVideoURI(Uri.fromFile(file))
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .testTag("preview_video")
        )
    }
}

@Composable
private fun AudioStage(file: File) {
    val stage = PulseStageColors
    val colorScheme = MaterialTheme.colorScheme
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableIntStateOf(0) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        val created = MediaPlayer()
        player = created
        created.setOnPreparedListener { prepared = true }
        created.setOnErrorListener { _, _, _ -> failed = true; true }
        runCatching { created.setDataSource(file.absolutePath) }
            .onFailure { failed = true }
            .onSuccess { created.prepareAsync() }
        onDispose {
            runCatching { created.release() }
            player = null
            prepared = false
        }
    }

    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            position = player?.currentPosition ?: 0
            kotlinx.coroutines.delay(250)
        }
    }

    if (failed) {
        PreviewMessage("This audio format is not supported by the device player.")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = colorScheme.onPrimary, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = file.name.substringAfterLast('/'),
            color = stage.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(16.dp))
        val duration = player?.duration ?: 0
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { fraction ->
                val ms = (fraction * duration).toInt()
                player?.seekTo(ms.coerceIn(0, (duration - 1).coerceAtLeast(0)))
                position = ms
            },
            enabled = prepared && duration > 0,
            modifier = Modifier.fillMaxWidth().testTag("preview_audio_seek")
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(clockLabel(position), color = stage.textSecondary, style = MaterialTheme.typography.labelSmall)
            FilledIconButton(
                onClick = {
                    val mp = player ?: return@FilledIconButton
                    if (!prepared) return@FilledIconButton
                    if (mp.isPlaying) {
                        mp.pause()
                        playing = false
                    } else {
                        mp.start()
                        playing = true
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colorScheme.primary),
                modifier = Modifier.testTag("preview_audio_play")
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = colorScheme.onPrimary
                )
            }
            Text(clockLabel(duration - position), color = stage.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun clockLabel(millis: Int): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun PdfStage(file: File, transform: StageTransform) {
    val stage = PulseStageColors
    var failed by remember(file) { mutableStateOf(false) }
    val descriptor = remember(file) {
        runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            .getOrNull()
    }
    val renderer = remember(descriptor) {
        descriptor?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var page by remember(file) { mutableIntStateOf(0) }

    DisposableEffect(renderer, descriptor) {
        pageCount = renderer?.pageCount ?: 0
        onDispose {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var renderedFor by remember(file) { mutableIntStateOf(-1) }
    LaunchedEffect(renderer, page) {
        val active = renderer ?: return@LaunchedEffect
        // A page is rastered into a bitmap up to 2200 px wide. In `remember` that work runs during
        // composition, which is the main thread, and a dense first page is a frame the interface
        // cannot cancel or draw inside — the preview reads as a freeze rather than as loading.
        bitmap = withContext(Dispatchers.IO) { runCatching { renderPdfPage(active, page) }.getOrNull() }
        renderedFor = page
    }

    // A page still being drawn shows the previous one, not an error: the file opened fine.
    if (renderer == null || failed || (bitmap == null && renderedFor == page)) {
        PreviewMessage("This PDF could not be opened.")
        return
    }
    val shown = bitmap ?: return

    ZoomableStage(transform) { modifier ->
        androidx.compose.foundation.Image(
            bitmap = shown,
            contentDescription = "PDF page ${page + 1}",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag("preview_pdf")
        )
    }
    if (pageCount > 1) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                modifier = Modifier
                    .padding(bottom = 84.dp)
                    .clip(CircleShape)
                    .background(stage.scrim)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (page > 0) page-- },
                    enabled = page > 0,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.NavigateBefore, "Previous page", tint = stage.textPrimary)
                }
                Text("${page + 1} / $pageCount", color = stage.textPrimary, style = MaterialTheme.typography.labelMedium)
                IconButton(
                    onClick = { if (page < pageCount - 1) page++ },
                    enabled = page < pageCount - 1,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.NavigateNext, "Next page", tint = stage.textPrimary)
                }
            }
        }
    }
}

private fun renderPdfPage(renderer: PdfRenderer, index: Int): ImageBitmap? {
    if (index !in 0 until renderer.pageCount) return null
    return renderer.openPage(index).use { page ->
        val width = (page.width * 2).coerceIn(360, 2200)
        val height = (width * page.height / page.width).coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, android.graphics.Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp.asImageBitmap()
    }
}

@Composable
private fun TextStage(file: File) {
    val stage = PulseStageColors
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text = runCatching {
            val bytes = ByteArray(200_000)
            file.inputStream().use { input ->
                val read = input.read(bytes)
                String(bytes, 0, read.coerceAtLeast(0), Charsets.UTF_8)
            }
        }.getOrElse { "Could not read this file." }
    }
    val body = text
    if (body == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = stage.textPrimary)
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(stage.surface)
            .padding(horizontal = 14.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = body,
                color = stage.textPrimary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun OtherStage(file: File, attachment: ChatAttachment) {
    val stage = PulseStageColors
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(stage.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                tint = stage.onAccentTint,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(attachment.name, color = stage.textPrimary, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatAttachmentSize(attachment.size) + " · PulseDrop has no inline viewer for this type.",
            color = stage.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(18.dp))
        TextButton(
            onClick = { openExternally(context, file, attachment.mime, attachment.name) },
            modifier = Modifier.testTag("preview_open_with")
        ) {
            Text("Open with another app", color = stage.onAccentTint)
        }
    }
}

@Composable
private fun PreviewMessage(message: String) {
    val stage = PulseStageColors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = stage.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun PreviewToolbar(
    transform: StageTransform,
    file: File,
    attachment: ChatAttachment
) {
    val stage = PulseStageColors
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(stage.surfaceOverlay)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = { transform.zoomBy(1f / 1.25f) }, modifier = Modifier.testTag("preview_zoom_out")) {
            Icon(Icons.Default.ZoomOutMap, "Zoom out", tint = stage.textPrimary)
        }
        Text(
            text = "${(transform.scale * 100).roundToInt()}%",
            color = stage.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = { transform.zoomBy(1.25f) }, modifier = Modifier.testTag("preview_zoom_in")) {
            Icon(Icons.Default.ZoomInMap, "Zoom in", tint = stage.textPrimary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = { transform.rotate() }, modifier = Modifier.testTag("preview_rotate")) {
            Icon(Icons.Default.RotateRight, "Rotate", tint = stage.textPrimary)
        }
        IconButton(onClick = { transform.fit() }, modifier = Modifier.testTag("preview_fit")) {
            Icon(Icons.Default.Refresh, "Fit to screen", tint = stage.textPrimary)
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = { openExternally(context, file, attachment.mime, attachment.name) },
            modifier = Modifier.testTag("preview_share")
        ) {
            Icon(Icons.Default.Check, null, tint = stage.onAccentTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open", color = stage.onAccentTint)
        }
    }
}
