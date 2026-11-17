package app.spine.player.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.risingFill
import app.spine.player.ui.components.smoothProgress
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted

/**
 * Tidy many song names in one go. The songs come in two groups. "Check these" are the ones
 * the app is not sure about: they are shown with the name they have now and are left
 * alone. "Look right" are the ones that will be changed, shown with their fixed name.
 * Ticking a song moves it down with its fixed name; tapping a fixed one sends it back up.
 * The two groups share the screen half and half, each scrolling by itself.
 *
 * While [run] is set the screen shows the saving instead: each song turns blue as its file
 * is done.
 */
@Composable
fun TidyScreen(
    candidates: List<TidyCandidate>,
    run: TidyRun?,
    onBack: () -> Unit,
    onStart: (List<TidyCandidate>) -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    // Leaving half way would hide a save that is still going; Stop ends it properly.
    BackHandler(enabled = run != null) { if (run?.running == false) onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        if (run == null) {
            BackLink(text = "Fix your library", onClick = onBack, color = PaperText)
        } else {
            // Keeps the heading where it was once the back link has gone.
            Box(Modifier.height(44.dp))
        }
        Text(
            text = "TIDY NAMES",
            modifier = Modifier.padding(top = 8.dp),
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 46.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        )
        if (run != null) {
            Saving(run, onStop, onDone)
        } else {
            Choosing(candidates, onBack, onStart)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Choosing(
    candidates: List<TidyCandidate>,
    onBack: () -> Unit,
    onStart: (List<TidyCandidate>) -> Unit,
) {
    val feel = rememberFeel()
    // Until the user ticks or unticks something, the songs the app is sure of are ticked.
    var chosen by remember { mutableStateOf<Set<Long>?>(null) }
    // The songs the user ticked, latest first, so each one lands at the top of "Look right".
    var picked by remember { mutableStateOf<List<Long>>(emptyList()) }
    val ticked = chosen ?: remember(candidates) { candidates.filter { it.sure }.mapTo(HashSet()) { it.song.id } }

    val toCheck = candidates.filter { it.song.id !in ticked }
    val byId = remember(candidates) { candidates.associateBy { it.song.id } }
    val lookRight = picked.mapNotNull { id -> byId[id]?.takeIf { id in ticked } } +
        candidates.filter { it.song.id in ticked && it.song.id !in picked }

    fun setTicked(song: Long, on: Boolean) {
        feel.switch(on)
        chosen = if (on) ticked + song else ticked - song
        picked = if (on) listOf(song) + (picked - song) else picked - song
    }

    Text(
        text = if (candidates.isEmpty()) {
            "Every song's name already looks tidy."
        } else {
            "Songs under Check these keep the name they have. Tick one and it moves down with " +
                "its fixed name. Everything under Look right will be changed."
        },
        modifier = Modifier.padding(top = 6.dp),
        color = PaperMuted,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )

    // The screen is split in two, each half scrolling by itself, so a song that is ticked
    // can be seen arriving below with its fixed name.
    val rightState = rememberLazyListState()
    LaunchedEffect(picked.firstOrNull()) {
        if (picked.isNotEmpty()) rightState.animateScrollToItem(0)
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        SectionHead("CHECK THESE · ${toCheck.size}", "Tick all".takeIf { toCheck.isNotEmpty() }) {
            feel.done()
            chosen = candidates.mapTo(HashSet()) { it.song.id }
        }
        Half(empty = "Nothing left to check.".takeIf { toCheck.isEmpty() && candidates.isNotEmpty() }) {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(toCheck, key = { it.song.id }) { candidate ->
                    TidyRow(
                        title = candidate.song.title,
                        artist = candidate.song.artist,
                        ticked = false,
                        modifier = Modifier.animateItem(),
                        onClick = { setTicked(candidate.song.id, true) },
                    )
                }
            }
        }
        SectionHead(
            "LOOK RIGHT · ${lookRight.size}",
            "Untick all".takeIf { lookRight.isNotEmpty() },
            Modifier.padding(top = 8.dp),
        ) {
            feel.step()
            chosen = emptySet()
            picked = emptyList()
        }
        Half(empty = "Nothing is ticked, so nothing will change.".takeIf { lookRight.isEmpty() && candidates.isNotEmpty() }) {
            LazyColumn(
                state = rightState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(lookRight, key = { it.song.id }) { candidate ->
                    TidyRow(
                        title = candidate.title,
                        artist = candidate.artist,
                        ticked = true,
                        modifier = Modifier.animateItem(),
                        onClick = { setTicked(candidate.song.id, false) },
                    )
                }
            }
        }
    }

    Text(
        text = "This changes the song files themselves. Android will ask you once to allow it for all of them.",
        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlockButton(text = "Cancel", onClick = onBack, onInk = false, modifier = Modifier.weight(1f))
        BlockButton(
            text = if (lookRight.isEmpty()) "Nothing ticked" else "Tidy ${songCount(lookRight.size)}",
            onClick = { if (lookRight.isNotEmpty()) onStart(lookRight) },
            onInk = false,
            modifier = Modifier.weight(1.4f),
            filled = true,
        )
    }
}

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.Saving(
    run: TidyRun,
    onStop: () -> Unit,
    onDone: () -> Unit,
    /** True when the screen around this has already said what is going on. */
    explained: Boolean = false,
) {
    val feel = rememberFeel()
    val total = run.songs.size
    val current = if (run.running) run.songs.indexOfFirst { it.song.id !in run.saved && it.song.id !in run.failed } else -1

    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        // Keep the song being saved in view, a row or two from the top.
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LaunchedEffect(run.running) {
        if (!run.running) feel.done()
    }

    if (!explained) Text(
        text = if (run.running) {
            "Each song turns blue once its file is saved. Keep the app open until it finishes."
        } else if (run.handled < total) {
            "Stopped. The songs in blue have been changed; the rest are as they were."
        } else {
            "Finished. The songs in blue have been changed."
        },
        modifier = Modifier.padding(top = 6.dp),
        color = PaperMuted,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(run.songs, key = { _, candidate -> candidate.song.id }) { index, candidate ->
            val id = candidate.song.id
            val failed = id in run.failed
            val fill = when {
                id in run.saved -> 1f
                index == current -> 0.5f
                else -> 0f
            }
            val onBlue = fill > 0f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 58.dp)
                    .background(Ink)
                    .risingFill(smoothProgress(fill))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = if (failed) candidate.song.title else candidate.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (failed) "Could not be changed" else candidate.detail,
                    color = if (onBlue) OnPlayMuted else InkMuted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(64.dp)
            .background(Ink)
            .risingFill(smoothProgress(if (total > 0) run.handled.toFloat() / total else 0f))
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${run.saved.size} of $total saved",
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    run.running -> timeLeft(run)
                    run.failed.isNotEmpty() -> "${songCount(run.failed.size)} could not be changed"
                    else -> "All done"
                },
                color = OnPlayMuted,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        }
        if (run.running) {
            BlockButton(text = "Stop", onClick = onStop, onInk = true)
        } else {
            BlockButton(text = "Done", onClick = onDone, onInk = true, filled = true)
        }
    }
}

