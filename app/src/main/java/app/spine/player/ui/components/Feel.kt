package app.spine.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The app's vocabulary of vibration: each kind of touch has its own feel, so the hand can
 * tell a tap from a switch from a drag without looking.
 *
 * Everything goes through the phone's own touch feedback, so it needs no permission and
 * stays silent if the user has touch vibration switched off for the whole phone. The app's
 * own switch in Settings is applied by [SwitchableHaptics].
 */
class Feel(private val feedback: HapticFeedback) {

    /** A finger lands on anything tappable. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.VirtualKey)

    /** A drag passes a small mark, such as a few seconds of a song. Light, made to repeat. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

    /** A value lands on a new step, such as one decibel, or a choice moves to the next one. */
    fun step() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** A drag takes hold of something. */
    fun grab() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** A drag lets go and what it did is applied. */
    fun letGo() = feedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

    /** Something turns on or off: a switch, shuffle, a favourite. */
    fun switch(on: Boolean) =
        feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** Something was made or set: a playlist created, a song added, a timer started. */
    fun done() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** Something was taken away for good, or could not be done. */
    fun warn() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberFeel(): Feel {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Feel(feedback) }
}

/** Passes vibration on to the phone only while [enabled] says so. */
class SwitchableHaptics(
    private val platform: HapticFeedback,
    private val enabled: () -> Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) platform.performHapticFeedback(hapticFeedbackType)
    }
}
