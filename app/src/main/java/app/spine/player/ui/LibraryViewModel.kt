package app.spine.player.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.TextureView
import app.spine.player.data.VideoProgress
import app.spine.player.download.Downloads
import app.spine.player.playback.PlayableVideo
import app.spine.player.playback.QueueVideo
import app.spine.player.data.suggestFromName
import app.spine.player.data.DuplicateGroup
import app.spine.player.data.SortFixes
import app.spine.player.data.findDuplicates
import app.spine.player.data.findSortFixes
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import app.spine.player.data.Lyrics
import app.spine.player.data.LyricsFinder
import app.spine.player.data.SongTags
import app.spine.player.data.TagEditor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spine.player.data.Library
import app.spine.player.data.LibraryStore
import app.spine.player.data.MusicLibrary
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.data.Stats
import app.spine.player.data.StatsPeriod
import app.spine.player.data.DownloadStats
import app.spine.player.data.StatsKind
import app.spine.player.data.VideoStats
import app.spine.player.data.buildDownloadStats
import app.spine.player.data.buildVideoStats
import app.spine.player.data.buildLibrary
import app.spine.player.data.buildPlaylists
import app.spine.player.data.buildStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import app.spine.player.playback.AudioSettings
import app.spine.player.playback.NowPlaying
import app.spine.player.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A song whose name could be tidier, with the title and artist it would get. */
data class TidyCandidate(val song: Song, val title: String, val artist: String, val sure: Boolean)

/** One file to change in a batch, with the two lines shown for it while it is saved. */
data class EditItem(val song: Song, val title: String, val detail: String, val changes: Map<String, String>)

/**
 * A batch of changes to many files, while it runs and after. [songs] is what was asked for,
 * in the order it is worked through; [saved] and [failed] fill up as each file is done.
 * [kind] says which screen started it, so another screen does not show it as its own.
 */
data class TidyRun(
    val kind: String,
    val songs: List<EditItem>,
    val saved: Set<Long> = emptySet(),
    val failed: Set<Long> = emptySet(),
    val running: Boolean = true,
    val startedMs: Long = SystemClock.elapsedRealtime(),
) {
    val handled: Int get() = saved.size + failed.size
}

/** The kinds of batch run, by the screen that starts them. */
const val RUN_TIDY = "tidy"
const val RUN_SORT = "sort"

/** What the Lyrics screen has to show for the loaded song. */
sealed interface LyricsState {
    /** Still looking; shown as nothing, since it rarely lasts long enough to read. */
    data object Looking : LyricsState

    data object None : LyricsState

    data class Found(val lyrics: Lyrics) : LyricsState
}

/** A queued song together with its slot in the player's playlist. */
data class QueueSong(val index: Int, val song: Song)

