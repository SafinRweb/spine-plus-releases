package app.spine.player.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SleepState {
    data object Off : SleepState

    /** Stop after a set time. [atElapsedMs] is on the clock that keeps running while the phone sleeps. */
    data class After(val minutes: Int, val atElapsedMs: Long) : SleepState

    /** Stop when the song that is playing ends. */
    data object EndOfSong : SleepState
}

/**
 * The sleep timer. The screens set it and the playback service acts on it; it lives only
 * as long as the app does, so closing the app cancels it.
 */
object SleepTimer {
    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state.asStateFlow()

    /** The lengths offered, in minutes. */
    val CHOICES = listOf(15, 30, 45, 60)

    fun stopAfter(minutes: Int) {
        _state.value = SleepState.After(minutes, SystemClock.elapsedRealtime() + minutes * 60_000L)
    }

    fun stopAtEndOfSong() {
        _state.value = SleepState.EndOfSong
    }

    fun off() {
        _state.value = SleepState.Off
    }
}
