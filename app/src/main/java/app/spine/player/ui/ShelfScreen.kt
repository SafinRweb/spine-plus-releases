package app.spine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.LocalEntrance
import app.spine.player.ui.components.StackFrom
import app.spine.player.ui.components.stackIn
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Folder
import app.spine.player.data.Library
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.ArtistDivider
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.DeleteRequest
import app.spine.player.ui.components.MenuState
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.OwnedTitle
import app.spine.player.ui.components.MiniPlayer
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.Spine
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.StripState
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.pressable
import app.spine.player.ui.components.songCount
import app.spine.player.ui.components.spineWidth
import app.spine.player.ui.components.stripState
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted

enum class ShelfTab(val label: String) {
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
    Songs("Songs"),
    Folders("Folders"),

    /** Spine+ only: finding music that is not on the phone yet and downloading it. */
    Get("Downloader"),

    /** Spine+ only: the videos the Downloader has saved. */
    Videos("Videos");

    companion object {
        /**
         * The order the tabs stand in until the user moves them: the Downloader at the far
         * left, the videos it saved, then Songs. The app still opens on Albums unless
         * "Open on" says otherwise.
         */
        val DefaultOrder = listOf(Get, Videos, Songs, Albums, Artists, Playlists, Folders)
    }
}

/** Scroll positions of the tabs, kept by the caller so they survive leaving the shelf. */
class ShelfListStates(
    val albums: LazyListState,
    val singles: LazyListState,
    val artists: LazyListState,
    val playlists: LazyListState,
    val songs: LazyListState,
)

