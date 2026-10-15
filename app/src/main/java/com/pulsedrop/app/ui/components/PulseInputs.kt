package com.pulsedrop.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseAvatarInk
import com.pulsedrop.app.ui.theme.PulseAvatarPalette
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * Inputs, choices, menus, tabs, identity and rows.
 *
 * Same rule as the rest of the kit: colour comes from the semantic table, every control carries
 * its own accessible label, and error/disabled/loading are rendered behaviour rather than
 * call-site decoration.
 */

// ── Text fields ────────────────────────────────────────────────────────────

/**
 * A labelled text field with room for a helper or an error underneath.
 *
 * The error travels with the field in accessibility semantics: a screen reader hears the problem
 * attached to the control it belongs to, and a failed field always shows a reason, never just a
 * red outline.
 */
@Composable
fun PulseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    helper: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {}
) {
    val c = LocalPulseColors.current
    val lineColor = when {
        !enabled -> c.borderSubtle
        error != null -> c.danger
        else -> c.borderStrong
    }
    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(PulseRadius.md.dp),
            color = if (enabled) c.surfaceSunken else c.surface,
            border = BorderStroke(PulseHairline, lineColor)
        ) {
            Row(
                Modifier
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(horizontal = PulseSpacing.md.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon, null,
                        tint = if (error != null) c.danger else c.textTertiary,
                        modifier = Modifier.size(PulseSizes.icon.dp)
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = PulseSpacing.sm.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error != null) c.danger else c.textTertiary
                    )
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else minLines,
                        maxLines = if (singleLine) 1 else minLines.coerceAtLeast(4),
                        textStyle = LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyLarge
                                .copy(color = if (enabled) c.textPrimary else c.disabled)
                        ),
                        visualTransformation =
                            if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(imeAction = imeAction),
                        keyboardActions = KeyboardActions(
                            onDone = { onImeAction() },
                            onGo = { onImeAction() },
                            onSend = { onImeAction() },
                            onSearch = { onImeAction() },
                            onNext = { onImeAction() },
                            onPrevious = { onImeAction() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = label }
                    )
                }
                trailing?.invoke()
            }
        }
        when {
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = c.danger,
                modifier = Modifier
                    .padding(start = PulseSpacing.xs.dp, top = PulseSpacing.xxs.dp)
                    .semantics { contentDescription = "$label error: $error" }
            )
            helper != null -> Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                modifier = Modifier.padding(start = PulseSpacing.xs.dp, top = PulseSpacing.xxs.dp)
            )
        }
    }
}

/**
 * Search input that debounces internally. Bind [value] to the keyboard state and read
 * [onDebouncedChange] for the query to actually filter with: reacting to every keystroke
 * re-renders whole screens for letters nobody has finished typing.
 */
@Composable
fun PulseSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onDebouncedChange: (String) -> Unit = {},
    debounceMs: Long = 220
) {
    val c = LocalPulseColors.current
    LaunchedEffect(value) {
        kotlinx.coroutines.delay(debounceMs)
        onDebouncedChange(value)
    }
    Surface(
        shape = CircleShape,
        color = c.surfaceElevated,
        border = BorderStroke(PulseHairline, c.borderSubtle),
        modifier = modifier.defaultMinSize(minHeight = PulseSizes.touchTarget.dp)
    ) {
        Row(
            Modifier.padding(horizontal = PulseSpacing.lg.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.textTertiary, modifier = Modifier.size(PulseSizes.icon.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary)
                ),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
                        }
                        inner()
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.semantics { contentDescription = placeholder }
            )
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(PulseSizes.iconSm.dp),
                    strokeWidth = 2.dp,
                    color = c.textTertiary
                )
                value.isNotEmpty() -> Box(
                    Modifier
                        .size(PulseSizes.iconLg.dp)
                        .clip(CircleShape)
                        .clickable {
                            onValueChange("")
                            onDebouncedChange("")
                        }
                        .semantics { contentDescription = "Clear search" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, null, tint = c.textTertiary, modifier = Modifier.size(PulseSizes.iconSm.dp))
                }
            }
        }
    }
}

// ── Choices ────────────────────────────────────────────────────────────────

/** A row-wide switch: tapping anywhere on the row flips it, which is the mobile-sized target. */
@Composable
fun PulseSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true
) {
    val c = LocalPulseColors.current
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PulseSizes.touchTarget.dp)
            .then(
                if (onCheckedChange != null && enabled) {
                    Modifier
                        .clip(RoundedCornerShape(PulseRadius.md.dp))
                        .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .padding(vertical = PulseSpacing.sm.dp, horizontal = PulseSpacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) c.textPrimary else c.disabled
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = c.onAccent,
                uncheckedTrackColor = c.borderStrong,
                uncheckedThumbColor = c.surfaceElevated,
                uncheckedBorderColor = c.borderSubtle
            )
        )
    }
}

@Composable
fun PulseCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = LocalPulseColors.current
    Row(
        modifier
            .defaultMinSize(minHeight = PulseSizes.touchTarget.dp)
            .padding(vertical = PulseSpacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
            colors = CheckboxDefaults.colors(
                checkedColor = c.accent,
                checkmarkColor = c.onAccent,
                uncheckedColor = c.textSecondary
            )
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) c.textPrimary else c.disabled
        )
    }
}

