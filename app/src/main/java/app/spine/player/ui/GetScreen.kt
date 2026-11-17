package app.spine.player.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.spine.player.data.Library
import app.spine.player.data.Song
import app.spine.player.data.letters
import app.spine.player.data.suggestFromName
import app.spine.player.download.Catalogue
import app.spine.player.download.Downloads
import app.spine.player.download.Engine
import app.spine.player.download.EngineFault
import app.spine.player.download.Found
import app.spine.player.download.Peek
import app.spine.player.download.PictureFormat
import app.spine.player.download.SoundFormat
import app.spine.player.download.GetOptions
import app.spine.player.download.GetState
import app.spine.player.download.Getting
import app.spine.player.playback.NowPlaying
import android.net.Uri
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.DeleteRequest
import app.spine.player.ui.components.MenuAction
import app.spine.player.ui.components.MenuState
import app.spine.player.ui.components.pressable
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.MiniPlayer
import app.spine.player.ui.components.SongStrip
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.SpineLoader
import app.spine.player.ui.components.StripState
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.risingFill
import app.spine.player.ui.components.smoothProgress
import app.spine.player.ui.components.songCount
import app.spine.player.ui.components.stripHeight
import app.spine.player.ui.components.rememberTextBox
import app.spine.player.ui.components.stripState
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.DangerOnPaper
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.InkLine
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Play
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** What the box's words led to. */
private sealed interface Lookup {
    data object Idle : Lookup

    /** [words] says what is being done, such as "Reading the link". */
    data class Looking(val words: String) : Lookup

    /** [fromLink] is true for the songs a link points to, which can all be taken at once. */
    data class Results(val songs: List<Found>, val fromLink: Boolean) : Lookup

    data class Problem(val words: String) : Lookup
}

/** Whether the box holds an address, as opposed to the name of a song. */
private fun String.isLink(): Boolean {
    val text = trim()
    return text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) ||
        (!text.contains(' ') && Regex("""^[\w-]+(\.[\w-]+)+/\S*""").containsMatchIn(text))
}

/** Sharing from another app often hands over "Song name https://…"; the address is what matters. */
private fun String.linkInside(): String? =
    Regex("""https?://\S+""").find(this)?.value

/** Two files count as the same recording when they are this close in length. */
private const val SAME_LENGTH_MS = 2_000L

/**
 * Finds songs that are already on the phone: the same title and artist, by the same test
 * as Remove duplicates (letters only, tidied first), and the same length where both are known.
 */
private class OnPhone(library: Library) {
    private val byTitle: Map<String, List<Pair<Song, String>>> = library.songs.map { song ->
        val tidy = suggestFromName(song.title, song.artist)
        Triple(song, (tidy?.title ?: song.title).letters(), (tidy?.artist ?: song.artist).letters())
    }.groupBy({ it.second }, { it.first to it.third })

    fun find(found: Found): Song? {
        val tidy = if (found.tidy) null else suggestFromName(found.title, found.artist)
        val title = (tidy?.title ?: found.title).letters()
        val artist = (tidy?.artist ?: found.artist).letters()
        if (title.isEmpty()) return null
        return byTitle[title]?.firstOrNull { (song, songArtist) ->
            val sameArtist = artist.isEmpty() || songArtist.isEmpty() || artist in songArtist || songArtist in artist
            val sameLength = found.durationMs == 0L || abs(song.durationMs - found.durationMs) <= SAME_LENGTH_MS
            sameArtist && sameLength
        }?.first
    }
}

/**
 * Get music, the Shelf's tab for what is not on the phone yet: one box for a song's name
 * or a link. Results lie down as strips; a tap downloads one, and the strip fills with the
 * play colour as the song arrives.
 *
 * With the box empty it shows what is downloading and what arrived lately. It is drawn
 * inside the Shelf, which supplies the heading, the tabs and the playing strip.
 */
