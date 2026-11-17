package app.spine.player.playback

import android.content.Context
import androidx.core.content.edit

/** Remembers the queue and where playback stopped, so the app reopens where it was left. */
class PlaybackStore(context: Context) {

    data class Saved(
        val songIds: List<Long>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun load(): Saved? {
        val ids = prefs.getString(KEY_QUEUE, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        if (ids.isEmpty()) return null
        return Saved(
            songIds = ids,
            index = prefs.getInt(KEY_INDEX, 0),
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0),
        )
    }

    fun saveQueue(songIds: List<Long>) = prefs.edit { putString(KEY_QUEUE, songIds.joinToString(",")) }

    fun savePosition(index: Int, positionMs: Long) = prefs.edit {
        putInt(KEY_INDEX, index)
        putLong(KEY_POSITION, positionMs)
    }

    fun saveModes(shuffle: Boolean, repeatMode: Int) = prefs.edit {
        putBoolean(KEY_SHUFFLE, shuffle)
        putInt(KEY_REPEAT, repeatMode)
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}
