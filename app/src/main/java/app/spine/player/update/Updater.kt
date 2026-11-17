package app.spine.player.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer version that can be installed: its number, what it brings, and where its file is. */
data class UpdateOffer(val version: String, val notes: List<String>, val apkUrl: String, val bytes: Long)

/** Where the app stands with updating itself. */
sealed interface UpdateState {
    /** No place to ask has been set yet ([Updater.REPOSITORY] is empty). */
    data object NotSetUp : UpdateState

    /** Not asked yet since the app started. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val offer: UpdateOffer) : UpdateState

    /** [progress] is from 0 to 1. */
    data class Downloading(val offer: UpdateOffer, val progress: Float) : UpdateState

    /** Downloaded and waiting for the user to install it. */
    data class Ready(val offer: UpdateOffer, val file: File) : UpdateState

    /** [offer] is set when the failure was in downloading it, so it can be tried again. */
    data class Failed(val words: String, val offer: UpdateOffer?) : UpdateState
}

/**
 * Lets the app update itself, since it is not in a store that would do it.
 *
 * It asks GitHub for the newest release of [REPOSITORY]. If that release's number is higher
 * than this app's, and it has an APK attached, the app offers it: the user sees what is new,
 * the file is downloaded into the app's own cache, and Android's installer is opened on it.
 * Android asks the user to allow Spine+ to install apps the first time, and installs only
 * if the file is signed with the same key as the app already on the phone.
 *
 * To publish a version: make a release on GitHub tagged with the version ("v1.2.0"),
 * attach the signed APK, and write what is new in the release's text, one point a line.
 */
object Updater {

    /**
     * Where releases are published, as "owner/repository" on GitHub. It is a public
     * repository that holds only the releases. Left empty, the app says updating is not
     * set up and asks nobody.
     */
    const val REPOSITORY = "SafinRweb/spine-plus-releases"

