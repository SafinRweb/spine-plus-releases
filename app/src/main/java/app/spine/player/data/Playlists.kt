package app.spine.player.data

import java.util.Calendar

/**
 * A playlist ready for the screens. One the user made lists the songs they put in it; a
 * smart one ([rule] is set) is worked out from a rule each time and cannot be edited.
 */
data class Playlist(
    val id: Long,
    val name: String,
    val songs: List<Song>,
    /** What a smart playlist collects, in words; null for the user's own playlists. */
    val rule: String? = null,
    /** How many songs the user has taken out of a smart playlist; they can be brought back. */
    val hiddenCount: Int = 0,
) {
    val durationMs: Long = songs.sumOf { it.durationMs }

    /** How many of [songs] are videos. */
    val videoCount: Int = songs.count { it.isVideo }
    val isSmart: Boolean get() = rule != null
    val isFavourites: Boolean get() = id == LibraryStore.FAVOURITES_ID
}

/** Smart playlists have fixed negative ids so they never clash with stored ones. */
private const val MOST_PLAYED_ID = -1L
private const val RECENTLY_ADDED_ID = -2L
private const val MOST_PLAYED_MIN_PLAYS = 3
private const val RECENT_DAYS = 30

/** The user's playlists followed by the smart ones. */
fun buildPlaylists(
    library: Library,
    stored: List<StoredPlaylist>,
    allTimePlays: Map<Long, PlayTotal>,
    hiddenFromSmart: Map<Long, Set<Long>> = emptyMap(),
    nowMs: Long = System.currentTimeMillis(),
): List<Playlist> {
    fun smart(id: Long, name: String, matching: List<Song>, rule: String): Playlist {
        val hidden = hiddenFromSmart[id].orEmpty()
        val shown = if (hidden.isEmpty()) matching else matching.filterNot { it.id in hidden }
        return Playlist(id, name, shown, rule, hiddenCount = matching.size - shown.size)
    }

    val own = stored.map { playlist ->
        Playlist(playlist.id, playlist.name, playlist.songIds.mapNotNull { library.songsById[it] })
    }
    val mostPlayed = allTimePlays.entries
        .filter { it.value.plays >= MOST_PLAYED_MIN_PLAYS }
        .sortedByDescending { it.value.plays }
        // "Most played" is a list of songs; a video watched often does not belong in it.
        .mapNotNull { library.songsById[it.key]?.takeIf { song -> !song.isVideo } }
    val recentCutoffSec = nowMs / 1000 - RECENT_DAYS * 24L * 3600
    val recentlyAdded = library.songs
        .filter { it.dateAddedSec >= recentCutoffSec }
        .sortedByDescending { it.dateAddedSec }

    return own + listOf(
        smart(MOST_PLAYED_ID, "Most played", mostPlayed, "played $MOST_PLAYED_MIN_PLAYS times or more"),
        smart(RECENTLY_ADDED_ID, "Recently added", recentlyAdded, "added in the last $RECENT_DAYS days"),
    )
}

enum class StatsPeriod(val label: String) {
    Week("Week"),
    Month("Month"),
    Year("Year"),
    All("All");

    /** The moment this period started, as epoch milliseconds. */
    fun startMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (this == All) return 0L
        return Calendar.getInstance().apply {
            timeInMillis = nowMs
            when (this@StatsPeriod) {
                Week -> add(Calendar.DAY_OF_YEAR, -7)
                Month -> add(Calendar.MONTH, -1)
                Year -> add(Calendar.YEAR, -1)
                All -> Unit
            }
        }.timeInMillis
    }
}

/** An artist's share of the listening in a period. */
data class ArtistListening(val artist: Artist?, val name: String, val listenedMs: Long)

/** How often one song was played in a period. */
data class SongPlays(val song: Song, val plays: Int)

data class Stats(
    val listenedMs: Long = 0,
    val plays: Int = 0,
    val songs: Int = 0,
    /** The most listened-to artists, longest first. */
    val topArtists: List<ArtistListening> = emptyList(),
    /** The most played songs, most plays first. */
    val topSongs: List<SongPlays> = emptyList(),
)

fun buildStats(library: Library, totals: Map<Long, PlayTotal>, rows: Int = 6): Stats {
    // Songs only: watching a video is counted on its own, not as listening.
    val played = totals.mapNotNull { (songId, total) ->
        library.songsById[songId]?.takeIf { !it.isVideo }?.let { it to total }
    }
    val byArtist = played.groupBy({ it.first.artist }, { it.second.listenedMs })
        .mapValues { it.value.sum() }
    return Stats(
        listenedMs = played.sumOf { it.second.listenedMs },
        plays = played.sumOf { it.second.plays },
        songs = played.size,
        topArtists = byArtist.entries
            .sortedByDescending { it.value }
            .take(rows)
            .map { ArtistListening(library.artist(it.key), it.key, it.value) },
        topSongs = played
            .sortedByDescending { it.second.plays }
            .take(rows)
            .map { SongPlays(it.first, it.second.plays) },
    )
}
