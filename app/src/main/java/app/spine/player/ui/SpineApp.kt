package app.spine.player.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.produceState
import app.spine.player.playback.SleepState
import app.spine.player.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.spine.player.data.Album
import app.spine.player.data.Artist
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.data.SongDeleter
import app.spine.player.data.TagEditor
import app.spine.player.data.renameEdits
import app.spine.player.data.StatsKind
import app.spine.player.data.VideoProgress
import app.spine.player.download.Downloads
import app.spine.player.playback.PlayableVideo
import app.spine.player.ui.components.DeletePopup
import app.spine.player.ui.components.DeleteRequest
import app.spine.player.ui.components.ItemMenu
import app.spine.player.ui.components.MenuAction
import app.spine.player.ui.components.MenuState
import app.spine.player.ui.components.NamePopup
import app.spine.player.ui.components.NameRequest
import app.spine.player.ui.components.SystemBarIcons
import app.spine.player.ui.components.owned
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.Palette
import app.spine.player.update.UpdateOffer
import app.spine.player.update.UpdateState
import app.spine.player.update.Updater

private sealed interface Screen {
    data object Shelf : Screen
    data class AlbumDetail(val albumId: Long) : Screen
    data class ArtistDetail(val name: String) : Screen
    data class PlaylistDetail(val playlistId: Long) : Screen
    data object NowPlaying : Screen
    data object VideoPlayer : Screen

    /** The list a new playlist called [name] is filled from. */
    data class PickForPlaylist(val name: String, val from: FillFrom) : Screen
    data object Queue : Screen
    data object Lyrics : Screen
    data class EditSong(val songId: Long) : Screen
    data class EditAlbum(val albumId: Long) : Screen
    data class EditArtist(val name: String) : Screen
    data class EditPlaylist(val playlistId: Long) : Screen
    data object Tidy : Screen
    data object Fix : Screen
    data object Duplicates : Screen
    data object Sort : Screen
    data object Search : Screen
    data object Stats : Screen
    data object Settings : Screen
    data object Permissions : Screen
    data object About : Screen
    data object Licences : Screen

    /** [backLabel] names the screen it was opened from, for its back link. */
    data class Equalizer(val backLabel: String) : Screen

    /** The screens that save into song files and close themselves when that is done. */
    val isEditor: Boolean
        get() = this is EditSong || this is EditAlbum || this is EditArtist

    /** Dark screens are the ones inside the music; light ones are for browsing. */
    val onInk: Boolean
        get() = this is AlbumDetail || this is PlaylistDetail || this is NowPlaying || this is Queue ||
            this is Equalizer || this is Lyrics || this is VideoPlayer
}

/**
 * One screen on the back stack. [origin] is where on screen it was opened from (the spine
 * that was tapped, the strip at the bottom); the screen grows out of that spot and shrinks
 * back into it.
 */
private data class Entry(val screen: Screen, val depth: Int, val origin: Rect?)

/** What every browsing screen can do with songs, albums, artists and playlists. */
@Immutable
class LibraryActions(
    val play: (songs: List<Song>, index: Int) -> Unit,
    val openAlbum: (album: Album, from: Rect?) -> Unit,
    val openArtist: (artist: Artist, from: Rect?) -> Unit,
    val openPlaylist: (playlist: Playlist, from: Rect?) -> Unit,
    val songMenu: (song: Song, bounds: Rect) -> Unit,
    /** The song menu with more actions put in before Delete, such as those only a download has. */
    val songMenuWith: (song: Song, bounds: Rect, extra: List<MenuAction>) -> Unit,
    val albumMenu: (album: Album, bounds: Rect) -> Unit,
    val artistMenu: (artist: Artist, bounds: Rect) -> Unit,
    val playlistMenu: (playlist: Playlist, bounds: Rect) -> Unit,
)

/** After this many seeks the "drag to seek" hint has done its job and stops showing. */
private const val SEEK_HINT_USES = 3
private const val KEY_SEEK_USES = "seek_uses"
private const val SCREEN_TRANSITION_MS = 340
private const val DELETE_WARNING = "This cannot be undone."

