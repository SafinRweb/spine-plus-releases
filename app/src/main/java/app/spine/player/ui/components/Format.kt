package app.spine.player.ui.components

import app.spine.player.data.Song
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** "3:42", or "1:02:10" for anything over an hour. */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** "34 min" or "1 h 12 min", for totals. */
fun formatLength(durationMs: Long): String {
    val totalMinutes = ((durationMs + 30_000) / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0L -> "$hours h"
        else -> "$hours h $minutes min"
    }
}

fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

/**
 * "Songs lie down": a strip's height follows the song's length at [dpPerMinute], but never
 * smaller than a comfortable touch target or so tall that one long track fills the screen.
 */
fun stripHeight(durationMs: Long, dpPerMinute: Float = 17f): Dp =
    (durationMs / 60_000f * dpPerMinute).coerceIn(48f, 200f).dp

/** "Albums stand up": a longer album is a wider spine. */
fun spineWidth(durationMs: Long): Dp =
    (40f + durationMs / 60_000f * 0.7f).coerceIn(44f, 84f).dp

/**
 * A thing named as the user's own: "Safin's Shelf" for the name Safin, and plain "Shelf"
 * when no name was given.
 */
fun owned(name: String, word: String): String = if (name.isBlank()) word else "${name.trim()}'s $word"

/** How many of each a list holds, in words: "6 songs · 3 videos", or just the kind there is. */
fun itemCount(songs: List<Song>): String {
    val videos = songs.count { it.isVideo }
    val songCountOnly = songs.size - videos
    return listOfNotNull(
        songCount(songCountOnly).takeIf { songCountOnly > 0 || videos == 0 },
        (if (videos == 1) "1 video" else "$videos videos").takeIf { videos > 0 },
    ).joinToString(" · ")
}