@Composable
fun GetMusic(
    library: Library,
    nowPlaying: NowPlaying,
    query: String,
    onQueryChange: (String) -> Unit,
    actions: LibraryActions,
    /** Shows a long-press menu, and the "are you sure" before a delete. */
    onMenu: (MenuState) -> Unit,
    onAskDelete: (DeleteRequest) -> Unit,
) {
    val context = LocalContext.current
    val feel = rememberFeel()
    val getting by Downloads.items.collectAsStateWithLifecycle()
    val arrived by Downloads.arrived.collectAsStateWithLifecycle()
    val onPhone = remember(library) { OnPhone(library) }
    val gettingById = remember(getting) { getting.associateBy { it.song.id } }

    // The engine takes a few seconds to unpack the first time, and YouTube's puzzle takes
    // ten to solve; both are started as the tab opens, not when the first song is tapped.
    LaunchedEffect(Unit) { Engine.warm(context) }

    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }
    // Of a link's several songs, the ones ticked out: not to be fetched.
    var leftOut by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(query) {
        val words = query.trim()
        if (words.length < 2) {
            lookup = Lookup.Idle
            return@LaunchedEffect
        }
        val link = words.takeIf { it.isLink() } ?: words.linkInside()
        // A link is complete the moment it is pasted; a name is still being typed, so
        // wait for a pause in the typing before asking.
        if (link == null) delay(450)
        lookup = Lookup.Looking(if (link != null) "Reading the link" else "Looking it up")
        lookup = try {
            if (link != null) {
                // YouTube says what a video is in a fraction of a second; the engine takes
                // several. The quick answer is shown at once, and the engine fills in the
                // rest (the length, a proper title) while the user reads it.
                val quick = Peek.youtube(link)
                if (quick != null) {
                    lookup = Lookup.Results(listOf(quick), fromLink = true)
                    Lookup.Results(listOf(Engine.prepare(context, quick).await() ?: quick), fromLink = true)
                } else {
                    Lookup.Results(Engine.read(context, link), fromLink = true)
                }
            } else {
                Lookup.Results(Catalogue.songs(words), fromLink = false)
            }
        } catch (fault: EngineFault) {
            Lookup.Problem(fault.message ?: "That did not work. Try again in a moment.")
        } catch (fault: Exception) {
            Lookup.Problem("Could not look that up. Check the internet and try again.")
        }
        // A link to several songs starts with all of them ticked, except those already here.
        (lookup as? Lookup.Results)?.takeIf { it.fromLink && it.songs.size > 1 }?.let { results ->
            leftOut = results.songs.filter { onPhone.find(it) != null }.mapTo(HashSet()) { it.id }
        }
    }

    // Before any download: ask for whatever it needs and does not have, then go ahead.
    val gate = rememberDownloadGate()
    // A tap on a search result fetches it "as usual"; a pasted link is asked about first.
    val get: (Found) -> Unit = { song ->
        gate {
            feel.done()
            // A search result is a song: it goes to the Shelf even if a link was last fetched as a video.
            Downloads.add(context, song, Downloads.preset(context).copy(video = false))
        }
    }
    var options by remember { mutableStateOf(Downloads.preset(context)) }
    val choose: (GetOptions) -> Unit = { chosen ->
        options = chosen
        // What is picked here is what "as usual" means next time.
        Downloads.setPreset(context, chosen)
    }
    // A sound picked for one link belongs to that link only.
    LaunchedEffect(query) { options = options.copy(formatId = null) }
    val play: (Song) -> Unit = { song -> actions.play(listOf(song), 0) }
    // On anything this app downloaded: where it came from.
    val linkActions: (String?) -> List<MenuAction> = { url ->
        if (url.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                MenuAction("Copy the link") { copyText(context, url) },
                MenuAction("Open the page it came from") { openPage(context, url) },
            )
        }
    }
    // The songs downloaded lately, newest first. Read again whenever one arrives.
    val recent = remember(library, arrived) {
        Downloads.recent(context).mapNotNull { library.songsById[it] }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            // The words are kept by the caller; the box adds where the cursor is, which goes
            // after the last letter whenever the words come from elsewhere or the tab is come back to.
            var box by rememberTextBox(query)
            BasicTextField(
                value = box,
                onValueChange = {
                    box = it
                    if (it.text != query) onQueryChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val thickness = 2.dp.toPx()
                        drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                    },
                singleLine = true,
                textStyle = TextStyle(
                    color = PaperText,
                    fontFamily = BarlowCondensed,
                    fontSize = 36.sp,
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
                                    text = "Song name or link",
                                    color = PaperMuted,
                                    fontFamily = BarlowCondensed,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            field()
                        }
                        if (query.isNotEmpty()) {
                            IconTap(
                                icon = SpineIcons.Close,
                                label = "Clear",
                                onClick = { onQueryChange("") },
                                tint = PaperText,
                                iconSize = 22.dp,
                            )
                        }
                    }
                },
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            when (val state = lookup) {
                Lookup.Idle -> {
                    item(key = "paste") {
                        Row(modifier = Modifier.padding(bottom = 10.dp)) {
                            BlockButton(
                                text = "Paste a link",
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!text.isNullOrBlank()) onQueryChange(text.trim())
                                },
                                onInk = false,
                            )
                        }
                    }

                    val unfinished = getting.filter { it.state != GetState.Done }
                    if (unfinished.isNotEmpty()) {
                        item(key = "downloading") { Label("DOWNLOADING · ${unfinished.count { it.busy }}") }
                        items(unfinished, key = { "getting:${it.song.id}" }) { item ->
                            GetStrip(
                                song = item.song,
                                getting = item,
                                modifier = Modifier.animateItem(),
                                // A second try keeps what was chosen the first time, video included.
                                onClick = { if (item.state == GetState.Failed) gate { Downloads.add(context, item.song, item.options) } },
                                onStop = {
                                    if (item.state == GetState.Failed) Downloads.dismiss(item.song.id) else Downloads.stop(item.song.id)
                                },
                                onLongPress = { bounds ->
                                    val failed = item.state == GetState.Failed
                                    onMenu(
                                        MenuState(
                                            bounds = bounds,
                                            onInk = false,
                                            actions = buildList {
                                                if (failed) {
                                                    add(MenuAction("Try again") { gate { Downloads.add(context, item.song, item.options) } })
                                                    item.problem?.let { problem -> add(MenuAction("Copy the error") { copyText(context, problem) }) }
                                                } else {
                                                    add(MenuAction("Stop") { Downloads.stop(item.song.id) })
                                                }
                                                addAll(linkActions(item.song.url))
                                                if (failed) add(MenuAction("Remove from the list", destructive = true) { Downloads.dismiss(item.song.id) })
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    if (recent.isNotEmpty()) {
                        item(key = "new") { Label("NEW ON YOUR PHONE", Modifier.padding(top = if (unfinished.isEmpty()) 0.dp else 12.dp)) }
                        itemsIndexed(recent, key = { _, song -> "song:${song.id}" }) { index, song ->
                            val strip = stripState(song, nowPlaying)
                            SongStrip(
                                song = song,
                                onClick = { actions.play(recent, index) },
                                modifier = Modifier.animateItem(),
                                // The usual song menu, with what only a download has: its file to pass on, and its source.
                                onLongPress = { bounds ->
                                    actions.songMenuWith(
                                        song,
                                        bounds,
                                        buildList {
                                            add(MenuAction("Share the file") { shareFile(context, song.uri, "audio/*") })
                                            addAll(linkActions(Downloads.sourceOf(context, song.id)))
                                            add(
                                                MenuAction(
                                                    "Details",
                                                    detail = listOf(song.fileName.substringAfterLast('.', "").uppercase(), sizeInMegabytes(song.sizeBytes))
                                                        .filter { it.isNotEmpty() }.joinToString(" · "),
                                                ) { },
                                            )
                                        },
                                    )
                                },
                                state = strip,
                                fill = if (strip != StripState.Idle) nowPlaying.progress else 0f,
                            )
                        }
                    }
                    if (recent.isEmpty() && unfinished.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                // Sharing a link from another app is not built yet, so it is not promised here.
                                text = "Type a song's name, or paste a link from YouTube or another site.",
                                color = PaperMuted,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }

                is Lookup.Looking -> item(key = "looking") {
                    Working(words = state.words, modifier = Modifier.padding(top = 6.dp), detail = query.trim())
                }

                is Lookup.Problem -> item(key = "problem") { Note(state.words) }

                is Lookup.Results -> {
                    if (state.songs.isEmpty()) {
                        item(key = "none") { Note("Nothing found for that.") }
                    } else if (state.fromLink && state.songs.size == 1) {
                        // A link to one song: say what it is, then how to get it.
                        val song = state.songs.single()
                        val item = gettingById[song.id]
                        val here = item?.songId?.let { library.songsById[it] } ?: onPhone.find(song)
                        item(key = "one") {
                            // Every sound and picture the link offers, known once the engine has worked it out.
                            val sounds by produceState<List<SoundFormat>?>(initialValue = null, song) {
                                value = withContext(Dispatchers.IO) { Engine.sounds(song) }
                            }
                            val pictures by produceState<List<PictureFormat>?>(initialValue = null, song) {
                                value = withContext(Dispatchers.IO) { Engine.pictures(song) }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SongCard(song = song, onInk = true)
                                if (item != null) {
                                    DownloadProgress(listOf(item))
                                    val saved = item.videoUri
                                    when {
                                        item.state == GetState.Failed -> Row {
                                            BlockButton(text = "Try again", onClick = { Downloads.add(context, song, options) }, onInk = false, filled = true)
                                        }
                                        item.state == GetState.Done && saved != null -> Row {
                                            BlockButton(text = "Watch it", onClick = { watch(context, saved) }, onInk = false, filled = true)
                                        }
                                    }
                                } else {
                                    KindChoice(options = options, onChange = choose)
                                    if (!options.video && here != null) {
                                        // As a song it is here already; as a video it can still be fetched.
                                        Note("This one is on your phone.")
                                        Row { BlockButton(text = "Play it", onClick = { play(here) }, onInk = false, filled = true) }
                                    } else {
                                        FormatChoice(options = options, onChange = choose, sounds = sounds, pictures = pictures)
                                        ExtraChoices(options = options, onChange = choose)
                                        val size = if (options.video) {
                                            pictures?.firstOrNull { it.id == options.formatId }?.bytes
                                        } else {
                                            sounds?.firstOrNull { it.id == options.formatId }?.bytes
                                        }
                                        DownloadButton(
                                            text = listOf("Download", sizeInMegabytes(size ?: 0)).filter { it.isNotEmpty() }.joinToString(" · "),
                                            onClick = {
                                                gate {
                                                    feel.done()
                                                    Downloads.add(context, song, options)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    } else if (state.fromLink && state.songs.none { gettingById[it.id] != null }) {
                        // A link to an album or a playlist, with nothing asked for yet: each
                        // song can be ticked in or out first.
                        val picked = state.songs.filter { it.id !in leftOut }
                        item(key = "pick-kind") {
                            KindChoice(options = options, onChange = choose, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        item(key = "pick-head") {
                            SongPickerHead(
                                count = state.songs.size,
                                picked = picked.size,
                                onAll = { tick ->
                                    feel.step()
                                    leftOut = if (tick) emptySet() else state.songs.mapTo(HashSet()) { it.id }
                                },
                            )
                        }
                        // Under the heading, not after the songs: a long list would hide it.
                        item(key = "pick-go") {
                            DownloadButton(
                                text = if (picked.isEmpty()) "Nothing ticked" else "Get ${songCount(picked.size)}",
                                onClick = {
                                    if (picked.isNotEmpty()) {
                                        gate {
                                            feel.done()
                                            picked.forEach { Downloads.add(context, it, options.copy(formatId = null)) }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                            )
                        }
                        itemsIndexed(state.songs, key = { _, song -> "pick:${song.id}" }) { index, song ->
                            val out = song.id in leftOut
                            SongPickerRow(
                                place = index + 1,
                                song = song,
                                onPhone = onPhone.find(song) != null,
                                ticked = !out,
                                onClick = {
                                    feel.switch(out)
                                    leftOut = if (out) leftOut - song.id else leftOut + song.id
                                },
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                    } else {
                        item(key = "head") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Label(
                                    if (state.fromLink && state.songs.size > 1) songCount(state.songs.size).uppercase()
                                    else "SONGS · TAP ONE TO GET IT",
                                )
                                // A link to an album or a playlist: everything that is not here yet.
                                val missing = state.songs.filter { gettingById[it.id] == null && onPhone.find(it) == null }
                                if (state.fromLink && state.songs.size > 1 && missing.isNotEmpty()) {
                                    BlockButton(
                                        text = if (missing.size == state.songs.size) "Get all" else "Get the other ${missing.size}",
                                        onClick = {
                                            gate {
                                                feel.done()
                                                missing.forEach { Downloads.add(context, it) }
                                            }
                                        },
                                        onInk = false,
                                        filled = true,
                                    )
                                }
                            }
                        }
                        items(state.songs, key = { "found:${it.id}" }) { song ->
                            val item = gettingById[song.id]
                            val here = item?.songId?.let { library.songsById[it] } ?: onPhone.find(song)
                            GetStrip(
                                song = song,
                                getting = item,
                                onPhone = here != null,
                                onClick = {
                                    when {
                                        here != null -> play(here)
                                        item == null || item.state == GetState.Failed -> get(song)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 3.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
private fun Note(text: String) {
    Text(text = text, color = PaperMuted, fontSize = 16.sp, lineHeight = 22.sp)
}

/**
 * A song that is not on the phone yet, lying down like any other. Solid with an arrow when
 * it can be fetched; filling with the play colour while it downloads; outlined with a tick
 * once it is on the phone; outlined in red with the reason if it failed.
 */
@Composable
private fun GetStrip(
    song: Found,
    getting: Getting?,
    modifier: Modifier = Modifier,
    onPhone: Boolean = false,
    onClick: () -> Unit,
    /** Shows a cross that stops the download, or clears a failed one. */
    onStop: (() -> Unit)? = null,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
) {
    val state = getting?.state
    val here = onPhone || state == GetState.Done
    val failed = state == GetState.Failed
    val busy = getting?.busy == true
    val outlined = here || failed
    val fill = when (state) {
        GetState.Downloading -> getting.progress
        GetState.Finishing -> 1f
        else -> 0f
    }
    val main = if (outlined) PaperText else Color.White
    val quiet = when {
        outlined -> PaperMuted
        fill > 0.5f -> OnPlayMuted
        else -> InkMuted
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = stripHeight(song.durationMs))
            .pressable({ onClick() }, onLongPress)
            .then(
                when {
                    failed -> Modifier.border(2.dp, DangerOnPaper)
                    here -> Modifier.border(2.dp, InkOnPaper)
                    else -> Modifier
                        .background(Ink)
                        .risingFill(smoothProgress(fill))
                },
            )
            .padding(start = 14.dp, top = 8.dp, end = if (onStop != null) 0.dp else 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = main,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = if (busy) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    failed -> getting?.problem ?: "The download did not work."
                    else -> listOfNotNull(
                        song.artist.takeIf { it.isNotBlank() },
                        song.album?.takeIf { it.isNotBlank() && it != song.title },
                        "on your phone".takeIf { here },
                    ).joinToString(" · ")
                },
                color = if (failed) DangerOnPaper else quiet,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = if (failed) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = when (state) {
                GetState.Waiting -> "Waiting"
                // Before the first byte the engine is working out where the song is.
                GetState.Downloading -> if (getting.progress <= 0f) "Getting ready" else "${(getting.progress * 100).toInt()}%"
                GetState.Finishing -> "Saving"
                GetState.Failed -> "Try again"
                else -> if (song.durationMs > 0) formatDuration(song.durationMs) else ""
            },
            color = if (failed) PaperText else if (busy && fill > 0f) Color.White else quiet,
            fontSize = 13.sp,
            fontWeight = if (failed) FontWeight.SemiBold else FontWeight.Normal,
        )
        when {
            // Before the first byte there is no level to show, so the loader says it is alive.
            onStop == null && state == GetState.Downloading && getting.progress <= 0f ->
                SpineLoader(height = 22.dp, base = InkLine, label = "Getting ready")
            onStop != null -> IconTap(
                icon = SpineIcons.Close,
                label = if (failed) "Clear" else "Stop this download",
                onClick = onStop,
                tint = main,
                iconSize = 18.dp,
            )
            here -> Icon(SpineIcons.Check, contentDescription = "On your phone", tint = PaperText, modifier = Modifier.size(20.dp))
            !busy && !failed -> Icon(SpineIcons.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
