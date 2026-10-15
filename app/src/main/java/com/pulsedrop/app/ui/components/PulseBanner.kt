package com.pulsedrop.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One in-app heads-up card. The system shade already carries server and call events;
 * this stack mirrors them where an active user can actually see them, and tapping a
 * card jumps to whatever it is about.
 */
data class PulseBanner(
    val id: String,
    val group: String,
    val title: String,
    val text: String,
    val icon: ImageVector,
    val accent: Color? = null,
    val action: String? = null,
    val onTap: (() -> Unit)? = null,
    val visible: Boolean = true
)

@Composable
fun PulseBannerStack(
    banners: List<PulseBanner>,
    onDismiss: (PulseBanner) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        banners.forEach { banner ->
            AnimatedVisibility(
                visible = banner.visible,
                enter = slideInVertically(tween(240)) { -it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160))
            ) {
                val accent = banner.accent ?: MaterialTheme.colorScheme.primary
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "banner_${banner.group}" }
                        .clickable {
                            banner.onTap?.invoke()
                            onDismiss(banner)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = banner.icon,
                            contentDescription = null,
                            tint = accent
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = banner.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = banner.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (banner.action != null) {
                                Text(
                                    text = banner.action,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accent,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50)
                                )
                                .padding(6.dp)
                                .clickable { onDismiss(banner) }
                        )
                    }
                }
            }
        }
    }
}
