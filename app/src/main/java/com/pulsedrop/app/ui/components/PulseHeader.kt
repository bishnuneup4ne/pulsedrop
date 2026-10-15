package com.pulsedrop.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.R
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.ui.theme.PulseTheme
import com.pulsedrop.app.ui.theme.ThemePreference

/**
 * The top bar: who this device is, whether it is reachable, and the two things a person actually
 * press from here — re-scan the network, and start or stop serving.
 *
 * The appearance menu lives beside them because that is the one setting a person reaches for while
 * looking at the screen they want changed, and switching it here takes effect on this very frame.
 */
@Composable
fun PulseHeader(
    isRunning: Boolean,
    deviceName: String,
    onToggleServer: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val context = LocalContext.current
    val mode = ThemePreference.mode.value
    var appearanceOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = c.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(PulseRadius.lg.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // A full-colour vector logo: Icon would flatten its gradient to a tint,
                    // which is what made this tile render as a blank white square. Image keeps it.
                    Image(
                        painter = painterResource(id = R.drawable.ic_localdrop_logo),
                        contentDescription = "PulseDrop logo",
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "PulseDrop",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.textPrimary
                    )
                    // Words carry the state; the dot only reinforces it, so this reads the same
                    // in grayscale and to a screen reader.
                    PulseStatus(
                        text = if (isRunning) "Live" else "Idle",
                        tone = if (isRunning) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                        detail = deviceName,
                        modifier = Modifier.padding(top = 2.dp).testTag("header_status")
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulseIconButton(
                    icon = Icons.Default.Refresh,
                    label = "Rescan the network",
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_network_btn")
                )

                Box {
                    PulseIconButton(
                        icon = Icons.Default.Palette,
                        label = "Appearance",
                        onClick = { appearanceOpen = true },
                        selected = mode != PulseTheme.SYSTEM,
                        modifier = Modifier.testTag("header_appearance_btn")
                    )
                    DropdownMenu(
                        expanded = appearanceOpen,
                        onDismissRequest = { appearanceOpen = false }
                    ) {
                        PulseTheme.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                leadingIcon = {
                                    if (choice == mode) Icon(Icons.Default.Check, null)
                                    else Spacer(Modifier.size(24.dp))
                                },
                                onClick = {
                                    appearanceOpen = false
                                    // Written straight to the store the theme reads, so the next
                                    // frame is already the chosen one — nothing to apply.
                                    ThemePreference.save(context, choice)
                                }
                            )
                        }
                    }
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
            }
        }
    }
}
