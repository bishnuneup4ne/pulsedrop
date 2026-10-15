package com.pulsedrop.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.ui.theme.PulseTheme
import com.pulsedrop.app.ui.theme.ThemePreference

/**
 * The top bar in its slim, final shape: hamburger for the sidebar, the section name on one line
 * that never wraps (even on a narrow device the words ellipsize instead of breaking mid-word),
 * the live/idle status, the server power, and everything else behind one overflow.
 *
 * The logo and the device name moved into the drawer header — this bar's job is where you are
 * and whether you are serving, and it stays that way at 320 dp of width.
 */
@Composable
fun PulseHeader(
    title: String,
    isRunning: Boolean,
    deviceName: String,
    onOpenDrawer: () -> Unit,
    onToggleServer: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val context = LocalContext.current
    val mode = ThemePreference.mode.value
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = c.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.testTag("open_drawer_btn")
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                PulseStatus(
                    text = if (isRunning) "Live" else "Idle",
                    tone = if (isRunning) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                    detail = deviceName,
                    modifier = Modifier.padding(top = 1.dp).testTag("header_status")
                )
            }

            val running = isRunning
            Surface(
                onClick = onToggleServer,
                modifier = Modifier
                    .size(PulseSizes.touchTarget.dp)
                    .testTag("toggle_server_btn"),
                shape = RoundedCornerShape(PulseRadius.md.dp),
                color = if (running) c.accent else c.surfaceElevated,
                contentColor = if (running) c.onAccent else c.textSecondary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = if (running) "Stop server" else "Start server",
                        modifier = Modifier.size(PulseSizes.icon.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("header_overflow_btn")
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rescan the network") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRefresh()
                        },
                        modifier = Modifier.testTag("refresh_network_btn")
                    )
                    HorizontalDivider()
                    PulseTheme.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.label) },
                            leadingIcon = {
                                if (choice == mode) Icon(Icons.Default.Check, null)
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                menuOpen = false
                                // Written straight to the store the theme reads, so the next
                                // frame is already the chosen one — nothing to apply.
                                ThemePreference.save(context, choice)
                            },
                            modifier = Modifier.testTag("theme_${choice.name.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}
