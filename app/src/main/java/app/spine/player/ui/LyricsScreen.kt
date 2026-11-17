package app.spine.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Lyrics
import app.spine.player.data.LyricsOrigin
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.components.risingFill
import app.spine.player.ui.components.smoothProgress
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.OnPlayMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The loaded song's lyrics, over the same rising blue as Now playing. Timed lyrics follow
 * the song and a tap on a line jumps to it; lyrics without timing are plain text to
 * scroll; a song with none offers to add them.
 *
 * [notice] is a line to show under the add buttons, such as why a paste did nothing.
 */
@Composable
fun LyricsScreen(
    nowPlaying: NowPlaying,
    state: LyricsState,
    notice: String?,
    onBack: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    onPlayPause: () -> Unit,
    onChooseFile: () -> Unit,
    onPaste: () -> Unit,
    onRemove: () -> Unit,
) {
    val lyrics = (state as? LyricsState.Found)?.lyrics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .risingFill(smoothProgress(nowPlaying.progress))
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 24.dp),
    ) {
        BackLink(text = "Playing", onClick = onBack, color = Color.White)
        Text(
            text = nowPlaying.title,
            modifier = Modifier.padding(top = 8.dp),
            color = Color.White,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = when {
                lyrics == null -> nowPlaying.artist
                lyrics.synced -> "${nowPlaying.artist} · tap a line to jump to it"
                else -> "${nowPlaying.artist} · these lyrics have no timing, so scroll to read"
            },
            modifier = Modifier.padding(top = 2.dp),
            color = Quiet,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )

        // What is shown changes with the song, so fade between the three kinds of content.
        Crossfade(
            targetState = state,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            animationSpec = tween(220),
            label = "lyrics",
        ) { shown ->
            when (shown) {
                LyricsState.Looking -> Box(Modifier.fillMaxSize())
                LyricsState.None -> NoLyrics(notice, onChooseFile, onPaste)
                is LyricsState.Found ->
                    if (shown.lyrics.synced) {
                        TimedLyrics(shown.lyrics, nowPlaying.positionMs, onSeek, onRemove)
                    } else {
                        PlainLyrics(shown.lyrics, onRemove)
                    }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = formatDuration(nowPlaying.positionMs),
                    modifier = Modifier.alignByBaseline(),
                    color = Color.White,
                    fontFamily = BarlowCondensed,
                    fontSize = 40.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "of ${formatDuration(nowPlaying.durationMs)}",
                    modifier = Modifier.alignByBaseline(),
                    color = OnPlayMuted,
                    fontSize = 15.sp,
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onPlayPause)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (nowPlaying.isPlaying) SpineIcons.Pause else SpineIcons.Play,
                    contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                    tint = PaperText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Quieter text that stays readable both on the ink and once the blue has risen behind it. */
private val Quiet = Color.White.copy(alpha = 0.7f)

/** How long after the user scrolls the lyrics by hand before they follow the song again. */
private const val FOLLOW_AGAIN_MS = 3_500L

/**
 * Lyrics that follow the song. The line being sung is large and white and is kept a
 * little below the top; the ones sung are dim above it, the ones to come sit below.
 */
@Composable
private fun TimedLyrics(
    lyrics: Lyrics,
    positionMs: Long,
    onSeek: (positionMs: Long) -> Unit,
    onRemove: () -> Unit,
) {
    val current = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()

    // Scrolling by hand takes over; the lyrics pick the song up again a few seconds later.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(FOLLOW_AGAIN_MS)
                    following = true
                }
            }
        }
    }
    LaunchedEffect(current, following) {
        if (following) listState.animateScrollToItem(current.coerceAtLeast(0))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The padding lets the first line sit where the current line is kept, and the
            // last line scroll all the way up to it.
            contentPadding = PaddingValues(top = maxHeight * 0.22f, bottom = maxHeight * 0.7f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isCurrent = index == current
                val size by animateFloatAsState(
                    targetValue = if (isCurrent) 50f else 34f,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    label = "line size",
                )
                val color by animateColorAsState(
                    targetValue = when {
                        isCurrent -> Color.White
                        index < current -> Color.White.copy(alpha = 0.4f)
                        else -> Color.White.copy(alpha = 0.78f)
                    },
                    animationSpec = tween(260),
                    label = "line colour",
                )
                Text(
                    text = line.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Jump to this line") {
                            following = true
                            onSeek(line.timeMs ?: 0L)
                        },
                    color = color,
                    fontFamily = BarlowCondensed,
                    fontSize = size.sp,
                    lineHeight = (size * 1.08f).sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                )
            }
            if (lyrics.origin == LyricsOrigin.Added) {
                item { RemoveLyrics(onRemove) }
            }
        }
    }
}

/** Lyrics with no timing: plain text, a gap between verses, scrolled by hand. */
@Composable
private fun PlainLyrics(lyrics: Lyrics, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        for (line in lyrics.lines) {
            if (line.text.isEmpty()) {
                Spacer(Modifier.height(22.dp))
            } else {
                Text(
                    text = line.text,
                    color = Color.White,
                    fontSize = 19.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (lyrics.origin == LyricsOrigin.Added) RemoveLyrics(onRemove)
    }
}

/** Only for lyrics the user added: a way to take them out again. */
@Composable
private fun RemoveLyrics(onRemove: () -> Unit) {
    Row(modifier = Modifier.padding(top = 28.dp)) {
        BlockButton(text = "Remove these lyrics", onClick = onRemove, onInk = true)
    }
}

@Composable
private fun NoLyrics(notice: String?, onChooseFile: () -> Unit, onPaste: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 32.dp),
    ) {
        Text(
            text = "NO LYRICS FOR THIS SONG YET",
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = 50.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(
            modifier = Modifier.padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BlockButton(text = "Choose a lyrics file", onClick = onChooseFile, onInk = true, filled = true)
            BlockButton(text = "Paste lyrics", onClick = onPaste, onInk = true)
        }
        if (notice != null) {
            Text(
                text = notice,
                modifier = Modifier.padding(top = 16.dp),
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        }
    }
}
