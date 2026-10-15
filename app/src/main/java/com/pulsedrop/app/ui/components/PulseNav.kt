package com.pulsedrop.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * The side navigation for tablet and desktop windows.
 *
 * Badges are only ever fed real counts — pending requests, unread threads, live transfers — and a
 * count over the cap reads "99+" rather than lying about the number.
 */
@Composable
fun PulseSideRail(
    selectedId: String,
    items: List<PulseNavItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true
) {
    val c = LocalPulseColors.current
    NavigationRail(
        modifier = modifier.semantics { contentDescription = "Navigation" },
        containerColor = c.surface
    ) {
        items.forEach { item ->
            val selected = item.id == selectedId
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(item.id) },
                icon = { PulseBadgeBox(count = item.badge, live = item.live, icon = item.icon) },
                label = if (showLabels) { { Text(item.title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) } } else null,
                alwaysShowLabel = showLabels,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = c.onAccentTint,
                    selectedTextColor = c.textPrimary,
                    unselectedIconColor = c.textSecondary,
                    unselectedTextColor = c.textSecondary,
                    indicatorColor = c.accentTint
                )
            )
        }
    }
}

/** One entry in either navigation: title, icon, and a badge that only says something real. */
data class PulseNavItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val badge: Int = 0,
    /** A live job (an ongoing transfer) gets a dot rather than a number that never finishes. */
    val live: Boolean = false,
    val tag: String? = null
)

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