    private const val PREFS = "updates"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_LATER = "later_version"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(if (REPOSITORY.isEmpty()) UpdateState.NotSetUp else UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** This app's version, such as "1.1.0". */
    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /**
     * Whether version [candidate] is newer than [than]. Versions are compared number by
     * number ("1.10.0" is newer than "1.9.3"); a leading "v" and anything after the numbers,
     * such as "-beta", are ignored.
     */
    fun newer(candidate: String, than: String): Boolean {
        fun numbers(version: String) = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = numbers(candidate)
        val b = numbers(than)
        for (place in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(place) { 0 } - b.getOrElse(place) { 0 }
            if (difference != 0) return difference > 0
        }
        return false
    }

    /**
     * Reads GitHub's description of a release. Returns the offer if it is newer than
     * [installed] and has an APK attached, otherwise null.
     */
    fun parse(answer: String, installed: String): UpdateOffer? {
        val json = JSONObject(answer)
        val version = json.optString("tag_name").trim().removePrefix("v")
        if (version.isEmpty() || !newer(version, installed)) return null
        val assets = json.optJSONArray("assets") ?: return null
        val files = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        // If a release carries several builds, the one for this kind of phone.
        val file = files.firstOrNull { it.optString("name").contains("arm64", ignoreCase = true) } ?: files.firstOrNull() ?: return null
        val address = file.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
        // The release's text, a point to a line, without the marks GitHub's formatting uses.
        val notes = json.optString("body").lineSequence()
            .map { it.trim().trimStart('-', '*', '#', '•', ' ').replace("**", "").replace("`", "").trim() }
            // The line GitHub adds by itself is a web address, not news.
            .filter { it.isNotEmpty() && !it.startsWith("Full Changelog", ignoreCase = true) }
            .take(8).toList()
        return UpdateOffer(version, notes, address, file.optLong("size", 0))
    }

    /** Asks GitHub for the newest release of [repository]. Null if there is nothing newer; throws if it cannot be reached. */
    suspend fun latest(repository: String, installed: String): UpdateOffer? = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/repos/$repository/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SpinePlus")
        }
        try {
            when (connection.responseCode) {
                200 -> parse(connection.inputStream.bufferedReader().use { it.readText() }, installed)
                // A repository with no release yet.
                404 -> null
                // GitHub answers an address only 60 such questions an hour, and a phone on a
                // shared address (a mobile network, a VPN) can find them used up by others.
                403, 429 -> latestFromWebsite(repository, installed)
                else -> error("GitHub answered ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The second way to find the newest release, with no hourly limit: GitHub's website
     * sends "releases/latest" on to the newest release's page, whose address ends in its
     * tag. That gives the version but not what is new, and the APK is then expected under
     * the name every release uses: "spine-plus-VERSION-arm64.apk".
     */
    suspend fun latestFromWebsite(repository: String, installed: String): UpdateOffer? = withContext(Dispatchers.IO) {
        val connection = (URL("https://github.com/$repository/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "SpinePlus")
        }
        try {
            val code = connection.responseCode
            if (code == 404) return@withContext null
            if (code !in 301..308) error("GitHub's website answered $code")
            // With no release yet it sends on to the list of releases, which has no tag in it.
            val tag = connection.getHeaderField("Location").orEmpty().substringAfter("/releases/tag/", "").substringBefore('?')
            val version = tag.removePrefix("v")
            if (tag.isEmpty() || tag.any { !it.isLetterOrDigit() && it != '.' && it != '-' } || !newer(version, installed)) {
                return@withContext null
            }
            UpdateOffer(
                version = version,
                notes = emptyList(),
                apkUrl = "https://github.com/$repository/releases/download/$tag/spine-plus-$version-arm64.apk",
                bytes = 0,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Looks for a newer version. With [quiet] it is the look the app takes by itself as it
     * opens: at most once a day, and a failure is not shown.
     */
    fun check(context: Context, quiet: Boolean = false) {
        if (REPOSITORY.isEmpty()) return
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading || current is UpdateState.Ready) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (quiet && System.currentTimeMillis() - prefs.getLong(KEY_CHECKED_AT, 0) < ONE_DAY_MS) return
        job = scope.launch {
            if (!quiet) _state.value = UpdateState.Checking
            try {
                val offer = latest(REPOSITORY, installedVersion(app))
                prefs.edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }
                val kept = offer?.let { keptFile(app, it) }
                _state.value = when {
                    offer == null -> {
                        // Nothing newer, so a file left from the last update has done its work.
                        withContext(Dispatchers.IO) { folder(app).deleteRecursively() }
                        UpdateState.UpToDate
                    }
                    // Downloaded before and not yet installed: no need to fetch it twice.
                    kept != null -> UpdateState.Ready(offer, kept)
                    else -> UpdateState.Available(offer)
                }
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (fault: Exception) {
                if (!quiet) _state.value = UpdateState.Failed("Could not look for an update. Check the internet and try again.", null)
            }
        }
    }

    /** Downloads the offered version into the app's cache, reporting how far it is. */
    fun download(context: Context) {
        val offer = when (val current = _state.value) {
            is UpdateState.Available -> current.offer
            is UpdateState.Failed -> current.offer ?: return
            else -> return
        }
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(offer, 0f)
        job = scope.launch {
            _state.value = try {
                UpdateState.Ready(offer, fetch(app, offer))
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (fault: Exception) {
                UpdateState.Failed("The update did not download. Try again in a moment.", offer)
            }
        }
    }

    /** Stops a download that is under way; the version is still on offer. */
    fun stop() {
        val current = _state.value as? UpdateState.Downloading ?: return
        job?.cancel()
        _state.value = UpdateState.Available(current.offer)
    }

    private fun folder(context: Context) = File(context.cacheDir, "updates")

    private fun fileFor(context: Context, offer: UpdateOffer) = File(folder(context), "spine-plus-${offer.version}.apk")

    /** The offered version's file, if it was downloaded whole before. */
    private fun keptFile(context: Context, offer: UpdateOffer): File? =
        fileFor(context, offer).takeIf { offer.bytes > 0 && it.length() == offer.bytes }

    /** Puts the updater in [state] without asking anyone, for a debug build taking screenshots. */
    fun preview(state: UpdateState) {
        _state.value = state
    }

    private suspend fun fetch(context: Context, offer: UpdateOffer): File = withContext(Dispatchers.IO) {
        val folder = folder(context).apply { mkdirs() }
        // Only one update is ever kept.
        folder.listFiles()?.forEach { it.delete() }
        val file = fileFor(context, offer)
        val connection = (URL(offer.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "SpinePlus")
        }
        try {
            if (connection.responseCode != 200) error("The file's address answered ${connection.responseCode}")
            val total = offer.bytes.takeIf { it > 0 } ?: connection.contentLengthLong
            var arrived = 0L
            var shown = 0f
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Stopped by the user: reading on would only waste their data.
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        arrived += count
                        if (total > 0) {
                            val progress = (arrived.toFloat() / total).coerceIn(0f, 1f)
                            if (progress - shown >= 0.01f) {
                                shown = progress
                                // Only while it is still downloading, so a late report cannot undo "Stop".
                                _state.update { if (it is UpdateState.Downloading) UpdateState.Downloading(offer, progress) else it }
                            }
                        }
                    }
                }
            }
            // Half a file must never reach the installer.
            if (total > 0 && arrived != total) {
                file.delete()
                error("The file came short: $arrived of $total bytes")
            }
            file
        } finally {
            connection.disconnect()
        }
    }

    /** Opens Android's installer on the downloaded update. Returns false if that could not be done. */
    fun install(context: Context): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /** The user said "Later" to [version]: it is not put in front of them again, though About still names it. */
    fun later(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_LATER, version) }
    }

    fun saidLater(context: Context, version: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LATER, null) == version
}
