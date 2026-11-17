package app.spine.player.data

import android.content.Context
import androidx.core.content.edit

/**
 * How far into each video the user has watched, kept so a video carries on where it was
 * left and so the Videos tab can show the play colour risen that far in its row.
 *
 * One line is kept for each video, under its address: the position and the length, in
 * milliseconds. A video watched to within its last few seconds counts as watched whole.
 */
object VideoProgress {

    private const val PREFS = "video_progress"

    /** A video left this close to its end starts from the beginning next time. */
    private const val NEAR_END_MS = 8_000L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Notes that [uri] has been watched up to [positionMs] of [durationMs]. */
    fun note(context: Context, uri: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        prefs(context).edit { putString(uri, "$positionMs,$durationMs") }
    }

    private fun read(context: Context, uri: String): Pair<Long, Long>? {
        val parts = prefs(context).getString(uri, null)?.split(',') ?: return null
        val position = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val duration = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return position to duration
    }

    /** Where to start [uri] from: where it was left, or the beginning if it was watched to the end. */
    fun resumeAt(context: Context, uri: String): Long {
        val (position, duration) = read(context, uri) ?: return 0L
        return if (position >= duration - NEAR_END_MS) 0L else position
    }

    /** How much of [uri] has been watched, from 0 to 1. */
    fun fraction(context: Context, uri: String): Float {
        val (position, duration) = read(context, uri) ?: return 0f
        return if (position >= duration - NEAR_END_MS) 1f else (position.toFloat() / duration).coerceIn(0f, 1f)
    }

    /** Forgets a video, for when its file has been deleted. */
    fun forget(context: Context, uri: String) {
        prefs(context).edit { remove(uri) }
    }
}
