package app.spine.player.data

import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** The details of a song that can be edited, as text. An empty string means "none". */
data class SongTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val track: String = "",
    val year: String = "",
) {
    /** The fields that differ from [before], by the name the file knows them under. */
    fun changesFrom(before: SongTags): Map<String, String> = buildMap {
        if (title != before.title) put(TITLE, title)
        if (artist != before.artist) put(ARTIST, artist)
        if (album != before.album) put(ALBUM, album)
        if (track != before.track) put(TRACK, track)
        if (year != before.year) put(YEAR, year)
    }

    companion object {
        const val TITLE = "TITLE"
        const val ARTIST = "ARTIST"
        const val ALBUM = "ALBUM"
        const val TRACK = "TRACKNUMBER"
        const val YEAR = "DATE"
    }
}

/**
 * Reads and writes the details stored inside song files (M4A, Opus, MP3, FLAC and the
 * other formats TagLib knows).
 *
 * Writing changes the user's own file. From Android 11 the app may only do that after the
 * user allows it: [consentToWrite] gives the system prompt to show first.
 */
object TagEditor {

    /** What the file itself says, or null if it could not be read. */
    suspend fun read(context: Context, song: Song): SongTags? = withContext(Dispatchers.IO) {
        runCatching {
            val properties = context.contentResolver.openFileDescriptor(song.uri, "r")?.use { file ->
                // TagLib closes the descriptor it is handed, so it gets its own copy.
                TagLib.getMetadata(file.dup().detachFd(), false)?.propertyMap
            } ?: return@runCatching null
            fun first(key: String) = properties[key]?.firstOrNull().orEmpty().trim()
            SongTags(
                title = first(SongTags.TITLE),
                artist = first(SongTags.ARTIST),
                album = first(SongTags.ALBUM),
                // "3/12" means track 3 of 12; only the 3 is shown.
                track = first(SongTags.TRACK).substringBefore('/'),
                // Often a full date; the year is its first four characters.
                year = first(SongTags.YEAR).take(4),
            )
        }.getOrNull()
    }

    /**
     * The prompt Android shows before the app may change [songs], or null where no prompt
     * exists (before Android 11) and the write is simply tried.
     */
    fun consentToWrite(context: Context, songs: List<Song>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createWriteRequest(context.contentResolver, songs.map { it.uri }).intentSender
        } else {
            null
        }

