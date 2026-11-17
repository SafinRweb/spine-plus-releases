package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Artist
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.ArtistDivider
import app.spine.player.ui.components.TopLinks
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.MiniPlayer
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.Spine
import app.spine.player.ui.components.StripState
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.songCount
import app.spine.player.ui.components.spineWidth
import app.spine.player.ui.components.stripState
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted

/**
 * One artist: their divider pulled out of the shelf on the left, and beside it their
 * albums standing up and all their songs lying down.
 */
@Composable
fun ArtistScreen(
    artist: Artist,
    nowPlaying: NowPlaying,
    onBack: () -> Unit,
    actions: LibraryActions,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onOpenNowPlaying: (bounds: Rect?) -> Unit,
    onEdit: () -> Unit,
) {
    val isLoaded = remember(artist, nowPlaying.songId) { artist.songs.any { it.id == nowPlaying.songId } }
    // Each song's album among this artist's, by the song: an album can hold several album ids.
    val albumBySong = remember(artist) {
        artist.albums.flatMap { album -> album.songs.map { it.id to album } }.toMap()
    }
    val playingAlbumId = albumBySong[nowPlaying.songId]?.id

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(top = 28.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArtistDivider(
                name = artist.name,
                count = 0,
                modifier = Modifier.fillMaxHeight(),
                width = 64.dp,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(end = 20.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopLinks(back = "Shelf", onBack = onBack, color = PaperText, onEdit = onEdit)
                        Text(
                            text = buildList {
                                if (artist.albums.isNotEmpty()) {
                                    add(if (artist.albums.size == 1) "1 album" else "${artist.albums.size} albums")
                                }
                                add(songCount(artist.songs.size))
                                add(formatLength(artist.durationMs))
                            }.joinToString(" · "),
                            color = PaperMuted,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BlockButton(
                                text = if (isLoaded && nowPlaying.isPlaying) "Pause" else "Play all",
                                onClick = { if (isLoaded) onPlayPause() else actions.play(artist.songs, 0) },
                                onInk = false,
                                filled = true,
                            )
                            BlockButton(text = "Shuffle", onClick = onShuffle, onInk = false)
                        }
                    }
                }

                if (artist.albums.isNotEmpty()) {
                    item(key = "albums") {
                        Column {
                            Label("ALBUMS")
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(artist.albums, key = { it.id }) { album ->
                                    Spine(
                                        title = album.title,
                                        width = spineWidth(album.durationMs),
                                        modifier = Modifier.fillParentMaxHeight(),
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
                }

                item(key = "songs") { Label("SONGS") }
                itemsIndexed(artist.songs, key = { _, song -> song.id }) { index, song ->
                    val state = stripState(song, nowPlaying)
                    SongStrip(
                        song = song,
                        onClick = { actions.play(artist.songs, index) },
                        modifier = Modifier
                            .padding(bottom = 3.dp)
                            .animateItem(),
                        onLongPress = { bounds -> actions.songMenu(song, bounds) },
                        // A single's "album" is just its own name again, so only real albums are shown.
                        secondLine = song.album.takeIf { song.id in albumBySong },
                        state = state,
                        fill = if (state != StripState.Idle) nowPlaying.progress else 0f,
                    )
                }
            }
        }

        if (nowPlaying.loaded) {
            MiniPlayer(
                nowPlaying = nowPlaying,
                onOpen = onOpenNowPlaying,
                onPlayPause = onPlayPause,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}
