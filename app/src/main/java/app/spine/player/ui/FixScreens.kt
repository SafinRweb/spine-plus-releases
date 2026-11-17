package app.spine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.DuplicateGroup
import app.spine.player.data.Song
import app.spine.player.data.SortChange
import app.spine.player.data.SortFixes
import app.spine.player.data.kbps
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.riseIn
import app.spine.player.ui.components.risingFill
import app.spine.player.ui.components.smoothProgress
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.DangerOnPaper
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.Play

/** What the three steps of "Fix your library" have found, as numbers. */
data class FixFindings(
    /** Names the app is sure how to tidy. */
    val namesSure: Int,
    /** Names it has a guess for but wants looked at. */
    val namesToCheck: Int,
    /** Extra copies of songs, beyond one of each. */
    val duplicateCopies: Int,
    val duplicateBytes: Long,
    val artistsToSort: Int,
    val albumsToClear: Int,
) {
    val names: Int get() = namesSure + namesToCheck
    val sorting: Int get() = artistsToSort + albumsToClear
    val nothingToFix: Boolean get() = names == 0 && duplicateCopies == 0 && sorting == 0
}

/** The page every fix screen shares: back link, big heading, a line of explanation. */
@Composable
private fun FixPage(
    back: String?,
    onBack: () -> Unit,
    heading: String,
    explanation: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        if (back != null) BackLink(text = back, onClick = onBack, color = PaperText) else Box(Modifier.height(44.dp))
        Text(
            text = heading,
            modifier = Modifier.padding(top = 8.dp),
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 46.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = explanation,
            modifier = Modifier.padding(top = 6.dp),
            color = PaperMuted,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        content()
    }
}

/**
 * "Fix your library": the three steps, each a numbered block saying what it found. A step
 * with nothing left to do is filled with the play colour and ticked.
 */
@Composable
fun FixScreen(
    findings: FixFindings,
    /** The screen this was opened from: Settings, or the Shelf when it came from the first-open offer. */
    backLabel: String,
    onBack: () -> Unit,
    onTidy: () -> Unit,
    onDuplicates: () -> Unit,
    onSort: () -> Unit,
) {
    FixPage(
        back = backLabel,
        onBack = onBack,
        heading = "FIX YOUR LIBRARY",
        explanation = "Three steps. Do them in order or pick one. Each shows you what it will change " +
            "before anything is changed.",
    ) {
        // The three steps share the room between them. A step whose words need more than its
        // share grows (large text, a narrow phone), and the three then scroll, so nothing is cut.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
        val share = (maxHeight - 8.dp) / 3
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FixStep(
                number = 1,
                name = "TIDY NAMES",
                // Names still waiting to be checked are work left, so the step is not done.
                done = findings.names == 0,
                found = when {
                    findings.namesSure > 0 -> "${songCount(findings.namesSure)} carry extras in their names"
                    findings.namesToCheck > 0 -> "The sure ones are tidy"
                    else -> "Every name is tidy"
                },
                detail = when {
                    findings.namesToCheck > 0 -> "${findings.namesToCheck} more for you to check"
                    findings.namesSure > 0 -> "Like (Official Video) and [4K Upgrade]"
                    else -> "Nothing to do"
                },
                onClick = onTidy,
                modifier = Modifier.heightIn(min = share).riseIn(0),
            )
            FixStep(
                number = 2,
                name = "REMOVE DUPLICATES",
                done = findings.duplicateCopies == 0,
                found = if (findings.duplicateCopies > 0) {
                    if (findings.duplicateCopies == 1) {
                        "1 extra copy of a song you have"
                    } else {
                        "${findings.duplicateCopies} extra copies of songs you have"
                    }
                } else {
                    "No song is here twice"
                },
                detail = if (findings.duplicateCopies > 0) {
                    "Keeps the best copy of each, frees ${megabytes(findings.duplicateBytes)}"
                } else {
                    "Nothing to do"
                },
                onClick = onDuplicates,
                modifier = Modifier.heightIn(min = share).riseIn(1),
            )
            FixStep(
                number = 3,
                name = "SORT ARTISTS AND ALBUMS",
                done = findings.sorting == 0,
                found = when {
                    findings.artistsToSort > 0 && findings.albumsToClear > 0 ->
                        "${findings.artistsToSort} artists, ${findings.albumsToClear} albums to put right"
                    findings.artistsToSort > 0 -> "${findings.artistsToSort} artists to put right"
                    findings.albumsToClear > 0 -> "${findings.albumsToClear} singles posing as albums"
                    else -> "Artists and albums are in order"
                },
                detail = if (findings.sorting > 0) {
                    "One name per artist, and singles stop posing as albums"
                } else {
                    "Nothing to do"
                },
                onClick = onSort,
                modifier = Modifier.heightIn(min = share).riseIn(2),
            )
        }
        }
        Text(
            text = if (findings.nothingToFix) {
                "Everything is in order."
            } else {
                "Tidying and sorting change your song files. Removing duplicates deletes files. " +
                    "Android asks you each time."
            },
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
        // The way on. It is the main button once every step is done, and a quiet one until then.
        BlockButton(
            text = "Continue",
            onClick = onBack,
            onInk = false,
            modifier = Modifier.fillMaxWidth(),
            filled = findings.nothingToFix,
        )
    }
}

