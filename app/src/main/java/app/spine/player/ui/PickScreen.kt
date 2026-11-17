package app.spine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Library
import app.spine.player.data.Song
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.theme.Barlow
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkLine
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Play

/** What a new playlist is filled from. */
enum class FillFrom(val label: String) {
    Songs("Songs"),
    Videos("Videos"),
    Both("Both"),
}

/** Three choices side by side, the chosen one solid. */
@Composable
private fun FillChoice(chosen: FillFrom, onChange: (FillFrom) -> Unit, modifier: Modifier = Modifier) {
    val feel = rememberFeel()
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (choice in FillFrom.entries) {
            val on = choice == chosen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .selectable(selected = on, role = Role.RadioButton) {
                        feel.step()
                        onChange(choice)
                    }
                    .then(if (on) Modifier.background(Ink) else Modifier.border(1.dp, InkOnPaper)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = choice.label,
                    color = if (on) Color.White else PaperText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The popup that starts a new playlist: its name, and whether it is to be filled from
 * songs, videos or both. "Choose them" goes on to the list to tick from.
 */
@Composable
fun NewPlaylistPopup(visible: Boolean, onDismiss: () -> Unit, onChoose: (name: String, from: FillFrom) -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val feel = rememberFeel()
    BackHandler(enabled = visible, onBack = onDismiss)

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.92f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
        ) {
            // A fresh box each time the popup opens.
            var name by remember { mutableStateOf(TextFieldValue("")) }
            var from by remember { mutableStateOf(FillFrom.Songs) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            val confirm = {
                if (name.text.isNotBlank()) {
                    feel.done()
                    dismiss()
                    onChoose(name.text.trim(), from)
                }
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .background(Paper)
                    // A thin edge: on dark paper the popup would otherwise melt into the dimmed screen.
                    .border(1.dp, Hairline)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "NEW PLAYLIST",
                    color = PaperText,
                    fontFamily = BarlowCondensed,
                    fontSize = 36.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PanelLabel("NAME")
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .drawBehind {
                                val thickness = 2.dp.toPx()
                                drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                            }
                            .padding(vertical = 10.dp),
                        singleLine = true,
                        textStyle = TextStyle(color = PaperText, fontFamily = Barlow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Play),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PanelLabel("FILL IT FROM")
                    FillChoice(chosen = from, onChange = { from = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        onInk = false,
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        sidePadding = 6.dp,
                    )
                    BlockButton(
                        text = "Choose them",
                        onClick = confirm,
                        onInk = false,
                        modifier = Modifier.weight(1.6f).defaultMinSize(minHeight = 48.dp),
                        filled = true,
                        sidePadding = 6.dp,
                    )
                }
                Text(
                    text = "Tick nothing on the next screen and the playlist starts empty.",
                    color = PaperMuted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/**
 * The list a new playlist is filled from: songs, videos or both, with a box to search it
 * by, each row ticked or not. The button at the bottom makes the playlist with what is
 * ticked, in the order of the list.
 */
@Composable
fun PickScreen(
    name: String,
    startFrom: FillFrom,
    library: Library,
    onBack: () -> Unit,
    onMake: (picked: List<Song>) -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(startFrom) }
    var search by remember { mutableStateOf(TextFieldValue("")) }
    // The ids that are ticked. Kept across a search and a change of kind, so nothing ticked is lost.
    var ticked by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val feel = rememberFeel()
    val keyboard = LocalSoftwareKeyboardController.current

    val all = remember(library, from) {
        when (from) {
            FillFrom.Songs -> library.songs
            FillFrom.Videos -> library.videos
            FillFrom.Both -> (library.songs + library.videos).sortedBy { it.title.lowercase() }
        }
    }
    val words = search.text.trim()
    val shown = remember(all, words) {
        if (words.isEmpty()) all else all.filter { it.title.contains(words, ignoreCase = true) || it.artist.contains(words, ignoreCase = true) }
    }
    val tickedSet = remember(ticked) { ticked.toSet() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .imePadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            BackLink(text = "Playlists", onClick = onBack, color = PaperText)
            Text(
                text = name.uppercase(),
                modifier = Modifier.padding(top = 8.dp),
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = 46.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FillChoice(chosen = from, onChange = { from = it })

        BasicTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val thickness = 2.dp.toPx()
                    drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                },
            singleLine = true,
            textStyle = TextStyle(color = PaperText, fontFamily = BarlowCondensed, fontSize = 26.sp, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(Play),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (search.text.isEmpty()) {
                            Text(
                                text = when (from) {
                                    FillFrom.Songs -> "Search songs"
                                    FillFrom.Videos -> "Search videos"
                                    FillFrom.Both -> "Search songs and videos"
                                },
                                color = PaperMuted,
                                fontFamily = BarlowCondensed,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                    if (search.text.isEmpty()) {
                        Icon(SpineIcons.Search, contentDescription = null, tint = PaperText, modifier = Modifier.size(22.dp))
                    } else {
                        IconTap(SpineIcons.Close, label = "Clear", onClick = { search = TextFieldValue("") }, tint = PaperText, iconSize = 22.dp)
                    }
                }
            },
        )

        Text(
            text = when {
                all.isEmpty() -> when (from) {
                    FillFrom.Videos -> "NO VIDEOS ON THIS PHONE YET"
                    else -> "NOTHING HERE YET"
                }
                shown.isEmpty() -> "NOTHING MATCHES"
                else -> "TICK WHAT GOES IN · ${ticked.size} TICKED"
            },
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(shown, key = { it.id }) { song ->
                val on = song.id in tickedSet
                PickRow(
                    song = song,
                    ticked = on,
                    onToggle = {
                        feel.switch(!on)
                        ticked = if (on) ticked - song.id else ticked + song.id
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        BlockButton(
            text = when (ticked.size) {
                0 -> "Make the playlist empty"
                else -> "Make the playlist with ${ticked.size}"
            },
            // In the order of the whole list, not the order they were ticked in.
            onClick = {
                val everything = (library.songs + library.videos).associateBy { it.id }
                val order = (library.songs + library.videos).sortedBy { it.title.lowercase() }.map { it.id }
                    .filter { it in tickedSet }
                onMake(order.mapNotNull { everything[it] })
            },
            onInk = false,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp),
            filled = true,
        )
    }
}

/** One song or video in the list: a box that is ticked or not, its name, and a mark if it is a video. */
@Composable
private fun PickRow(song: Song, ticked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() })
            .background(Ink)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .then(if (ticked) Modifier.background(Color.White) else Modifier.border(1.dp, InkLine)),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) Icon(SpineIcons.Check, contentDescription = null, tint = Color(0xFF111113), modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(text = song.artist, color = InkMuted, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (song.isVideo) {
            Text(
                text = "VIDEO",
                modifier = Modifier
                    .border(1.dp, InkMuted)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = InkMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
        }
        Text(text = formatDuration(song.durationMs), color = InkMuted, fontSize = 13.sp)
    }
}
