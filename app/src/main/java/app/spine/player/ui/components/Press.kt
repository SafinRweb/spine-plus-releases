package app.spine.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * How anything tappable answers a touch: it presses in a little, gives a small tick, and
 * springs back. This replaces the stock grey flash, which reads as a box laid over the item.
 *
 * It scales whatever is drawn after the click modifier, so a tappable item puts its
 * background after `clickable` or `pressable` for the whole item to move, not just its text.
 */
object PressIn : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressInNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

private class PressInNode(private val interactionSource: InteractionSource) :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {

    /** 0 at rest, 1 fully pressed in. */
    private val pressed = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        // The same tick as Feel.tap(). Inside a scrolling list a press is only
                        // reported once the touch has proved not to be a scroll, so flicking
                        // through the library stays silent.
                        currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.VirtualKey)
                        pressed.animateTo(1f, tween(durationMillis = 90))
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // A quick tap lifts before the press-in has got anywhere; finish most
                        // of it first so even the fastest tap is answered.
                        if (pressed.value < 0.6f) pressed.animateTo(0.6f, tween(durationMillis = 60))
                        pressed.animateTo(
                            0f,
                            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                        )
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val amount = pressed.value
        if (amount == 0f) {
            drawContent()
        } else {
            // Lose about the same few pixels whatever the size: a small icon needs a bigger
            // share to be seen moving, a full-width strip a smaller one.
            val shrink = (10f * density / size.maxDimension).coerceIn(0.02f, 0.08f)
            scale(1f - shrink * amount) { this@draw.drawContent() }
        }
    }
}