/** One choice in a radio group. */
@Composable
fun PulseRadio(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val c = LocalPulseColors.current
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PulseSizes.touchTarget.dp)
            .clip(RoundedCornerShape(PulseRadius.md.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = PulseSpacing.sm.dp, horizontal = PulseSpacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.semantics { contentDescription = label },
            colors = RadioButtonDefaults.colors(
                selectedColor = c.accent,
                unselectedColor = c.textSecondary
            )
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

@Composable
fun <T> PulseDropdown(
    selected: T?,
    options: List<T>,
    onSelected: (T) -> Unit,
    label: String,
    optionText: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = LocalPulseColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { if (enabled) open = true },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .semantics { contentDescription = label },
            shape = RoundedCornerShape(PulseRadius.md.dp),
            color = c.surfaceSunken,
            border = BorderStroke(PulseHairline, c.borderStrong),
            enabled = enabled
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PulseSpacing.md.dp, vertical = PulseSpacing.md.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(
                        selected?.let(optionText) ?: "Not set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) c.textPrimary else c.disabled
                    )
                }
                Icon(Icons.Rounded.ArrowDropDown, null, tint = c.textTertiary)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = c.surfaceOverlay
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option), color = c.onOverlay) },
                    onClick = { open = false; onSelected(option) }
                )
            }
        }
    }
}

// ── Menus ──────────────────────────────────────────────────────────────────

/**
 * An action offered by a three-dot menu. Consequential actions are expected to confirm through
 * their own [onClick] — the menu itself never decides that.
 */
@Immutable
data class PulseAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true
)

/**
 * The three-dot menu every row and card carries. It opens anchored to the trigger, closes on
 * Escape and outside taps, and stays keyboard-navigable because it is the stock M3 menu rather
 * than a hand-rolled popup.
 */
@Composable
fun PulseOverflowMenu(
    actions: List<PulseAction>,
    label: String,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        PulseIconButton(
            icon = Icons.Rounded.MoreVert,
            label = label,
            onClick = { open = true },
            selected = open
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = c.surfaceOverlay,
            modifier = Modifier.semantics { contentDescription = label }
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) c.danger else c.onOverlay,
                            fontWeight = if (action.destructive) FontWeight.Medium else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        if (action.icon != null) {
                            Icon(
                                action.icon, null,
                                tint = if (action.destructive) c.danger else c.textSecondary,
                                modifier = Modifier.size(PulseSizes.icon.dp)
                            )
                        }
                    },
                    enabled = action.enabled,
                    onClick = { open = false; action.onClick() }
                )
            }
        }
    }
}

// ── Tabs ───────────────────────────────────────────────────────────────────

/**
 * Primary tabs. The active tab reads bold text *and* an accent indicator, so the selected panel
 * is legible without relying on colour alone, and each tab announces its count when one exists.
 */
@Composable
fun PulseTabs(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<Int?> = emptyList()
) {
    val c = LocalPulseColors.current
    TabRow(
        selectedTabIndex = selected,
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = c.textPrimary,
        divider = { Box(Modifier.height(PulseHairline).background(c.borderSubtle)) },
        indicator = { positions ->
            val current: TabPosition = positions.getOrNull(selected) ?: return@TabRow
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(current),
                height = 2.5.dp,
                color = c.accent
            )
        }
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            val count = counts.getOrNull(index)
            Box(
                Modifier
                    .fillMaxWidth(1f / items.size)
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics {
                        contentDescription = if (count != null) "$item, $count" else item
                    }
                    .padding(vertical = PulseSpacing.md.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) c.textPrimary else c.textSecondary
                )
            }
        }
    }
}

// ── Identity ───────────────────────────────────────────────────────────────

/**
 * The one avatar in the app: initials on a per-contact colour from the identity palette, or a
 * picture when there is one. The browser paints the same six colours, so a peer is the same face
 * on both clients.
 */
@Composable
fun PulseAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = PulseSizes.avatar,
    image: ImageBitmap? = null
) {
    val color = remember(name) {
        PulseAvatarPalette[(name.hashCode() and Int.MAX_VALUE) % PulseAvatarPalette.size]
    }
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = name,
                modifier = Modifier.size(size.dp).clip(CircleShape)
            )
        } else {
            Box(
                Modifier
                    .size(size.dp)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initialsOf(name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = PulseAvatarInk
                )
            }
        }
    }
}

/** Up to two letters: first word's first, last word's first. Matches the browser's rule. */
fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

// ── List rows ──────────────────────────────────────────────────────────────

/**
 * The standard row: leading slot, title with optional overline and subtitle, trailing actions.
 * Device, file, transfer and conversation lists are all this, so a page is data plus actions
 * rather than a bespoke layout per feature.
 */
@Composable
fun PulseListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    overline: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    selected: Boolean = false
) {
    val c = LocalPulseColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PulseRadius.md.dp))
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        shape = RoundedCornerShape(PulseRadius.md.dp),
        color = if (selected) c.highlight else Color.Transparent
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = PulseSizes.touchTarget.dp)
                .padding(horizontal = PulseSpacing.lg.dp, vertical = PulseSpacing.md.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                if (overline != null) {
                    Text(overline, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = c.textPrimary,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                        maxLines = 2
                    )
                }
            }
            trailing()
        }
    }
}
