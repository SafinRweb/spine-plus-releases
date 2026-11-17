package app.spine.player.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the Stats screen is showing: listening, watching, or what was downloaded. */
enum class StatsKind(val label: String) {
    Songs("Songs"),
    Videos("Videos"),
    Downloads("Downloads"),
}

/** One sitting of watching, as the play log has it. [songId] is the video's negative id. */
data class WatchRow(val songId: Long, val playedAtMs: Long, val watchedMs: Long)

/** How long one video was watched in a period. */
data class VideoWatched(val video: Song, val watchedMs: Long)

/** Watching in a period. Only the videos the Downloader saved are counted. */
data class VideoStats(
    val watchedMs: Long = 0,
    /** How many times a video was sat down to. */
    val sittings: Int = 0,
    /** How many different videos. */
    val videos: Int = 0,
    val longestMs: Long = 0,
    /** Watching time for each day of the week, Monday first. */
    val byDay: List<Long> = List(7) { 0L },
    /** The most watched, longest first. */
    val top: List<VideoWatched> = emptyList(),
) {
    companion object {
        val DayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

fun buildVideoStats(library: Library, rows: List<WatchRow>, top: Int = 5): VideoStats {
    if (rows.isEmpty()) return VideoStats()
    val byDay = LongArray(7)
    val calendar = Calendar.getInstance()
    for (row in rows) {
        calendar.timeInMillis = row.playedAtMs
        // Calendar counts Sunday as 1; the list starts on Monday.
        byDay[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7] += row.watchedMs
    }
    val perVideo = rows.groupBy({ it.songId }, { it.watchedMs }).mapValues { it.value.sum() }
    return VideoStats(
        watchedMs = rows.sumOf { it.watchedMs },
        sittings = rows.size,
        videos = perVideo.size,
        longestMs = rows.maxOf { it.watchedMs },
        byDay = byDay.toList(),
        // A video deleted since still counts towards the time, but has no name to list.
        top = perVideo.entries.sortedByDescending { it.value }
            .mapNotNull { (id, ms) -> library.songsById[id]?.let { VideoWatched(it, ms) } }
            .take(top),
    )
}

/** What was downloaded on one day. */
data class DownloadDay(val label: String, val songs: Int, val videos: Int) {
    val total: Int get() = songs + videos
}

/**
 * What the Downloader has brought in during a period, worked out from the files it saved
 * that are still on the phone (so one deleted since is not counted).
 */
data class DownloadStats(
    val songs: Int = 0,
    val videos: Int = 0,
    val bytes: Long = 0,
    /** How long they would play, one after the other. */
    val playMs: Long = 0,
    /** The last fourteen days, oldest first, whatever the period. */
    val days: List<DownloadDay> = emptyList(),
    /** The day of those fourteen with the most, if any had any. */
    val busiest: DownloadDay? = null,
    /** The kind of file downloaded most, such as "M4A". */
    val topKind: String = "",
    /** How many of the songs have never been played. */
    val neverPlayed: Int = 0,
) {
    val total: Int get() = songs + videos

    /** How wide a shelf the songs would fill as CDs, in centimetres: twelve songs to a disc, a centimetre a case. */
    val shelfCm: Int get() = Math.round(songs / 12f)
}

/** The folders the Downloader saves into, as [Song.folder] writes them. */
private const val SONG_FOLDER = "Music/Spine"

fun buildDownloadStats(
    library: Library,
    sinceMs: Long,
    allTimePlays: Map<Long, PlayTotal>,
    nowMs: Long = System.currentTimeMillis(),
): DownloadStats {
    val allSongs = library.songs.filter { it.folder == SONG_FOLDER || it.folder.startsWith("$SONG_FOLDER/") }
    val allVideos = library.videos
    val sinceSec = sinceMs / 1000
    val songs = allSongs.filter { it.dateAddedSec >= sinceSec }
    val videos = allVideos.filter { it.dateAddedSec >= sinceSec }

    val dayStart = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -13)
    }
    val format = SimpleDateFormat("d MMM", Locale.getDefault())
    val days = (0 until 14).map {
        val fromSec = dayStart.timeInMillis / 1000
        val label = format.format(Date(dayStart.timeInMillis))
        dayStart.add(Calendar.DAY_OF_YEAR, 1)
        val toSec = dayStart.timeInMillis / 1000
        DownloadDay(
            label = label,
            songs = allSongs.count { it.dateAddedSec in fromSec until toSec },
            videos = allVideos.count { it.dateAddedSec in fromSec until toSec },
        )
    }
    val kinds = (songs + videos).groupingBy { it.fileName.substringAfterLast('.', "").uppercase() }.eachCount()
    return DownloadStats(
        songs = songs.size,
        videos = videos.size,
        bytes = (songs + videos).sumOf { it.sizeBytes },
        playMs = (songs + videos).sumOf { it.durationMs },
        days = days,
        busiest = days.maxByOrNull { it.total }?.takeIf { it.total > 0 },
        topKind = kinds.maxByOrNull { it.value }?.key.orEmpty(),
        neverPlayed = songs.count { it.id !in allTimePlays },
    )
}
