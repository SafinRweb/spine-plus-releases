package app.spine.player.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A song that can be fetched: one result of a search, or what a link points to.
 *
 * [tidy] says the title, artist and album came from a music catalogue and can be written
 * into the file as they are. When it is false they are an upload's name and its channel,
 * and the Tidy names rules are run over them first.
 */
data class Found(
    /** The source's own id for the song; also names it in the download queue. */
    val id: String,
    /** The address the download engine is given. */
    val url: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** 0 when the source does not say. */
    val durationMs: Long = 0,
    val tidy: Boolean = false,
    /** The site it comes from, in words for the user, such as "YouTube". */
    val source: String? = null,
)

/**
 * Looks a song up by name in YouTube Music's catalogue. That catalogue lists songs as
 * songs, with a proper title, artist, album and length, where an ordinary video search
 * gives upload names such as "Artist - Title (Official Video)".
 *
 * This asks the same address the YouTube Music website asks. It is not a published
 * interface and may change; when it does, [songs] throws and the screen says so.
 */
object Catalogue {

    private const val ADDRESS = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20241106.01.00"

    /** The website's own filter for "Songs", so albums, videos and artists are left out. */
    private const val SONGS_ONLY = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

    private val Length = Regex("""\d+(?::\d\d){1,2}""")

    /** Up to twenty songs matching [words], best match first. Throws if the lookup fails. */
    suspend fun songs(words: String): List<Found> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en"),
                ),
            )
            .put("query", words)
            .put("params", SONGS_ONLY)
            .toString()

        val connection = (URL(ADDRESS).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != 200) error("The catalogue answered ${connection.responseCode}")
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Picks the songs out of the website's answer. Anything it does not recognise is skipped. */
    internal fun parse(answer: String): List<Found> {
        val sections = JSONObject(answer)
            .optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?: return emptyList()

        val found = mutableListOf<Found>()
        for (section in sections.objects()) {
            val rows = section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue
            for (row in rows.objects()) {
                val item = row.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                val id = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                val columns = item.optJSONArray("flexColumns") ?: continue
                val title = columns.textOf(0)
                if (id.isEmpty() || title.isEmpty()) continue

                // The second column reads "Artist • Album • 3:42"; the album is sometimes absent.
                val parts = columns.textOf(1).split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
                    .dropWhile { it == "Song" || it == "Video" }
                val length = parts.lastOrNull()?.takeIf { Length.matches(it) }
                val names = if (length != null) parts.dropLast(1) else parts
                found += Found(
                    id = id,
                    url = "https://music.youtube.com/watch?v=$id",
                    title = title,
                    artist = names.firstOrNull().orEmpty(),
                    album = names.getOrNull(1),
                    durationMs = length?.let(::lengthMs) ?: 0,
                    tidy = true,
                    source = "YouTube Music",
                )
            }
        }
        return found
    }

    /** "3:42" or "1:02:10" as milliseconds. */
    private fun lengthMs(text: String): Long =
        text.split(':').fold(0L) { seconds, part -> seconds * 60 + part.toLong() } * 1000

    /** All the words of one column, which the website sends in pieces. */
    private fun JSONArray.textOf(column: Int): String {
        val runs = optJSONObject(column)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
        return runs.objects().joinToString("") { it.optString("text") }.trim()
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