/** The home screen: the library as a shelf, with tabs for the other ways to browse it. */
@Composable
fun ShelfScreen(
    library: Library,
    playlists: List<Playlist>,
    playingPlaylist: PlayingPlaylist?,
    scanned: Boolean,
    nowPlaying: NowPlaying,
    tab: ShelfTab,
    onTabChange: (ShelfTab) -> Unit,
    listStates: ShelfListStates,
    folderPath: String,
    onFolderChange: (String) -> Unit,
    actions: LibraryActions,
    onShuffleAll: () -> Unit,
    onNewPlaylist: () -> Unit,
    /** What the user asked to be called, or empty: it heads the screen as "NAME'S SHELF". */
    name: String,
    /** The tabs in the user's order, and what to do when a tab has been dragged to a new place. */
    tabOrder: List<ShelfTab>,
    onTabOrderChange: (List<ShelfTab>) -> Unit,
    /** The words in the Get tab's box, kept by the caller so they survive leaving the tab. */
    getQuery: String,
    onGetQueryChange: (String) -> Unit,
    /** For the Videos tab: plays the list from the video that was tapped. */
    onPlayVideos: (videos: List<Song>, index: Int) -> Unit,
    /** For the Downloader tab: showing a long-press menu, and asking before a delete. */
    onMenu: (MenuState) -> Unit,
    onAskDelete: (DeleteRequest) -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNowPlaying: (bounds: Rect?) -> Unit,
    onPlayPause: () -> Unit,
    /** True while the app is opening: whichever tab shows first stacks itself into place. */
    entering: Boolean = false,
    onEntered: () -> Unit = {},
) {
    val hasContent = scanned && library.songs.isNotEmpty()
    if (entering && hasContent) {
        // Long enough for the last item on screen to have started arriving.
        LaunchedEffect(Unit) {
            delay(1_400)
            onEntered()
        }
    }
    // The spines bleed off the right edge, so side padding is applied per row, not here.
    CompositionLocalProvider(LocalEntrance provides entering) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(top = 28.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .stackIn(order = 0)
                .padding(start = 20.dp, end = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The title takes what the icons leave, so a long name shrinks instead of pushing them off.
            OwnedTitle(name = name, word = "Shelf", modifier = Modifier.weight(1f))
            Row {
                IconTap(SpineIcons.Stats, label = "Stats", onClick = onOpenStats, tint = PaperText)
                IconTap(SpineIcons.Search, label = "Search", onClick = onOpenSearch, tint = PaperText)
                IconTap(SpineIcons.Settings, label = "Settings", onClick = onOpenSettings, tint = PaperText)
            }
        }

        ShelfTabs(
            order = tabOrder,
            selected = tab,
            onSelect = onTabChange,
            onReorder = onTabOrderChange,
            modifier = Modifier
                .stackIn(order = 1)
                .padding(horizontal = 14.dp),
        )

        // The tabs lie side by side: a swipe to the left or right moves to the next one, as a
        // tap on its name does. Where a tab has something of its own that moves sideways (the
        // albums standing on the Shelf), that moves first, and the swipe only turns the page
        // once it has reached its end.
        val pages = rememberPagerState(initialPage = tabOrder.indexOf(tab).coerceAtLeast(0)) { tabOrder.size }
        val focus = LocalFocusManager.current
        // A tap on a tab's name, or the tabs being put in a new order: the page follows. It
        // slides for a tap and jumps for a new order, where what is on show has not changed.
        var lastOrder by remember { mutableStateOf(tabOrder) }
        LaunchedEffect(tab, tabOrder) {
            val page = tabOrder.indexOf(tab)
            if (page < 0) return@LaunchedEffect
            if (lastOrder != tabOrder) {
                lastOrder = tabOrder
                pages.scrollToPage(page)
            } else if (pages.currentPage != page || pages.isScrollInProgress) {
                pages.animateScrollToPage(page, animationSpec = tween(280, easing = FastOutSlowInEasing))
            }
        }
        // A swipe that has come to rest on another page: that tab is now the chosen one.
        val order by rememberUpdatedState(tabOrder)
        val chosen by rememberUpdatedState(tab)
        val choose by rememberUpdatedState(onTabChange)
        LaunchedEffect(pages) {
            snapshotFlow { pages.settledPage }.collect { page ->
                val settled = order.getOrNull(page) ?: return@collect
                if (settled != chosen) {
                    // Leaving the Downloader's box behind puts the keyboard away with it.
                    focus.clearFocus()
                    choose(settled)
                }
            }
        }
        HorizontalPager(
            state = pages,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            key = { order[it] },
        ) { page ->
            val current = tabOrder.getOrElse(page) { tab }
            Box(Modifier.fillMaxSize()) {
                when {
                    // Getting music does not need any to be on the phone already.
                    current == ShelfTab.Get -> GetMusic(
                        library = library,
                        nowPlaying = nowPlaying,
                        query = getQuery,
                        onQueryChange = onGetQueryChange,
                        actions = actions,
                        onMenu = onMenu,
                        onAskDelete = onAskDelete,
                    )
                    // Nor do the videos it has saved.
                    current == ShelfTab.Videos -> VideoList(
                        library = library,
                        nowPlaying = nowPlaying,
                        actions = actions,
                        onPlay = onPlayVideos,
                    )
                    !scanned -> Message("Looking for music")
                    library.songs.isEmpty() -> Message("No music found on this phone")
                    else -> when (current) {
                        ShelfTab.Albums -> AlbumShelf(library, nowPlaying, listStates, actions)
                        ShelfTab.Artists -> ArtistShelf(library, nowPlaying, listStates.artists, actions)
                        ShelfTab.Playlists -> PlaylistShelf(
                            playlists = playlists,
                            nowPlaying = nowPlaying,
                            playing = playingPlaylist,
                            state = listStates.playlists,
                            actions = actions,
                            onNewPlaylist = onNewPlaylist,
                        )
                        ShelfTab.Songs -> SongList(library, nowPlaying, listStates.songs, actions, onShuffleAll)
                        ShelfTab.Get, ShelfTab.Videos -> Unit
                        ShelfTab.Folders -> FolderList(
                            root = library.rootFolder,
                            path = folderPath,
                            nowPlaying = nowPlaying,
                            onFolderChange = onFolderChange,
                            actions = actions,
                        )
                    }
                }
            }
        }

        // The Downloader is for finding and fetching: the strip of what is playing stays off it.
        if (nowPlaying.loaded && tab != ShelfTab.Get) {
            MiniPlayer(
                nowPlaying = nowPlaying,
                onOpen = onOpenNowPlaying,
                onPlayPause = onPlayPause,
                modifier = Modifier
                    .stackIn(order = 9)
                    .padding(horizontal = 20.dp),
            )
        }
    }
    }
}

/**
 * The tabs, in the user's order. A tap opens a tab. Holding one lifts it as a solid block;
 * dragging it sideways carries it past its neighbours, which step aside, and letting go
 * leaves it there. The row scrolls sideways if large text makes it wider than the screen.
 */