/** Holds the library and the link to the player for the screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val connection = PlayerConnection(application)

    /** True when the app was started to show its made-up library for screenshots (see [DemoMode]). */
    private val demo = DemoMode.enabled

    private val createdAtMs = SystemClock.elapsedRealtime()

    /**
     * Waits out the app's opening. What "Fix your library" works out is not needed on the
     * first screen, and working it out while the shelf stacks itself made that stutter.
     */
    private suspend fun afterOpening() {
        val wait = createdAtMs + OPENING_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
    }

    private val _library = MutableStateFlow(if (demo) DemoMode.library else Library.Empty)
    val library: StateFlow<Library> = _library.asStateFlow()

    /** False until the first scan has finished, so "no music" is not shown while still looking. */
    private val _scanned = MutableStateFlow(demo)
    val scanned: StateFlow<Boolean> = _scanned.asStateFlow()

    val nowPlaying: StateFlow<NowPlaying> = if (demo) MutableStateFlow(DemoMode.nowPlaying) else connection.state

    /** The loaded song first, then what plays after it. */
    val queue: StateFlow<List<QueueSong>> = if (demo) MutableStateFlow(DemoMode.queue) else
        combine(connection.upcoming, _library) { entries, library ->
            entries.mapNotNull { entry ->
                library.songsById[entry.songId]?.let { QueueSong(entry.index, it) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val store = LibraryStore.get(application)

    private val uiPrefs = application.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _playingPlaylistId = MutableStateFlow(
        if (demo) DemoMode.PLAYING_PLAYLIST else uiPrefs.getLong(KEY_PLAYING_PLAYLIST, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
    )

    /**
     * The playlist the queue was started from, or null when it came from anywhere else. The
     * player only knows songs, so this is kept here; it survives a restart with the queue.
     */
    val playingPlaylistId: StateFlow<Long?> = _playingPlaylistId.asStateFlow()

    private val _showPlayingAsked = MutableStateFlow(false)

    /** True from a tap on the playback notification until the app has opened Now playing for it. */
    val showPlayingAsked: StateFlow<Boolean> = _showPlayingAsked.asStateFlow()

    fun askToShowPlaying() {
        _showPlayingAsked.value = true
    }

    fun showPlayingDone() {
        _showPlayingAsked.value = false
    }

    private val _openAsked = MutableStateFlow<Uri?>(null)

    /** A song or video another app handed over to be played, until the app has started it. */
    val openAsked: StateFlow<Uri?> = _openAsked.asStateFlow()

    fun askToOpen(file: Uri) {
        _openAsked.value = file
    }

    fun openDone() {
        _openAsked.value = null
    }

    /**
     * Plays a file another app handed over. A song that is in the library plays as that
     * song, with everything the library knows about it. Anything else, a video or a song
     * from outside the library, plays by its address. Returns true if it went to the video
     * player's kind of playing, false if it is a library song.
     */
    suspend fun openFile(file: Uri): Boolean {
        val app = getApplication<Application>()
        // This can be the very first thing the app is asked to do, before the player is reachable.
        connection.awaitReady()
        if (DemoMode.quiet) connection.setVolume(0f)
        val known = withContext(Dispatchers.IO) {
            val songs = library.value.songsById
            // A file from the phone's own music index names its id at the end of its address.
            val byId = file.takeIf { it.authority == MediaStore.AUTHORITY && it.path.orEmpty().contains("/audio/") }
                ?.lastPathSegment?.toLongOrNull()?.let { songs[it] }
            byId ?: runCatching {
                app.contentResolver.query(file, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                    if (!row.moveToFirst()) return@use null
                    val name = row.getString(0)
                    val size = row.getLong(1)
                    songs.values.firstOrNull { it.fileName == name && it.sizeBytes == size && size > 0 }
                }
            }.getOrNull()
        }
        if (known != null) {
            playFromStart(listOf(known))
            return false
        }
        val name = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.query(file, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row ->
                    if (row.moveToFirst()) row.getString(0) else null
                }
            }.getOrNull()
        }.orEmpty().substringBeforeLast('.').ifBlank { "From another app" }
        playVideos(listOf(PlayableVideo(file.toString(), name, "")), 0)
        return true
    }

    /** The videos after the one playing, and the subtitle lines to show now. */
    val upcomingVideos: StateFlow<List<QueueVideo>> = connection.upcomingVideos
    val videoCues: StateFlow<String> = connection.cues

    /** Replaces the queue with [videos] and plays the one at [index], from where it was left last time. */
    fun playVideos(videos: List<PlayableVideo>, index: Int) {
        val video = videos.getOrNull(index) ?: return
        setPlayingPlaylist(null)
        connection.playVideos(videos, index, VideoProgress.resumeAt(getApplication(), video.uri))
    }

    /** Shows the playing video's picture on [view]; null takes it off the screen and leaves the sound. */
    fun showVideoOn(view: TextureView?) = connection.showVideoOn(view)

    fun pause() = connection.pause()

    fun skip(byMs: Long) = connection.skip(byMs)

    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    fun setSubtitles(on: Boolean) = connection.setSubtitles(on)

    private val _getAsked = MutableStateFlow<String?>(null)

    /** A link another app shared to Spine+, until the Get tab has taken it. */
    val getAsked: StateFlow<String?> = _getAsked.asStateFlow()

    fun askToGet(words: String) {
        _getAsked.value = words
    }

    fun getDone() {
        _getAsked.value = null
    }

    /** Equalizer, fade and volume choices; the playback service applies them as they change. */
    val audio: AudioSettings = AudioSettings.get(application)

    /** The user's playlists, then the smart ones. Re-read whenever the library or the store changes. */
    val playlists: StateFlow<List<Playlist>> = if (demo) MutableStateFlow(DemoMode.playlists) else
        combine(_library, store.version) { library, _ -> library }
            .mapLatest { library -> buildPlaylists(library, store.playlists(), store.playTotals(0L), store.hiddenFromSmart()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ids of the songs in Favourites, for the heart on Now playing. */
    val favouriteIds: StateFlow<Set<Long>> =
        playlists.map { all ->
            all.firstOrNull { it.isFavourites }?.songs?.mapTo(HashSet()) { it.id }.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val lyricsFinder = LyricsFinder(application)

    /** Ticks when the user adds or removes lyrics, so they are looked up again. */
    private val lyricsChanged = MutableStateFlow(0)

    /**
     * Lyrics for the loaded song. Looked up only while a screen is showing them, because
     * reading inside a song file takes a moment.
     */
    val lyrics: StateFlow<LyricsState> = if (demo) MutableStateFlow(LyricsState.Found(DemoMode.lyrics)) else
        combine(nowPlaying.map { it.songId }.distinctUntilChanged(), _library, lyricsChanged) { songId, library, _ ->
            songId?.let { library.songsById[it] }
        }.transformLatest { song ->
            emit(LyricsState.Looking)
            val found = song?.let { lyricsFinder.find(it) }
            emit(if (found != null) LyricsState.Found(found) else LyricsState.None)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsState.Looking)

    /** Keeps [text] as the song's lyrics; [onResult] hears whether it held any. */
    fun addLyrics(songId: Long, text: String, onResult: (added: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = lyricsFinder.add(songId, text)
            if (added) lyricsChanged.value++
            onResult(added)
        }
    }

    /** As [addLyrics], from a file the user picked. */
    fun addLyricsFrom(songId: Long, file: Uri, onResult: (added: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = lyricsFinder.addFrom(songId, file)
            if (added) lyricsChanged.value++
            onResult(added)
        }
    }

    /** Forgets lyrics the user added; lyrics from files are not touched. */
    fun removeLyrics(songId: Long) {
        viewModelScope.launch {
            lyricsFinder.remove(songId)
            lyricsChanged.value++
        }
    }

    private val _statsPeriod = MutableStateFlow(StatsPeriod.Month)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    val stats: StateFlow<Stats> =
        combine(_library, store.version, _statsPeriod) { library, _, period -> library to period }
            .mapLatest { (library, period) ->
                buildStats(library, if (demo) DemoMode.playTotals else store.playTotals(period.startMs()))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Stats())

    private val _statsKind = MutableStateFlow(
        if (demo) StatsKind.entries.firstOrNull { it.name.equals(DemoMode.statsKind, ignoreCase = true) } ?: StatsKind.Songs
        else StatsKind.Songs,
    )

    /** Which of the three the Stats screen is showing. */
    val statsKind: StateFlow<StatsKind> = _statsKind.asStateFlow()

    fun setStatsKind(kind: StatsKind) {
        _statsKind.value = kind
    }

    /** Watching in the chosen period. */
    val videoStats: StateFlow<VideoStats> =
        combine(_library, store.version, _statsPeriod) { library, _, period -> library to period }
            .mapLatest { (library, period) ->
                if (demo) DemoMode.videoStats else buildVideoStats(library, store.videoPlays(period.startMs()))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoStats())

    /** What was downloaded in the chosen period. */
    val downloadStats: StateFlow<DownloadStats> =
        combine(_library, store.version, _statsPeriod) { library, _, period -> library to period }
            .mapLatest { (library, period) ->
                if (demo) DemoMode.downloadStats else buildDownloadStats(library, period.startMs(), store.playTotals(0))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadStats())

    fun setStatsPeriod(period: StatsPeriod) {
        _statsPeriod.value = period
    }

    /** Makes a playlist and, if given, puts [songs] in it. */
    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = store.createPlaylist(trimmed)
            if (songs.isNotEmpty()) store.addSongs(id, songs.map { it.id })
        }
    }

    /** Saves what "Edit playlist" changed: a new name, and songs taken out. */
    fun editPlaylist(playlist: Playlist, name: String, removed: Set<Long>) {
        if (playlist.isSmart) return
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty() && trimmed != playlist.name) store.renamePlaylist(playlist.id, trimmed)
            if (removed.isNotEmpty()) store.removeSongs(playlist.id, removed)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { store.deletePlaylist(playlistId) }
    }

    fun addToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch { store.addSongs(playlistId, songs.map { it.id }) }
    }

    /**
     * Takes [song] out of a playlist. A smart playlist is worked out from a rule, so there
     * the song is remembered as left out instead; [restoreRemoved] brings those back.
     */
    fun removeFromPlaylist(playlist: Playlist, song: Song) {
        viewModelScope.launch {
            if (playlist.isSmart) {
                store.addSongs(playlist.id, listOf(song.id))
            } else {
                store.removeSong(playlist.id, song.id)
            }
        }
    }

    /** Puts back every song the user removed from a smart playlist. */
    fun restoreRemoved(playlist: Playlist) {
        viewModelScope.launch { store.clearSongs(playlist.id) }
    }

    /** Plays a playlist from its first song, whatever is loaded now. */
    fun playPlaylistFromStart(playlist: Playlist) {
        setPlayingPlaylist(playlist.id)
        connection.play(playlist.songs, 0)
    }

    fun toggleFavourite(songId: Long) {
        viewModelScope.launch {
            if (songId in favouriteIds.value) {
                store.removeSong(LibraryStore.FAVOURITES_ID, songId)
            } else {
                store.addSongs(LibraryStore.FAVOURITES_ID, listOf(songId))
            }
        }
    }

    init {
        // The player has no position callback, so poll while something is playing.
        viewModelScope.launch {
            var noted = 0L
            var notedPosition = -1L
            while (isActive) {
                val playing = nowPlaying.value
                if (playing.isPlaying) connection.refreshPosition()
                // How far a video has been watched is written down every few seconds, and at
                // once when it stops, so it can carry on from there and its row can show it.
                val video = playing.videoUri
                if (video != null && playing.durationMs > 0) {
                    val now = SystemClock.elapsedRealtime()
                    val due = if (playing.isPlaying) now - noted >= VIDEO_NOTE_MS else playing.positionMs != notedPosition
                    if (due) {
                        VideoProgress.note(getApplication(), video, playing.positionMs, playing.durationMs)
                        noted = now
                        notedPosition = playing.positionMs
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    /** A song's details as its file has them, falling back to what the phone's index says. */
    suspend fun readTags(song: Song): SongTags =
        TagEditor.read(getApplication(), song)
            ?: SongTags(title = song.title, artist = song.artist, album = song.album)

    /**
     * Writes each song's changed fields into its file, then reads the library again.
     * [onDone] hears whether every file was saved. Call once the user has allowed the write.
     */
    fun writeTags(edits: List<Pair<Song, Map<String, String>>>, onDone: (allSaved: Boolean) -> Unit) {
        viewModelScope.launch {
            var allSaved = true
            for ((song, changes) in edits) {
                if (!TagEditor.write(getApplication(), song, changes)) allSaved = false
            }
            reloadAfterEdit(edits.mapTo(HashSet()) { it.first.id })
            onDone(allSaved)
        }
    }

    /** Every song whose name has a tidier suggestion. */
    val tidyCandidates: StateFlow<List<TidyCandidate>> =
        _library.mapLatest { library ->
            afterOpening()
            withContext(Dispatchers.Default) {
                library.songs.mapNotNull { song ->
                    suggestFromName(song.title, song.artist)?.let { TidyCandidate(song, it.title, it.artist, it.sure) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tidyRun = MutableStateFlow<TidyRun?>(null)

    /** The tidy-up under way or just finished, or null. */
    val tidyRun: StateFlow<TidyRun?> = _tidyRun.asStateFlow()

    private var tidyJob: Job? = null

    /**
     * Gives each of [songs] its tidy name, one file at a time, reporting through [tidyRun].
     * Call once the user has allowed the write. [stopTidy] ends it after the song in hand:
     * a file is never left half written.
     */
    fun startTidy(songs: List<TidyCandidate>) = startEdits(
        RUN_TIDY,
        songs.map { candidate ->
            val song = candidate.song
            EditItem(
                song = song,
                title = candidate.title,
                detail = candidate.artist,
                changes = buildMap {
                    if (candidate.title != song.title) put(SongTags.TITLE, candidate.title)
                    if (candidate.artist != song.artist) put(SongTags.ARTIST, candidate.artist)
                },
            )
        },
    )

    /** Carries out [items] one file at a time, reporting through [tidyRun]. */
    fun startEdits(kind: String, items: List<EditItem>) {
        if (items.isEmpty() || _tidyRun.value?.running == true) return
        _tidyRun.value = TidyRun(kind, items)
        tidyJob = viewModelScope.launch {
            try {
                for (item in items) {
                    if (!isActive) break
                    val song = item.song
                    val changes = item.changes
                    val saved = withContext(NonCancellable) { TagEditor.write(getApplication(), song, changes) }
                    _tidyRun.update { run ->
                        run?.let { if (saved) it.copy(saved = it.saved + song.id) else it.copy(failed = it.failed + song.id) }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    reloadAfterEdit(_tidyRun.value?.saved.orEmpty())
                    _tidyRun.update { it?.copy(running = false) }
                }
            }
        }
    }

    fun stopTidy() {
        tidyJob?.cancel()
    }

    /** Forgets a finished run, so the screen starts fresh next time. */
    fun clearTidy() {
        if (_tidyRun.value?.running != true) _tidyRun.value = null
    }

    /** Songs that are on the phone more than once. */
    val duplicates: StateFlow<List<DuplicateGroup>> =
        _library.mapLatest { library ->
            afterOpening()
            withContext(Dispatchers.Default) { findDuplicates(library.songs) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What would tidy the Artists and Albums tabs. */
    val sortFixes: StateFlow<SortFixes> =
        _library.mapLatest { library ->
            afterOpening()
            withContext(Dispatchers.Default) { findSortFixes(library) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortFixes(emptyList(), emptyList()))

    /**
     * Before copies are deleted: playlists and play counts that named a copy (the keys) are
     * pointed at the copy that stays (the values), so nothing the user saved goes with the file.
     */
    fun keepInsteadOf(replacements: Map<Long, Long>) {
        viewModelScope.launch {
            for ((gone, kept) in replacements) store.replaceSong(gone, kept)
        }
    }

    /** Reads the library again after files changed, and renames queued copies of [editedIds]. */
    private suspend fun reloadAfterEdit(editedIds: Set<Long>) {
        val songs = MusicLibrary.loadSongs(getApplication())
        if (songs != _library.value.songs) {
            _library.value = withContext(Dispatchers.Default) { buildLibrary(songs) }
        }
        // Anything of these that is loaded or queued should show its new name too.
        connection.renameSongs(songs.filter { it.id in editedIds })
    }

    private var scanJob: Job? = null

    /** Reads the phone's music. Call only once the audio permission is granted. */
    fun scan() {
        // The app asks at launch and again as its screen appears; one read answers both.
        if (demo || scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val songs = MusicLibrary.loadSongs(getApplication())
            val videos = Downloads.videoSongs(getApplication())
            // Returning to the app rescans; skip the rebuild when nothing changed.
            if (!_scanned.value || songs != _library.value.songs) {
                _library.value = withContext(Dispatchers.Default) { buildLibrary(songs) }.copy(videos = videos)
            } else if (videos != _library.value.videos) {
                _library.value = _library.value.copy(videos = videos)
            }
            _scanned.value = true
        }
    }

    /**
     * Plays [songs] from [index], with the rest of the list queued behind it. Tapping the
     * song that is already loaded pauses or resumes it instead of starting it again.
     */
    fun play(songs: List<Song>, index: Int) = start(songs, index, playlistId = null)

    /** As [play], for a tap inside a playlist: remembers that this playlist is what is playing. */
    fun playPlaylist(playlist: Playlist, index: Int) = start(playlist.songs, index, playlist.id)

    fun shufflePlaylist(playlist: Playlist) {
        setPlayingPlaylist(playlist.id)
        connection.playShuffled(playlist.songs)
    }

    private fun start(songs: List<Song>, index: Int, playlistId: Long?) {
        if (songs.getOrNull(index)?.id == nowPlaying.value.songId) {
            connection.togglePlayPause()
        } else {
            setPlayingPlaylist(playlistId)
            // A video carries on from where it was left.
            val first = songs.getOrNull(index)
            val from = if (first?.isVideo == true) VideoProgress.resumeAt(getApplication(), first.uri.toString()) else 0L
            connection.play(songs, index, from)
        }
    }

    private fun setPlayingPlaylist(playlistId: Long?) {
        _playingPlaylistId.value = playlistId
        uiPrefs.edit {
            if (playlistId == null) remove(KEY_PLAYING_PLAYLIST) else putLong(KEY_PLAYING_PLAYLIST, playlistId)
        }
    }

    /** Replaces the queue with [songs] and plays from the first, whatever is loaded now. */
    fun playFromStart(songs: List<Song>) {
        setPlayingPlaylist(null)
        connection.play(songs, 0)
    }

    /** Called once files are gone from the phone: clears them from the queue and the library. */
    fun onSongsDeleted(songIds: Set<Long>) {
        connection.removeSongs(songIds)
        scan()
    }

    fun playShuffled(songs: List<Song>) {
        setPlayingPlaylist(null)
        connection.playShuffled(songs)
    }

    fun togglePlayPause() = connection.togglePlayPause()

    fun next() = connection.next()

    fun previous() = connection.previous()

    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)

    fun toggleShuffle() = connection.toggleShuffle()

    fun cycleRepeat() = connection.cycleRepeat()

    fun jumpToQueueSlot(index: Int) = connection.jumpTo(index)

    fun removeFromQueue(index: Int) = connection.removeFromQueue(index)

    fun clearQueue() = connection.clearUpcoming()

    fun playNext(songs: List<Song>) = connection.playNext(songs)

    fun addToQueue(songs: List<Song>) = connection.addToQueue(songs)

    override fun onCleared() = connection.release()

    private companion object {
        const val POSITION_POLL_MS = 250L

        /** How often a playing video's position is written down. */
        const val VIDEO_NOTE_MS = 5_000L

        /** How long after launch the opening animation has the phone to itself. */
        const val OPENING_MS = 2_500L
        const val KEY_PLAYING_PLAYLIST = "playing_playlist"
    }
}
