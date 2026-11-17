package app.spine.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.spine.player.ui.theme.GhostLine
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.Palette
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.InkLine
import app.spine.player.ui.theme.Play

/** An icon with a full-size touch target. */
@Composable
fun IconTap(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** "< Shelf": the way back out of a screen. */
@Composable
fun BackLink(text: String, onClick: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(SpineIcons.ChevronLeft, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text = text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The top of a screen that can be edited: the way back on the left and "Edit" on the right.
 * Without [onEdit] it is the back link alone.
 */
@Composable
fun TopLinks(back: String, onBack: () -> Unit, color: Color, onEdit: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackLink(text = back, onClick = onBack, color = color)
        if (onEdit != null) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .clickable(role = Role.Button, onClick = onEdit)
                    .padding(start = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(text = "Edit", color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * A square-cornered text button. [onInk] says which kind of screen it sits on, so a filled
 * button is white on a dark screen and black on a light one.
 */
@Composable
fun BlockButton(
    text: String,
    onClick: () -> Unit,
    onInk: Boolean,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    /** The room at each side of the words. Less of it where two buttons share a narrow popup. */
    sidePadding: Dp = 18.dp,
) {
    // On dark paper a block of the shelf colour would hardly show as a button, so the main
    // button there is the page's opposite: light, with dark words.
    val lightOnDarkPaper = filled && !onInk && Palette.dark
    val content = when {
        // On a dark screen the main button is white, whatever the paper is.
        filled && onInk -> Color(0xFF111113)
        lightOnDarkPaper -> Paper
        filled || onInk -> Color.White
        else -> PaperText
    }
    val shape = when {
        filled && onInk -> Modifier.background(Color.White)
        lightOnDarkPaper -> Modifier.background(PaperText)
        filled -> Modifier.background(Ink)
        onInk -> Modifier.border(1.dp, InkLine)
        else -> Modifier.border(1.dp, InkOnPaper)
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .then(shape)
            .padding(horizontal = sidePadding),
        contentAlignment = Alignment.Center,
    ) {
        // One line always: a button squeezed for room is cut short, never stood on end a letter to a line.
        Text(
            text = text,
            color = content,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An on/off switch in the app's own shapes: a pill that turns the play colour when on. */
@Composable
fun SpineSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    onInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val knobOffset by animateDpAsState(if (checked) 22.dp else 0.dp, tween(180), label = "switch")
    val track by animateColorAsState(
        if (checked) Play else if (onInk) InkLine else GhostLine,
        tween(180),
        label = "switch colour",
    )
    val feel = rememberFeel()
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 44.dp)
            .toggleable(value = checked, role = Role.Switch) { on ->
                // Change first, so the vibration switch is felt going on but not going off.
                onCheckedChange(on)
                feel.switch(on)
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(CircleShape)
                .background(track)
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .offset(x = knobOffset)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
