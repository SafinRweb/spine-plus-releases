package app.spine.player.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The built-in equalizer shapes. Custom is whatever the user has dragged the bands to. */
enum class EqPreset(val label: String) {
    Flat("Flat"),
    Bass("Bass"),
    Vocal("Vocal"),
    Custom("Custom");

    /** Band levels in millibels (hundredths of a decibel) for bands centred at [centerHz]. */
    fun levelsFor(centerHz: List<Int>): List<Int> = centerHz.map { hz ->
        when (this) {
            Flat, Custom -> 0
            Bass -> when {
                hz < 120 -> 600
                hz < 400 -> 300
                else -> 0
            }
            Vocal -> if (hz in 400..4000) 400 else -100
        }
    }
}

data class EqSettings(
    val enabled: Boolean = false,
    val preset: EqPreset = EqPreset.Flat,
    /** One level per band, in millibels. Empty until the phone's bands are known. */
    val levelsMb: List<Int> = emptyList(),
)

/** What this phone's equalizer offers: its bands and how far each can be pushed. */
data class EqBands(val centerHz: List<Int>, val minMb: Int, val maxMb: Int)

/**
 * The user's audio choices. One instance is shared by the screens, which change it, and
 * the playback service, which applies it, so a change is heard at once.
 */
class AudioSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)

    private val _eq = MutableStateFlow(
        EqSettings(
            enabled = prefs.getBoolean(KEY_EQ_ON, false),
            preset = EqPreset.entries.firstOrNull { it.name == prefs.getString(KEY_EQ_PRESET, null) } ?: EqPreset.Flat,
            levelsMb = prefs.getString(KEY_EQ_LEVELS, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() },
        ),
    )
    val eq: StateFlow<EqSettings> = _eq.asStateFlow()

    /** Set by the playback service once the equalizer exists; null before that. */
    val bands = MutableStateFlow<EqBands?>(null)

    /** True if the phone refused to create an equalizer at all. */
    val eqUnavailable = MutableStateFlow(false)

    private val _fadeSeconds = MutableStateFlow(prefs.getInt(KEY_FADE, 0))

    /** How long each song fades out and the next fades in; 0 is off. */
    val fadeSeconds: StateFlow<Int> = _fadeSeconds.asStateFlow()

    private val _normalize = MutableStateFlow(prefs.getBoolean(KEY_NORMALIZE, false))

    /** Whether to turn loud songs down using the volume tags stored in their files. */
    val normalize: StateFlow<Boolean> = _normalize.asStateFlow()

    fun setEq(settings: EqSettings) {
        _eq.value = settings
        prefs.edit {
            putBoolean(KEY_EQ_ON, settings.enabled)
            putString(KEY_EQ_PRESET, settings.preset.name)
            putString(KEY_EQ_LEVELS, settings.levelsMb.joinToString(","))
        }
    }

    fun setFadeSeconds(seconds: Int) {
        _fadeSeconds.value = seconds
        prefs.edit { putInt(KEY_FADE, seconds) }
    }

    fun setNormalize(on: Boolean) {
        _normalize.value = on
        prefs.edit { putBoolean(KEY_NORMALIZE, on) }
    }

    companion object {
        private const val KEY_EQ_ON = "eq_on"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_EQ_LEVELS = "eq_levels"
        private const val KEY_FADE = "fade_seconds"
        private const val KEY_NORMALIZE = "normalize"

        /** The fade lengths offered, in seconds; 0 is off. */
        val FADE_CHOICES = listOf(0, 2, 4, 6, 8)

        @Volatile
        private var instance: AudioSettings? = null

        fun get(context: Context): AudioSettings =
            instance ?: synchronized(this) {
                instance ?: AudioSettings(context.applicationContext).also { instance = it }
            }
    }
}
