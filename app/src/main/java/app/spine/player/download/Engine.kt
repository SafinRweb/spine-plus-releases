package app.spine.player.download

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The two kinds of file a song can be saved as. Neither is re-encoded when the source has it. */
enum class SaveAs(val label: String, val extension: String, val mime: String, val pick: String) {
    /** AAC in an M4A file: plays on everything. */
    M4a("M4A", "m4a", "audio/mp4", "bestaudio[ext=m4a]/bestaudio"),

    /** Opus: a little better at the same size, but some car stereos and old players cannot read it. */
    Opus("Opus", "opus", "audio/ogg", "bestaudio[acodec=opus]/bestaudio"),
}

/**
 * One sound that a link offers: which of the engine's formats it is, the kind of file it
 * makes, how many kilobits a second, and its size in bytes (0 when the site does not say).
 */
data class SoundFormat(val id: String, val saveAs: SaveAs, val kbps: Int, val bytes: Long)

/**
 * One picture that a link offers, for a video: which of the engine's formats it is, how
 * tall, how many frames a second, whether it goes in an MP4 (otherwise an MKV), the name of
 * its coding, and the size in bytes of picture and sound together (0 when not known).
 */
data class PictureFormat(val id: String, val height: Int, val fps: Int, val mp4: Boolean, val codec: String, val bytes: Long)

/** The download was stopped by the user, not by a fault. */
class Stopped : Exception()

/**
 * The download engine: yt-dlp, with the Python, ffmpeg and QuickJS it needs, packed for
 * Android by the youtubedl-android library. Everything here blocks while the engine works,
 * so it all runs off the main thread.
 *
 * ## Why there is a plug-in
 *
 * Before YouTube hands out a song's proper audio it sets a puzzle written in JavaScript.
 * yt-dlp solves it with QuickJS, and on a phone that takes about ten seconds. The puzzle
 * is the same for every song until YouTube changes its player, a few times a week, but
 * yt-dlp does not keep the worked-out answer (it is switched off in yt-dlp because the
 * file is a few megabytes). So every song paid the ten seconds again.
 *
 * yt-dlp lets plug-ins supply the puzzle solver. [PLUGIN] is one that is yt-dlp's own
 * QuickJS solver with the keeping switched on. Measured on the phone (2026-11-16): reading
 * a link took 15.4 s without it and 6.9 s with it, from the second song on. [warm] solves
 * the puzzle once in the background so the first song is quick too, and [tidyCache] keeps
 * only the two newest answers.
 */
object Engine {

    private val gate = Mutex()
    private var started = false

    /** Held while the puzzle is being solved ahead of time, so a download does not solve it twice over. */
    private val warming = Mutex()

    private const val TAG = "SpinePlus"
    private const val PREFS = "downloads"
    private const val KEY_UPDATED_AT = "engine_updated_at"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val KEY_WARMED_AT = "engine_warmed_at"
    private const val WARM_ID = "spine-warm"

    /** A kept answer to the puzzle this young is taken to be the current one. */
    private const val PUZZLE_GOOD_FOR_MS = 6 * 60 * 60 * 1000L

    /** What the engine worked out about a song is used for this long; YouTube's addresses in it last about six hours. */
    private const val INFO_GOOD_FOR_MS = 3 * 60 * 60 * 1000L

    /** YouTube was seen to change its player twice in ten minutes, so a warm-up is not trusted for long. */
    private const val WARM_FOR_MS = 30 * 60 * 1000L

    /** A short public video that has been up since 2005, used only to fetch the current puzzle. */
    private const val WARM_WITH = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

    /**
     * See the class comment. Everything is inside a `try`: if a later yt-dlp moves these
     * names, the plug-in does nothing and yt-dlp's own solver is used, slower but working.
     */
    private val PLUGIN = """
        try:
            from yt_dlp.extractor.youtube.jsc._builtin.quickjs import QuickJSJCP
            from yt_dlp.extractor.youtube.jsc.provider import register_provider, register_preference

            @register_provider
            class SpineQuickJSJCP(QuickJSJCP):
                PROVIDER_NAME = 'spine-quickjs'
                _ENABLE_PREPROCESSED_PLAYER_CACHE = True

            @register_preference(SpineQuickJSJCP)
            def preference(provider, requests):
                return 900
        except Exception:
            pass
    """.trimIndent() + "\n"

    private fun pluginFolder(context: Context) = File(context.filesDir, "engine-plugins")

