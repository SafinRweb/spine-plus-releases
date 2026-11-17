package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Album
import app.spine.player.data.Library
import app.spine.player.data.Song
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.Spine
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.StripState
import app.spine.player.ui.components.spineWidth
import app.spine.player.ui.components.rememberTextBox
import app.spine.player.ui.components.stripState
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.Play

private class SearchResults(val albums: List<Album>, val songs: List<Song>)

/** A long list of matches is noise; the query should be narrowed instead. */
private const val MAX_SONG_RESULTS = 100

private fun search(library: Library, query: String): SearchResults {
    val text = query.trim()
    if (text.isEmpty()) return SearchResults(emptyList(), emptyList())
    fun String.matches() = contains(text, ignoreCase = true)
    return SearchResults(
        albums = library.albums.filter { it.title.matches() || it.artist.matches() },
        songs = library.songs
            .filter { it.title.matches() || it.artist.matches() || it.album.matches() }
            .take(MAX_SONG_RESULTS),
    )
}

/** Type to find: matching albums stand up as spines, matching songs lie down as strips. */
@Composable
fun SearchScreen(
    library: Library,
    nowPlaying: NowPlaying,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    actions: LibraryActions,
    /** Opens Get music with these words in its box. */
    onGet: (String) -> Unit,
) {
    val results = remember(library, query) { search(library, query) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink(
            text = "Shelf",
            onClick = onBack,
            color = PaperText,
            modifier = Modifier.padding(start = 14.dp),
        )

        // The words are kept by the caller; the box adds where the cursor is, which starts
        // after the last letter when the screen is opened again with words still in it.
        var box by rememberTextBox(query)
        BasicTextField(
            value = box,
            onValueChange = {
                box = it
                if (it.text != query) onQueryChange(it.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .focusRequester(focusRequester)
                .drawBehind {
                    val thickness = 2.dp.toPx()
                    drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                },
            singleLine = true,
            textStyle = TextStyle(
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            ),
            cursorBrush = SolidColor(Play),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search",
                                color = PaperMuted,
                                fontFamily = BarlowCondensed,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        field()
                    }
                    if (query.isNotEmpty()) {
                        IconTap(
                            icon = SpineIcons.Close,
                            label = "Clear search",
                            onClick = { onQueryChange("") },
                            tint = PaperText,
                            iconSize = 22.dp,
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            if (query.isNotBlank() && results.albums.isEmpty() && results.songs.isEmpty()) {
                item(key = "none") {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(text = "Nothing on your phone matches.", color = PaperMuted, fontSize = 16.sp)
                        // Not here yet: one tap takes the same words to Get music.
                        BlockButton(
                            text = "Download “${query.trim()}”",
                            onClick = { onGet(query.trim()) },
                            onInk = false,
                            filled = true,
                        )
                    }
                }
            }

            if (results.albums.isNotEmpty()) {
                item(key = "albums") {
                    SectionLabel("ALBUMS")
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(results.albums, key = { it.id }) { album ->
                            Spine(
                                title = album.title,
                                subtitle = album.artist,
                                width = spineWidth(album.durationMs),
                                modifier = Modifier.fillParentMaxHeight(),
                                onClick = { bounds -> actions.openAlbum(album, bounds) },
                                onLongPress = { bounds -> actions.albumMenu(album, bounds) },
                            )
                        }
                    }
                }
            }

            if (results.songs.isNotEmpty()) {
                item(key = "songs") { SectionLabel("SONGS") }
                itemsIndexed(results.songs, key = { _, song -> song.id }) { index, song ->
                    val state = stripState(song, nowPlaying)
                    SongStrip(
                        song = song,
                        onClick = { actions.play(results.songs, index) },
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, bottom = 3.dp)
                            .animateItem(),
                        onLongPress = { bounds -> actions.songMenu(song, bounds) },
                        state = state,
                        fill = if (state != StripState.Idle) nowPlaying.progress else 0f,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}
