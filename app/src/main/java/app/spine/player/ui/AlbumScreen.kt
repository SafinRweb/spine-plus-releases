package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import app.spine.player.ui.components.MiniPlayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Album
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.TopLinks
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.Spine
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.riseIn
import app.spine.player.ui.components.smoothLevel
import app.spine.player.ui.components.songCount
import app.spine.player.ui.components.stripState
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.InkRaised

private val StripGap = 3.dp

/**
 * An album pulled off the shelf: its spine on the left, its songs stacked beside it from
 * the bottom up, so the blue rises through the stack as the album plays.
 */
@Composable
fun AlbumScreen(
    album: Album,
    nowPlaying: NowPlaying,
    onBack: () -> Unit,
    actions: LibraryActions,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onOpenNowPlaying: (bounds: Rect?) -> Unit,
    onEdit: () -> Unit,
) {
    val currentIndex = album.songs.indexOfFirst { it.id == nowPlaying.songId }
    val isLoaded = currentIndex >= 0
    // How far the blue has risen through the stack, counted in songs: 2.5 is two songs
    // played and the third half way. It glides, so starting a song in the middle of the
    // album sends the blue flowing up through the ones below it.
    val level = smoothLevel(if (isLoaded) currentIndex + nowPlaying.progress else 0f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        TopLinks(back = "Shelf", onBack = onBack, color = Color.White, onEdit = onEdit)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The words take what the buttons leave, and wrap there, so the buttons keep their shape.
            Text(
                text = "${songCount(album.songs.size)} · ${formatLength(album.durationMs)}",
                modifier = Modifier.weight(1f),
                color = InkMuted,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The button says what a tap will do: pause while this album is playing.
                BlockButton(
                    text = if (isLoaded && nowPlaying.isPlaying) "Pause" else "Play",
                    onClick = { if (isLoaded) onPlayPause() else actions.play(album.songs, 0) },
                    onInk = true,
                    filled = true,
                )
                BlockButton(text = "Shuffle", onClick = onShuffle, onInk = true)
            }
        }
        Spacer(Modifier.height(16.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // Scale the stack towards the full height so its blue level tracks the spine's,
            // but keep strips a sensible size: a short album does not balloon to fill the
            // screen, and a long one scrolls.
            val minutes = (album.durationMs / 60_000f).coerceAtLeast(0.1f)
            val gaps = StripGap * (album.songs.size - 1).coerceAtLeast(0)
            val dpPerMinute = ((maxHeight - gaps).value / minutes).coerceIn(12f, 30f)
            val heights = remember(album, dpPerMinute) {
                album.songs.map { (it.durationMs / 60_000f * dpPerMinute).coerceAtLeast(48f).dp }
            }
            val spineHeight = maxHeight

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Spine(
                    title = album.title,
                    subtitle = album.artist,
                    width = 84.dp,
                    modifier = Modifier.height(spineHeight),
                    fill = album.progress(nowPlaying.songId, nowPlaying.positionMs),
                    background = InkRaised,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // Starts scrolled to the bottom, where track 1 is.
                        .verticalScroll(rememberScrollState(), reverseScrolling = true),
                    verticalArrangement = Arrangement.spacedBy(StripGap, Alignment.Bottom),
                ) {
                    // Track 1 sits at the bottom: the stack plays upwards.
                    for (index in album.songs.indices.reversed()) {
                        val song = album.songs[index]
                        SongStrip(
                            song = song,
                            onClick = { actions.play(album.songs, index) },
                            modifier = Modifier.riseIn(order = index),
                            onLongPress = { bounds -> actions.songMenu(song, bounds) },
                            secondLine = null,
                            leading = (song.trackNumber.takeIf { it > 0 } ?: (index + 1)).toString(),
                            state = stripState(song, nowPlaying),
                            background = InkRaised,
                            minHeight = heights[index],
                            level = { level.value - index },
                        )
                    }
                }
            }
        }

        // The loaded song, as on the Shelf: the way into Now playing from inside an album.
        if (nowPlaying.loaded) {
            Spacer(Modifier.height(14.dp))
            MiniPlayer(
                nowPlaying = nowPlaying,
                onOpen = onOpenNowPlaying,
                onPlayPause = onPlayPause,
                background = InkRaised,
            )
        }
    }
}