/** A rough "how long still", from how long the songs so far have taken. */
private fun timeLeft(run: TidyRun): String {
    if (run.handled == 0) return "Starting"
    val perSongMs = (SystemClock.elapsedRealtime() - run.startedMs) / run.handled
    val leftSec = perSongMs * (run.songs.size - run.handled) / 1000
    return when {
        leftSec < 50 -> "Less than a minute left"
        leftSec < 100 -> "About a minute left"
        else -> "About ${(leftSec + 30) / 60} minutes left"
    }
}

/** One half of the choosing screen: its list, or a line saying why it is empty. */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.Half(empty: String?, list: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (empty != null) {
            Text(text = empty, color = PaperMuted, fontSize = 14.sp, lineHeight = 19.sp)
        } else {
            list()
        }
    }
}

/** A group's name and count, with an action at the right while the group has songs. */
@Composable
internal fun SectionHead(text: String, action: String?, modifier: Modifier = Modifier, onAction: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        if (action != null) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(start = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = action,
                    color = PaperText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

/** One song: solid with a tick when it will be changed, outlined with an empty box when not. */
@Composable
internal fun TidyRow(
    title: String,
    artist: String,
    ticked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clickable(
                role = Role.Checkbox,
                onClickLabel = if (ticked) "Leave this song as it is" else "Tidy this song",
                onClick = onClick,
            )
            .then(if (ticked) Modifier.background(Ink) else Modifier.border(2.dp, InkOnPaper))
            .padding(start = 14.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                color = if (ticked) Color.White else PaperText,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = artist,
                color = if (ticked) InkMuted else PaperMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
        if (ticked) {
            Icon(SpineIcons.Check, contentDescription = "Will be changed", tint = Color.White, modifier = Modifier.size(22.dp))
        } else {
            Box(
                Modifier
                    .size(20.dp)
                    .border(2.dp, InkOnPaper),
            )
        }
    }
}
