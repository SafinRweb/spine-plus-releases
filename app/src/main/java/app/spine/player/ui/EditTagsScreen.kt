package app.spine.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Album
import app.spine.player.data.Artist
import app.spine.player.data.NameSuggestion
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.data.SongTags
import app.spine.player.data.suggestFromName
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.Barlow
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.DangerOnPaper
import app.spine.player.ui.theme.GhostLine
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.Play

/**
 * Edit the details stored in one song's file. A song named the way downloads usually are
 * gets a suggestion at the top: its title and artist worked out from that name.
 *
 * [onSave] is handed the fields that changed, by the name the file knows them under.
 */
@Composable
fun EditSongScreen(
    song: Song,
    loadTags: suspend (Song) -> SongTags,
    saving: Boolean,
    problem: String?,
    onBack: () -> Unit,
    onSave: (changes: Map<String, String>) -> Unit,
) {
    var before by remember(song.id) { mutableStateOf<SongTags?>(null) }
    var tags by remember(song.id) { mutableStateOf(SongTags()) }
    LaunchedEffect(song.id) {
        val loaded = loadTags(song)
        before = loaded
        tags = loaded
    }
    val suggestion = remember(tags.title, tags.artist) { suggestFromName(tags.title, tags.artist) }
    val feel = rememberFeel()

    EditFrame(
        heading = "EDIT SONG",
        note = "Saving changes the song file itself. Android will ask you to allow it.",
        problem = problem,
        saving = saving,
        canSave = before != null,
        onBack = onBack,
        onSave = { before?.let { onSave(tags.trimmed().changesFrom(it)) } },
    ) {
        if (suggestion != null && before != null) {
            Suggestion(suggestion) {
                feel.done()
                tags = tags.copy(title = suggestion.title, artist = suggestion.artist)
            }
        }
        TagField("Title", tags.title, { tags = tags.copy(title = it) }, singleLine = false)
        TagField("Artist", tags.artist, { tags = tags.copy(artist = it) })
        TagField("Album", tags.album, { tags = tags.copy(album = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TagField("Track", tags.track, { tags = tags.copy(track = it.digits(3)) }, Modifier.weight(1f), numeric = true)
            TagField("Year", tags.year, { tags = tags.copy(year = it.digits(4)) }, Modifier.weight(1f), numeric = true)
        }
        Text(
            text = "${song.fileName} · ${song.folder}",
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
    }
}

/**
 * Edit what all the songs of an album share: its name, artist and year go into every
 * song at once. A song can be opened from the list to edit it alone.
 */
@Composable
fun EditAlbumScreen(
    album: Album,
    loadTags: suspend (Song) -> SongTags,
    saving: Boolean,
    problem: String?,
    onBack: () -> Unit,
    onEditSong: (Song) -> Unit,
    onSave: (changes: Map<String, String>) -> Unit,
) {
    var before by remember(album.id) { mutableStateOf<SongTags?>(null) }
    var tags by remember(album.id) { mutableStateOf(SongTags()) }
    LaunchedEffect(album.id) {
        // The first song stands for the album; only what is changed here gets written.
        val first = album.songs.firstOrNull()?.let { loadTags(it) } ?: SongTags()
        val shared = SongTags(album = first.album, artist = first.artist, year = first.year)
        before = shared
        tags = shared
    }

    EditFrame(
        heading = "EDIT ALBUM",
        note = "Saving changes the song files themselves. Android will ask you once to allow it for all of them.",
        problem = problem,
        saving = saving,
        canSave = before != null,
        onBack = onBack,
        onSave = { before?.let { onSave(tags.trimmed().changesFrom(it)) } },
    ) {
        TagField("Album", tags.album, { tags = tags.copy(album = it) })
        TagField("Artist", tags.artist, { tags = tags.copy(artist = it) })
        TagField("Year", tags.year, { tags = tags.copy(year = it.digits(4)) }, Modifier.fillMaxWidth(0.5f), numeric = true)
        Text(
            text = "These three go into all ${songCount(album.songs.size)}.",
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )

        Column {
            SectionLabel("SONGS · TAP ONE TO EDIT IT ALONE")
            album.songs.forEachIndexed { index, song ->
                EditRow(
                    number = song.trackNumber.takeIf { it > 0 } ?: (index + 1),
                    title = song.title,
                    onClick = { onEditSong(song) },
                ) {
                    Icon(SpineIcons.ChevronRight, contentDescription = null, tint = PaperMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Give an artist another name. It goes into the songs credited to the artist alone; a song
 * shared with someone else keeps its credit and is kept filed under the new name.
 */
@Composable
fun EditArtistScreen(
    artist: Artist,
    saving: Boolean,
    problem: String?,
    onBack: () -> Unit,
    onEditSong: (Song) -> Unit,
    onSave: (name: String) -> Unit,
) {
    var name by remember(artist.name) { mutableStateOf(artist.name) }
    val own = remember(artist) { artist.songs.count { it.artist == artist.name } }
    val shared = artist.songs.size - own

    EditFrame(
        heading = "EDIT ARTIST",
        note = "Saving changes the song files themselves. Android will ask you once to allow it for all of them.",
        problem = problem,
        saving = saving,
        canSave = name.isNotBlank(),
        onBack = onBack,
        onSave = { onSave(name.trim()) },
    ) {
        TagField("Artist name", name, { name = it })
        Text(
            text = when {
                shared == 0 -> "The name goes into all ${songCount(own)}."
                own == 0 -> "${songCount(shared).replaceFirstChar { it.uppercase() }} shared with someone else: " +
                    "each keeps its full credit and stays filed here."
                else -> "The name goes into the ${songCount(own)} by this artist alone. The ${songCount(shared)} " +
                    "shared with someone else ${if (shared == 1) "keeps its" else "keep their"} full credit and " +
                    "${if (shared == 1) "stays" else "stay"} filed here."
            },
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )

        Column {
            SectionLabel("SONGS · TAP ONE TO EDIT IT ALONE")
            artist.songs.forEach { song ->
                EditRow(
                    title = song.title,
                    // Only said when it is not simply this artist.
                    detail = song.artist.takeIf { it != artist.name },
                    onClick = { onEditSong(song) },
                ) {
                    Icon(SpineIcons.ChevronRight, contentDescription = null, tint = PaperMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Rename a playlist, take songs out of it, or delete it. Nothing is changed until Save.
 * Favourites keeps its name and cannot be deleted, so there it only lists the songs.
 */
@Composable
fun EditPlaylistScreen(
    playlist: Playlist,
    onBack: () -> Unit,
    onSave: (name: String, removed: Set<Long>) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var removed by remember(playlist.id) { mutableStateOf(emptySet<Long>()) }
    val feel = rememberFeel()
    // Where each song that is staying stands, counted from 1.
    val places = remember(playlist, removed) {
        playlist.songs.filter { it.id !in removed }.withIndex().associate { (index, song) -> song.id to index + 1 }
    }

    EditFrame(
        heading = "EDIT PLAYLIST",
        note = "Songs you take out stay on your phone. Nothing changes until you save.",
        problem = null,
        saving = false,
        canSave = playlist.isFavourites || name.isNotBlank(),
        onBack = onBack,
        onSave = { onSave(name, removed) },
        footer = if (playlist.isFavourites) {
            null
        } else {
            {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.Button, onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Delete playlist", color = DangerOnPaper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        if (!playlist.isFavourites) TagField("Name", name, { name = it })

        Column {
            SectionLabel(
                if (places.isEmpty()) {
                    "NO SONGS IN THIS PLAYLIST"
                } else {
                    "${songCount(places.size).uppercase()} · TAP THE CROSS TO TAKE ONE OUT"
                },
            )
            playlist.songs.forEach { song ->
                key(song.id) {
                    // Keeps the number it had while it folds away.
                    var number by remember { mutableStateOf(places[song.id] ?: 0) }
                    places[song.id]?.let { number = it }
                    AnimatedVisibility(
                        visible = song.id !in removed,
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
                    ) {
                        EditRow(number = number, title = song.title, detail = song.artist) {
                            IconTap(
                                icon = SpineIcons.Close,
                                label = "Take ${song.title} out",
                                onClick = {
                                    feel.step()
                                    removed = removed + song.id
                                },
                                tint = PaperText,
                                iconSize = 18.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

/** A song in an editor's list: an optional number, its name, and whatever the row does at the right. */
@Composable
private fun EditRow(
    title: String,
    number: Int? = null,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind {
                val thickness = 1.dp.toPx()
                drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) {
            Text(
                text = number.toString(),
                modifier = Modifier.defaultMinSize(minWidth = 20.dp),
                color = PaperMuted,
                fontSize = 13.sp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = PaperText, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(text = detail, color = PaperMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
        trailing()
    }
}

/** The page every editor shares: heading, the fields in a scrolling column, then Cancel and Save. */
@Composable
private fun EditFrame(
    heading: String,
    note: String,
    problem: String?,
    saving: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    /** Something under Cancel and Save, such as a way to delete the thing being edited. */
    footer: (@Composable () -> Unit)? = null,
    fields: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            // Includes the keyboard, so Save stays in reach while typing.
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        BackLink(text = "Back", onClick = onBack, color = PaperText)
        Text(
            text = heading,
            modifier = Modifier.padding(top = 8.dp),
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 46.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = fields,
        )
        Text(
            text = problem ?: note,
            modifier = Modifier.padding(bottom = 12.dp),
            color = if (problem != null) PaperText else PaperMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = if (problem != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockButton(text = "Cancel", onClick = onBack, onInk = false, modifier = Modifier.weight(1f))
            BlockButton(
                text = if (saving) "Saving" else "Save",
                onClick = { if (canSave && !saving) onSave() },
                onInk = false,
                modifier = Modifier.weight(1f),
                filled = true,
            )
        }
        footer?.invoke()
    }
}

/** The title and artist worked out from the song's name, with one tap to take them. */
@Composable
private fun Suggestion(suggestion: NameSuggestion, onUse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink)
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "FROM THE NAME",
                color = InkMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = suggestion.title,
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (suggestion.artist.isNotBlank()) {
                Text(text = suggestion.artist, color = InkMuted, fontSize = 14.sp, lineHeight = 18.sp)
            }
        }
        BlockButton(text = "Use", onClick = onUse, onInk = true, filled = true)
    }
}

/** A labelled line to type on. Empty, it reads "None" in a quieter colour. */
@Composable
private fun TagField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    singleLine: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            color = PaperMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = { onChange(if (singleLine) it.replace("\n", " ") else it.replace("\n", "")) },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val thickness = 2.dp.toPx()
                    drawRect(
                        if (value.isEmpty()) GhostLine else PaperText,
                        Offset(0f, size.height - thickness),
                        Size(size.width, thickness),
                    )
                }
                .padding(top = 8.dp, bottom = 8.dp),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            textStyle = TextStyle(
                color = PaperText,
                fontFamily = Barlow,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(Play),
            keyboardOptions = KeyboardOptions(
                capitalization = if (numeric) KeyboardCapitalization.None else KeyboardCapitalization.Words,
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = "None",
                            color = PaperMuted,
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    field()
                }
            },
        )
    }
}

private fun String.digits(max: Int): String = filter { it.isDigit() }.take(max)

private fun SongTags.trimmed(): SongTags =
    copy(title = title.trim(), artist = artist.trim(), album = album.trim(), track = track.trim(), year = year.trim())