@Composable
private fun ShelfTabs(
    order: List<ShelfTab>,
    selected: ShelfTab,
    onSelect: (ShelfTab) -> Unit,
    onReorder: (List<ShelfTab>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feel = rememberFeel()
    val reorder by rememberUpdatedState(onReorder)
    // The order as it stands while a tab is being carried; it is saved when the finger lifts.
    var working by remember { mutableStateOf(order) }
    var lifted by remember { mutableStateOf<ShelfTab?>(null) }
    LaunchedEffect(order) { if (lifted == null) working = order }
    // How far the lifted tab has been carried from where it now sits in the row.
    var pull by remember { mutableFloatStateOf(0f) }
    val widths = remember { mutableStateMapOf<ShelfTab, Int>() }
    val gap = with(LocalDensity.current) { TabGap.toPx() }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TabGap),
    ) {
        for (tab in working) {
            // Keyed, so a tab keeps its finger when the row is put in a new order under it.
            key(tab) {
                val isSelected = tab == selected
                val isLifted = tab == lifted
                Box(
                    modifier = Modifier
                        .zIndex(if (isLifted) 1f else 0f)
                        .graphicsLayer {
                            if (isLifted) {
                                translationX = pull
                                translationY = -6.dp.toPx()
                            }
                        }
                        .onSizeChanged { widths[tab] = it.width }
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.Tab, onClick = { onSelect(tab) })
                        .pointerInput(tab) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    lifted = tab
                                    pull = 0f
                                    feel.grab()
                                },
                                onDragEnd = {
                                    lifted = null
                                    pull = 0f
                                    feel.letGo()
                                    reorder(working)
                                },
                                onDragCancel = {
                                    lifted = null
                                    pull = 0f
                                    reorder(working)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    pull += amount.x
                                    // Past the middle of a neighbour, the two change places. The
                                    // pull is taken back by the same distance, so the lifted tab
                                    // stays under the finger while the neighbour steps aside.
                                    val at = working.indexOf(tab)
                                    val next = working.getOrNull(at + 1)
                                    val previous = working.getOrNull(at - 1)
                                    val nextStep = next?.let { (widths[it] ?: 0) + gap }
                                    val previousStep = previous?.let { (widths[it] ?: 0) + gap }
                                    if (next != null && nextStep != null && pull > nextStep / 2) {
                                        working = working.toMutableList().also {
                                            it[at] = next
                                            it[at + 1] = tab
                                        }
                                        pull -= nextStep
                                        feel.step()
                                    } else if (previous != null && previousStep != null && pull < -previousStep / 2) {
                                        working = working.toMutableList().also {
                                            it[at] = previous
                                            it[at - 1] = tab
                                        }
                                        pull += previousStep
                                        feel.step()
                                    }
                                },
                            )
                        }
                        .then(if (isLifted) Modifier.background(Ink) else Modifier)
                        .drawBehind {
                            if (isSelected && !isLifted) {
                                val thickness = 2.dp.toPx()
                                drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                            }
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        color = when {
                            isLifted -> Color.White
                            isSelected -> PaperText
                            else -> PaperMuted
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isSelected || isLifted) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private val TabGap = 4.dp

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp),
        color = PaperMuted,
        fontSize = 16.sp,
    )
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 20.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

/**
 * Albums stand up on top, wider for a longer album. Songs that are not part of an album
 * are singles: they lie down underneath as strips, like every other song.
 */
@Composable
private fun AlbumShelf(
    library: Library,
    nowPlaying: NowPlaying,
    listStates: ShelfListStates,
    actions: LibraryActions,
) {
    val playingAlbumId = remember(library, nowPlaying.songId) { library.albumOf(nowPlaying.songId)?.id }
    val hasAlbums = library.albums.isNotEmpty()
    val hasSingles = library.singles.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hasAlbums) {
            if (hasSingles) SectionLabel("ALBUMS")
            LazyRow(
                state = listStates.albums,
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(library.albums, key = { _, album -> album.id }) { index, album ->
                    Spine(
                        title = album.title,
                        subtitle = album.artist,
                        width = spineWidth(album.durationMs),
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .animateItem()
                            .stackIn(order = 2 + index, from = StackFrom.Above),
                        fill = if (album.id == playingAlbumId) {
                            album.progress(nowPlaying.songId, nowPlaying.positionMs)
                        } else {
                            0f
                        },
                        onClick = { bounds -> actions.openAlbum(album, bounds) },
                        onLongPress = { bounds -> actions.albumMenu(album, bounds) },
                    )
                }
            }
        }
        if (hasSingles) {
            SectionLabel("SINGLES", Modifier.padding(top = if (hasAlbums) 8.dp else 0.dp))
            LazyColumn(
                state = listStates.singles,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(library.singles, key = { _, song -> song.id }) { index, song ->
                    val state = stripState(song, nowPlaying)
                    SongStrip(
                        song = song,
                        onClick = { actions.play(library.singles, index) },
                        // The singles start once the first few albums are standing.
                        modifier = Modifier
                            .animateItem()
                            .stackIn(order = (if (hasAlbums) 5 else 2) + index),
                        onLongPress = { bounds -> actions.songMenu(song, bounds) },
                        state = state,
                        fill = if (state != StripState.Idle) nowPlaying.progress else 0f,
                    )
                }
            }
        }
    }
}

