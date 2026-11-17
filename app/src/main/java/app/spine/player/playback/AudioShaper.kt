package app.spine.player.playback

import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Applies the user's audio choices to the player: the equalizer, the fade between songs,
 * and evening out volume. Lives in the playback service.
 *
 * The fade is a fade-out followed by a fade-in, not a true crossfade: the two songs do not
 * overlap, which would need a second player running alongside the first.
 */
class AudioShaper(
    private val player: Player,
    private val settings: AudioSettings,
    audioSessionId: Int,
    scope: CoroutineScope,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    // Some phones ship without an equalizer effect, or refuse to attach one.
    private val equalizer: Equalizer? = runCatching { Equalizer(0, audioSessionId) }.getOrNull()

    /** How much to turn the loaded song down, from its volume tag; 1 when it has none. */
    private var tagVolume = 1f

    /** True while the song that just started on its own is fading in. */
    private var fadingIn = false

    private val tick = object : Runnable {
        override fun run() {
            applyVolume()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        if (equalizer == null) {
            settings.eqUnavailable.value = true
        } else {
            val range = equalizer.bandLevelRange
            val count = equalizer.numberOfBands.toInt()
            settings.bands.value = EqBands(
                // The platform reports milliHertz.
                centerHz = (0 until count).map { equalizer.getCenterFreq(it.toShort()) / 1000 },
                minMb = range[0].toInt(),
                maxMb = range[1].toInt(),
            )
            scope.launch { settings.eq.collect(::applyEqualizer) }
        }
        scope.launch { settings.fadeSeconds.collect { restartTick() } }
        scope.launch { settings.normalize.collect { applyVolume() } }
        player.addListener(this)
    }

    fun release() {
        handler.removeCallbacks(tick)
        player.removeListener(this)
        equalizer?.release()
    }

    private fun applyEqualizer(eq: EqSettings) {
        val effect = equalizer ?: return
        val bands = settings.bands.value ?: return
        runCatching {
            bands.centerHz.indices.forEach { band ->
                val level = eq.levelsMb.getOrElse(band) { 0 }.coerceIn(bands.minMb, bands.maxMb)
                effect.setBandLevel(band.toShort(), level.toShort())
            }
            effect.enabled = eq.enabled
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = restartTick()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Only a song that follows on its own fades in; one the user picked starts at once.
        fadingIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && settings.fadeSeconds.value > 0
        tagVolume = 1f
        applyVolume()
    }

    override fun onTracksChanged(tracks: Tracks) {
        tagVolume = replayGainDb(tracks)?.let { 10f.pow(it / 20f).coerceIn(0.1f, 1f) } ?: 1f
        applyVolume()
    }

    /** The fade only needs a clock while something is playing and a fade is switched on. */
    private fun restartTick() {
        handler.removeCallbacks(tick)
        if (player.isPlaying && settings.fadeSeconds.value > 0) handler.post(tick) else applyVolume()
    }

    private fun applyVolume() {
        var fade = 1f
        val fadeMs = settings.fadeSeconds.value * 1000L
        val duration = player.duration
        if (fadeMs > 0 && duration != C.TIME_UNSET && duration > fadeMs * 2) {
            val position = player.currentPosition
            val remaining = duration - position
            if (remaining < fadeMs) {
                fade = (remaining.toFloat() / fadeMs).coerceIn(0f, 1f)
            } else if (fadingIn) {
                val fadeInMs = fadeMs / 2
                if (position < fadeInMs) fade = (position.toFloat() / fadeInMs).coerceIn(0.05f, 1f) else fadingIn = false
            }
        }
        player.volume = fade * if (settings.normalize.value) tagVolume else 1f
    }

    /** Reads a ReplayGain track gain from the file's tags, in decibels, if it has one. */
    private fun replayGainDb(tracks: Tracks): Float? {
        for (group in tracks.groups) {
            for (index in 0 until group.length) {
                val metadata = group.getTrackFormat(index).metadata ?: continue
                for (entryIndex in 0 until metadata.length()) {
                    val text = when (val entry = metadata[entryIndex]) {
                        is VorbisComment -> entry.value.takeIf { entry.key.equals(REPLAYGAIN_TAG, ignoreCase = true) }
                        is TextInformationFrame ->
                            entry.values.firstOrNull().takeIf { entry.description.equals(REPLAYGAIN_TAG, ignoreCase = true) }
                        else -> null
                    }
                    // Written as "-6.54 dB".
                    text?.trim()?.substringBefore(' ')?.toFloatOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private companion object {
        const val TICK_MS = 100L
        const val REPLAYGAIN_TAG = "REPLAYGAIN_TRACK_GAIN"
    }
}
