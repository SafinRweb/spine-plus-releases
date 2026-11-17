package app.spine.player.ui.components

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.spine.player.ui.theme.Play
import kotlinx.coroutines.delay

/**
 * Turns content a quarter turn so it reads from bottom to top, like the title on a record
 * spine. The content is measured against the available height, so long text wraps or is
 * cut by the height of the spine and not by its width.
 */
fun Modifier.rotateVertically(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width - placeable.height) / 2,
            y = -(placeable.height - placeable.width) / 2,
        ) { rotationZ = -90f }
    }
}

/** "Blue rises as you play": fills the bottom [fraction] of the area with the play colour. */
fun Modifier.risingFill(fraction: Float, color: Color = Play): Modifier = drawBehind {
    if (fraction > 0f) {
        val filled = size.height * fraction.coerceAtMost(1f)
        drawRect(color, topLeft = Offset(0f, size.height - filled), size = Size(size.width, filled))
    }
}

/**
 * The same fill, read while drawing. Use this when the level moves every frame, so that
 * only the drawing is redone and not the layout around it.
 */
fun Modifier.risingFill(color: Color = Play, fraction: () -> Float): Modifier = drawBehind {
    val level = fraction().coerceIn(0f, 1f)
    if (level > 0f) {
        val filled = size.height * level
        drawRect(color, topLeft = Offset(0f, size.height - filled), size = Size(size.width, filled))
    }
}

/**
 * A soft spring with no bounce. It keeps its speed when the target moves, so the player's
 * position reports (a few a second) blend into one steady rise, and a big jump, such as
 * starting a song in the middle of an album, flows there instead of snapping.
 */
private val LevelSpring = spring<Float>(dampingRatio = 1f, stiffness = 70f, visibilityThreshold = 0.0005f)

/** [target] as it should be drawn: gliding towards the latest value. */
@Composable
fun smoothProgress(target: Float): Float = smoothLevel(target).value

/** As [smoothProgress], but handed over unread so it can be read while drawing. */
@Composable
fun smoothLevel(target: Float): State<Float> =
    animateFloatAsState(targetValue = target, animationSpec = LevelSpring, label = "level")

/**
 * Tap and long-press for anything that opens a screen or a menu. Both report where the
 * item sits on screen: a new screen grows out of that spot, and a menu keeps the item lit
 * while the rest dims.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    onClick: (bounds: Rect?) -> Unit,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
): Modifier = composed {
    val coordinates = remember { Ref<LayoutCoordinates>() }
    fun bounds(): Rect? = coordinates.value?.takeIf { it.isAttached }?.boundsInRoot()
    onGloballyPositioned { coordinates.value = it }
        .combinedClickable(
            onClick = { onClick(bounds()) },
            onLongClick = onLongPress?.let { report -> { bounds()?.let(report) } },
        )
}

/**
 * True while the app is still opening. Items composed in that time arrive one after
 * another with [stackIn]; anything composed later, such as a row scrolled into view,
 * is simply there.
 */
val LocalEntrance = compositionLocalOf { false }

/**
 * False when the user has switched animations off in Settings. Compose's own animations
 * are stopped at the root (see MainActivity); this is for the places that also wait before
 * they start, which would otherwise still arrive one after another.
 */
val LocalAnimations = compositionLocalOf { true }

/** Where an item comes from as the app opens. */
enum class StackFrom {
    /** Dropped in from above, like a record put on the shelf. For things that stand up. */
    Above,

    /** Slid up from below, like a strip added to a pile. For things that lie down. */
    Below,
}

/**
 * The opening animation: the item fades in and settles into place with a small bounce,
 * [order] places later than the first. Does nothing once the app has opened.
 */
fun Modifier.stackIn(order: Int, from: StackFrom = StackFrom.Below): Modifier = composed {
    val entering = LocalEntrance.current && LocalAnimations.current
    // Decided once: an item that began arriving finishes, and one that came later never starts.
    val animate = remember { entering }
    if (!animate) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // The frame that first lays out a full screen is a long one. Count from the frame
        // after it: otherwise the waits below are over before anything has been seen, and
        // the first items are already in place instead of arriving one after another.
        withFrameNanos { }
        delay(order.coerceIn(0, 18) * 45L)
        progress.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 220f))
    }
    graphicsLayer {
        val arrived = progress.value
        alpha = (arrived * 2f).coerceIn(0f, 1f)
        val distance = if (from == StackFrom.Above) -64.dp.toPx() else 48.dp.toPx()
        // The spring runs a little past 1 before settling, which is the bounce.
        translationY = (1f - arrived) * distance
    }
}

/** Fades and lifts an item into place shortly after its screen opens; [order] staggers a list. */
fun Modifier.riseIn(order: Int): Modifier = composed {
    if (!LocalAnimations.current) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // As in stackIn: wait out the long first frame of a new screen before counting.
        withFrameNanos { }
        delay(140L + order.coerceAtMost(14) * 28L)
        progress.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 28.dp.toPx()
    }
}

/**
 * Sets the colour of the status and navigation bar icons for the screen on show:
 * dark icons on the light browsing screens, light icons on the dark ones.
 */
@Composable
fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
