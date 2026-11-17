package app.spine.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.download.Downloads
import app.spine.player.download.Found
import app.spine.player.download.GetOptions
import app.spine.player.download.GetState
import app.spine.player.download.Getting
import app.spine.player.download.PictureFormat
import app.spine.player.download.SaveAs
import app.spine.player.download.SoundFormat
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.SpineLoader
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.components.pressable
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.risingFill
import app.spine.player.ui.components.smoothProgress
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.InkLine
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText

// The pieces that make up "what is this, and how do you want it": shown in the Get tab
// when a link is pasted, and in the Quick download panel over another app.

/**
 * "Working on it", said so it cannot be missed: the loader, with what is being done in
 * large type beside it and, under that, what it is being done to.
 */
@Composable
fun Working(words: String, modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SpineLoader(height = 64.dp, label = words)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = words.uppercase(),
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = 30.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            if (detail != null) {
                Text(text = detail, color = PaperMuted, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A small heading over a group of choices. */
@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = PaperMuted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

/** Who made it, how long it is and where it comes from, on one line. */
fun Found.detailLine(): String = listOfNotNull(
    artist.takeIf { it.isNotBlank() },
    formatDuration(durationMs).takeIf { durationMs > 0 },
    source?.let { "from $it" },
).joinToString(" · ")

/**
 * The song a link points to, said large before anything is asked about it. [onInk] draws
 * it as a dark block, for the paper of the Get tab; without it the words stand alone.
 */
@Composable
fun SongCard(song: Found, onInk: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onInk) Modifier.background(Ink).padding(14.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = song.title.uppercase(),
            color = if (onInk) Color.White else PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 34.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.detailLine(),
            color = if (onInk) InkMuted else PaperMuted,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )
    }
}

private val SaveAs.blurb: String
    get() = when (this) {
        SaveAs.M4a -> "Plays on everything"
        SaveAs.Opus -> "A little better at the same size"
    }

/** "3.9 MB", or "12 MB" from ten up. Empty when the size is not known. */
fun sizeInMegabytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val tenths = bytes * 10 / (1024 * 1024)
    return if (tenths >= 100) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
}

/** "M4A · 129 kbps". */
private val SoundFormat.name: String get() = "${saveAs.label} · $kbps kbps"

/** "1080p · MP4". */
private val PictureFormat.name: String get() = "${height}p · ${if (mp4) "MP4" else "MKV"}"

/** The tallest pictures "as usual" can be set to. */
private val Heights = listOf(2160, 1440, 1080, 720, 480, 360)

private fun heightName(height: Int): String = if (height >= 2160) "4K" else "${height}p"

private fun heightBlurb(height: Int): String = when {
    height >= 2160 -> "The sharpest, and the largest files"
    height >= 1440 -> "Sharper than full HD"
    height >= 1080 -> "Full HD: sharp on any phone"
    height >= 720 -> "HD: about half the size of full HD"
    else -> "Small files"
}

/** One line of a short list to pick from. */
private class Pick(val title: String, val line: String, val value: String, val chosen: Boolean, val take: () -> GetOptions)