/** The app once it can read music: decides which screen is on show and wires each to the player. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SpineApp(viewModel: LibraryViewModel) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val favouriteIds by viewModel.favouriteIds.collectAsStateWithLifecycle()
    val scanned by viewModel.scanned.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val feel = rememberFeel()
    val playingPlaylistId by viewModel.playingPlaylistId.collectAsStateWithLifecycle()
    // The playlist the queue came from, with the time the queue still has to run.
    val playingPlaylist = playingPlaylistId?.let { id ->
        PlayingPlaylist(id, (queue.sumOf { it.song.durationMs } - nowPlaying.positionMs).coerceAtLeast(0))
    }
    val currentLibrary by rememberUpdatedState(library)
    val currentPlaylists by rememberUpdatedState(playlists)

    val backStack = remember {
        mutableStateListOf(Entry(Screen.Shelf, depth = 0, origin = null)).apply {
            // A debug build taking screenshots can start on another screen.
            when (DemoMode.screen) {
                "fix" -> add(Entry(Screen.Fix, depth = 1, origin = null))
                "pick" -> add(Entry(Screen.PickForPlaylist("Road trip", FillFrom.Both), depth = 1, origin = null))
                "stats" -> add(Entry(Screen.Stats, depth = 1, origin = null))
                "settings", "look", "play-colour", "shelf-colour" -> add(Entry(Screen.Settings, depth = 1, origin = null))
                "permissions" -> {
                    add(Entry(Screen.Settings, depth = 1, origin = null))
                    add(Entry(Screen.Permissions, depth = 2, origin = null))
                }
                "about" -> {
                    add(Entry(Screen.Settings, depth = 1, origin = null))
                    add(Entry(Screen.About, depth = 2, origin = null))
                }
                "licences" -> {
                    add(Entry(Screen.Settings, depth = 1, origin = null))
                    add(Entry(Screen.About, depth = 2, origin = null))
                    add(Entry(Screen.Licences, depth = 3, origin = null))
                }
                "album", "edit-album" -> DemoMode.library.albums.firstOrNull { it.title == "Paper Sun" }?.let { album ->
                    add(Entry(Screen.AlbumDetail(album.id), depth = 1, origin = null))
                    if (DemoMode.screen == "edit-album") add(Entry(Screen.EditAlbum(album.id), depth = 2, origin = null))
                }
                "artist", "edit-artist" -> {
                    add(Entry(Screen.ArtistDetail("June Arcade"), depth = 1, origin = null))
                    if (DemoMode.screen == "edit-artist") add(Entry(Screen.EditArtist("June Arcade"), depth = 2, origin = null))
                }
                "playlist", "edit-playlist" -> {
                    add(Entry(Screen.PlaylistDetail(DemoMode.PLAYING_PLAYLIST), depth = 1, origin = null))
                    if (DemoMode.screen == "edit-playlist") {
                        add(Entry(Screen.EditPlaylist(DemoMode.PLAYING_PLAYLIST), depth = 2, origin = null))
                    }
                }
            }
        }
    }
    // Whether the last change opened a screen or closed one; decides which screen animates.
    var opening by remember { mutableStateOf(true) }
    val top = backStack.last()
    val open: (Screen, Rect?) -> Unit = { screen, origin ->
        if (backStack.last().screen != screen) {
            opening = true
            backStack.add(Entry(screen, depth = backStack.size, origin = origin))
        }
    }
    val back: () -> Unit = {
        if (backStack.size > 1) {
            opening = false
            backStack.removeAt(backStack.lastIndex)
        }
    }
    BackHandler(enabled = backStack.size > 1, onBack = back)

    val context = LocalContext.current
    val uiSettings = remember { UiSettings.get(context) }
    val startTab by uiSettings.startTab.collectAsStateWithLifecycle()
    val tabOrder by uiSettings.tabOrder.collectAsStateWithLifecycle()
    val showEqualizer by uiSettings.showEqualizer.collectAsStateWithLifecycle()
    val name by uiSettings.name.collectAsStateWithLifecycle()
    val videoKeepsPlaying by uiSettings.videoKeepsPlaying.collectAsStateWithLifecycle()
    // Leaving the video player pauses the video, if the user has asked for that.
    val onVideoScreen = backStack.any { it.screen == Screen.VideoPlayer }
    LaunchedEffect(onVideoScreen) {
        if (!onVideoScreen && !videoKeepsPlaying && viewModel.nowPlaying.value.isVideo) viewModel.pause()
    }
    val theme by uiSettings.theme.collectAsStateWithLifecycle()
    // Which of Settings' pages is open, null for its boxes. Kept here so it is still open
    // after a trip into the equalizer or a colour sheet.
    var settingsPage by rememberSaveable {
        mutableStateOf(if (DemoMode.screen == "look") SettingsPage.Look else null)
    }
    val showSleep by uiSettings.showSleep.collectAsStateWithLifecycle()
    val vibration by uiSettings.vibration.collectAsStateWithLifecycle()
    val animations by uiSettings.animations.collectAsStateWithLifecycle()
    val showLyrics by uiSettings.showLyrics.collectAsStateWithLifecycle()
    val playColour by uiSettings.playColour.collectAsStateWithLifecycle()
    val shelfColour by uiSettings.shelfColour.collectAsStateWithLifecycle()
    // Which colour sheet is up, if either.
    var colourSheet by remember {
        mutableStateOf(
            when (DemoMode.screen) {
                "play-colour" -> ColourSetting.Play
                "shelf-colour" -> ColourSetting.Shelf
                else -> null
            },
        )
    }

    // The sleep timer and, while it runs, how long is left in words.
    val sleep by SleepTimer.state.collectAsStateWithLifecycle()
    val sleepLabel by produceState<String?>(initialValue = null, sleep) {
        when (val state = sleep) {
            SleepState.Off -> value = null
            SleepState.EndOfSong -> value = "end of song"
            is SleepState.After -> while (true) {
                val leftMs = state.atElapsedMs - SystemClock.elapsedRealtime()
                value = "${((leftMs + 59_999) / 60_000).coerceAtLeast(1)} min"
                delay(15_000)
            }
        }
    }
    val currentSleep by rememberUpdatedState(sleep)
    val currentSleepLabel by rememberUpdatedState(sleepLabel)
    val currentStartTab by rememberUpdatedState(startTab)

    // Shelf state lives here so it survives opening an album and coming back.
    // A debug build taking screenshots can start on the Get tab.
    var tab by rememberSaveable {
        mutableStateOf(if (DemoMode.screen == "get") ShelfTab.Get else uiSettings.startTab.value)
    }
    var folderPath by rememberSaveable { mutableStateOf("") }
    // True from launch until the first screen has stacked itself into place. Not saved:
    // coming back to a running app should not replay it, a fresh start should.
    var entering by remember { mutableStateOf(true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var getQuery by rememberSaveable { mutableStateOf(DemoMode.getWords.orEmpty()) }
    // A downloaded song is on the phone but not yet in what the app has read: read it again.
    val downloadsArrived by Downloads.arrived.collectAsStateWithLifecycle()
    LaunchedEffect(downloadsArrived) {
        if (downloadsArrived > 0) {
            viewModel.scan()
            // Android lists a new file a moment after it is handed over; look once more.
            delay(1_500)
            viewModel.scan()
        }
    }
    // A newer version of the app: looked for quietly as the app opens, and put in front of
    // the user once unless they have said "Later" to that version.
    val updateState by Updater.state.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        when (DemoMode.screen) {
            // A debug build taking screenshots shows a made-up update.
            "update" -> Updater.preview(UpdateState.Available(DemoUpdate))
            "update-downloading" -> {
                Updater.preview(UpdateState.Downloading(DemoUpdate, 0.42f))
                showUpdate = true
            }
            else -> if (!DemoMode.enabled) Updater.check(context, quiet = true)
        }
    }
    LaunchedEffect(updateState) {
        val version = when (val state = updateState) {
            is UpdateState.Available -> state.offer.version
            // Downloaded while the popup was put away: time to say so.
            is UpdateState.Ready -> state.offer.version
            else -> null
        }
        if (version != null && !Updater.saidLater(context, version)) showUpdate = true
    }
    val albumsState = rememberLazyListState()
    val singlesState = rememberLazyListState()
    val artistsState = rememberLazyListState()
    val playlistsState = rememberLazyListState()
    val songsState = rememberLazyListState()
    val listStates = remember {
        ShelfListStates(albumsState, singlesState, artistsState, playlistsState, songsState)
    }

    var menu by remember { mutableStateOf<MenuState?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var nameRequest by remember { mutableStateOf<NameRequest?>(null) }

    val uiPrefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var seekUses by remember { mutableIntStateOf(uiPrefs.getInt(KEY_SEEK_USES, 0)) }

    // Android asks the user itself before an app may delete a file it did not create.
    var awaitingConsentFor by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onSongsDeleted(awaitingConsentFor)
        awaitingConsentFor = emptySet()
    }
    // Adding lyrics: why the last try did nothing, and the song a picked file is meant for.
    var lyricsNotice by remember { mutableStateOf<String?>(null) }
    var lyricsFileFor by remember { mutableStateOf<Long?>(null) }
    val onLyricsAdded: (Boolean, String) -> Unit = { added, problem ->
        if (added) feel.done() else feel.warn()
        lyricsNotice = if (added) null else problem
    }
    // Saving edited details: Android asks the user first, then the files are written.
    var tagEdits by remember { mutableStateOf<List<Pair<Song, Map<String, String>>>>(emptyList()) }
    var savingTags by remember { mutableStateOf(false) }
    var tagProblem by remember { mutableStateOf<String?>(null) }
    // One of the songs being saved, to find its album and artist again afterwards.
    var tagFollow by remember { mutableStateOf<Long?>(null) }
    val writeTagEdits: () -> Unit = {
        savingTags = true
        viewModel.writeTags(tagEdits) { allSaved ->
            savingTags = false
            tagEdits = emptyList()
            if (allSaved) {
                feel.done()
                // Renaming an album or an artist gives it a new identity. The screens under
                // the editor follow one of the edited songs to where it is filed now, so
                // saving returns to the album or artist rather than to the Shelf.
                val after = viewModel.library.value
                val followed = tagFollow
                backStack.forEachIndexed { index, entry ->
                    val moved = when (val screen = entry.screen) {
                        is Screen.AlbumDetail ->
                            after.albumOf(followed)?.takeIf { after.album(screen.albumId) == null }
                                ?.let { Screen.AlbumDetail(it.id) }
                        is Screen.ArtistDetail ->
                            after.artistOf(followed)?.takeIf { after.artist(screen.name) == null }
                                ?.let { Screen.ArtistDetail(it.name) }
                        else -> null
                    }
                    if (moved != null) backStack[index] = entry.copy(screen = moved)
                }
                // The editor may already have closed itself, if what it was editing is gone.
                if (backStack.last().screen.isEditor) back()
            } else {
                feel.warn()
                tagProblem = "Could not save. The file may be of a kind Spine cannot change, or in use."
            }
        }
    }
    val tagConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) writeTagEdits() else tagEdits = emptyList()
    }
    val saveTags: (List<Pair<Song, Map<String, String>>>) -> Unit = { edits ->
        val changed = edits.filter { it.second.isNotEmpty() }
        tagProblem = null
        if (changed.isEmpty()) {
            back()
        } else {
            tagEdits = changed
            tagFollow = changed.first().first.id
            val consent = runCatching { TagEditor.consentToWrite(context, changed.map { it.first }) }.getOrNull()
            if (consent != null) {
                tagConsentLauncher.launch(IntentSenderRequest.Builder(consent).build())
            } else {
                writeTagEdits()
            }
        }
    }
    // Tidying many names: one prompt from Android covers all the files, then they are saved.
    val tidyCandidates by viewModel.tidyCandidates.collectAsStateWithLifecycle()
    val tidyRun by viewModel.tidyRun.collectAsStateWithLifecycle()
    var tidyPicked by remember { mutableStateOf<List<TidyCandidate>>(emptyList()) }
    val tidyConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startTidy(tidyPicked)
        tidyPicked = emptyList()
    }
    // Fix your library: what its three steps have found.
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val sortFixes by viewModel.sortFixes.collectAsStateWithLifecycle()
    val findings = if (DemoMode.enabled) DemoMode.findings else FixFindings(
        namesSure = tidyCandidates.count { it.sure },
        namesToCheck = tidyCandidates.count { !it.sure },
        duplicateCopies = duplicates.sumOf { it.extras.size },
        duplicateBytes = duplicates.sumOf { group -> group.extras.sumOf { it.sizeBytes } },
        artistsToSort = sortFixes.artists.size,
        albumsToClear = sortFixes.albums.size,
    )
    // Offered once, after the first scan that finds music with something to fix.
    var offerFix by remember { mutableStateOf(false) }
    LaunchedEffect(scanned, library.songs.isNotEmpty()) {
        if (scanned && library.songs.isNotEmpty() && !uiSettings.fixOffered && !DemoMode.enabled) {
            // Give the findings a moment to be worked out before deciding.
            delay(900)
            offerFix = true
        }
    }
    var sortPicked by remember { mutableStateOf<List<EditItem>>(emptyList()) }
    val sortConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startEdits(RUN_SORT, sortPicked)
        sortPicked = emptyList()
    }
    val startSort: (List<EditItem>) -> Unit = { picked ->
        sortPicked = picked
        val consent = runCatching { TagEditor.consentToWrite(context, picked.map { it.song }) }.getOrNull()
        if (consent != null) {
            sortConsentLauncher.launch(IntentSenderRequest.Builder(consent).build())
        } else {
            viewModel.startEdits(RUN_SORT, picked)
        }
    }
    val startTidy: (List<TidyCandidate>) -> Unit = { picked ->
        tidyPicked = picked
        val consent = runCatching { TagEditor.consentToWrite(context, picked.map { it.song }) }.getOrNull()
        if (consent != null) {
            tidyConsentLauncher.launch(IntentSenderRequest.Builder(consent).build())
        } else {
            viewModel.startTidy(picked)
        }
    }
    val lyricsFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file ->
        val songId = lyricsFileFor
        lyricsFileFor = null
        if (file != null && songId != null) {
            viewModel.addLyricsFrom(songId, file) { added ->
                onLyricsAdded(added, "That file has no lyrics Spine can read. It needs a text or .lrc file.")
            }
        }
    }
    val pasteLyrics: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        val songId = nowPlaying.songId
        if (text.isNullOrBlank() || songId == null) {
            onLyricsAdded(false, "Nothing to paste. Copy the lyrics first, then tap Paste lyrics.")
        } else {
            viewModel.addLyrics(songId, text) { added ->
                onLyricsAdded(added, "Nothing to paste. Copy the lyrics first, then tap Paste lyrics.")
            }
        }
    }
    val deleteSongs: (List<Song>) -> Unit = { songs ->
        val ids = songs.mapTo(HashSet()) { it.id }
        when (val result = SongDeleter.delete(context, songs)) {
            SongDeleter.Result.Deleted -> viewModel.onSongsDeleted(ids)
            is SongDeleter.Result.NeedsConsent -> {
                awaitingConsentFor = ids
                consentLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
            }
            SongDeleter.Result.Refused -> Unit
        }
    }

    // A new playlist from the Playlists tab asks its name and what to fill it from, then
    // shows the list to tick; one made while adding songs to a playlist only needs its name.
    var newPlaylist by remember { mutableStateOf(DemoMode.screen == "new-playlist") }
    val askForPlaylistName: (songs: List<Song>) -> Unit = { songs ->
        if (songs.isEmpty()) {
            newPlaylist = true
        } else {
            nameRequest = NameRequest(title = "New playlist", confirmLabel = "Create") { name ->
                viewModel.createPlaylist(name, songs)
            }
        }
    }

    /** The sleep timer sheet: stop after a while, at the end of the song, or not at all. */
    val showSleepMenu: () -> Unit = {
        val running = currentSleep
        menu = MenuState(
            bounds = null,
            onInk = backStack.last().screen.onInk,
            title = "Stop the music after",
            actions = SleepTimer.CHOICES.map { minutes ->
                val selected = (running as? SleepState.After)?.minutes == minutes
                MenuAction(
                    label = if (minutes == 60) "1 hour" else "$minutes minutes",
                    selected = selected,
                    detail = if (selected) currentSleepLabel?.let { "$it left" } else null,
                ) {
                    feel.done()
                    SleepTimer.stopAfter(minutes)
                }
            } + MenuAction(
                label = "The end of this song",
                selected = running == SleepState.EndOfSong,
            ) {
                feel.done()
                SleepTimer.stopAtEndOfSong()
            } + MenuAction("Turn the timer off") { SleepTimer.off() },
        )
    }

    /** The sheet that picks which Shelf tab the app opens on. */
    val showStartTabMenu: () -> Unit = {
        menu = MenuState(
            bounds = null,
            onInk = backStack.last().screen.onInk,
            title = "Open on",
            actions = tabOrder.map { choice ->
                MenuAction(label = choice.label, selected = choice == currentStartTab) {
                    uiSettings.setStartTab(choice)
                }
            },
        )
    }

    /** The sheet that asks which playlist to add [songs] to. */
    val addToPlaylistMenu: (songs: List<Song>, bounds: Rect) -> Unit = { songs, bounds ->
        val ids = songs.map { it.id }
        menu = MenuState(
            bounds = bounds,
            onInk = backStack.last().screen.onInk,
            title = "Add to playlist",
            actions = currentPlaylists.filterNot { it.isSmart }.map { playlist ->
                val has = playlist.songs.mapTo(HashSet()) { it.id }.containsAll(ids)
                MenuAction(
                    label = playlist.name,
                    detail = if (has) "Already in it" else songCount(playlist.songs.size),
                ) {
                    feel.done()
                    viewModel.addToPlaylist(playlist.id, songs)
                }
            } + MenuAction("New playlist") { askForPlaylistName(songs) },
        )
    }

    /** The long-press menu for a song; [inPlaylist] adds "Remove from playlist". */
    val showSongMenu: (song: Song, bounds: Rect, inPlaylist: Playlist?, extra: List<MenuAction>) -> Unit = { song, bounds, inPlaylist, extra ->
        menu = MenuState(
            bounds = bounds,
            onInk = backStack.last().screen.onInk,
            actions = buildList {
                add(MenuAction("Play next") { viewModel.playNext(listOf(song)) })
                add(MenuAction("Add to queue") { viewModel.addToQueue(listOf(song)) })
                add(MenuAction("Add to playlist") { addToPlaylistMenu(listOf(song), bounds) })
                if (inPlaylist != null) {
                    add(MenuAction("Remove from playlist") { viewModel.removeFromPlaylist(inPlaylist, song) })
                }
                // A video has no details to edit, and no album or artist page.
                if (!song.isVideo) {
                    add(
                        MenuAction("Edit details") {
                            tagProblem = null
                            open(Screen.EditSong(song.id), null)
                        },
                    )
                }
                currentLibrary.albumOf(song.id)?.let { album ->
                    add(MenuAction("Go to album") { open(Screen.AlbumDetail(album.id), null) })
                }
                currentLibrary.artistOf(song.id)?.let { artist ->
                    add(MenuAction("Go to artist") { open(Screen.ArtistDetail(artist.name), null) })
                }
                addAll(extra)
                add(
                    MenuAction("Delete from phone", destructive = true) {
                        deleteRequest = DeleteRequest(
                            question = if (song.isVideo) "Delete this video?" else "Delete this song?",
                            name = song.title,
                            detail = song.artist,
                            warning = "The file will be removed from your phone. $DELETE_WARNING",
                            onConfirm = {
                                if (song.isVideo) {
                                    // The app made this file, so Android lets it go without asking.
                                    val address = song.uri.toString()
                                    runCatching { context.contentResolver.delete(song.uri, null, null) }
                                    VideoProgress.forget(context, address)
                                    viewModel.onSongsDeleted(setOf(song.id))
                                    Downloads.forgetVideo(context, address)
                                } else {
                                    deleteSongs(listOf(song))
                                }
                            },
                        )
                    },
                )
            },
        )
    }

    val actions = remember(viewModel) {
        fun onInk() = backStack.last().screen.onInk

        LibraryActions(
            play = viewModel::play,
            openAlbum = { album, from -> open(Screen.AlbumDetail(album.id), from) },
            openArtist = { artist, from -> open(Screen.ArtistDetail(artist.name), from) },
            openPlaylist = { playlist, from -> open(Screen.PlaylistDetail(playlist.id), from) },
            songMenu = { song, bounds -> showSongMenu(song, bounds, null, emptyList()) },
            songMenuWith = { song, bounds, extra -> showSongMenu(song, bounds, null, extra) },
            albumMenu = { album, bounds ->
                menu = MenuState(
                    bounds = bounds,
                    onInk = onInk(),
                    actions = buildList {
                        add(MenuAction("Play") { viewModel.playFromStart(album.songs) })
                        add(MenuAction("Shuffle") { viewModel.playShuffled(album.songs) })
                        add(MenuAction("Play next") { viewModel.playNext(album.songs) })
                        add(MenuAction("Add to queue") { viewModel.addToQueue(album.songs) })
                        add(MenuAction("Add to playlist") { addToPlaylistMenu(album.songs, bounds) })
                        add(
                            MenuAction("Edit album") {
                                tagProblem = null
                                open(Screen.EditAlbum(album.id), null)
                            },
                        )
                        currentLibrary.artist(album.artist)?.let { artist ->
                            add(MenuAction("Go to artist") { open(Screen.ArtistDetail(artist.name), null) })
                        }
                        add(
                            MenuAction("Delete album from phone", destructive = true) {
                                deleteRequest = DeleteRequest(
                                    question = "Delete this album?",
                                    name = album.title,
                                    detail = "${album.artist} · ${songCount(album.songs.size)}",
                                    warning = "All ${album.songs.size} files will be removed from your phone. " +
                                        DELETE_WARNING,
                                    onConfirm = { deleteSongs(album.songs) },
                                )
                            },
                        )
                    },
                )
            },
            artistMenu = { artist, bounds ->
                menu = MenuState(
                    bounds = bounds,
                    onInk = onInk(),
                    actions = listOf(
                        MenuAction("Play all") { viewModel.playFromStart(artist.songs) },
                        MenuAction("Shuffle") { viewModel.playShuffled(artist.songs) },
                        MenuAction("Play next") { viewModel.playNext(artist.songs) },
                        MenuAction("Add to queue") { viewModel.addToQueue(artist.songs) },
                        MenuAction("Add to playlist") { addToPlaylistMenu(artist.songs, bounds) },
                    ),
                )
            },
            playlistMenu = { playlist, bounds ->
                menu = MenuState(
                    bounds = bounds,
                    onInk = onInk(),
                    actions = buildList {
                        add(MenuAction("Play") { viewModel.playPlaylistFromStart(playlist) })
                        add(MenuAction("Shuffle") { viewModel.shufflePlaylist(playlist) })
                        add(MenuAction("Play next") { viewModel.playNext(playlist.songs) })
                        add(MenuAction("Add to queue") { viewModel.addToQueue(playlist.songs) })
                        // Songs removed from a smart playlist are only left out, so offer them back.
                        if (playlist.hiddenCount > 0) {
                            add(
                                MenuAction("Bring back removed songs", detail = songCount(playlist.hiddenCount)) {
                                    viewModel.restoreRemoved(playlist)
                                },
                            )
                        }
                        // Favourites and the smart playlists are built in and stay.
                        if (!playlist.isSmart && !playlist.isFavourites) {
                            add(
                                MenuAction("Delete playlist", destructive = true) {
                                    deleteRequest = DeleteRequest(
                                        question = "Delete this playlist?",
                                        name = playlist.name,
                                        detail = songCount(playlist.songs.size),
                                        warning = "Only the playlist goes. Its songs stay on your phone.",
                                        onConfirm = { viewModel.deletePlaylist(playlist.id) },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    // A link shared to Spine+ from another app: back to the Shelf, on the Get tab, with
    // the link in its box.
    val getAsked by viewModel.getAsked.collectAsStateWithLifecycle()
    LaunchedEffect(getAsked) {
        val words = getAsked ?: return@LaunchedEffect
        viewModel.getDone()
        menu = null
        getQuery = words
        tab = ShelfTab.Get
        if (backStack.size > 1) {
            opening = false
            backStack.removeRange(1, backStack.size)
        }
    }

    // The strip at the bottom opens the screen for what it holds: a song's, or a video's.
    val openPlaying: (Rect?) -> Unit = { from ->
        open(if (nowPlaying.isVideo) Screen.VideoPlayer else Screen.NowPlaying, from)
    }
    val videoActions = remember {
        VideoActions(
            showOn = viewModel::showVideoOn,
            playPause = viewModel::togglePlayPause,
            seekTo = viewModel::seekTo,
            skip = viewModel::skip,
            previous = viewModel::previous,
            next = viewModel::next,
            setSpeed = viewModel::setSpeed,
            setSubtitles = viewModel::setSubtitles,
            jumpTo = viewModel::jumpToQueueSlot,
        )
    }
    // While the screen of what is playing is on top, it follows the queue from a song to a
    // video and back: each has its own screen. The first reading is skipped, as it may
    // still be of whatever was playing before this screen was opened.
    val currentNowPlaying by rememberUpdatedState(nowPlaying)
    LaunchedEffect(top.screen) {
        if (top.screen != Screen.NowPlaying && top.screen != Screen.VideoPlayer) return@LaunchedEffect
        snapshotFlow { currentNowPlaying.isVideo }.drop(1).collect { isVideo ->
            val right = if (isVideo) Screen.VideoPlayer else Screen.NowPlaying
            if (currentNowPlaying.loaded && backStack.last().screen != right) {
                opening = true
                backStack[backStack.lastIndex] = Entry(right, depth = backStack.lastIndex, origin = null)
            }
        }
    }

    // A song or a video opened from another app: played, with its screen brought up.
    val openAsked by viewModel.openAsked.collectAsStateWithLifecycle()
    LaunchedEffect(openAsked) {
        val file = openAsked ?: return@LaunchedEffect
        menu = null
        val asVideo = viewModel.openFile(file)
        open(if (asVideo) Screen.VideoPlayer else Screen.NowPlaying, null)
        // Only now: marking it done changes what this block is keyed on, which would stop
        // it before the file had been started.
        viewModel.openDone()
    }

    // A tap on the playback notification lands on Now playing: back down to it if it is
    // already open under Queue or Lyrics, on top of whatever is showing otherwise.
    val showPlayingAsked by viewModel.showPlayingAsked.collectAsStateWithLifecycle()
    LaunchedEffect(showPlayingAsked) {
        if (!showPlayingAsked) return@LaunchedEffect
        viewModel.showPlayingDone()
        menu = null
        // A video's own screen, if a video is what is playing.
        val playing = if (nowPlaying.isVideo) Screen.VideoPlayer else Screen.NowPlaying
        val at = backStack.indexOfLast { it.screen == playing }
        when {
            at < 0 -> open(playing, null)
            at < backStack.lastIndex -> {
                opening = false
                backStack.removeRange(at + 1, backStack.size)
            }
        }
    }

    // The first-open screen is dark and covers whatever is underneath.
    // Dark icons only where the bar sits over light paper.
    SystemBarIcons(darkIcons = !Palette.dark && !top.screen.onInk && !(offerFix && !findings.nothingToFix))

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = top,
            modifier = Modifier.fillMaxSize(),
            // The screens animate themselves below; a deeper screen always sits on top.
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = EnterTransition.None,
                    initialContentExit = ExitTransition.None,
                    targetContentZIndex = targetState.depth.toFloat(),
                    sizeTransform = null,
                )
            },
            label = "screen",
        ) { entry ->
            val shown by transition.animateFloat(
                transitionSpec = { tween(SCREEN_TRANSITION_MS, easing = FastOutSlowInEasing) },
                label = "reveal",
            ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
            // Only the upper of the two screens moves: the new one while opening, the old
            // one while closing. The screen underneath stays still.
            val leaving = transition.targetState == EnterExitState.PostExit
            val moves = if (leaving) !opening else opening

            Box(
                Modifier
                    .fillMaxSize()
                    .reveal(entry.origin, if (moves) shown else 1f),
            ) {
                when (val screen = entry.screen) {
                    Screen.Shelf -> ShelfScreen(
                        name = name,
                        onPlayVideos = { videos, index ->
                            // The one already playing is not started again: its screen is just brought up.
                            if (videos.getOrNull(index)?.id != nowPlaying.songId) viewModel.play(videos, index)
                            open(Screen.VideoPlayer, null)
                        },
                        library = library,
                        playlists = playlists,
                        playingPlaylist = playingPlaylist,
                        scanned = scanned,
                        nowPlaying = nowPlaying,
                        tab = tab,
                        onTabChange = { tab = it },
                        listStates = listStates,
                        folderPath = folderPath,
                        onFolderChange = { folderPath = it },
                        actions = actions,
                        onShuffleAll = { viewModel.playShuffled(library.songs) },
                        onNewPlaylist = { askForPlaylistName(emptyList()) },
                        tabOrder = tabOrder,
                        onTabOrderChange = uiSettings::setTabOrder,
                        getQuery = getQuery,
                        onGetQueryChange = { getQuery = it },
                        onMenu = { menu = it },
                        onAskDelete = { deleteRequest = it },
                        onOpenStats = { open(Screen.Stats, null) },
                        onOpenSettings = { open(Screen.Settings, null) },
                        onOpenSearch = { open(Screen.Search, null) },
                        onOpenNowPlaying = openPlaying,
                        onPlayPause = viewModel::togglePlayPause,
                        entering = entering,
                        onEntered = { entering = false },
                    )

                    is Screen.AlbumDetail -> {
                        val album = library.album(screen.albumId)
                        if (album == null) {
                            // The album left the library (its files were deleted) while open.
                            LaunchedEffect(screen) { back() }
                        } else {
                            AlbumScreen(
                                album = album,
                                nowPlaying = nowPlaying,
                                onBack = back,
                                actions = actions,
                                onPlayPause = viewModel::togglePlayPause,
                                onShuffle = { viewModel.playShuffled(album.songs) },
                                onOpenNowPlaying = openPlaying,
                                onEdit = {
                                    tagProblem = null
                                    open(Screen.EditAlbum(album.id), null)
                                },
                            )
                        }
                    }

                    is Screen.ArtistDetail -> {
                        val artist = library.artist(screen.name)
                        if (artist == null) {
                            LaunchedEffect(screen) { back() }
                        } else {
                            ArtistScreen(
                                artist = artist,
                                nowPlaying = nowPlaying,
                                onBack = back,
                                actions = actions,
                                onPlayPause = viewModel::togglePlayPause,
                                onShuffle = { viewModel.playShuffled(artist.songs) },
                                onOpenNowPlaying = openPlaying,
                                onEdit = {
                                    tagProblem = null
                                    open(Screen.EditArtist(artist.name), null)
                                },
                            )
                        }
                    }

                    is Screen.PlaylistDetail -> {
                        val playlist = playlists.firstOrNull { it.id == screen.playlistId }
                        if (playlist == null) {
                            // The playlist was deleted while open.
                            LaunchedEffect(screen) { back() }
                        } else {
                            PlaylistScreen(
                                playlist = playlist,
                                nowPlaying = nowPlaying,
                                leftMs = playingPlaylist?.takeIf { it.id == playlist.id }?.leftMs,
                                onBack = back,
                                onPlay = { index ->
                                    val tapped = playlist.songs.getOrNull(index)
                                    if (tapped?.isVideo == true) {
                                        // A video that was tapped is there to be watched: its screen comes
                                        // up, and the one already playing is not paused by the tap.
                                        if (tapped.id != nowPlaying.songId) viewModel.playPlaylist(playlist, index)
                                        open(Screen.VideoPlayer, null)
                                    } else {
                                        viewModel.playPlaylist(playlist, index)
                                    }
                                },
                                onPlayPause = viewModel::togglePlayPause,
                                onShuffle = { viewModel.shufflePlaylist(playlist) },
                                onSongMenu = { song, bounds -> showSongMenu(song, bounds, playlist, emptyList()) },
                                onOpenNowPlaying = openPlaying,
                                onEdit = if (playlist.isSmart) null else ({ open(Screen.EditPlaylist(playlist.id), null) }),
                            )
                        }
                    }

                    Screen.VideoPlayer -> {
                        val cues by viewModel.videoCues.collectAsStateWithLifecycle()
                        VideoPlayerScreen(
                            nowPlaying = nowPlaying,
                            // The queue starts with what is playing; the rest is what is up next.
                            upNext = queue.drop(1),
                            cues = cues,
                            backLabel = "Shelf",
                            actions = videoActions,
                            onBack = back,
                        )
                    }

                    Screen.NowPlaying -> NowPlayingScreen(
                        nowPlaying = nowPlaying,
                        showSeekHint = seekUses < SEEK_HINT_USES,
                        onBack = back,
                        // Queue is always there; the other two can be switched off in Settings.
                        links = buildList {
                            add(NowPlayingLink("Queue") { open(Screen.Queue, null) })
                            if (showLyrics) {
                                add(
                                    NowPlayingLink("Lyrics") {
                                        lyricsNotice = null
                                        open(Screen.Lyrics, null)
                                    },
                                )
                            }
                            if (showEqualizer) {
                                add(NowPlayingLink("Equalizer") { open(Screen.Equalizer(backLabel = "Playing"), null) })
                            }
                            if (showSleep) {
                                add(NowPlayingLink("Sleep", detail = sleepLabel, onClick = showSleepMenu))
                            }
                        },
                        onPlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                        onPrevious = viewModel::previous,
                        onSeek = viewModel::seekTo,
                        onSeekUsed = {
                            if (seekUses < SEEK_HINT_USES) {
                                seekUses += 1
                                uiPrefs.edit { putInt(KEY_SEEK_USES, seekUses) }
                            }
                        },
                        onToggleShuffle = viewModel::toggleShuffle,
                        onCycleRepeat = viewModel::cycleRepeat,
                        isFavourite = nowPlaying.songId in favouriteIds,
                        onToggleFavourite = { nowPlaying.songId?.let(viewModel::toggleFavourite) },
                    )

                    Screen.Queue -> QueueScreen(
                        queue = queue,
                        nowPlaying = nowPlaying,
                        onBack = back,
                        onJump = viewModel::jumpToQueueSlot,
                        onRemove = viewModel::removeFromQueue,
                        onPlayPause = viewModel::togglePlayPause,
                        onToggleShuffle = viewModel::toggleShuffle,
                        onClear = viewModel::clearQueue,
                    )

                    is Screen.EditSong -> {
                        val song = library.songsById[screen.songId]
                        if (song == null) {
                            LaunchedEffect(screen) { back() }
                        } else {
                            EditSongScreen(
                                song = song,
                                loadTags = viewModel::readTags,
                                saving = savingTags,
                                problem = tagProblem,
                                onBack = back,
                                onSave = { changes -> saveTags(listOf(song to changes)) },
                            )
                        }
                    }

                    is Screen.EditAlbum -> {
                        val album = library.album(screen.albumId)
                        if (album == null) {
                            // Renaming an album gives it a new identity; the old one is gone.
                            LaunchedEffect(screen) { back() }
                        } else {
                            EditAlbumScreen(
                                album = album,
                                loadTags = viewModel::readTags,
                                saving = savingTags,
                                problem = tagProblem,
                                onBack = back,
                                onEditSong = { song ->
                                    tagProblem = null
                                    open(Screen.EditSong(song.id), null)
                                },
                                onSave = { changes -> saveTags(album.songs.map { it to changes }) },
                            )
                        }
                    }

                    is Screen.EditArtist -> {
                        val artist = library.artist(screen.name)
                        if (artist == null) {
                            // Renamed: the artist is under the new name now.
                            LaunchedEffect(screen) { back() }
                        } else {
                            EditArtistScreen(
                                artist = artist,
                                saving = savingTags,
                                problem = tagProblem,
                                onBack = back,
                                onEditSong = { song ->
                                    tagProblem = null
                                    open(Screen.EditSong(song.id), null)
                                },
                                onSave = { name -> saveTags(artist.renameEdits(name)) },
                            )
                        }
                    }

                    is Screen.EditPlaylist -> {
                        val playlist = playlists.firstOrNull { it.id == screen.playlistId }
                        if (playlist == null) {
                            // Deleted from here: there is nothing left to edit.
                            LaunchedEffect(screen) { back() }
                        } else {
                            EditPlaylistScreen(
                                playlist = playlist,
                                onBack = back,
                                onSave = { name, removed ->
                                    viewModel.editPlaylist(playlist, name, removed)
                                    feel.done()
                                    back()
                                },
                                onDelete = {
                                    deleteRequest = DeleteRequest(
                                        question = "Delete this playlist?",
                                        name = playlist.name,
                                        detail = songCount(playlist.songs.size),
                                        warning = "Only the playlist goes. Its songs stay on your phone.",
                                        onConfirm = { viewModel.deletePlaylist(playlist.id) },
                                    )
                                },
                            )
                        }
                    }

                    Screen.Fix -> FixScreen(
                        findings = findings,
                        backLabel = if (backStack.getOrNull(entry.depth - 1)?.screen == Screen.Settings) "Settings" else "Shelf",
                        onBack = back,
                        onTidy = {
                            viewModel.clearTidy()
                            open(Screen.Tidy, null)
                        },
                        onDuplicates = { open(Screen.Duplicates, null) },
                        onSort = {
                            viewModel.clearTidy()
                            open(Screen.Sort, null)
                        },
                    )

                    Screen.Duplicates -> DuplicatesScreen(
                        groups = duplicates,
                        onBack = back,
                        onDelete = { copies, keptInstead ->
                            deleteRequest = DeleteRequest(
                                question = if (copies.size == 1) "Delete this copy?" else "Delete these copies?",
                                name = if (copies.size == 1) copies.first().title else "${copies.size} copies",
                                detail = "The best copy of each song stays",
                                warning = "The files are removed from your phone. This cannot be undone.",
                                onConfirm = {
                                    // Favourites, playlists and play counts move to the copy that stays.
                                    viewModel.keepInsteadOf(keptInstead)
                                    deleteSongs(copies)
                                },
                            )
                        },
                    )

                    Screen.Sort -> SortScreen(
                        fixes = sortFixes,
                        run = tidyRun?.takeIf { it.kind == RUN_SORT },
                        onBack = back,
                        onStart = startSort,
                        onStop = viewModel::stopTidy,
                        onDone = {
                            viewModel.clearTidy()
                            back()
                        },
                    )

                    Screen.Tidy -> TidyScreen(
                        candidates = tidyCandidates,
                        run = tidyRun?.takeIf { it.kind == RUN_TIDY },
                        onBack = back,
                        onStart = startTidy,
                        onStop = viewModel::stopTidy,
                        onDone = {
                            viewModel.clearTidy()
                            back()
                        },
                    )

                    Screen.Lyrics -> {
                        val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
                        LyricsScreen(
                            nowPlaying = nowPlaying,
                            state = lyrics,
                            notice = lyricsNotice,
                            onBack = back,
                            onSeek = viewModel::seekTo,
                            onPlayPause = viewModel::togglePlayPause,
                            onChooseFile = {
                                lyricsFileFor = nowPlaying.songId
                                lyricsFileLauncher.launch(arrayOf("*/*"))
                            },
                            onPaste = pasteLyrics,
                            onRemove = { nowPlaying.songId?.let(viewModel::removeLyrics) },
                        )
                    }

                    is Screen.PickForPlaylist -> PickScreen(
                        name = screen.name,
                        startFrom = screen.from,
                        library = library,
                        onBack = back,
                        onMake = { picked ->
                            viewModel.createPlaylist(screen.name, picked)
                            back()
                        },
                    )

                    Screen.Search -> SearchScreen(
                        library = library,
                        nowPlaying = nowPlaying,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onBack = back,
                        actions = actions,
                        // Not on the phone: the same words go to the Get tab, back on the Shelf.
                        onGet = { words ->
                            getQuery = words
                            tab = ShelfTab.Get
                            back()
                        },
                    )

                    Screen.Settings -> {
                        val eq by viewModel.audio.eq.collectAsStateWithLifecycle()
                        val fadeSeconds by viewModel.audio.fadeSeconds.collectAsStateWithLifecycle()
                        val normalize by viewModel.audio.normalize.collectAsStateWithLifecycle()
                        SettingsScreen(
                            page = settingsPage,
                            onPageChange = { settingsPage = it },
                            name = name,
                            onChangeName = {
                                nameRequest = NameRequest(
                                    title = "Your name",
                                    confirmLabel = "Save",
                                    initial = name,
                                    // Saving an empty box takes the name off again.
                                    allowEmpty = true,
                                ) { uiSettings.setName(it) }
                            },
                            theme = theme,
                            onThemeChange = uiSettings::setTheme,
                            eq = eq,
                            fadeSeconds = fadeSeconds,
                            normalize = normalize,
                            videoKeepsPlaying = videoKeepsPlaying,
                            onVideoKeepsPlayingChange = uiSettings::setVideoKeepsPlaying,
                            songsInLibrary = library.songs.size,
                            thingsToFix = findings.names + findings.duplicateCopies + findings.sorting,
                            onOpenFix = { open(Screen.Fix, null) },
                            startTab = startTab,
                            showEqualizer = showEqualizer,
                            showSleep = showSleep,
                            sleepLabel = sleepLabel,
                            onBack = back,
                            onOpenEqualizer = { open(Screen.Equalizer(backLabel = "Settings"), null) },
                            onOpenSleep = showSleepMenu,
                            onFadeChange = viewModel.audio::setFadeSeconds,
                            onNormalizeChange = viewModel.audio::setNormalize,
                            onChooseStartTab = showStartTabMenu,
                            playColour = playColour,
                            shelfColour = shelfColour,
                            onChoosePlayColour = { colourSheet = ColourSetting.Play },
                            onChooseShelfColour = { colourSheet = ColourSetting.Shelf },
                            onShowEqualizerChange = uiSettings::setShowEqualizer,
                            onShowSleepChange = uiSettings::setShowSleep,
                            showLyrics = showLyrics,
                            onShowLyricsChange = uiSettings::setShowLyrics,
                            animations = animations,
                            onAnimationsChange = uiSettings::setAnimations,
                            vibration = vibration,
                            onVibrationChange = uiSettings::setVibration,
                            onRescan = viewModel::scan,
                            permissionsSummary = permissionsSummary(context),
                            onOpenPermissions = { open(Screen.Permissions, null) },
                            version = Releases.first().version,
                            onOpenAbout = { open(Screen.About, null) },
                        )
                    }

                    Screen.Permissions -> PermissionsScreen(onBack = back)

                    Screen.About -> AboutScreen(
                        onBack = back,
                        onOpenLicences = { open(Screen.Licences, null) },
                        onShowUpdate = { showUpdate = true },
                    )

                    Screen.Licences -> LicencesScreen(onBack = back)

                    is Screen.Equalizer -> {
                        val eq by viewModel.audio.eq.collectAsStateWithLifecycle()
                        val bands by viewModel.audio.bands.collectAsStateWithLifecycle()
                        val unavailable by viewModel.audio.eqUnavailable.collectAsStateWithLifecycle()
                        EqualizerScreen(
                            eq = eq,
                            bands = bands,
                            unavailable = unavailable,
                            backLabel = screen.backLabel,
                            onBack = back,
                            onChange = viewModel.audio::setEq,
                        )
                    }

                    Screen.Stats -> {
                        val stats by viewModel.stats.collectAsStateWithLifecycle()
                        val period by viewModel.statsPeriod.collectAsStateWithLifecycle()
                        val statsKind by viewModel.statsKind.collectAsStateWithLifecycle()
                        val videoStats by viewModel.videoStats.collectAsStateWithLifecycle()
                        val downloadStats by viewModel.downloadStats.collectAsStateWithLifecycle()
                        StatsScreen(
                            name = name,
                            kind = statsKind,
                            onChooseKind = {
                                menu = MenuState(
                                    bounds = null,
                                    onInk = false,
                                    title = "Stats for",
                                    actions = StatsKind.entries.map { choice ->
                                        MenuAction(label = choice.label, selected = choice == statsKind) {
                                            viewModel.setStatsKind(choice)
                                        }
                                    },
                                )
                            },
                            videoStats = videoStats,
                            downloadStats = downloadStats,
                            onSaveImage = { image -> savePicture(context, image, owned(name, "stats")) },
                            onShareImage = { picture -> shareFile(context, picture, "image/png") },
                            stats = stats,
                            period = period,
                            onPeriodChange = viewModel::setStatsPeriod,
                            onBack = back,
                            onOpenArtist = actions.openArtist,
                            onPlaySong = viewModel::play,
                        )
                    }
                }
            }
        }

        // Once, after the first scan: what could be fixed, over whatever screen is showing.
        androidx.compose.animation.AnimatedVisibility(
            visible = offerFix && !findings.nothingToFix,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
        ) {
            FirstOpenScreen(
                songs = library.songs.size,
                findings = findings,
                onFix = {
                    uiSettings.fixOffered = true
                    offerFix = false
                    open(Screen.Fix, null)
                },
                onLater = {
                    uiSettings.fixOffered = true
                    offerFix = false
                },
            )
        }
        ItemMenu(menu = menu, onDismiss = { menu = null })
        ColourSheet(
            setting = colourSheet,
            playColour = playColour,
            shelfColour = shelfColour,
            // The song that is loaded, or stand-in words when nothing is.
            songTitle = nowPlaying.title.ifBlank { "A song you play" },
            songArtist = nowPlaying.artist.ifBlank { "Its artist" },
            onPickPlay = uiSettings::setPlayColour,
            onPickShelf = uiSettings::setShelfColour,
            onDismiss = { colourSheet = null },
        )
        DeletePopup(request = deleteRequest, onDismiss = { deleteRequest = null })
        NamePopup(request = nameRequest, onDismiss = { nameRequest = null })
        NewPlaylistPopup(
            visible = newPlaylist,
            onDismiss = { newPlaylist = false },
            onChoose = { name, from -> open(Screen.PickForPlaylist(name, from), null) },
        )
        UpdatePopup(
            visible = showUpdate,
            state = updateState,
            installed = remember { Updater.installedVersion(context) },
            onUpdate = { Updater.download(context) },
            onStop = { Updater.stop() },
            onInstall = { Updater.install(context) },
            onLater = { version ->
                Updater.later(context, version)
                showUpdate = false
            },
            onHide = { showUpdate = false },
        )
    }
}

/** A made-up update, for a debug build taking screenshots of the update popup. */
private val DemoUpdate = UpdateOffer(
    version = "1.2.0",
    notes = listOf(
        "Share a link from any app and choose the format before it downloads.",
        "Videos have a tab of their own.",
        "Downloads start about twice as fast.",
    ),
    apkUrl = "",
    bytes = 61L * 1024 * 1024,
)

/**
 * Shows a screen growing out of [origin] as [fraction] goes from 0 to 1: the spine widens
 * into the album, the strip at the bottom grows into Now playing. A screen with no origin
 * fades in while rising a little.
 */
private fun Modifier.reveal(origin: Rect?, fraction: Float): Modifier = when {
    fraction >= 1f -> this
    origin == null -> graphicsLayer {
        alpha = fraction
        translationY = (1f - fraction) * 40.dp.toPx()
    }
    else -> drawWithContent {
        clipRect(
            left = lerp(origin.left, 0f, fraction),
            top = lerp(origin.top, 0f, fraction),
            right = lerp(origin.right, size.width, fraction),
            bottom = lerp(origin.bottom, size.height, fraction),
        ) {
            this@drawWithContent.drawContent()
        }
    }
}
