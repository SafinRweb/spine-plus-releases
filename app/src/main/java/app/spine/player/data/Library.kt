package app.spine.player.data

/** An album as it stands on the shelf: its songs in playing order. Always two songs or more. */
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
) {
    val durationMs: Long = songs.sumOf { it.durationMs }

    /** How much of the album has played, from 0 to 1, given the song that is loaded. */
    fun progress(currentSongId: Long?, positionMs: Long): Float {
        val index = songs.indexOfFirst { it.id == currentSongId }
        if (index < 0 || durationMs <= 0) return 0f
        val played = songs.take(index).sumOf { it.durationMs } + positionMs
        return (played.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

data class Artist(
    val name: String,
    /** Albums credited to this artist. */
    val albums: List<Album>,
    /** Every song by this artist, singles included, sorted by title. */
    val songs: List<Song>,
) {
    val durationMs: Long = songs.sumOf { it.durationMs }
}

/**
 * What to write into each song's file so that this artist is called [newName]. A song
 * credited to the artist alone gets the name as its artist. A song shared with someone
 * else keeps its credit, and is given the name as its album artist so it stays filed here.
 */
fun Artist.renameEdits(newName: String): List<Pair<Song, Map<String, String>>> =
    if (newName.isBlank() || newName == name) {
        emptyList()
    } else {
        songs.map { song ->
            song to buildMap {
                if (song.artist == name) put(SongTags.ARTIST, newName)
                if (song.artist != name || song.albumArtist == name) put(TagEditor.ALBUM_ARTIST, newName)
            }
        }
    }

/** A folder on the phone that has music somewhere inside it. */
data class Folder(
    /** Path relative to shared storage; empty for the root. */
    val path: String,
    val name: String,
    val subfolders: List<Folder>,
    /** Songs directly in this folder, not in subfolders. */
    val songs: List<Song>,
) {
    val songCount: Int = songs.size + subfolders.sumOf { it.songCount }

    fun find(path: String): Folder? {
        if (path == this.path) return this
        return subfolders.firstOrNull { path == it.path || path.startsWith(it.path + "/") }?.find(path)
    }
}

/** Everything the screens browse, derived once from a scan. */
data class Library(
    /** All songs, sorted by title. */
    val songs: List<Song>,
    /** Albums with two songs or more, sorted by artist, then title: the order of the shelf. */
    val albums: List<Album>,
    /**
     * Songs whose "album" holds nothing else, sorted by title. A single is a song, so it lies
     * down as a strip instead of standing on the shelf as a one-song album.
     */
    val singles: List<Song>,
    val artists: List<Artist>,
    val rootFolder: Folder,
    /**
     * The videos the Downloader saved, newest first, each carried as a [Song] with a negative
     * id. They are not among [songs] (so they are in no album, artist or folder), but they
     * are in [songsById], which is how a playlist or the queue finds them.
     */
    val videos: List<Song> = emptyList(),
) {
    val songsById: Map<Long, Song> = (songs + videos).associateBy { it.id }
    private val albumsById: Map<Long, Album> = albums.associateBy { it.id }
    private val artistsByName: Map<String, Artist> = artists.associateBy { it.name }
    private val albumsBySong: Map<Long, Album> =
        albums.flatMap { album -> album.songs.map { it.id to album } }.toMap()
    private val artistsBySong: Map<Long, Artist> =
        artists.flatMap { artist -> artist.songs.map { it.id to artist } }.toMap()

    fun album(id: Long): Album? = albumsById[id]

    fun artist(name: String): Artist? = artistsByName[name]

    /**
     * The album a song stands in, if it is in one. Ask this rather than matching the song's
     * own album id: one album can hold songs the phone's index filed under several ids.
     */
    fun albumOf(songId: Long?): Album? = albumsBySong[songId]

    /** The artist a song is filed under, which for a song by several is the first of them. */
    fun artistOf(songId: Long?): Artist? = artistsBySong[songId]

    companion object {
        val Empty = Library(
            songs = emptyList(),
            albums = emptyList(),
            singles = emptyList(),
            artists = emptyList(),
            rootFolder = Folder("", "", emptyList(), emptyList()),
        )
    }
}

/** What separates the names when a song is credited to several artists. */
private val SeveralArtists = Regex(
    """\s*,\s*|\s+&\s+|\s+x\s+|\s+(?:feat|ft)\.?\s+|\s+featuring\s+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Well-known acts whose own name reads like a list of artists. A short list, not a full
 * one: a band it misses is still kept whole once it has three songs (see [BAND_SONGS]).
 * Compared without case or punctuation.
 */
private val OneActNames = setOf(
    "Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills & Nash", "Crosby, Stills, Nash & Young",
    "Emerson, Lake & Palmer", "Blood, Sweat & Tears", "Now, Now",
    "Simon & Garfunkel", "Hall & Oates", "Mumford & Sons", "Florence & The Machine", "Angus & Julia Stone",
    "Chase & Status", "Above & Beyond", "Kool & The Gang", "Belle & Sebastian", "Iron & Wine", "Brooks & Dunn",
).mapTo(HashSet()) { it.letters() }

/** A name joined by "&" or "and" with this many songs or more, whose first part is nobody else here, is taken to be a band. */
private const val BAND_SONGS = 3
private val BandJoin = Regex("""\s(?:&|and)\s""", RegexOption.IGNORE_CASE)

/**
 * The name each artist credit is filed under. A song by several artists ("Joji, BENEE")
 * goes under the first of them rather than getting an entry of its own on the Artists tab.
 * A name is left whole when it is one act: a known one like "Tyler, The Creator", or a name joined
 * by "&" that has three songs or more and whose first part has no songs of its own
 * ("Simon & Garfunkel").
 */
private fun fileArtists(songs: List<Song>): Map<String, String> {
    val credits = songs.groupingBy { it.filedUnder }.eachCount()
    fun parts(name: String): List<String> {
        val parts = SeveralArtists.split(name).map { it.trim() }
        val oneAct = parts.size < 2 || parts.any { it.isEmpty() } || name.letters() in OneActNames
        return if (oneAct) listOf(name) else parts
    }
    // Artists credited alone, by their name with case and punctuation taken out.
    val alone = credits.keys.filter { parts(it).size == 1 }
        .groupBy { it.artistKey() }
        .mapValues { (_, spellings) -> spellings.maxBy { credits.getValue(it) } }
    // First names of shared credits, so "a, B" and "A & C" end up under one spelling.
    val firstNames = HashMap<String, String>()
    return credits.mapValues { (name, count) ->
        val first = parts(name).first()
        val key = first.artistKey()
        when {
            first == name || key.isEmpty() -> name
            key in alone -> alone.getValue(key)
            count >= BAND_SONGS && BandJoin.containsMatchIn(name) -> name
            else -> firstNames.getOrPut(key) { first }
        }
    }
}

/**
 * The songs of each album, and the songs that are in none. The phone's index gives an
 * album a second id when its tracks differ in artist ("A" and "A, B") or sit in different
 * folders, which showed one album twice. Here groups with the same album name are one
 * album when they are filed under the same artist or lie in the same folder.
 */
private fun albumGroups(songs: List<Song>, artistOf: (Song) -> String): List<List<Song>> {
    val groups = songs.groupBy { it.albumId }.values.toList()
    // Each group points at the group it has been joined to.
    val joined = IntArray(groups.size) { it }
    fun root(index: Int): Int {
        var at = index
        while (joined[at] != at) at = joined[at]
        return at
    }
    val firstWith = HashMap<String, Int>()
    groups.forEachIndexed { index, tracks ->
        val title = tracks.first().album.letters()
        if (title.isEmpty() || tracks.first().album == "Unknown album") return@forEachIndexed
        val keys = tracks.mapTo(HashSet()) { "artist:$title:${artistOf(it).artistKey()}" } +
            tracks.map { "folder:$title:${it.folder}" }
        for (key in keys) {
            val other = firstWith.getOrPut(key) { index }
            if (root(other) != root(index)) joined[root(index)] = root(other)
        }
    }
    return groups.indices.groupBy(::root).values.map { members -> members.flatMap { groups[it] } }
}

fun buildLibrary(songs: List<Song>): Library {
    val byName = String.CASE_INSENSITIVE_ORDER
    val filing = fileArtists(songs)
    fun Song.shelfArtist(): String = filing.getValue(filedUnder)

    val (albumGroups, singleGroups) = albumGroups(songs) { it.shelfArtist() }.partition { tracks ->
        // A song with no album in its file is given its folder's name by the phone's index,
        // so a folder of loose downloads looks like one big album. It is told apart from a
        // real album kept in a folder of the same name by what loose downloads lack: track
        // numbers, and a single artist.
        val namedAfterFolder = tracks.all { it.album == it.folder.substringAfterLast('/') }
        val looseFiles = namedAfterFolder &&
            tracks.none { it.trackNumber > 0 } &&
            tracks.distinctBy { it.shelfArtist() }.size > 1
        tracks.size > 1 && !looseFiles
    }

    val albums = albumGroups
        .map { tracks ->
            Album(
                // The id of most of its tracks; [Library.albumOf] finds the album for the rest.
                id = tracks.groupingBy { it.albumId }.eachCount().maxBy { it.value }.key,
                title = tracks.first().album,
                // The artist most of the tracks share, so one guest track does not rename the album.
                artist = tracks.groupingBy { it.shelfArtist() }.eachCount().maxBy { it.value }.key,
                songs = tracks.sortedWith(
                    compareBy<Song> { it.discNumber }.thenBy { it.trackNumber }.thenBy(byName) { it.title },
                ),
            )
        }
        .sortedWith(compareBy<Album, String>(byName) { it.artist }.thenBy(byName) { it.title })

    val singleIds = singleGroups.flatMapTo(HashSet()) { tracks -> tracks.map { it.id } }
    val singles = songs.filter { it.id in singleIds }

    val albumsByArtist = albums.groupBy { it.artist }
    val artists = songs.groupBy { it.shelfArtist() }
        .map { (name, artistSongs) -> Artist(name, albumsByArtist[name].orEmpty(), artistSongs) }
        .sortedWith(compareBy(byName) { it.name })

    return Library(songs, albums, singles, artists, buildFolders(songs))
}

private class FolderBuilder(val path: String, val name: String) {
    val children = sortedMapOf<String, FolderBuilder>(String.CASE_INSENSITIVE_ORDER)
    val songs = mutableListOf<Song>()

    fun build(): Folder = Folder(path, name, children.values.map { it.build() }, songs)
}

private fun buildFolders(songs: List<Song>): Folder {
    val root = FolderBuilder("", "")
    for (song in songs) {
        var node = root
        if (song.folder.isNotEmpty()) {
            for (segment in song.folder.split('/')) {
                val parent = node
                node = parent.children.getOrPut(segment) {
                    FolderBuilder(if (parent.path.isEmpty()) segment else "${parent.path}/$segment", segment)
                }
            }
        }
        node.songs += song
    }
    return root.build()
}