/** Whether to fetch the sound alone, as a song for the Shelf, or the picture with it, as a video. */
@Composable
fun KindChoice(options: GetOptions, onChange: (GetOptions) -> Unit, modifier: Modifier = Modifier) {
    val feel = rememberFeel()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelLabel("GET IT AS")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (video in listOf(false, true)) {
                val on = options.video == video
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.RadioButton) {
                            if (!on) {
                                feel.step()
                                // A sound or picture picked for the other kind does not carry over.
                                onChange(options.copy(video = video, formatId = null))
                            }
                        }
                        .then(if (on) Modifier.background(Ink) else Modifier.border(1.dp, InkOnPaper)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (video) "Video" else "Audio",
                        color = if (on) Color.White else PaperText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The format, in two ways. "As usual" is what was chosen last time; its pencil opens the
 * kinds there are, and picking one makes it the usual from then on. "Choose the format"
 * lists what this one link offers, with sizes, for this download only: its sounds for a
 * song ([sounds]), its pictures for a video ([pictures]).
 *
 * Those lists are null while the engine is still working the link out, which takes a few
 * seconds after the song first shows.
 */
@Composable
fun FormatChoice(
    options: GetOptions,
    onChange: (GetOptions) -> Unit,
    modifier: Modifier = Modifier,
    sounds: List<SoundFormat>? = null,
    pictures: List<PictureFormat>? = null,
    /** False where there is nothing to choose from, such as several songs at once. */
    canChoose: Boolean = true,
) {
    val feel = rememberFeel()
    val context = LocalContext.current
    // What "as usual" means now; something picked for this download does not change it.
    val usual = remember(options) { Downloads.preset(context) }
    val asUsual = options.formatId == null
    var changingUsual by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    // The same three things for a song and for a video: what "as usual" is, what it can be
    // changed to, and what this link offers.
    val usualLine: String
    val kinds: List<Pick>
    val offered: List<Pick>?
    if (options.video) {
        usualLine = "MP4 · up to ${heightName(usual.height)}"
        kinds = Heights.map { height ->
            Pick("Up to ${heightName(height)}", heightBlurb(height), "", height == usual.height) {
                options.copy(height = height, formatId = null)
            }
        }
        offered = pictures?.map { picture ->
            Pick(
                picture.name,
                listOfNotNull(picture.codec, "${picture.fps} frames a second".takeIf { picture.fps > 0 }).joinToString(" · "),
                sizeInMegabytes(picture.bytes),
                picture.id == options.formatId,
            ) { options.copy(formatId = picture.id, mp4 = picture.mp4) }
        }
    } else {
        usualLine = "${usual.saveAs.label} · best quality"
        kinds = SaveAs.entries.map { kind ->
            Pick(kind.label, kind.blurb, "", kind == usual.saveAs) { options.copy(saveAs = kind, formatId = null) }
        }
        offered = sounds?.map { sound ->
            // The best of each kind says what the kind is good for; the rest are smaller.
            val best = sounds.first { it.saveAs == sound.saveAs } == sound
            Pick(sound.name, if (best) sound.saveAs.blurb else "A smaller file", sizeInMegabytes(sound.bytes), sound.id == options.formatId) {
                options.copy(saveAs = sound.saveAs, formatId = sound.id)
            }
        }
    }
    val picked = offered?.firstOrNull { it.chosen }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelLabel("FORMAT", Modifier.padding(bottom = 2.dp))

        // As usual: solid while it is what will happen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clickable(role = Role.RadioButton) {
                    choosing = false
                    if (asUsual) {
                        changingUsual = !changingUsual
                    } else {
                        onChange(options.copy(saveAs = usual.saveAs, formatId = null))
                    }
                }
                .then(if (asUsual) Modifier.background(Ink) else Modifier.border(2.dp, InkOnPaper))
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(text = "As usual", color = if (asUsual) Color.White else PaperText, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(text = usualLine, color = if (asUsual) InkMuted else PaperMuted, fontSize = 13.sp, lineHeight = 16.sp)
            }
            IconTap(
                icon = SpineIcons.Pencil,
                label = "Change the usual format",
                onClick = {
                    choosing = false
                    changingUsual = !changingUsual
                },
                tint = if (asUsual) Color.White else PaperText,
                size = 56.dp,
                iconSize = 20.dp,
            )
        }
        if (changingUsual) {
            for (kind in kinds) {
                ChoiceRow(
                    title = kind.title,
                    line = kind.line,
                    chosen = kind.chosen,
                    onClick = {
                        feel.done()
                        onChange(kind.take())
                        changingUsual = false
                    },
                )
            }
        }

        // Choose the format: everything this link offers.
        if (canChoose) {
            val ready = !offered.isNullOrEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .then(
                        if (ready) {
                            Modifier.clickable(role = Role.Button) {
                                changingUsual = false
                                choosing = !choosing
                            }
                        } else {
                            Modifier
                        },
                    )
                    .then(if (!asUsual) Modifier.background(Ink) else Modifier.border(2.dp, InkOnPaper))
                    .padding(start = 14.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(text = "Choose the format", color = if (!asUsual) Color.White else PaperText, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            picked != null -> listOf(picked.title, picked.value).filter { it.isNotEmpty() }.joinToString(" · ")
                            offered == null -> "Working out what this link offers"
                            offered.isEmpty() -> "This link offers nothing to choose from"
                            else -> "Pick from the ${offered.size} this link offers"
                        },
                        color = if (!asUsual) InkMuted else PaperMuted,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                    )
                }
                when {
                    offered == null -> SpineLoader(height = 22.dp, base = if (asUsual) Ink else InkLine, label = "Working it out")
                    ready -> Icon(
                        imageVector = if (choosing) SpineIcons.ChevronDown else SpineIcons.ChevronRight,
                        contentDescription = null,
                        tint = if (!asUsual) Color.White else PaperText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (choosing && offered != null) {
                for (offer in offered) {
                    ChoiceRow(
                        title = offer.title,
                        line = offer.line,
                        chosen = offer.chosen,
                        value = offer.value,
                        onClick = {
                            feel.done()
                            onChange(offer.take())
                            choosing = false
                        },
                    )
                }
            }
        }
    }
}

/** One thing to pick from a short list: outlined, with a tick on the one that is chosen. */
@Composable
private fun ChoiceRow(title: String, line: String, chosen: Boolean, onClick: () -> Unit, value: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .border(2.dp, InkOnPaper)
            .padding(start = 14.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = title, color = PaperText, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(text = line, color = PaperMuted, fontSize = 13.sp, lineHeight = 16.sp)
        }
        if (value.isNotEmpty()) Text(text = value, color = PaperMuted, fontSize = 14.sp)
        if (chosen) Icon(SpineIcons.Check, contentDescription = "Chosen", tint = PaperText, modifier = Modifier.size(20.dp))
    }
}

