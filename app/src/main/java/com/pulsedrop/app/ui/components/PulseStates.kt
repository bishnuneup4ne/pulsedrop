package com.pulsedrop.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.LocalReducedMotion
import com.pulsedrop.app.ui.theme.PulseMotion
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * The states every data-driven page has to answer for.
 *
 * A screen that only handles "loaded" is a screen that shows a blank list when the network is down
 * and a spinner forever when the request failed. These are the nine answers, named once so a page
 * can be checked for coverage by looking at which ones it renders.
 */
sealed interface PageState {
    /** Requested, not yet answered. */
    data object Loading : PageState

    /** Real content. */
    data object Loaded : PageState

    /** Reached the page, nothing to show, no query in the way. */
    data class Empty(
        val title: String,
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    ) : PageState

    /** A query ran and genuinely found nothing — different from an empty list. */
    data class NoResults(val query: String, val hint: String? = null) : PageState

    /** Something failed and can be tried again. */
    data class Error(val message: String, val retryLabel: String = "Try again") : PageState

    /** The route to the data is gone: no network, no host, no pairing. */
    data class Offline(val where: String, val sinceLabel: String? = null) : PageState

    /** The feature needs a permission the user has not granted. */
    data class PermissionDenied(
        val what: String,
        val howToFix: String,
        val actionLabel: String = "Open system settings",
        val onAction: (() -> Unit)? = null
    ) : PageState

    /** This build or this device cannot do it. Saying so is better than a button that does nothing. */
    data class Unsupported(val feature: String, val why: String) : PageState

    /** Partial or stale: what we have is real but not current. */
    data class PartialStale(val note: String) : PageState
}

/**
 * Renders a single non-content state as a panel.
 *
 * Every case gets an icon and words: colour and a spinner are hints, not the message, so the same
 * panel reads correctly to a screen reader and to someone with the display in grayscale.
 */
@Composable
fun PulseStatePanel(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: PulseStatusTone = PulseStatusTone.Neutral
) {
    val c = LocalPulseColors.current
    // The icon takes the indicator colour; the panel's own background is the tint, so these pairs
    // are exactly the ones ThemeTokensTest measures.
    val accent = when (tone) {
        PulseStatusTone.Negative -> c.onDangerTint
        PulseStatusTone.Warning -> c.onWarningTint
        PulseStatusTone.Positive -> c.onSuccessTint
        else -> c.textTertiary
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PulseSpacing.xl.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(c.surfaceElevated, RoundedCornerShape(PulseRadius.md.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = c.textPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            PulseButton(
                label = actionLabel,
                onClick = onAction,
                tone = PulseButtonTone.Neutral,
                modifier = Modifier.padding(top = PulseSpacing.xs.dp)
            )
        }
    }
}

/** Maps a [PageState] onto the panel that explains it, so a page cannot quietly skip a case. */
@Composable
fun PulseStateFor(
    state: PageState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is PageState.Loading -> PulseSkeletonList(modifier = modifier)
        is PageState.Loaded -> Unit
        is PageState.Empty -> PulseStatePanel(
            icon = Icons.Outlined.Inbox,
            title = state.title,
            message = state.message,
            actionLabel = state.actionLabel,
            onAction = state.onAction,
            modifier = modifier
        )
        is PageState.NoResults -> PulseStatePanel(
            icon = Icons.Outlined.SearchOff,
            title = "Nothing matches \"${state.query}\"",
            message = state.hint ?: "Try a shorter word, or clear the search.",
            modifier = modifier
        )
        is PageState.Error -> PulseStatePanel(
            icon = Icons.Outlined.ErrorOutline,
            title = "That didn't work",
            message = state.message,
            actionLabel = state.retryLabel,
            onAction = onRetry,
            tone = PulseStatusTone.Negative,
            modifier = modifier
        )
        is PageState.Offline -> PulseStatePanel(
            icon = Icons.Outlined.CloudOff,
            title = "Not connected",
            message = if (state.sinceLabel != null)
                "${state.where}. Last seen ${state.sinceLabel}."
            else state.where,
            actionLabel = "Reconnect",
            onAction = onRetry,
            tone = PulseStatusTone.Warning,
            modifier = modifier
        )
        is PageState.PermissionDenied -> PulseStatePanel(
            icon = Icons.Outlined.Lock,
            title = "${state.what} needs permission",
            message = state.howToFix,
            actionLabel = state.actionLabel,
            onAction = state.onAction,
            tone = PulseStatusTone.Warning,
            modifier = modifier
        )
        is PageState.Unsupported -> PulseStatePanel(
            icon = Icons.Outlined.Unsubscribe,
            title = "${state.feature} isn't available here",
            message = state.why,
            modifier = modifier
        )
        is PageState.PartialStale -> PulseStatePanel(
            icon = Icons.Outlined.Timer,
            title = "This may be out of date",
            message = state.note,
            actionLabel = "Refresh",
            onAction = onRetry,
            tone = PulseStatusTone.Warning,
            modifier = modifier
        )
    }
}

/**
 * Skeletons that match the shape of what is coming.
 *
 * The pulse is a fade of a flat tint, not a moving gradient, and it stops entirely under
 * reduced-motion — a shimmer that ignores that preference is exactly the animation the setting
 * exists to suppress.
 */
@Composable
fun PulseSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    widthFraction: Float = 1f,
    fixedWidth: Dp? = null
) {
    val c = LocalPulseColors.current
    // A shimmer is a motion cue, so it is the first thing to go when motion is turned off; the
    // flat tint that replaces it still reads as "not real content yet".
    val level = if (LocalReducedMotion.current) 0.7f else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.45f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(PulseMotion.slow), RepeatMode.Reverse),
            label = "skeletonLevel"
        ).value
    }
    val shape = RoundedCornerShape(PulseRadius.sm.dp)
    val sized = if (fixedWidth != null) {
        modifier.width(fixedWidth).height(height)
    } else {
        modifier.fillMaxWidth(widthFraction).height(height)
    }
    Box(sized.background(c.borderSubtle.copy(alpha = level), shape))
}

@Composable
fun PulseSkeletonList(modifier: Modifier = Modifier, rows: Int = 4) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PulseSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
                PulseSkeleton(height = 40.dp, fixedWidth = 40.dp)
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseSkeleton(height = 14.dp, widthFraction = 0.6f)
                    PulseSkeleton(height = 12.dp, widthFraction = 0.4f)
                }
            }
        }
    }
}

/** A thin band for a state that must be visible *above* content rather than instead of it. */
@Composable
fun PulseInlineBanner(
    text: String,
    tone: PulseStatusTone,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    val c = LocalPulseColors.current
    val (bg, fg) = when (tone) {
        PulseStatusTone.Negative -> c.dangerTint to c.onDangerTint
        PulseStatusTone.Warning -> c.warningTint to c.onWarningTint
        PulseStatusTone.Positive -> c.successTint to c.onSuccessTint
        else -> c.surfaceElevated to c.textSecondary
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = PulseSpacing.lg.dp, vertical = PulseSpacing.md.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}
