package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import coil.Coil
import coil.ImageLoader
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.screens.FileCategory
import com.pulsedrop.app.ui.screens.MediaScreen
import com.pulsedrop.app.ui.screens.formatMediaDuration
import com.pulsedrop.app.ui.screens.mediaKindLabel
import com.pulsedrop.app.ui.screens.mediaRecords
import com.pulsedrop.app.ui.screens.readMediaFacts
import com.pulsedrop.app.ui.theme.PulseDropTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The library may only show media that really arrived and really exists, and it may state only
 * facts the file itself gives up. These tests pin both halves of that promise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun initCoil() {
        // AsyncImage needs a real ImageLoader in a bare compose test; nothing is fetched.
        Coil.setImageLoader(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build())
    }

    private fun tmpFile(name: String): File =
        File.createTempFile("media", "-$name").apply { writeText("x") }

    private fun row(
        id: String,
        name: String,
        mime: String?,
        dir: String = "INCOMING",
        status: String = "COMPLETED",
        path: String? = tmpFile(name).absolutePath
    ) = TransferHistoryEntity(
        id = id,
        fileName = name,
        fileSize = 1234L,
        direction = dir,
        status = status,
        remoteDevice = "Laptop",
        clientIp = null,
        localFilePath = path,
        mimeType = mime,
        timestamp = 1_730_000_000_000L
    )

    private fun screen(
        history: List<TransferHistoryEntity>,
        opened: MutableList<String> = mutableListOf(),
        details: MutableList<String> = mutableListOf()
    ) {
        compose.setContent {
            PulseDropTheme {
                MediaScreen(
                    history = history,
                    onOpenFile = { opened += it },
                    onOpenTransferDetails = { details += it }
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    }

    // ── Pure mappings ──────────────────────────────────────────────────────

    @Test
    fun onlyIncomingCompletedMediaSurviveTheFilter() {
        val rows = listOf(
            row("img", "photo.jpg", "image/jpeg"),
            row("vid", "clip.mp4", "video/mp4"),
            row("aud", "song.mp3", "audio/mpeg"),
            row("doc", "notes.pdf", "application/pdf"),
            row("out", "sent.jpg", "image/jpeg", dir = "OUTGOING"),
            row("fail", "bad.jpg", "image/jpeg", status = "FAILED")
        )
        assertEquals(listOf("img", "vid", "aud"), mediaRecords(rows).map { it.id })
    }

    @Test
    fun kindLabelsArePlainWords() {
        assertEquals("Image", mediaKindLabel(FileCategory.IMAGE))
        assertEquals("Video", mediaKindLabel(FileCategory.VIDEO))
        assertEquals("Audio", mediaKindLabel(FileCategory.AUDIO))
    }

    @Test
    fun durationFormattingIsClockStyle() {
        assertEquals("1:23", formatMediaDuration(83))
        assertEquals("1:02:05", formatMediaDuration(3725))
        assertEquals("0:00", formatMediaDuration(0))
    }

    @Test
    fun aMissingFileYieldsNoFacts() {
        val rec = mediaRecords(listOf(row("gone", "x.jpg", "image/jpeg", path = "/nope/x.jpg")))
            .first()
        assertTrue(readMediaFacts(rec).isEmpty)
    }

    // ── UI ─────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyLibrarySaysSoWithoutFakeTiles() {
        screen(listOf(row("doc", "notes.pdf", "application/pdf")))
        compose.onNodeWithTag("media_empty").assertExists()
        compose.onNodeWithText("notes.pdf").assertDoesNotExist()
    }

    @Test
    fun tilesShowTheRecordedTruthAndKindLabels() {
        screen(listOf(row("img", "photo.jpg", "image/jpeg"), row("aud", "song.mp3", "audio/mpeg")))
        scrollTo("media_tile_img")
        compose.onNodeWithTag("media_tile_img").assertExists()
        compose.onNodeWithTag("media_tile_aud").assertExists()
        compose.onNodeWithText("Image").assertExists()
        compose.onNodeWithText("Audio").assertExists()
        compose.onNodeWithText("song.mp3").assertExists()
    }

    @Test
    fun tabsFilterAndCountsMatchTheRealSet() {
        screen(listOf(row("img", "photo.jpg", "image/jpeg"), row("aud", "song.mp3", "audio/mpeg")))
        compose.onNodeWithTag("media_tab_Video").performClick()
        compose.onNodeWithTag("media_none_match").assertExists()
        compose.onNodeWithTag("media_tile_img").assertDoesNotExist()
        compose.onNodeWithTag("media_tab_Image").performClick()
        scrollTo("media_tile_img")
        compose.onNodeWithTag("media_tile_img").assertExists()
    }

    @Test
    fun detailDialogLinksToTheRealTransferRecord() {
        val details = mutableListOf<String>()
        screen(listOf(row("aud", "song.mp3", "audio/mpeg")), details = details)
        scrollTo("media_tile_aud")
        compose.onNodeWithTag("media_tile_aud").performClick()
        compose.onNodeWithTag("media_detail").assertExists()
        compose.onNodeWithTag("media_detail_transfer").performClick()
        assertEquals(listOf("aud"), details)
    }

    @Test
    fun detailDialogOpensTheRealFile() {
        val opened = mutableListOf<String>()
        val f = tmpFile("song.mp3")
        screen(listOf(row("aud", "song.mp3", "audio/mpeg", path = f.absolutePath)), opened)
        scrollTo("media_tile_aud")
        compose.onNodeWithTag("media_tile_aud").performClick()
        compose.onNodeWithTag("media_detail_open").performClick()
        assertEquals(listOf(f.absolutePath), opened)
    }

    @Test
    fun aGoneFileIsAnnouncedAndOpeningItIsRefused() {
        screen(listOf(row("gone", "x.jpg", "image/jpeg", path = "/nope/x.jpg")))
        scrollTo("media_tile_gone")
        compose.onNodeWithTag("media_tile_gone").performClick()
        compose.onNodeWithText("Missing on disk").assertExists()
        compose.onNodeWithTag("media_detail_open").assertIsNotEnabled()
    }

    @Test
    fun thePageDisclosesItsLimits() {
        screen(listOf(row("img", "photo.jpg", "image/jpeg")))
        scrollTo("media_disclosure")
        compose.onNodeWithText("keeps no media cache", substring = true).assertExists()
    }
}
