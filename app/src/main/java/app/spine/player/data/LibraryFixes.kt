package app.spine.player.data

import kotlin.math.abs

/** A song that is on the phone more than once. [copies] has the best copy first. */
data class DuplicateGroup(val title: String, val artist: String, val copies: List<Song>) {
    val best: Song get() = copies.first()
    val extras: List<Song> get() = copies.drop(1)
}

/**
 * One line on the "Sort artists and albums" screen: what will change, in words, and the
 * edits to the files that carry it out.
 */
data class SortChange(
    /** Unique among the changes, so a list can tell them apart. */
    val key: String,
    val title: String,
    val detail: String,
    val edits: List<Pair<Song, Map<String, String>>>,
)

/** Everything "Sort artists and albums" proposes. */
data class SortFixes(val artists: List<SortChange>, val albums: List<SortChange>) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty()
}

/** Roughly how good a copy sounds: how many bits it spends on each second. */
val Song.kbps: Int get() = if (durationMs > 0) (sizeBytes * 8 / durationMs).toInt() else 0

/** Two files count as the same recording when they are this close in length. */
private const val SAME_LENGTH_MS = 2_000L

/**
 * Songs that are on the phone more than once: the same title and artist (going by the
 * tidied name, so "Artist - Title (Official Video)" matches a copy already named "Title")
 * and the same length to within two seconds. A live version or a remix differs in name or
 * in length and is left alone.
 */
fun findDuplicates(songs: List<Song>): List<DuplicateGroup> {
    data class Named(val song: Song, val title: String, val artist: String)

    val named = songs.map { song ->
        val tidy = suggestFromName(song.title, song.artist)
        Named(song, tidy?.title ?: song.title, tidy?.artist ?: song.artist)
    }
    val groups = mutableListOf<DuplicateGroup>()
    for (sameName in named.groupBy { it.title.letters() to it.artist.letters() }.values) {
        if (sameName.size < 2 || sameName.first().title.letters().isEmpty()) continue
        // Among those, split wherever the lengths jump.
        var run = mutableListOf<Named>()
        fun close() {
            if (run.size > 1) {
                val copies = run.map { it.song }
                    .sortedWith(compareByDescending<Song> { it.kbps }.thenBy { it.dateAddedSec })
                groups += DuplicateGroup(run.first().title, run.first().artist, copies)
            }
        }
        for (item in sameName.sortedBy { it.song.durationMs }) {
            if (run.isNotEmpty() && abs(item.song.durationMs - run.last().song.durationMs) > SAME_LENGTH_MS) {
                close()
                run = mutableListOf()
            }
            run += item
        }
        close()
    }
    return groups.sortedBy { it.title.lowercase() }
}

/** Words an uploading channel adds to an artist's name. */
private val ChannelWords = Regex("""\b(official|vevo|topic)\b|vevo$""", RegexOption.IGNORE_CASE)
private val Collaboration = Regex("""\s*(?:,|&|\bx\b|\bfeat\b\.?|\bft\b\.?)\s*""", RegexOption.IGNORE_CASE)

/** An artist's name with case, spacing, punctuation and channel words taken out, for comparing. */
internal fun String.artistKey(): String = replace(ChannelWords, "").letters()

/**
 * What would make the Artists and Albums tabs tidy:
 *
 *  - an artist spelled more than one way ("joji", "Joji", "JojiVEVO") gets one spelling;
 *  - a song by several artists ("Joji, BENEE") is filed under the first, when that one has
 *    songs of their own here, by setting the file's album artist: the song's own artist
 *    line keeps everyone;
 *  - a single whose "album" is just its own name has that album cleared.
 */
fun findSortFixes(library: Library): SortFixes {
    val songs = library.songs

    // One spelling per artist: the plainest, most used one.
    val spellings = songs.groupBy { it.artist.artistKey() }
        .filterKeys { it.isNotEmpty() }
        .mapValues { (_, same) -> same.groupingBy { it.artist }.eachCount() }
    val canonical = spellings.mapValues { (_, counts) ->
        counts.entries.sortedWith(
            compareBy<Map.Entry<String, Int>> { ChannelWords.containsMatchIn(it.key) }
                .thenByDescending { it.value }
                .thenByDescending { entry -> entry.key.count { it.isUpperCase() } },
        ).first().key
    }
    val artistChanges = mutableListOf<SortChange>()
    for ((key, counts) in spellings) {
        if (counts.size < 2) continue
        val name = canonical.getValue(key)
        val others = songs.filter { it.artist.artistKey() == key && it.artist != name }
        artistChanges += SortChange(
            key = "spelling:$key",
            title = name,
            detail = "from ${others.map { it.artist }.distinct().joinToString(", ")} · ${countOf(others.size)}",
            edits = others.map { it to mapOf(SongTags.ARTIST to name) },
        )
    }

    // Several artists on one song: file it under the first, if that artist stands alone elsewhere.
    val soloKeys = songs.map { it.artist }.filter { Collaboration.find(it) == null }.mapTo(HashSet()) { it.artistKey() }
    val filed = songs.mapNotNull { song ->
        val first = Collaboration.split(song.artist).firstOrNull()?.trim().orEmpty()
        val key = first.artistKey()
        if (first == song.artist.trim() || key.isEmpty() || key !in soloKeys) return@mapNotNull null
        val name = canonical[key] ?: first
        if (song.albumArtist?.artistKey() == key) null else Triple(name, key, song)
    }
    for ((key, group) in filed.groupBy { it.second }) {
        val name = group.first().first
        artistChanges += SortChange(
            key = "filed:$key",
            title = name,
            detail = "${group.map { it.third.artist }.distinct().joinToString("; ")}: " +
                "filed under the first name · ${countOf(group.size)}",
            edits = group.map { it.third to mapOf(TagEditor.ALBUM_ARTIST to name) },
        )
    }

    // A single whose album is only its own name, or the name of its file.
    val alone = songs.groupBy { it.albumId }.filterValues { it.size == 1 }.values.map { it.first() }
        // Not a song the shelf has already put with the rest of its album.
        .filter { library.albumOf(it.id) == null }
    val albumChanges = alone.mapNotNull { song ->
        val album = song.album.letters()
        val title = song.title.letters()
        val ownName = album == title ||
            suggestFromName(song.album, song.artist)?.title?.letters() == title ||
            album == suggestFromName(song.title, song.artist)?.title?.letters()
        val fileName = album == song.fileName.substringBeforeLast('.').letters()
        val why = when {
            album.isEmpty() || song.album == "Unknown album" -> null
            ownName -> "album is the song's own name: cleared"
            fileName -> "album is the file's name: cleared"
            else -> null
        } ?: return@mapNotNull null
        SortChange("album:${song.id}", song.title, why, listOf(song to mapOf(SongTags.ALBUM to "")))
    }

    return SortFixes(
        artists = artistChanges.sortedBy { it.title.lowercase() },
        albums = albumChanges.sortedBy { it.title.lowercase() },
    )
}

private fun countOf(songs: Int): String = if (songs == 1) "1 song" else "$songs songs"