/** The shelf filed like a record shop: a divider per artist, then their albums. */
@Composable
private fun ArtistShelf(
    library: Library,
    nowPlaying: NowPlaying,
    state: LazyListState,
    actions: LibraryActions,
) {
    val playingAlbumId = remember(library, nowPlaying.songId) { library.albumOf(nowPlaying.songId)?.id }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Where each item stands along the row, so they arrive left to right.
        var place = 2
        library.artists.forEach { artist ->
            val dividerPlace = place
            place += 1 + artist.albums.size
            item(key = "artist:${artist.name}") {
                ArtistDivider(
                    name = artist.name,
                    count = artist.songs.size,
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .stackIn(order = dividerPlace, from = StackFrom.Above),
                    onClick = { bounds -> actions.openArtist(artist, bounds) },
                    onLongPress = { bounds -> actions.artistMenu(artist, bounds) },
                )
            }
            itemsIndexed(artist.albums, key = { _, album -> "album:${album.id}" }) { index, album ->
                Spine(
                    title = album.title,
                    width = spineWidth(album.durationMs),
                    // Shorter than the divider, so the artist tabs stand proud of the albums.
                    modifier = Modifier
                        .fillParentMaxHeight(0.86f)
                        .stackIn(order = dividerPlace + 1 + index, from = StackFrom.Above),
                    fill = if (album.id == playingAlbumId) {
                        album.progress(nowPlaying.songId, nowPlaying.positionMs)
                    } else {
                        0f
                    },
                    onClick = { bounds -> actions.openAlbum(album, bounds) },
                    onLongPress = { bounds -> actions.albumMenu(album, bounds) },
                )
            }
        }
    }
}

/** Songs lie down: every song is a strip, taller for a longer song. */
@Composable
private fun SongList(
    library: Library,
    nowPlaying: NowPlaying,
    state: LazyListState,
    actions: LibraryActions,
    onShuffleAll: () -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .stackIn(order = 2)
                    .padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockButton(text = "Shuffle all", onClick = onShuffleAll, onInk = false)
                Text(text = songCount(library.songs.size), color = PaperMuted, fontSize = 14.sp)
            }
        }
        itemsIndexed(library.songs, key = { _, song -> song.id }) { index, song ->
            val stripState = stripState(song, nowPlaying)
            SongStrip(
                song = song,
                onClick = { actions.play(library.songs, index) },
                modifier = Modifier
                    .animateItem()
                    .stackIn(order = 3 + index),
                onLongPress = { bounds -> actions.songMenu(song, bounds) },
                state = stripState,
                fill = if (stripState != StripState.Idle) nowPlaying.progress else 0f,
            )
        }
    }
}

/** The phone's folders that hold music: subfolders as rows, loose files as strips. */
@Composable
private fun FolderList(
    root: Folder,
    path: String,
    nowPlaying: NowPlaying,
    onFolderChange: (String) -> Unit,
    actions: LibraryActions,
) {
    val folder = remember(root, path) { root.find(path) ?: root }
    val parentPath = folder.path.substringBeforeLast('/', "")

    BackHandler(enabled = folder.path.isNotEmpty()) { onFolderChange(parentPath) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        item(key = "path") {
            if (folder.path.isEmpty()) {
                Text(
                    text = "Phone storage",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = PaperText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Column {
                    BackLink(
                        text = parentPath.substringAfterLast('/').ifEmpty { "Phone storage" },
                        onClick = { onFolderChange(parentPath) },
                        color = PaperText,
                    )
                    Text(
                        text = folder.path.replace("/", " / "),
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = PaperMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        itemsIndexed(folder.subfolders, key = { _, subfolder -> "folder:${subfolder.path}" }) { index, subfolder ->
            FolderRow(
                folder = subfolder,
                onClick = { onFolderChange(subfolder.path) },
                modifier = Modifier.stackIn(order = 2 + index),
            )
        }

        if (folder.songs.isNotEmpty()) {
            item(key = "files") {
                Text(
                    text = "FILES IN THIS FOLDER",
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    color = PaperMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )
            }
            itemsIndexed(folder.songs, key = { _, song -> "song:${song.id}" }) { index, song ->
                val stripState = stripState(song, nowPlaying)
                SongStrip(
                    song = song,
                    onClick = { actions.play(folder.songs, index) },
                    modifier = Modifier
                        .padding(bottom = 3.dp)
                        .animateItem()
                        .stackIn(order = 3 + folder.subfolders.size + index),
                    onLongPress = { bounds -> actions.songMenu(song, bounds) },
                    secondLine = song.fileName,
                    state = stripState,
                    fill = if (stripState != StripState.Idle) nowPlaying.progress else 0f,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(folder: Folder, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(onClick = onClick)
            .drawBehind {
                val thickness = 1.dp.toPx()
                drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = folder.name,
            modifier = Modifier.weight(1f),
            color = PaperText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = songCount(folder.songCount), color = PaperMuted, fontSize = 14.sp)
        Icon(
            SpineIcons.ChevronRight,
            contentDescription = null,
            tint = PaperMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}