    /**
     * Where the engine keeps what it worked out about a site between runs: the solved
     * puzzle above all.
     */
    private fun cacheFolder(context: Context) = File(context.cacheDir, "engine")

    /** Unpacks the engine on first use (a few seconds, once) and keeps it up to date. */
    suspend fun start(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        gate.withLock {
            if (!started) {
                YoutubeDL.init(app)
                FFmpeg.init(app)
                runCatching {
                    File(pluginFolder(app), "spine/yt_dlp_plugins/extractor").apply { mkdirs() }
                        .resolve("spine_jsc.py").writeText(PLUGIN)
                }
                started = true
            }
        }
        // The sites change and yt-dlp follows, so an old engine is the usual reason a
        // download fails. Once a day is often enough and costs one small request.
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_UPDATED_AT, 0) > ONE_DAY_MS) update(app)
    }

    /** Fetches the newest yt-dlp. Returns whether a newer one was installed. Never throws. */
    suspend fun update(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching {
            val status = gate.withLock { YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            }
            status == YoutubeDL.UpdateStatus.DONE
        }.getOrDefault(false)
    }

    /** The engine's version, such as "2026.11.02", or null before its first update. */
    fun version(context: Context): String? = runCatching { YoutubeDL.version(context.applicationContext) }.getOrNull()

    /**
     * Solves YouTube's puzzle ahead of time, so the first song of a sitting does not wait
     * for it. Does nothing if that was done in the last half hour. When the answer is
     * already kept this is one quick read; when YouTube has a new player it is the slow
     * ten seconds, spent before the user has picked a song.
     * Never throws; if it fails, the first download simply solves the puzzle itself.
     */
    suspend fun warm(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            start(context)
            if (warming.isLocked) return@runCatching
            warming.withLock {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (System.currentTimeMillis() - prefs.getLong(KEY_WARMED_AT, 0) < WARM_FOR_MS) return@withLock
                val began = System.currentTimeMillis()
                YoutubeDL.execute(common(context, YoutubeDLRequest(WARM_WITH)).addOption("--skip-download").addOption("-R", 1), WARM_ID)
                prefs.edit { putLong(KEY_WARMED_AT, System.currentTimeMillis()) }
                Log.i(TAG, "puzzle solved ahead of time in ${System.currentTimeMillis() - began} ms")
                tidyCache(context)
            }
        }
    }

    /**
     * A warm-up may be under way when a real request arrives. If an answer to the puzzle
     * is already kept, the warm-up is only checking it again: it is stopped, so the
     * request does not queue behind it. If none is kept, the warm-up is doing work the
     * request needs anyway, so the request waits for it.
     */
    private suspend fun afterWarming(context: Context) {
        if (warming.isLocked && puzzleKept(context)) YoutubeDL.destroyProcessById(WARM_ID)
        warming.withLock { }
    }

    private fun puzzleKept(context: Context): Boolean {
        val newest = File(cacheFolder(context), "challenge-solver").listFiles()?.maxOfOrNull { it.lastModified() } ?: return false
        return System.currentTimeMillis() - newest < PUZZLE_GOOD_FOR_MS
    }

    // --- Working a song out once ---
    // Finding where a song's audio is takes the engine about six seconds. A link's song is
    // on screen for a few seconds before Download is pressed, so that work is started as
    // soon as the song is known and its result is handed to the download, which then
    // starts in the time the engine takes to start.

    /** What the engine worked out about one song: the song as it describes it, and its full answer on disk. */
    private class Prepared(val song: Found, val info: File, val at: Long = System.currentTimeMillis())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepared = ConcurrentHashMap<String, Deferred<Prepared?>>()

    private fun infoFolder(context: Context) = File(context.cacheDir, "engine-info")

    /**
     * Starts working out where [song]'s audio is, in the background, unless that is already
     * done or under way. The result is the song as the engine describes it (its length, and
     * a proper title, artist and album where the site has them), or null if it failed.
     */
    fun prepare(context: Context, song: Found): Deferred<Found?> {
        val app = context.applicationContext
        val work = prepared.compute(song.id) { _, known ->
            val stale = known != null && known.isCompleted &&
                (runCatching { known.getCompleted() }.getOrNull()?.let { System.currentTimeMillis() - it.at > INFO_GOOD_FOR_MS } ?: true)
            if (known != null && !stale) known else scope.async {
                runCatching {
                    start(app)
                    afterWarming(app)
                    val request = common(app, YoutubeDLRequest(song.url))
                        .addOption("--dump-single-json")
                        .addOption("--no-playlist")
                        .addOption("-R", 1)
                    keep(app, YoutubeDL.execute(request, "prepare-${song.id}").out)
                }.onFailure { Log.w(TAG, "could not prepare ${song.id}: ${it.message.orEmpty().take(300)}") }.getOrNull()
            }
        }!!
        return scope.async { work.await()?.song }
    }

    /** Saves the engine's answer about one song, for [fetch] to load. Null if it is not about one song. */
    private fun keep(context: Context, answer: String): Prepared? {
        val json = runCatching { JSONObject(answer) }.getOrNull() ?: return null
        if (json.has("entries")) return null
        val song = found(json, album = null) ?: return null
        val file = File(infoFolder(context).apply { mkdirs() }, "${song.id}.json")
        file.writeText(answer)
        // Answers go stale within hours; old ones are only in the way.
        infoFolder(context).listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > INFO_GOOD_FOR_MS }?.forEach { it.delete() }
        return Prepared(song, file)
    }

    /**
     * The sounds [song]'s link offers, best first, for "Choose the format". Null until the
     * engine has worked the song out ([prepare]); reads a file, so call it off the main thread.
     */
    fun sounds(song: Found): List<SoundFormat>? {
        val ready = prepared[song.id]?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }
            ?: return null
        val formats = runCatching { JSONObject(ready.info.readText()).optJSONArray("formats") }.getOrNull() ?: return emptyList()
        return (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }.mapNotNull { format ->
            // Sound alone, of a kind the app saves as it is, with a known quality.
            if (format.optString("vcodec") != "none") return@mapNotNull null
            val codec = format.optString("acodec")
            val saveAs = when {
                codec.startsWith("mp4a") -> SaveAs.M4a
                codec.startsWith("opus") -> SaveAs.Opus
                else -> return@mapNotNull null
            }
            val kbps = format.optDouble("abr", format.optDouble("tbr", 0.0)).let { if (it.isNaN()) 0 else it.toInt() }
            val id = format.optString("format_id")
            if (kbps <= 0 || id.isBlank()) return@mapNotNull null
            SoundFormat(id, saveAs, kbps, format.optLong("filesize", format.optLong("filesize_approx", 0)))
        }.distinctBy { it.saveAs to it.kbps }.sortedByDescending { it.kbps }
    }

    /**
     * The pictures [song]'s link offers for a video, tallest first, one for each height:
     * H.264 where there is one, since every phone plays it, otherwise VP9, then AV1.
     * Null until the engine has worked the song out; reads a file, so call it off the main thread.
     */
    fun pictures(song: Found): List<PictureFormat>? {
        val ready = prepared[song.id]?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }
            ?: return null
        val formats = runCatching { JSONObject(ready.info.readText()).optJSONArray("formats") }.getOrNull() ?: return emptyList()
        // A picture is fetched with the best sound its kind of file takes; the size shown is both.
        val sound = sounds(song).orEmpty()
        val withMp4 = sound.firstOrNull { it.saveAs == SaveAs.M4a }?.bytes ?: 0
        val withMkv = sound.firstOrNull { it.saveAs == SaveAs.Opus }?.bytes ?: 0
        class Offer(val picture: PictureFormat, val rank: Int, val rate: Double)
        return (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }.mapNotNull { format ->
            if (format.optString("acodec") != "none") return@mapNotNull null
            val coding = format.optString("vcodec")
            val (codec, rank) = when {
                coding.startsWith("avc1") -> "H.264" to 0
                coding.startsWith("vp9") || coding.startsWith("vp09") -> "VP9" to 1
                coding.startsWith("av01") -> "AV1" to 2
                else -> return@mapNotNull null
            }
            val height = format.optInt("height")
            val id = format.optString("format_id")
            if (height <= 0 || id.isBlank()) return@mapNotNull null
            val mp4 = format.optString("ext") == "mp4"
            val alone = format.optLong("filesize", format.optLong("filesize_approx", 0))
            Offer(
                PictureFormat(
                    id = id,
                    height = height,
                    fps = format.optDouble("fps", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                    mp4 = mp4,
                    codec = codec,
                    bytes = if (alone > 0) alone + (if (mp4) withMp4 else withMkv) else 0,
                ),
                rank,
                format.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
            )
        }.groupBy { it.picture.height }
            .map { (_, same) -> same.sortedWith(compareBy<Offer> { it.rank }.thenByDescending { it.rate }).first().picture }
            .sortedByDescending { it.height }
    }

    /** [song] as the engine describes it, if that has been worked out; otherwise [song] as it is. */
    fun known(song: Found): Found =
        prepared[song.id]?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }?.song ?: song

    /**
     * Keeps the four newest solved puzzles. Each is about 4 MB, and YouTube can hand out
     * more than one player at a time, so keeping only the latest would throw away answers
     * that are still wanted.
     */
    private fun tidyCache(context: Context) {
        File(cacheFolder(context), "challenge-solver").listFiles()
            ?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    }

    /** What every run of the engine is told. [plain] leaves the plug-in out, as a last resort. */
    private fun common(context: Context, request: YoutubeDLRequest, plain: Boolean = false): YoutubeDLRequest {
        request
            .addOption("--cache-dir", cacheFolder(context).absolutePath)
            // A request to YouTube stalls now and then (about one in ten, on Wi-Fi, 2026-11-16;
            // forcing IPv4 did not help). It is given up on and retried; left alone, one
            // stall cost 20 seconds.
            .addOption("--socket-timeout", 6)
        if (!plain) {
            request
                .addOption("--plugin-dirs", pluginFolder(context).absolutePath)
                // YouTube's video playlists are of no use for audio and cost a second to fetch.
                .addOption("--extractor-args", "youtube:skip=hls")
        }
        return request
    }

    /**
     * What [link] points to: one song, or every song of an album or playlist. Nothing is
     * downloaded. Throws [EngineFault] with a line for the user when the link cannot be read.
     */
    suspend fun read(context: Context, link: String): List<Found> = withContext(Dispatchers.IO) {
        start(context)
        afterWarming(context)
        val request = common(context, YoutubeDLRequest(link))
            .addOption("--dump-single-json")
            // A list is read as a list of entries, without opening each song in turn.
            .addOption("--flat-playlist")
            .addOption("-R", 1)
        val answer = try {
            YoutubeDL.execute(request).out
        } catch (fault: Exception) {
            throw EngineFault.from(fault)
        }
        val json = runCatching { JSONObject(answer) }.getOrNull() ?: throw EngineFault("That link has nothing Spine+ can read.")
        val entries = json.optJSONArray("entries")
        if (entries == null) {
            // One song: its answer is kept, so the download does not work it out again.
            runCatching { keep(context.applicationContext, answer) }.getOrNull()
                ?.let { prepared[it.song.id] = CompletableDeferred(it) }
            listOfNotNull(found(json, album = null))
        } else {
            val listName = json.optString("title").takeIf { it.isNotBlank() }
            (0 until entries.length()).mapNotNull { index -> entries.optJSONObject(index)?.let { found(it, listName) } }
        }
    }

    /** One song as the engine describes it. Null for an entry with no address, such as a removed video. */
    private fun found(json: JSONObject, album: String?): Found? {
        fun text(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        val id = text("id") ?: return null
        val url = text("webpage_url") ?: text("url") ?: return null
        // A music release names its track and artist; an ordinary upload only has its own
        // name and the channel that put it up.
        val track = text("track")
        val artist = text("artist") ?: text("creator")
        return Found(
            id = id,
            url = url,
            title = track ?: text("title") ?: return null,
            artist = artist ?: text("channel") ?: text("uploader") ?: "",
            album = text("album") ?: album.takeIf { track != null },
            durationMs = (json.optDouble("duration", 0.0) * 1000).toLong(),
            tidy = track != null && artist != null,
            source = siteName(text("extractor_key") ?: text("ie_key")),
        )
    }

    /** The engine's name for a site, as people write it. */
    private fun siteName(key: String?): String? = when {
        key == null -> null
        key.startsWith("Youtube", ignoreCase = true) -> "YouTube"
        key.startsWith("Soundcloud", ignoreCase = true) -> "SoundCloud"
        key.startsWith("Bandcamp", ignoreCase = true) -> "Bandcamp"
        key == "Generic" -> null
        else -> key
    }

    /**
     * Downloads [song] into [folder] the way [options] say, as a song or as a video, and
     * returns the file. [onProgress] hears how far it is, from 0 to 1. [processId] is what
     * [stop] is told to end it.
     *
     * Throws [Stopped] if it was stopped, [EngineFault] if it failed.
     */
    suspend fun fetch(
        context: Context,
        song: Found,
        options: GetOptions,
        folder: File,
        processId: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        start(context)
        // If the song is being worked out, or has been, wait for that and use it.
        val ready = prepared[song.id]?.let { runCatching { it.await() }.getOrNull() }
            ?.takeIf { System.currentTimeMillis() - it.at < INFO_GOOD_FOR_MS && it.info.exists() }
        var info = ready?.info
        if (info == null) afterWarming(context)
        folder.mkdirs()
        // What is tried, in order, when a download fails with nothing to show for it:
        // without the cover (a picture that cannot be embedded should not cost the song),
        // then after updating the engine (a stale one is the usual cause), then with the
        // engine as it comes, without the plug-in.
        var withCover = options.cover
        var updated = false
        var plain = false
        while (true) {
            folder.listFiles()?.forEach { it.delete() }
            // A video comes as two downloads, the picture and then the sound, each counted
            // from nought to a hundred; here they are the two halves of one count.
            var half = 0
            var last = 0f
            try {
                YoutubeDL.execute(request(context, song, options, folder, withCover, plain, info), processId) { percent, _, _ ->
                    val now = (percent / 100f).coerceIn(0f, 1f)
                    if (options.video) {
                        if (now < last - 0.5f) half = 1
                        last = now
                        onProgress(((half + now) / 2f).coerceIn(0f, 1f))
                    } else {
                        onProgress(now)
                    }
                }
            } catch (stopped: YoutubeDL.CanceledException) {
                throw Stopped()
            } catch (fault: Exception) {
                // The song itself may have arrived before a later step, the cover, failed.
                if (arrived(folder, options, finished = false) == null) {
                    // A kept answer may have gone stale: work the song out afresh.
                    if (info != null) {
                        Log.w(TAG, "kept answer did not work, reading again: ${fault.message.orEmpty().take(200)}")
                        info = null
                        prepared.remove(song.id)
                        continue
                    }
                    if (withCover && fault.message.orEmpty().contains("thumbnail", ignoreCase = true)) {
                        withCover = false
                        continue
                    }
                    if (!updated) {
                        updated = true
                        if (update(context)) continue
                    }
                    if (!plain) {
                        Log.w(TAG, "trying without the plug-in after: ${fault.message.orEmpty().take(300)}")
                        plain = true
                        continue
                    }
                    throw EngineFault.from(fault)
                }
            }
            tidyCache(context)
            return@withContext arrived(folder, options, finished = true)
                ?: throw EngineFault("The download finished but left nothing.")
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** As [fetch], for a song, naming only the kind of file, the cover and the sound. */
    suspend fun fetch(
        context: Context,
        song: Found,
        saveAs: SaveAs,
        folder: File,
        processId: String,
        cover: Boolean = true,
        formatId: String? = null,
        onProgress: (Float) -> Unit,
    ): File = fetch(context, song, GetOptions(saveAs = saveAs, cover = cover, formatId = formatId), folder, processId, onProgress)

    /** Ends the download started with this id. */
    fun stop(processId: String) {
        YoutubeDL.destroyProcessById(processId)
    }

    private val VideoFiles = setOf("mp4", "mkv", "webm")

    /** While a video downloads, its picture and sound lie apart as "song.f137.mp4" and the like. */
    private val Piece = Regex("""\.f\d+\.""")

    /**
     * The file the download left in [folder]. After a failure ([finished] is false) only a
     * song counts, since it can be whole before a later step fails; a video that stopped
     * part way is still in pieces.
     */
    private fun arrived(folder: File, options: GetOptions, finished: Boolean): File? =
        folder.listFiles()?.firstOrNull { file ->
            file.length() > 0 && if (options.video) {
                finished && file.extension.lowercase() in VideoFiles && !Piece.containsMatchIn(file.name)
            } else {
                file.extension.equals(options.saveAs.extension, ignoreCase = true)
            }
        }

    private fun request(
        context: Context,
        song: Found,
        options: GetOptions,
        folder: File,
        withCover: Boolean,
        plain: Boolean,
        info: File?,
    ): YoutubeDLRequest {
        // With the engine's earlier answer in hand there is no address to visit: it is loaded instead.
        val request = common(context, if (info != null) YoutubeDLRequest(emptyList()) else YoutubeDLRequest(song.url), plain)
            .addOption("--no-playlist")
        if (info != null) request.addOption("--load-info-json", info.absolutePath)
        if (options.video) {
            val picked = options.formatId
            val tall = options.height
            request
                .addOption(
                    "-f",
                    when {
                        // As usual: H.264 with AAC up to the chosen height, which plays on
                        // everything; then whatever the site has at that height or under.
                        picked == null ->
                            "bestvideo[height<=$tall][vcodec^=avc1]+bestaudio[ext=m4a]/best[height<=$tall][ext=mp4]/" +
                                "bestvideo[height<=$tall]+bestaudio/best[height<=$tall]/best"
                        options.mp4 -> "$picked+bestaudio[ext=m4a]/$picked+bestaudio/$picked"
                        else -> "$picked+bestaudio[acodec=opus]/$picked+bestaudio/$picked"
                    },
                )
                // The picture and the sound are joined into one file, without re-encoding.
                .addOption("--merge-output-format", if (picked == null || options.mp4) "mp4" else "mkv")
            if (options.subtitles) {
                request
                    .addOption("--write-subs")
                    .addOption("--write-auto-subs")
                    .addOption("--sub-langs", "en.*")
                    .addOption("--embed-subs")
            }
        } else {
            request
                .addOption("-f", options.formatId ?: options.saveAs.pick)
                // Takes the sound out of its wrapper into the wanted kind of file. When the
                // source already holds that kind of sound it is copied, not encoded again.
                .addOption("-x")
                .addOption("--audio-format", options.saveAs.extension)
        }
        request
            .addOption("--embed-metadata")
            // The file should carry its own details, not the upload's: the description,
            // the site's category and the language are left out.
            .addOption("--parse-metadata", ":(?P<meta_synopsis>)")
            .addOption("--parse-metadata", ":(?P<meta_description>)")
            .addOption("--parse-metadata", ":(?P<meta_comment>)")
            .addOption("--parse-metadata", ":(?P<meta_genre>)")
            .addOption("--parse-metadata", ":(?P<meta_language>)")
            .addOption("--no-mtime")
            .addOption("-R", 3)
            .addOption("-o", File(folder, "song.%(ext)s").absolutePath)
        if (withCover) {
            request
                .addOption("--embed-thumbnail")
                .addOption("--convert-thumbnails", "jpg")
            // A song's cover comes wide and players show it square, so it is cropped to
            // the middle square. A video keeps its picture as it is.
            if (!options.video) {
                request.addOption(
                    "--ppa",
                    "ThumbnailsConvertor+ffmpeg_o:-c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"",
                )
            }
        }
        return request
    }
}

/** A failure of the engine, with a line that can be shown as it is. */
class EngineFault(message: String) : Exception(message) {
    companion object {
        /** Turns the engine's own report, which is long and technical, into one plain line. */
        fun from(fault: Throwable): EngineFault {
            val report = fault.message.orEmpty()
            // The full report goes to the phone's log, where it can be read over USB.
            Log.w("SpinePlus", "engine fault: ${fault.javaClass.simpleName}: $report")
            val line = report.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }
                ?.removePrefix("ERROR:")?.trim().orEmpty()
            // The engine's warnings are in the report too, so only its last word decides.
            val said = line.ifEmpty { report }
            val plain = when {
                said.contains("Unable to download webpage", ignoreCase = true) ||
                    said.contains("Temporary failure in name resolution", ignoreCase = true) ||
                    said.contains("getaddrinfo", ignoreCase = true) ||
                    said.contains("timed out", ignoreCase = true) ||
                    said.contains("Network is unreachable", ignoreCase = true) ->
                    "No connection. Check the internet and try again."
                said.contains("Unsupported URL", ignoreCase = true) -> "Spine+ cannot get music from that address."
                said.contains("Sign in", ignoreCase = true) || said.contains("logged-in", ignoreCase = true) ||
                    said.contains("log in", ignoreCase = true) || said.contains("login", ignoreCase = true) ||
                    said.contains("--cookies") ->
                    "This one needs a signed-in account, which Spine+ does not have."
                said.contains("Private video", ignoreCase = true) || said.contains("unavailable", ignoreCase = true) ->
                    "This song is not available."
                // Anything else: the engine's own first sentence, without its "[site] id:" label.
                // Its advice about command-line options means nothing here, so a line that
                // has any is replaced by a plain one.
                line.isNotEmpty() -> line.replace(Regex("""^\[[^\]]+]\s*\S+:\s*"""), "")
                    .substringBefore(". ").trim().trimEnd('.')
                    .takeIf { it.isNotEmpty() && "--" !in it && it.length <= 120 }
                    ?.let { "$it." }
                    ?: "Spine+ could not read that link."
                else -> "The download did not work. Try again in a moment."
            }
            return EngineFault(plain)
        }
    }
}