@Composable
private fun FixStep(
    number: Int,
    name: String,
    done: Boolean,
    found: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .background(Ink)
            .risingFill(smoothProgress(if (done) 1f else 0f))
            .padding(start = 14.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.width(44.dp),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = 84.sp,
            lineHeight = 84.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = name,
                color = Color.White,
                fontFamily = BarlowCondensed,
                fontSize = 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = found, color = Color.White, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(text = detail, color = if (done) OnPlayMuted else InkMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Icon(
            imageVector = if (done) SpineIcons.Check else SpineIcons.ChevronRight,
            contentDescription = if (done) "Done" else null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Step 2: songs that are on the phone more than once. The best copy of each stays; the
 * others are marked for deletion, and tapping one keeps it after all.
 *
 * [onDelete] is handed the copies to delete, and for each the copy that stays in its place.
 */
@Composable
fun DuplicatesScreen(
    groups: List<DuplicateGroup>,
    onBack: () -> Unit,
    onDelete: (copies: List<Song>, keptInstead: Map<Long, Long>) -> Unit,
) {
    val feel = rememberFeel()
    // Extra copies the user decided to keep after all.
    var spared by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val doomed = groups.flatMap { group -> group.extras.filter { it.id !in spared } }

    FixPage(
        back = "Fix your library",
        onBack = onBack,
        heading = "REMOVE DUPLICATES",
        explanation = if (groups.isEmpty()) {
            "No song is on the phone more than once."
        } else {
            "These songs are on the phone more than once. The best copy stays. Tap a copy to keep it as well."
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (group in groups) {
                item(key = "head:${group.best.id}") {
                    Text(
                        text = "${group.title} · ${group.artist} · ${group.copies.size} copies".uppercase(),
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        color = PaperMuted,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                }
                items(group.copies, key = { it.id }) { copy ->
                    val isBest = copy.id == group.best.id
                    val kept = isBest || copy.id in spared
                    CopyRow(
                        copy = copy,
                        best = isBest,
                        kept = kept,
                        onClick = if (isBest) {
                            null
                        } else {
                            {
                                feel.switch(kept)
                                spared = if (kept) spared - copy.id else spared + copy.id
                            }
                        },
                    )
                }
            }
        }
        if (groups.isNotEmpty()) {
            Text(
                text = "This deletes files from your phone and cannot be undone. Android will ask you to allow it.",
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                color = PaperMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(text = "Cancel", onClick = onBack, onInk = false, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.Button) {
                            if (doomed.isNotEmpty()) {
                                val keptInstead = groups.flatMap { group ->
                                    group.extras.filter { it.id !in spared }.map { it.id to group.best.id }
                                }.toMap()
                                onDelete(doomed, keptInstead)
                            }
                        }
                        .background(if (doomed.isEmpty()) PaperMuted else DangerOnPaper),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (doomed.size) {
                            0 -> "Nothing to delete"
                            1 -> "Delete 1 copy"
                            else -> "Delete ${doomed.size} copies"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** One copy of a song: where it is and how good it is. Solid if it stays, outlined in red if it goes. */
@Composable
private fun CopyRow(copy: Song, best: Boolean, kept: Boolean, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Checkbox, onClick = onClick) else Modifier)
            .then(
                when {
                    best -> Modifier.background(Ink)
                    kept -> Modifier.border(2.dp, InkOnPaper)
                    else -> Modifier.border(2.dp, DangerOnPaper)
                },
            )
            .padding(start = 14.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = copy.folder.replace("/", " / ").ifEmpty { "Phone storage" },
                color = if (best) Color.White else PaperText,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${copy.fileName.substringAfterLast('.', "").uppercase()} · ${copy.kbps} kbps · ${megabytes(copy.sizeBytes)}",
                color = if (best) InkMuted else PaperMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
        if (kept) {
            Text(
                text = "Keep",
                color = if (best) Color.White else PaperText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Icon(SpineIcons.Check, contentDescription = "Will be deleted", tint = DangerOnPaper, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Step 3: one spelling per artist, songs by several artists filed under the first, and
 * singles no longer listed under an "album" that is only their own name. Each change is
 * ticked to begin with; tapping one leaves it out.
 */
@Composable
fun SortScreen(
    fixes: SortFixes,
    run: TidyRun?,
    onBack: () -> Unit,
    onStart: (List<EditItem>) -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    BackHandler(enabled = run != null) { if (run?.running == false) onDone() }
    val feel = rememberFeel()
    var skipped by remember { mutableStateOf<Set<String>>(emptySet()) }
    val chosen = (fixes.artists + fixes.albums).filter { it.key !in skipped }
    // A song touched by two changes is still one file to save.
    val edits = chosen.flatMap { change -> change.edits.map { Triple(it.first, it.second, change.detail) } }
        .groupBy { it.first.id }
        .values.map { same ->
            EditItem(
                song = same.first().first,
                title = same.first().first.title,
                detail = same.joinToString("; ") { it.third.substringBefore(" · ") },
                changes = same.fold(emptyMap()) { all, one -> all + one.second },
            )
        }

    FixPage(
        back = "Fix your library".takeIf { run == null },
        onBack = onBack,
        heading = "SORT ARTISTS AND ALBUMS",
        explanation = when {
            run != null -> "Each song turns blue once its file is saved. Keep the app open until it finishes."
            fixes.isEmpty -> "Artists and albums are already in order."
            else -> "So each artist appears once on the Shelf, and a single is not listed as an album. " +
                "Tap a change to leave it out."
        },
    ) {
        if (run != null) {
            Saving(run, onStop, onDone, explained = true)
            return@FixPage
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            for ((index, section) in listOf("ONE NAME PER ARTIST" to fixes.artists, "SINGLES POSING AS ALBUMS" to fixes.albums).withIndex()) {
                val (name, changes) = section
                if (changes.isEmpty()) continue
                val keys = changes.mapTo(HashSet()) { it.key }
                val anyTicked = keys.any { it !in skipped }
                SectionHead(
                    text = "$name · ${changes.size}",
                    action = if (anyTicked) "Untick all" else "Tick all",
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                ) {
                    feel.step()
                    skipped = if (anyTicked) skipped + keys else skipped - keys
                }
                Half(empty = null) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(changes, key = { it.key }) { change ->
                            val ticked = change.key !in skipped
                            TidyRow(
                                title = change.title,
                                artist = change.detail,
                                ticked = ticked,
                                onClick = {
                                    feel.switch(!ticked)
                                    skipped = if (ticked) skipped + change.key else skipped - change.key
                                },
                            )
                        }
                    }
                }
            }
            if (fixes.isEmpty) Box(Modifier.weight(1f))
        }

        if (!fixes.isEmpty) {
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
                    text = if (edits.isEmpty()) "Nothing ticked" else "Sort ${songCount(edits.size)}",
                    onClick = { if (edits.isNotEmpty()) onStart(edits) },
                    onInk = false,
                    modifier = Modifier.weight(1.4f),
                    filled = true,
                )
            }
        }
    }
}

/**
 * Shown once, after the first scan finds music: how many songs there are and what could be
 * fixed, with a way into "Fix your library" and a way past it.
 */
@Composable
fun FirstOpenScreen(songs: Int, findings: FixFindings, onFix: () -> Unit, onLater: () -> Unit) {
    BackHandler(onBack = onLater)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .risingFill(0.3f, Play)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 44.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Text(
            text = "YOUR MUSIC IS IN",
            modifier = Modifier.riseIn(0),
            color = InkMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = songCount(songs).uppercase(),
            modifier = Modifier
                .padding(top = 6.dp)
                .riseIn(1),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = 92.sp,
            lineHeight = 84.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Spine had a look at them. A few minutes now will make the Shelf much cleaner:",
            modifier = Modifier
                .padding(top = 18.dp)
                .riseIn(2),
            color = Color.White,
            fontSize = 17.sp,
            lineHeight = 24.sp,
        )
        Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val lines = listOfNotNull(
                (findings.names to "names carry extras like (Official Video)").takeIf { findings.names > 0 },
                (findings.duplicateCopies to if (findings.duplicateCopies == 1) "extra copy of a song you already have" else "extra copies of songs you already have").takeIf { findings.duplicateCopies > 0 },
                (findings.sorting to "artists and albums to put in order").takeIf { findings.sorting > 0 },
            )
            lines.forEachIndexed { index, (count, what) ->
                Row(
                    modifier = Modifier.riseIn(3 + index),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = count.toString(),
                        modifier = Modifier
                            .width(64.dp)
                            .alignByBaseline(),
                        color = Color.White,
                        fontFamily = BarlowCondensed,
                        fontSize = 34.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = what,
                        modifier = Modifier
                            .weight(1f)
                            .alignByBaseline(),
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
        Box(Modifier.weight(1f))
        Text(
            text = "Nothing is changed without showing you first. You can do this any time from Settings, under Library.",
            modifier = Modifier.padding(bottom = 10.dp),
            color = OnPlayMuted,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockButton(text = "Later", onClick = onLater, onInk = true, modifier = Modifier.weight(1f))
            BlockButton(text = "Fix my library", onClick = onFix, onInk = true, modifier = Modifier.weight(1.4f), filled = true)
        }
    }
}

private fun megabytes(bytes: Long): String {
    val tenths = bytes * 10 / (1024 * 1024)
    return if (tenths >= 100) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
}
