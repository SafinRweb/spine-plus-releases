package app.spine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.pressable
import app.spine.player.ui.components.smoothLevel
import app.spine.player.ui.components.stackIn
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Play

/** The playlist the queue was started from, and how long the queue still has to run. */
data class PlayingPlaylist(val id: Long, val leftMs: Long)

/**
 * The Playlists tab. Each playlist is its name in large type, how many songs it holds, and
 * under that its songs laid end to end: one block per song, longer for a longer song, and
 * a longer run for a longer playlist. The run turns blue from the left as the playlist plays.
 */
@Composable
fun PlaylistShelf(
    playlists: List<Playlist>,
    nowPlaying: NowPlaying,
    playing: PlayingPlaylist?,
    state: LazyListState,
    actions: LibraryActions,
    onNewPlaylist: () -> Unit,
) {
    val longestMs = remember(playlists) { playlists.maxOfOrNull { it.durationMs } ?: 0L }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
            PlaylistRow(
                playlist = playlist,
                longestMs = longestMs,
                nowPlaying = nowPlaying,
                leftMs = playing?.takeIf { it.id == playlist.id }?.leftMs,
                actions = actions,
                modifier = Modifier
                    .animateItem()
                    .stackIn(order = 2 + index),
            )
        }
        item(key = "new") {
            Row(
                modifier = Modifier
                    .stackIn(order = 2 + playlists.size)
                    .padding(top = 16.dp, bottom = 8.dp),
            ) {
                BlockButton(text = "New playlist", onClick = onNewPlaylist, onInk = false)
            }
        }
    }
}

/** [leftMs] is set only for the playlist that is playing. */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    longestMs: Long,
    nowPlaying: NowPlaying,
    leftMs: Long?,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val currentIndex = if (leftMs != null) playlist.songs.indexOfFirst { it.id == nowPlaying.songId } else -1
    val isPlaying = currentIndex >= 0
    // Counted in songs, like the stack inside: 2.5 is two songs played and the third half way.
    val level = smoothLevel(if (isPlaying) currentIndex + nowPlaying.progress else 0f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pressable(
                onClick = { bounds -> actions.openPlaylist(playlist, bounds) },
                onLongPress = { bounds -> actions.playlistMenu(playlist, bounds) },
            )
            .drawBehind {
                val thickness = 1.dp.toPx()
                drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
            }
            .padding(top = 12.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playlist.isFavourites) {
                Icon(
                    imageVector = SpineIcons.HeartFilled,
                    contentDescription = null,
                    tint = PaperText,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(20.dp),
                )
            }
            Text(
                text = playlist.name.uppercase(),
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline(),
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = 32.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = playlist.songs.size.toString(),
                modifier = Modifier.alignByBaseline(),
                color = PaperMuted,
                fontFamily = BarlowCondensed,
                fontSize = 32.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (playlist.songs.isNotEmpty()) {
            val share = if (longestMs > 0) playlist.durationMs.toFloat() / longestMs else 1f
            SongRun(
                songs = playlist.songs,
                level = { level.value },
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0.06f, 1f))
                    .height(10.dp),
            )
        }

        Text(
            text = remember(playlist, isPlaying, nowPlaying.isPlaying, leftMs?.let(::formatLength), Play) {
                buildAnnotatedString {
                    if (isPlaying && leftMs != null) {
                        withStyle(SpanStyle(color = Play, fontWeight = FontWeight.SemiBold)) {
                            append(if (nowPlaying.isPlaying) "Playing" else "Paused")
                            append(" · ${formatLength(leftMs)} left")
                        }
                        append(" · ")
                    }
                    append(describe(playlist, withLength = !isPlaying))
                }
            },
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The line under a playlist: its length and first songs, or the rule of a smart one. */
private fun describe(playlist: Playlist, withLength: Boolean): String {
    val songs = playlist.songs
    val length = if (withLength && songs.isNotEmpty()) formatLength(playlist.durationMs) else null
    if (playlist.rule != null) {
        return listOfNotNull("Smart", playlist.rule, length ?: "nothing yet".takeIf { songs.isEmpty() })
            .joinToString(" · ")
    }
    if (songs.isEmpty()) return "No songs yet"
    val names = songs.take(2).joinToString(", ") { it.title }
    val more = songs.size - 2
    val preview = if (more > 0) "$names and $more more" else names
    return listOfNotNull(length, preview).joinToString(" · ")
}

/**
 * Songs laid end to end. The blue covers the songs below [level], which counts in songs,
 * and part of the one it has reached.
 */
@Composable
private fun SongRun(songs: List<Song>, level: () -> Float, modifier: Modifier = Modifier) {
    val lengths = remember(songs) { songs.map { it.durationMs.coerceAtLeast(1L).toFloat() } }
    val total = remember(lengths) { lengths.sum() }
    Box(
        modifier.drawBehind {
            val count = lengths.size
            var gap = 2.dp.toPx()
            // Too many songs to show apart at this width: let them run together.
            if ((size.width - gap * (count - 1)) / count < 2.dp.toPx()) gap = 0f
            val usable = size.width - gap * (count - 1)
            val risen = level()
            var x = 0f
            lengths.forEachIndexed { index, length ->
                val width = usable * length / total
                drawRect(Ink, Offset(x, 0f), Size(width, size.height))
                val played = (risen - index).coerceIn(0f, 1f)
                if (played > 0f) drawRect(Play, Offset(x, 0f), Size(width * played, size.height))
                x += width + gap
            }
        },
    )
}
