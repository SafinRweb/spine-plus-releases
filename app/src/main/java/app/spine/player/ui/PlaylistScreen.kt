package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.itemCount
import app.spine.player.ui.components.TopLinks
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.MiniPlayer
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.Spine
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.smoothLevel
import app.spine.player.ui.components.songCount
import app.spine.player.ui.components.stripState
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.InkRaised

/**
 * The inside of a playlist, laid out like an album: its name runs up a spine on the left
 * and its songs are stacked beside it from the bottom up, so the blue rises through both
 * as the playlist plays.
 *
 * [leftMs] is how long the queue still has to run, set only while this playlist is the one
 * playing.
 */
@Composable
fun PlaylistScreen(
    playlist: Playlist,
    nowPlaying: NowPlaying,
    leftMs: Long?,
    onBack: () -> Unit,
    onPlay: (index: Int) -> Unit,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onSongMenu: (song: Song, bounds: Rect) -> Unit,
    onOpenNowPlaying: (bounds: Rect?) -> Unit,
    /** Opens the playlist for editing; null for a smart playlist, which is made by a rule. */
    onEdit: (() -> Unit)?,
) {
    val currentIndex = playlist.songs.indexOfFirst { it.id == nowPlaying.songId }
    val isLoaded = currentIndex >= 0
    // Counted in songs: 2.5 is two songs played and the third half way. It glides, so
    // starting a song in the middle sends the blue flowing up through the ones below it.
    val level = smoothLevel(if (isLoaded) currentIndex + nowPlaying.progress else 0f)
    // The spine fills by time, not by count, so a long song moves it further.
    val playedBeforeMs = remember(playlist, currentIndex) {
        playlist.songs.take(currentIndex.coerceAtLeast(0)).sumOf { it.durationMs }
    }
    val spineFill = if (isLoaded && playlist.durationMs > 0) {
        ((playedBeforeMs + nowPlaying.positionMs).toFloat() / playlist.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${itemCount(playlist.songs)} · ${formatLength(playlist.durationMs)}",
                    color = InkMuted,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
                if (leftMs != null && isLoaded) {
                    Text(
                        text = "${formatLength(leftMs)} left",
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else if (playlist.rule != null) {
                    Text(
                        text = "Smart: ${playlist.rule}",
                        color = InkMuted,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            if (playlist.songs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockButton(
                        text = if (isLoaded && nowPlaying.isPlaying) "Pause" else "Play",
                        onClick = { if (isLoaded) onPlayPause() else onPlay(0) },
                        onInk = true,
                        filled = true,
                    )
                    BlockButton(text = "Shuffle", onClick = onShuffle, onInk = true)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spine(
                title = playlist.name,
                width = 84.dp,
                modifier = Modifier.fillMaxHeight(),
                fill = spineFill,
                background = InkRaised,
            )
            if (playlist.songs.isEmpty()) {
                Text(
                    text = if (playlist.isSmart) {
                        "Nothing matches yet. This list fills itself in as you listen."
                    } else {
                        "No songs yet. Long-press any song and choose Add to playlist."
                    },
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.Bottom)
                        .padding(start = 8.dp),
                    color = InkMuted,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    // Song 1 sits at the bottom: the stack plays upwards.
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
                ) {
                    itemsIndexed(playlist.songs, key = { _, song -> song.id }) { index, song ->
                        SongStrip(
                            song = song,
                            onClick = { onPlay(index) },
                            modifier = Modifier.animateItem(),
                            onLongPress = { bounds -> onSongMenu(song, bounds) },
                            leading = (index + 1).toString(),
                            state = stripState(song, nowPlaying),
                            background = InkRaised,
                            level = { level.value - index },
                        )
                    }
                }
            }
        }

        // The loaded song, as on the Shelf: the way into Now playing from inside a playlist.
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