    /**
     * Writes [changes] (field name to new value; an empty value removes the field) into
     * the song's file, leaving everything else in it as it was. Returns whether it worked.
     *
     * The song is never edited where it lies. Editing it in place, through the writable
     * handle Android gives out, once cut a field that had not been changed short. So the
     * song is copied into the app's own storage, the copy is edited and checked by
     * [editCopy], and only a copy that passed is written back over the song. If writing
     * back fails part way, the untouched original is put back.
     */
    suspend fun write(context: Context, song: Song, changes: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            if (changes.isEmpty()) return@withContext true
            val resolver = context.contentResolver
            val kind = song.fileName.substringAfterLast('.', "bin")
            val original = File(context.cacheDir, "tag-original-${song.id}.$kind")
            val edited = File(context.cacheDir, "tag-edited-${song.id}.$kind")
            val saved = runCatching {
                resolver.openInputStream(song.uri)?.use { input ->
                    original.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching false
                original.copyTo(edited, overwrite = true)
                if (!editCopy(edited, changes)) return@runCatching false

                try {
                    resolver.openOutputStream(song.uri, "wt")?.use { output ->
                        edited.inputStream().use { it.copyTo(output) }
                    } ?: return@runCatching false
                    true
                } catch (failure: Exception) {
                    // Half a file is worse than no change: put the song back as it was.
                    runCatching {
                        resolver.openOutputStream(song.uri, "wt")?.use { output ->
                            original.inputStream().use { it.copyTo(output) }
                        }
                    }
                    false
                }
            }.getOrDefault(false)
            original.delete()
            edited.delete()
            if (saved) rescan(context, song)
            saved
        }

    /**
     * Applies [changes] to [file], a private copy of a song, and checks the result before
     * saying yes: every field that was not changed reads back exactly as before, every
     * changed one reads back as asked, and the music is still the same length.
     */
    fun editCopy(file: File, changes: Map<String, String>): Boolean {
        val before = properties(file) ?: return false
        val lengthBefore = durationOf(file)

        // Saving replaces the whole set of fields, so start from what is there.
        val wanted = HashMap(before)
        for ((key, value) in changes) {
            if (value.isBlank()) wanted.remove(key) else wanted[key] = arrayOf(value.trim())
        }
        // An album's songs are filed under the album artist where there is one.
        changes[SongTags.ARTIST]?.takeIf { ALBUM_ARTIST !in changes && wanted.containsKey(ALBUM_ARTIST) }?.let { artist ->
            if (artist.isBlank()) wanted.remove(ALBUM_ARTIST) else wanted[ALBUM_ARTIST] = arrayOf(artist.trim())
        }
        val saved = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { opened ->
            TagLib.savePropertyMap(opened.dup().detachFd(), wanted)
        }
        if (!saved) return false

        val after = properties(file) ?: return false
        val changed = changes.keys + if (SongTags.ARTIST in changes) setOf(ALBUM_ARTIST) else emptySet()
        val untouchedKept = before.filterKeys { it !in changed }.all { (key, value) ->
            after[key]?.toList() == value.toList()
        }
        val changesTaken = changes.all { (key, value) ->
            val now = after[key]?.firstOrNull()?.trim()
            // A track number may come back as "3/12", a year inside a longer date.
            if (value.isBlank()) now.isNullOrEmpty() else now != null && now.startsWith(value.trim())
        }
        return untouchedKept && changesTaken && durationOf(file) == lengthBefore
    }

    /** Every field TagLib finds in [file], or null if it cannot read it. */
    private fun properties(file: File): HashMap<String, Array<String>>? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { opened ->
            // TagLib closes the descriptor it is handed, so it gets its own copy.
            TagLib.getMetadata(opened.dup().detachFd(), false)?.propertyMap
        }

    /** How long Android says the music in [file] is, or null if it cannot play it. */
    private fun durationOf(file: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        } catch (failure: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Has the system media index read the file again, so the new details show up. */
    private suspend fun rescan(context: Context, song: Song) {
        @Suppress("DEPRECATION")
        val path = context.contentResolver.query(
            song.uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return
        withTimeoutOrNull(RESCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    const val ALBUM_ARTIST = "ALBUMARTIST"
    private const val RESCAN_TIMEOUT_MS = 5_000L
}

/**
 * A cleaner title and artist worked out from a song's name. [sure] is false when the guess
 * could well be wrong: nothing in the file says which half of "A - B" is the artist, or the
 * name is one of the "Title | this | that" kind, whose parts follow no fixed order.
 */
data class NameSuggestion(val title: String, val artist: String, val sure: Boolean = true)

/**
 * Words that say what kind of upload a file came from, not what the song is. A bracket or
 * a "|" section made of nothing but these is dropped: "(Official Music Video)", "[HD]",
 * "(Official Lyric Video)", "[4K Upgrade]", "| Music Video" and so on.
 */
private val NoiseWords = setOf(
    "official", "music", "video", "audio", "lyric", "lyrics", "lyrical", "lyricvideo", "visualizer",
    "visualiser", "hd", "hq", "4k", "360", "upgrade", "mv", "full", "song", "with", "and", "version",
    "original", "track", "no", "copyright", "infringement", "uhd", "fhd", "8k", "soundtrack", "ost",
)

/** Picture sizes and frame rates, which say nothing about a song: "1080p", "720p60", "60fps". */
private val PictureSize = Regex("""\d{3,4}p(?:\d{2,3})?|\d{2,3}fps""")

/** Words that only count as noise in front of "soundtrack": "(Original Motion Picture Soundtrack)". */
private val SoundtrackWords = setOf("motion", "picture", "movie", "film", "game", "series", "tv", "from", "the")
private val Bracketed = Regex("""[(\[]([^()\[\]]*)[)\]]""")

/**
 * The same words without brackets, at the end of a name: "… Full Video Song", "… Official
 * Music Video", "… 1080p", "… HD 60fps", "… Original Soundtrack".
 */
private val TrailingNoise = Regex(
    """\s+(?:""" +
        """(?:(?:official|full|music|lyric|lyrics|lyrical|hd|hq|4k)\s+)+(?:video|audio|visuali[sz]er)(?:\s+song)?""" +
        """|(?:video|audio)\s+song""" +
        """|(?:\d{3,4}p(?:\d{2,3})?|\d{2,3}fps|4k|8k|uhd|fhd|hd|hq)""" +
        """|(?:(?:official|original|full|motion|picture|movie|film|game)\s+)*(?:soundtrack|ost)""" +
        """)\s*$""",
    RegexOption.IGNORE_CASE,
)

/** "ft. Someone" at the end of a title, or just before a bracket that stays. */
private val FeaturingInTitle = Regex(
    """\s*[(\[]?\s*\b(?:ft|feat|featuring)\b\.?\s+([^()\[\]|]+?)\s*[)\]]?(?=\s*(?:[(\[]|$))""",
    RegexOption.IGNORE_CASE,
)
private val FeaturingInArtist = Regex("""\s+\b(?:ft|feat|featuring)\b\.?\s+""", RegexOption.IGNORE_CASE)
private val Dash = Regex("""\s+[-–—]+\s+""")
private val Sections = Regex("""\s*(?:\|+|★)\s*""")

/** “Title” by Artist */
private val TitleByArtist = Regex("""^["“']?(.+?)["”']?\s+by\s+(.+)$""")
private val QuotedStart = Regex("""^["“]([^"”]{2,})["”]\s+\S""")

internal fun String.squeezed(): String = replace(Regex("""\s+"""), " ").trim()

internal fun String.letters(): String = lowercase().filter { it.isLetterOrDigit() }

private fun String.isNoise(): Boolean {
    val words = lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    if (words.all { it in NoiseWords || PictureSize.matches(it) }) return true
    // "Original Motion Picture Soundtrack", "From the Film Soundtrack".
    return (words.last() == "soundtrack" || words.last() == "ost") &&
        words.all { it in NoiseWords || it in SoundtrackWords }
}

/** Drops upload words from the end of a name, as often as they come: "… Official Video 1080p". */
private fun String.withoutTrailingNoise(): String {
    var name = trim()
    while (true) {
        val shorter = TrailingNoise.replace(name, "").trim()
        // Never down to nothing: a song may simply be called "Soundtrack".
        if (shorter == name || shorter.isEmpty()) return name
        name = shorter
    }
}

/** Whether [this] names the same act as the song's artist field, give or take "Official", "VEVO" and case. */
private fun String.names(artist: String): Boolean {
    val mine = letters()
    val theirs = artist.letters()
    return mine.length >= 3 && theirs.length >= 3 && (mine in theirs || theirs in mine)
}

/** The first " - " that is not inside brackets. */
private fun String.dashOutsideBrackets(): IntRange? =
    Dash.findAll(this).firstOrNull { match ->
        val before = substring(0, match.range.first)
        before.count { it == '(' || it == '[' } == before.count { it == ')' || it == ']' }
    }?.range

/**
 * Downloaded songs are often named "Artist - Title (Official Video) ft. Someone", with
 * the whole of that as the title. This works out a clean title and artist from such a
 * name, using [artist] (what the file says now, often the uploading channel) as a hint.
 * Returns null when there is nothing better to offer than what the song already has.
 *
 * It is a guess for the user to accept or not, so it prefers the common shapes:
 *  - brackets that only describe the upload are dropped, as is a bracket naming the channel;
 *  - "Artist - Title", or "Title - Artist" when the second half is the song's artist;
 *  - "Title | this | that", where only the title is taken, since the rest varies too much;
 *  - "ft. Someone" moves from the title to the artist.
 */
fun suggestFromName(title: String, artist: String): NameSuggestion? {
    val current = artist.trim()
    val artistKnown = current.isNotEmpty() && current != "<unknown>" && !current.equals("Unknown artist", ignoreCase = true)

    val name = Bracketed.replace(title) { match ->
        val inside = match.groupValues[1].trim()
        if (inside.isNoise() || (artistKnown && inside.letters() == current.letters())) " " else match.value
    }.squeezed()

    val sections = name.split(Sections).map { it.trim() }
        .filter { it.isNotEmpty() && !it.isNoise() && !it.startsWith("@") }
    if (sections.isEmpty()) return null

    var newTitle: String
    var newArtist: String? = null
    // Names in several sections are too varied to trust without a look.
    var sure = sections.size == 1
    val titleBy = if (sections.size > 1) sections.firstNotNullOfOrNull { TitleByArtist.matchEntire(it) } else null
    if (titleBy != null) {
        newTitle = titleBy.groupValues[1]
        newArtist = titleBy.groupValues[2]
    } else {
        // "A | B | C - Title": the artists come first and the last section holds the title.
        val artistsFirst = sections.size > 1 &&
            sections.first().dashOutsideBrackets() == null && sections.last().dashOutsideBrackets() != null
        val core = (if (artistsFirst) sections.last() else sections.first()).withoutTrailingNoise()
        val leading = if (artistsFirst) sections.dropLast(1) else emptyList()
        val dash = core.dashOutsideBrackets()
        if (dash != null) {
            val left = core.substring(0, dash.first).trim().withoutTrailingNoise()
            val right = core.substring(dash.last + 1).trim().withoutTrailingNoise()
            when {
                right.isEmpty() || right.isNoise() -> newTitle = left
                left.isEmpty() -> newTitle = right
                // "Title - Artist", told apart by the artist the file already names.
                artistKnown && leading.isEmpty() && right.names(current) && !left.names(current) -> {
                    newTitle = left
                    newArtist = right
                }
                // "Something by Artist - Title": the artist is the one the file names.
                artistKnown && leading.isEmpty() && left.endsWith(" by $current", ignoreCase = true) -> {
                    newTitle = right
                    newArtist = current
                }
                else -> {
                    newTitle = right
                    newArtist = (leading + left).joinToString(", ")
                    // "Artist - Title" is the usual order, but only the file's own artist confirms it.
                    if (!(artistKnown && left.names(current))) sure = false
                }
            }
        } else {
            newTitle = core
            // "Title-Artist" with no spaces, when the artist is known.
            if (artistKnown && sections.size == 1) {
                if (core.endsWith("-$current", ignoreCase = true)) {
                    newTitle = core.dropLast(current.length + 1)
                } else if (core.startsWith("$current-", ignoreCase = true)) {
                    newTitle = core.drop(current.length + 1)
                }
            }
        }
    }

    // Film-music uploads start with the song's name in quotes, then describe it.
    QuotedStart.find(newTitle)?.let { newTitle = it.groupValues[1] }

    var featured: String? = null
    FeaturingInTitle.find(newTitle)?.let { match ->
        featured = match.groupValues[1].trim()
        newTitle = newTitle.removeRange(match.range)
    }
    newTitle = newTitle.squeezed().trim(' ', '-', '–', '—', '|', ':')
    // A title wrapped whole in quotes loses them.
    if (newTitle.length > 2) {
        val wrapped = (newTitle.first() == '"' && newTitle.last() == '"') ||
            (newTitle.first() == '“' && newTitle.last() == '”') ||
            (newTitle.first() == '\'' && newTitle.last() == '\'')
        if (wrapped) newTitle = newTitle.substring(1, newTitle.length - 1).trim()
    }

    val lead = newArtist?.let { FeaturingInArtist.replace(it, ", ").squeezed() } ?: current.takeIf { artistKnown }
    val artists = listOfNotNull(lead, featured).filter { it.isNotBlank() }.joinToString(", ")
    val suggestion = NameSuggestion(newTitle, artists.ifBlank { current }, sure)
    return suggestion.takeIf {
        it.title.isNotBlank() && (it.title != title.trim() || it.artist != current)
    }
}
