package app.spine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.risingFill
import app.spine.player.ui.components.rotateVertically
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PlayColour
import app.spine.player.ui.theme.ShelfColour

/** Which of the two colour settings a sheet is choosing. */
enum class ColourSetting { Play, Shelf }

/** One colour on offer, as the sheet draws it. */
private class Swatch(val label: String, val colour: Color, val chosen: Boolean, val pick: () -> Unit)

/**
 * The sheet that picks the play colour or the shelf colour. Every colour stands as a spine
 * and the chosen one stands taller. A tap changes the colour at once, across the whole
 * app, and the sheet stays open so another can be tried; the strip under the spines shows
 * the two colours together. Pass null for [setting] to close it.
 */
@Composable
fun ColourSheet(
    setting: ColourSetting?,
    playColour: PlayColour,
    shelfColour: ShelfColour,
    /** The song to show in the sample strip. */
    songTitle: String,
    songArtist: String,
    onPickPlay: (PlayColour) -> Unit,
    onPickShelf: (ShelfColour) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep the last sheet so it is still there to draw while it slides away.
    var last by remember { mutableStateOf(setting) }
    if (setting != null) last = setting
    val shown = setting ?: last ?: return
    val dismiss by rememberUpdatedState(onDismiss)

    BackHandler(enabled = setting != null, onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = setting != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
        }
        AnimatedVisibility(
            visible = setting != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it },
        ) {
            val swatches = when (shown) {
                ColourSetting.Play -> PlayColour.entries.map {
                    Swatch(it.label, it.colour, it == playColour) { onPickPlay(it) }
                }
                ColourSetting.Shelf -> ShelfColour.entries.map {
                    Swatch(it.label, it.colour, it == shelfColour) { onPickShelf(it) }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Paper)
                    .drawBehind { drawRect(PaperText, Offset.Zero, Size(size.width, 2.dp.toPx())) }
                    // Swallow taps on the sheet's own padding so they do not close it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (shown == ColourSetting.Play) "PLAY COLOUR" else "SHELF COLOUR",
                    color = PaperText,
                    fontFamily = BarlowCondensed,
                    fontSize = 36.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (shown == ColourSetting.Play) {
                        "The colour that rises as you play. It changes the moment you tap one."
                    } else {
                        "The colour of albums, songs and the dark screens. The paper stays as it is."
                    },
                    color = PaperMuted,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHOSEN_HEIGHT)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    swatches.forEach { swatch -> ColourSpine(swatch, Modifier.weight(1f)) }
                }
                SampleStrip(songTitle, songArtist)
            }
        }
    }
}

private val CHOSEN_HEIGHT = 250.dp
private val OTHER_HEIGHT = 200.dp

/** A colour standing as a spine: its name runs up it, and the chosen one grows and gets a tick. */
@Composable
private fun ColourSpine(swatch: Swatch, modifier: Modifier = Modifier) {
    val feel = rememberFeel()
    val height by animateDpAsState(
        targetValue = if (swatch.chosen) CHOSEN_HEIGHT else OTHER_HEIGHT,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "colour spine",
    )
    Box(
        modifier = modifier
            .height(height)
            .selectable(selected = swatch.chosen, role = Role.RadioButton) {
                if (!swatch.chosen) feel.done()
                swatch.pick()
            }
            .background(swatch.colour)
            .semantics { contentDescription = swatch.label }
            .padding(vertical = 12.dp),
    ) {
        AnimatedVisibility(
            visible = swatch.chosen,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(180, delayMillis = 120)) + scaleIn(tween(220, delayMillis = 120), initialScale = 0.6f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.6f),
        ) {
            Icon(SpineIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(
            text = swatch.label.uppercase(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .rotateVertically(),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = 22.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
    }
}

/** The two colours as they meet in the app: a song strip in the shelf colour, part played. */
@Composable
private fun SampleStrip(title: String, artist: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Ink)
            .risingFill(0.42f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                color = OnPlayMuted,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(SpineIcons.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}