/**
 * The heading over a link's songs when there are several: how many are ticked, and a way
 * to tick or untick them all.
 */
@Composable
fun SongPickerHead(count: Int, picked: Int, onAll: (tick: Boolean) -> Unit, modifier: Modifier = Modifier) {
    SectionHead(
        text = "GETTING $picked OF $count",
        action = if (picked > 0) "Untick all" else "Tick all",
        modifier = modifier,
    ) { onAll(picked == 0) }
}

/** One song of several: ticked and solid if it will be fetched, outlined if it is left out. */
@Composable
fun SongPickerRow(place: Int, song: Found, onPhone: Boolean, ticked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TidyRow(
        title = song.title,
        artist = listOfNotNull(
            place.toString(),
            song.artist.takeIf { it.isNotBlank() },
            formatDuration(song.durationMs).takeIf { song.durationMs > 0 },
            "on your phone".takeIf { onPhone },
        ).joinToString(" · "),
        ticked = ticked,
        modifier = modifier,
        onClick = onClick,
    )
}

/** The things that can be added to a download or left off. */
@Composable
fun ExtraChoices(options: GetOptions, onChange: (GetOptions) -> Unit, modifier: Modifier = Modifier) {
    val feel = rememberFeel()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelLabel("EXTRAS")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(text = "Cover", on = options.cover) {
                feel.switch(!options.cover)
                onChange(options.copy(cover = !options.cover))
            }
            // Only a video has anything to show them on.
            if (options.video) {
                Chip(text = "Subtitles", on = options.subtitles) {
                    feel.switch(!options.subtitles)
                    onChange(options.copy(subtitles = !options.subtitles))
                }
            }
        }
    }
}

/** A choice that is on or off: solid with a tick when on, outlined when off. */
@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .then(if (on) Modifier.background(Ink) else Modifier.border(1.dp, InkOnPaper))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (on) Icon(SpineIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text = text, color = if (on) Color.White else PaperText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The one button that starts it. */
@Composable
fun DownloadButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .background(Ink)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(SpineIcons.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text = text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * How a download is getting on, as a strip that fills with the play colour. [progress] is
 * from 0 to 1 across everything asked for.
 */
@Composable
fun DownloadProgress(asked: List<Getting>, modifier: Modifier = Modifier) {
    val done = asked.count { it.state == GetState.Done }
    val failed = asked.count { it.state == GetState.Failed }
    val busy = asked.any { it.busy }
    val progress = if (asked.isEmpty()) 0f else asked.sumOf {
        when (it.state) {
            GetState.Done, GetState.Finishing -> 1.0
            GetState.Downloading -> it.progress.toDouble()
            else -> 0.0
        }
    }.toFloat() / asked.size
    val words = when {
        asked.size > 1 && busy -> "$done of ${asked.size} on your phone"
        asked.size > 1 && failed > 0 -> "$done arrived, $failed did not"
        asked.size > 1 -> "All ${asked.size} on your phone"
        busy && progress <= 0f -> "Getting ready"
        asked.firstOrNull()?.state == GetState.Finishing -> "Saving"
        busy -> "Downloading"
        failed > 0 -> asked.first().problem ?: "The download did not work."
        asked.firstOrNull()?.options?.video == true -> "Saved to your videos"
        else -> "On your phone"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Ink)
            .risingFill(smoothProgress(progress))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = words,
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = if (failed > 0 && !busy && asked.size == 1) 14.sp else 17.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            // Before the first byte there is no level to show, so the loader says it is alive.
            busy && progress <= 0f -> SpineLoader(height = 26.dp, base = InkLine, label = "Getting ready")
            busy && progress > 0f -> Text(
                text = "${(progress * 100).toInt()}%",
                color = if (progress > 0.5f) OnPlayMuted else Color.White,
                fontSize = 15.sp,
            )
            !busy && failed == 0 -> Icon(SpineIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** A video that was downloaded: lying down like a song, with a play mark, since a tap plays it. */
@Composable
fun VideoRow(
    title: String,
    line: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
    /** How much of it has been watched, from 0 to 1: the play colour stands that high in the row. */
    watched: Float = 0f,
    /** On a dark screen the row needs the raised shade to stand out from it. */
    background: Color = Ink,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .pressable({ onClick() }, onLongPress)
            .background(background)
            .risingFill(smoothProgress(watched))
            .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(text = line, color = InkMuted, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(SpineIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Opens a saved video in the phone's own player. False if nothing could open it, such as when the file has gone. */
fun watch(context: Context, uri: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

/** Hands a downloaded file to another app through Android's Share list. */
fun shareFile(context: Context, uri: Uri, mime: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The receiving app is let in to read this one file.
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Puts [text] on the clipboard, such as a link or an error to pass on. */
fun copyText(context: Context, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Spine+", text))
    }
}

/** Opens a web page in the phone's browser, or in the app that owns it. */
fun openPage(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
