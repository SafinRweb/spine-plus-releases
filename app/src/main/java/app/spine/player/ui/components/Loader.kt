package app.spine.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.Play

/** How wide each of the loader's spines is, as a share of its height; like albums, no two alike. */
private val SpineShares = floatArrayOf(0.18f, 0.30f, 0.22f, 0.36f, 0.26f)
private const val GapShare = 0.06f

/** How wide a [SpineLoader] of this height is. */
fun spineLoaderWidth(height: Dp): Dp = height * (SpineShares.sum() + GapShare * (SpineShares.size - 1))

/**
 * The app's way of saying "working on it": five album spines standing side by side, filling
 * with the play colour one after another from the left, then draining together and starting
 * again. It is the same rise that shows a song playing, on a loop.
 *
 * [base] is the colour of an empty spine: ink on paper, or a raised shade on a dark screen.
 * With animations switched off in Settings the spines stand still, part filled.
 */
@Composable
fun SpineLoader(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    base: Color = Ink,
    fill: Color = Play,
    label: String = "Working",
) {
    val count = SpineShares.size
    val phase: State<Float> = if (LocalAnimations.current) {
        rememberInfiniteTransition(label = "loader").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_500, easing = LinearEasing), RepeatMode.Restart),
            label = "loader phase",
        )
    } else {
        remember { mutableFloatStateOf(0.45f) }
    }
    Canvas(
        modifier = modifier
            .size(width = spineLoaderWidth(height), height = height)
            .semantics { contentDescription = label },
    ) {
        // The loop runs in "spines": one unit of time fills one spine, and the last unit
        // and a half drains them all.
        val time = phase.value * (count + 1.5f)
        val drain = ((time - count) / 1.5f).coerceIn(0f, 1f)
        val gap = size.height * GapShare
        var x = 0f
        for (index in 0 until count) {
            val width = size.height * SpineShares[index]
            val level = (time - index).coerceIn(0f, 1f) * (1f - drain)
            drawRect(base, Offset(x, 0f), Size(width, size.height))
            if (level > 0f) {
                val risen = size.height * level
                drawRect(fill, Offset(x, size.height - risen), Size(width, risen))
            }
            x += width + gap
        }
    }
}
