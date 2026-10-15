package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.R
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * The truthful facts about this build: what it is, which version is running, what it costs, and
 * where the code lives. Nothing here is decoration — every line answers a question someone asks
 * before trusting an app with their network.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val c = LocalPulseColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PulseSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(PulseSpacing.xl.dp))
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(PulseRadius.xl.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_localdrop_logo),
                contentDescription = "PulseDrop logo",
                modifier = Modifier.size(80.dp)
            )
        }
        Text(
            text = "PulseDrop",
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            modifier = Modifier.padding(top = PulseSpacing.md.dp)
        )
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary
        )
        Text(
            text = "Files, chats, calls and screen sharing between your own devices — over your " +
                    "network, with no account and no cloud.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = PulseSpacing.md.dp)
                .fillMaxWidth(0.86f)
        )

        Spacer(Modifier.height(PulseSpacing.xl.dp))
        PulseDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PulseSpacing.lg.dp),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
        ) {
            PulseSectionHeader(title = "How it works")
            AboutFact(Icons.Default.Wifi, "One server, every device",
                "Turn the server on and phones, tablets and browsers on the same network connect to " +
                        "this one address — there is no per-device server.")
            AboutFact(Icons.Default.Lock, "Encrypted pairing",
                "Pairing runs on an end-to-end encrypted channel with a spoken safety code, so you " +
                        "know the device you trusted is the one in the room.")
            AboutFact(Icons.Default.PhoneAndroid, "No accounts, no cloud",
                "Nothing is uploaded anywhere. Transfers go device to device; the optional browser " +
                        "server only ever talks to your own network.")
        }

        Spacer(Modifier.height(PulseSpacing.lg.dp))
        PulseDivider()
        PulseSectionHeader(title = "License & source")
        Text(
            text = "PulseDrop is free for noncommercial use under the PolyForm Noncommercial " +
                    "1.0.0 license. The full source is public.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "github.com/bishnuneup4ne/pulsedrop",
            style = MaterialTheme.typography.bodyMedium,
            color = c.accent,
            modifier = Modifier
                .padding(top = PulseSpacing.sm.dp)
                .fillMaxWidth()
        )
        Spacer(Modifier.height(PulseSpacing.xxl.dp))
    }
}

@Composable
private fun AboutFact(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val c = LocalPulseColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(PulseSizes.iconLg.dp))
        Spacer(Modifier.width(PulseSpacing.md.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}
