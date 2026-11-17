package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.itemCount
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.StripState
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.songCount
import app.spine.player.ui.components.stripHeight
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.InkRaised

/**
 * What plays next, stacked from the bottom up: the loaded song sits at the bottom, near
 * the thumb, and later songs are above it. The left edge says how long until each starts.
 */
@Composable
fun QueueScreen(
    queue: List<QueueSong>,
    nowPlaying: NowPlaying,
    onBack: () -> Unit,
    onJump: (index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onPlayPause: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClear: () -> Unit,
) {
    val totalMs = remember(queue) { queue.sumOf { it.song.durationMs } }
    // Minutes until each song starts. Recomputed a few times a minute, not on every tick.
    val startsInMinutes = remember(queue, nowPlaying.positionMs / 15_000) {
        var elapsedMs = -nowPlaying.positionMs
        queue.map { entry ->
            val minutes = (elapsedMs.coerceAtLeast(0) + 30_000) / 60_000
            elapsedMs += entry.song.durationMs
            minutes
        }
    }
    // Keys that follow a song when others are removed, so the stack closes up smoothly.
    // The same song can be queued twice, hence the running count.
    val keys = remember(queue) {
        val seen = HashMap<Long, Int>()
        queue.map { entry ->
            val copy = (seen[entry.song.id] ?: 0) + 1
            seen[entry.song.id] = copy
            "${entry.song.id}#$copy"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        BackLink(text = "Playing", onClick = onBack, color = Color.White)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Queue",
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${itemCount(queue.map { it.song })} · ${formatLength(totalMs)}",
                    color = InkMuted,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(
                    text = "Shuffle",
                    onClick = onToggleShuffle,
                    onInk = true,
                    filled = nowPlaying.shuffle,
                )
                BlockButton(text = "Clear", onClick = onClear, onInk = true)
            }
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // The first entry (the loaded song) is laid out at the bottom.
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
        ) {
            itemsIndexed(queue, key = { position, _ -> keys[position] }) { position, entry ->
                val isCurrent = position == 0
                Row(
                    modifier = Modifier.animateItem(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (isCurrent) "now" else "${startsInMinutes.getOrElse(position) { 0 }} min",
                        modifier = Modifier
                            .width(46.dp)
                            .padding(bottom = 2.dp),
                        color = if (isCurrent) Color.White else InkMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                    SongStrip(
                        song = entry.song,
                        // Tapping the loaded song pauses or resumes it; any other jumps to it.
                        onClick = { if (isCurrent) onPlayPause() else onJump(entry.index) },
                        modifier = Modifier.weight(1f),
                        state = when {
                            !isCurrent -> StripState.Idle
                            nowPlaying.isPlaying -> StripState.Playing
                            else -> StripState.Paused
                        },
                        fill = if (isCurrent) nowPlaying.progress else 0f,
                        background = InkRaised,
                        minHeight = stripHeight(entry.song.durationMs, dpPerMinute = 20f),
                        trailing = if (isCurrent) {
                            null
                        } else {
                            {
                                IconTap(
                                    icon = SpineIcons.Close,
                                    label = "Remove ${entry.song.title} from the queue",
                                    onClick = { onRemove(entry.index) },
                                    tint = InkMuted,
                                    iconSize = 18.dp,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
