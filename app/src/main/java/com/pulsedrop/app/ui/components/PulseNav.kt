package com.pulsedrop.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import com.pulsedrop.app.R
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseMotion
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * Every place the app can take you.
 *
 * A section without a screen yet is honest about it — a note saying what it will be, and a jump to
 * the place where that capability already lives today. Nothing in this app pretends to work.
 */
enum class PulseSection(
    val title: String,
    val icon: ImageVector,
    val tag: String,
    /** null means the section has a real screen; otherwise this says what is going on. */
    val comingSoonNote: String? = null,
    val shortcutLabel: String? = null,
    val shortcut: PulseSection? = null
) {
    HOME("Home", Icons.Filled.Home, "section_home"),
    ONBOARDING("Get started", Icons.Filled.Checklist, "section_onboarding"),
    DEVICES("Devices", Icons.Filled.Radar, "section_devices"),
    SEND_FILES("Send files", Icons.AutoMirrored.Filled.Send, "section_send"),
    RECEIVE_FILES("Receive files", Icons.Filled.Download, "section_receive"),
    TRANSFERS("Transfers", Icons.Filled.SwapHoriz, "section_transfers"),
    MY_FILES("My files", Icons.Filled.Folder, "section_myfiles"),
    FILE_MANAGER("File manager", Icons.Filled.FolderOpen, "section_filemanager"),
    MEDIA("Media library", Icons.Filled.PhotoLibrary, "section_media"),
    MESSAGES("Messages", Icons.AutoMirrored.Filled.Chat, "section_messages"),
    CALLS("Calls", Icons.Filled.Call, "section_calls",
        comingSoonNote = "Voice and video calls work today from Messages and Devices. " +
                "A call history and dialer will live here.",
        shortcutLabel = "Open messages", shortcut = MESSAGES),
    SCREEN_SHARING("Screen sharing", Icons.Filled.ScreenShare, "section_screenshare",
        comingSoonNote = "Screen sharing works today inside a call — start one from Messages " +
                "and tap Share screen.",
        shortcutLabel = "Open messages", shortcut = MESSAGES),
    ACTIVITY("Activity", Icons.Filled.Notifications, "section_activity"),
    NOTIFICATIONS("Notifications", Icons.Filled.NotificationsActive, "section_notifications"),
    NETWORK("Network status", Icons.Filled.Language, "section_network"),
    DIAGNOSTICS("Diagnostics", Icons.Filled.MonitorHeart, "section_diagnostics"),
    SECURITY("Security center", Icons.Filled.Security, "section_security"),
    SETTINGS("Settings", Icons.Filled.Settings, "section_settings"),
    HELP("Help & support", Icons.Filled.HelpOutline, "section_help"),
    ABOUT("About PulseDrop", Icons.Filled.Info, "section_about");

    val available: Boolean get() = comingSoonNote == null
}

/**
 * The navigation frame: a compact drawer behind the hamburger on phones, and on wide windows a
 * persistent sidebar the hamburger collapses and restores. Content is identical either way —
 * one app, two shapes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseNavFrame(
    wide: Boolean,
    drawerState: DrawerState,
    sidebarOpen: Boolean,
    drawerContent: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit
) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = slideInHorizontally(tween(PulseMotion.base)) { -it } + fadeIn(tween(PulseMotion.base)),
                exit = slideOutHorizontally(tween(PulseMotion.base)) { -it } + fadeOut(tween(PulseMotion.base))
            ) {
                PermanentDrawerSheet(
                    modifier = Modifier.fillMaxHeight().width(300.dp),
                    drawerContainerColor = LocalPulseColors.current.surface
                ) {
                    Column(Modifier.statusBarsPadding(), content = drawerContent)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), content = { content() })
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(312.dp),
                    drawerContainerColor = LocalPulseColors.current.surface
                ) {
                    drawerContent()
                }
            }
        ) { content() }
    }
}

/**
 * The drawer body: identity at the top, the sections in order, and Help/About pinned at the
 * bottom where people look for them. Badges only ever carry real counts, and a section that is
 * not built yet is marked rather than faked.
 */
@Composable
fun PulseDrawerContent(
    selected: PulseSection,
    badges: Map<PulseSection, Int>,
    liveTransfers: Boolean,
    isRunning: Boolean,
    deviceName: String,
    onSelect: (PulseSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(PulseRadius.md.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_localdrop_logo),
                    contentDescription = "PulseDrop logo",
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PulseDrop", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                PulseStatus(
                    text = if (isRunning) "Serving" else "Idle",
                    tone = if (isRunning) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                    detail = deviceName,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        PulseDivider()
        // One scroll column, in the exact order the sections are declared. Help and About sit at
        // the bottom of that order rather than pinned below a fold — pinning them pushed Settings,
        // a primary destination, off-screen on a short phone.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            PulseSection.entries.forEach { section ->
                if (section == PulseSection.HELP) {
                    PulseDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
                PulseDrawerItem(
                    section = section,
                    selected = section == selected,
                    badge = badges[section] ?: 0,
                    live = section == PulseSection.TRANSFERS && liveTransfers,
                    onClick = { onSelect(section) }
                )
            }
            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
                modifier = Modifier.padding(start = 28.dp, top = 10.dp, bottom = 6.dp)
            )
        }
    }
}

@Composable
private fun PulseDrawerItem(
    section: PulseSection,
    selected: Boolean,
    badge: Int,
    live: Boolean,
    onClick: () -> Unit
) {
    val c = LocalPulseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(PulseRadius.pill.dp))
            .background(if (selected) c.accentTint else Color.Transparent)
            .clickable(onClickLabel = "Open ${section.title}") { onClick() }
            .testTag(section.tag)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = if (selected) c.onAccentTint else c.textSecondary,
            modifier = Modifier.size(PulseSizes.iconLg.dp)
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = section.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) c.onAccentTint else c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            badge > 0 -> Surface(
                shape = RoundedCornerShape(10.dp),
                color = c.accent,
                contentColor = c.onAccent
            ) {
                Text(
                    text = if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            live -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(c.success)
                    .semantics { contentDescription = "Transfers in progress" }
            )
            !section.available -> Text(
                text = "Soon",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
                modifier = Modifier.alpha(0.9f)
            )
        }
    }
}

/** Kept for the states panel; a count over the cap reads "99+" rather than lying about the number. */
@Composable
fun PulseBadgeBox(
    count: Int,
    live: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    BadgedBox(
        modifier = modifier,
        badge = {
            when {
                count > 0 -> Badge(
                    containerColor = LocalPulseColors.current.accent,
                    contentColor = LocalPulseColors.current.onAccent
                ) { Text(if (count > 99) "99+" else count.toString()) }
                live -> Badge(
                    modifier = Modifier.size(10.dp),
                    containerColor = LocalPulseColors.current.success,
                    contentColor = LocalPulseColors.current.onAccent
                )
            }
        }
    ) {
        Icon(icon, contentDescription = null)
    }
}
