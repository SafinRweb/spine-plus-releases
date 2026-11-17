package app.spine.player.download

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A quick look at what a YouTube link is, without the download engine.
 *
 * The engine needs about six seconds to read a link, most of it spent on work that only the
 * download itself needs. YouTube also answers a much smaller question, "what is at this
 * address?", through its public oEmbed address in about a third of a second: the title and
 * who put it up. That is enough to put the song on screen at once. The engine then works
 * out the rest in the background ([Engine.prepare]).
 *
 * oEmbed does not give the length, and a few videos refuse it (private ones, and those
 * that may not be embedded); for those [youtube] returns null and the engine reads the link.
 */
object Peek {

    private val VideoId = Regex("""[A-Za-z0-9_-]{11}""")

    /**
     * The id of the one video [link] points to, or null if it is not a YouTube link or is a
     * link to a whole playlist. A link to a video inside a playlist counts as that video.
     */
    fun youtubeId(link: String): String? {
        val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val parts = uri.pathSegments.orEmpty()
        val id = when {
            host == "youtu.be" -> parts.firstOrNull()
            host == "youtube.com" || host == "music.youtube.com" -> when (parts.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")
                "shorts", "live", "embed", "v" -> parts.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { VideoId.matches(it) }
    }

    /** What [link] is, as far as YouTube says without the engine. Null if it will not say; never throws. */
    suspend fun youtube(link: String): Found? = withContext(Dispatchers.IO) {
        val id = youtubeId(link) ?: return@withContext null
        val music = Uri.parse(link.trim()).host.orEmpty().startsWith("music.", ignoreCase = true)
        val page = "https://www.youtube.com/watch?v=$id"
        runCatching {
            val address = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(page, "UTF-8")
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            try {
                if (connection.responseCode != 200) return@runCatching null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val title = json.optString("title").takeIf { it.isNotBlank() } ?: return@runCatching null
                Found(
                    id = id,
                    url = if (music) "https://music.youtube.com/watch?v=$id" else page,
                    title = title,
                    // YouTube files a label's releases under channels named "Artist - Topic".
                    artist = json.optString("author_name").removeSuffix(" - Topic").trim(),
                    source = if (music) "YouTube Music" else "YouTube",
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
