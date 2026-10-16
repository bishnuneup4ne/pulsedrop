package com.pulsedrop.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalPulseColors

/**
 * A flat bar that belongs to the page, not a grey band floating above it: where you are,
 * the menu, and the one switch that matters — whether this phone is serving.
 */
@Composable
fun PulseHeader(
    title: String,
    isRunning: Boolean,
    onOpenDrawer: () -> Unit,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.testTag("open_drawer_btn")
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Open navigation menu", tint = c.textPrimary)
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = c.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )

        // Filled only while serving, so the one state that costs battery is also the loud one.
        Surface(
            onClick = onToggleServer,
            modifier = Modifier
                .size(44.dp)
                .testTag("toggle_server_btn"),
            shape = CircleShape,
            color = if (isRunning) c.accent else Color.Transparent,
            contentColor = if (isRunning) c.onAccent else c.textSecondary,
            border = if (isRunning) null else BorderStroke(1.dp, c.borderSubtle)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (isRunning) "Stop server" else "Start server",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
