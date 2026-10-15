package com.pulsedrop.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseMotion
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.ui.theme.pulseSpec

/**
 * The shared component kit.
 *
 * Everything here reads the semantic palette and never a literal, which is what makes the two
 * theme tables the only places colour is decided: a screen that hardcodes a grey is a screen that
 * stays wrong the moment the theme changes.
 */

/** One hairline everywhere, so dividers and card edges are the same weight on every page. */
internal val PulseHairline = 1.dp

// ── Buttons ────────────────────────────────────────────────────────────────

enum class PulseButtonTone { Primary, Neutral, Quiet, Danger }

/**
 * The one button in the app.
 *
 * [loading] swaps the label for a spinner and blocks re-entry, which is how "prevent accidental
 * double submission" is satisfied without every call site inventing its own guard. A disabled
 * button given a [disabledReason] announces why: a greyed control with no explanation reads as a
 * broken app.
 */
@Composable
fun PulseButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PulseButtonTone = PulseButtonTone.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fullWidth: Boolean = false,
    disabledReason: String? = null
) {
    val c = LocalPulseColors.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val container = when {
        !enabled || loading -> c.borderSubtle
        tone == PulseButtonTone.Primary -> c.accent
        tone == PulseButtonTone.Danger -> c.dangerTint
        tone == PulseButtonTone.Neutral -> c.surfaceElevated
        else -> Color.Transparent
    }
    val content = when {
        !enabled || loading -> c.disabled
        tone == PulseButtonTone.Primary -> c.onAccent
        tone == PulseButtonTone.Danger -> c.danger
        tone == PulseButtonTone.Neutral -> c.textPrimary
        else -> c.textSecondary
    }
    val shown by animateColorAsState(
        targetValue = if (pressed && enabled && !loading) container.copy(alpha = 0.88f) else container,
        animationSpec = pulseSpec<Color>(PulseMotion.fast),
        label = "pulseButtonTone"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = PulseSizes.touchTarget.dp)
            .semantics {
                contentDescription = when {
                    loading -> "$label, loading"
                    !enabled && disabledReason != null -> "$label. $disabledReason"
                    else -> label
                }
            },
        shape = RoundedCornerShape(PulseRadius.md.dp),
        color = shown,
        contentColor = content,
        border = if (tone == PulseButtonTone.Quiet) BorderStroke(PulseHairline, c.borderSubtle) else null,
        enabled = enabled && !loading,
        interactionSource = interactions
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = PulseSpacing.xl.dp,
                vertical = PulseSpacing.md.dp
            ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PulseSizes.iconSm.dp),
                    strokeWidth = 2.dp,
                    color = content
                )
                Spacer(Modifier.width(PulseSpacing.sm.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(PulseSizes.icon.dp))
                Spacer(Modifier.width(PulseSpacing.sm.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * An icon-only control. The accessible name is mandatory: without it the button is announced as
 * "Button" and nobody using a screen reader can tell a refresh from a delete.
 */
@Composable
fun PulseIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val c = LocalPulseColors.current
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(PulseSizes.touchTarget.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(PulseRadius.md.dp),
        color = if (selected && enabled) c.accentTint else Color.Transparent,
        contentColor = when {
            !enabled -> c.disabled
            selected -> c.onAccentTint
            else -> c.textSecondary
        },
        enabled = enabled
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(PulseSizes.icon.dp))
        }
    }
}

// ── Containers ─────────────────────────────────────────────────────────────

/** A card: one elevation step above its parent plus a hairline. No shadow race, no gradients. */
@Composable
fun PulseCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    contentPadding: Dp = PulseSpacing.lg.dp,
    content: @Composable () -> Unit
) {
    val c = LocalPulseColors.current
    val shape = RoundedCornerShape(PulseRadius.lg.dp)
    val card = @Composable {
        Surface(
            modifier = modifier,
            shape = shape,
            color = c.surfaceElevated,
            contentColor = c.textPrimary,
            border = BorderStroke(PulseHairline, if (selected) c.accent else c.borderSubtle)
        ) {
            Box(Modifier.padding(contentPadding)) { content() }
        }
    }
    if (onClick == null) card()
    else Box(Modifier.clip(shape).clickable(onClick = onClick)) { card() }
}

@Composable
fun PulseDivider(modifier: Modifier = Modifier) {
    Box(modifier.height(PulseHairline).background(LocalPulseColors.current.borderSubtle))
}

@Composable
fun PulseSectionHeader(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {}
) {
    val c = LocalPulseColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = PulseSpacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        action()
    }
}

/** Filter or selection pill. Selected reads a tint *and* a heavier weight, never colour alone. */
@Composable
fun PulseChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    val c = LocalPulseColors.current
    Surface(
        onClick = { onClick?.invoke() },
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        shape = CircleShape,
        color = when {
            !enabled -> c.borderSubtle
            selected -> c.accentTint
            else -> c.surfaceElevated
        },
        contentColor = when {
            !enabled -> c.disabled
            selected -> c.onAccentTint
            else -> c.textSecondary
        },
        border = BorderStroke(PulseHairline, if (selected) c.accent else c.borderSubtle),
        enabled = enabled && onClick != null
    ) {
        Row(
            Modifier.padding(
                horizontal = PulseSpacing.md.dp,
                vertical = PulseSpacing.sm.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, modifier = Modifier.size(PulseSizes.iconSm.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

// ── Status ─────────────────────────────────────────────────────────────────

enum class PulseStatusTone { Positive, Warning, Negative, Neutral, Active }

/**
 * Status, said in words.
 *
 * Colour is the quickest way to read a state and the least reliable way to guarantee it is seen,
 * so the label is always rendered and the dot sits beside it.
 */
@Composable
fun PulseStatus(
    text: String,
    tone: PulseStatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val c = LocalPulseColors.current
    val tint = when (tone) {
        PulseStatusTone.Positive -> c.success
        PulseStatusTone.Warning -> c.warning
        PulseStatusTone.Negative -> c.danger
        PulseStatusTone.Neutral -> c.textTertiary
        PulseStatusTone.Active -> c.accent
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint)
                .alpha(if (tone == PulseStatusTone.Neutral) 0.6f else 1f)
        )
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = c.textPrimary
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}
