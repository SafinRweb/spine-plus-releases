package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.spine.player.download.Engine
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.update.UpdateState
import app.spine.player.update.Updater

/** One version of the app and what it brought, for "Recent updates". */
class Release(val version: String, val date: String, val notes: List<String>)

/**
 * What each version brought, newest first. This is kept in the app, so it can be read with
 * no connection. Add to the top when the version number goes up.
 */
val Releases = listOf(
    Release(
        version = "1.2.0",
        date = "17 November 2026",
        notes = listOf(
            "Videos play inside the app, upright or sideways. Swipe the picture sideways to move through it, up or down for volume.",
            "Playlists and the queue can hold videos beside songs. A new playlist lets you tick songs, videos or both, with a search box.",
            "Stats for videos and for downloads, and Save as an image: one picture, shaped for a story.",
            "Your name on the Shelf and on your stats. Change it in Settings.",
            "Settings is in boxes, and there is a dark mode.",
            "Swipe left or right on the Shelf to change tab.",
            "Other apps can open songs and videos with Spine+.",
            "Large text on the phone no longer breaks the screens.",
        ),
    ),
    Release(
        version = "1.1.1",
        date = "16 November 2026",
        notes = listOf(
            "Spine+ updates itself from now on: when a new version is out, it tells you what is new and installs it.",
            "Settings, About has Check for updates.",
        ),
    ),
    Release(
        version = "1.1.0",
        date = "16 November 2026",
        notes = listOf(
            "Downloader: find a song by name, or paste or share a link, and it lands on the Shelf.",
            "Choose the format, pick songs out of a playlist, and save videos as well as songs.",
            "Videos you download have a tab of their own.",
            "Tabs can be put in your own order: hold one and drag it.",
            "The notification opens Now playing, and the playing strip shows inside albums and playlists.",
            "Permissions has its own page in Settings.",
        ),
    ),
    Release(
        version = "1.0.0",
        date = "14 November 2026",
        notes = listOf("The first version: the Shelf, albums, playlists, lyrics, the equalizer and Fix your library."),
    ),
)

/** A piece of free software the app is built on: its name, what it does here, and its licence. */
private class Part(val name: String, val does: String, val licence: String)

private val Parts = listOf(
    Part("yt-dlp", "Finds and downloads the music.", "The Unlicense"),
    Part("youtubedl-android", "Packs yt-dlp for Android.", "GNU GPL, version 3"),
    Part("FFmpeg", "Takes the sound out of a download and joins picture and sound.", "GNU LGPL and GPL"),
    Part("Python", "Runs yt-dlp.", "Python Software Foundation License"),
    Part("QuickJS", "Solves the puzzle YouTube sets before a download.", "MIT License"),
    Part("Mutagen", "Writes names and covers into downloaded files.", "GNU GPL, version 2 or later"),
    Part("AndroidX Media3", "Plays the music.", "Apache License 2.0"),
    Part("Jetpack Compose", "Draws every screen.", "Apache License 2.0"),
    Part("TagLib", "Reads and writes a song's details.", "GNU LGPL 2.1 and MPL 1.1"),
    Part("Barlow", "The typeface.", "SIL Open Font License 1.1"),
)

/**
 * Settings, About: which version this is, whether a newer one is out, what the last
 * versions brought, the download engine's version, and the way to the licences.
 * [onShowUpdate] opens the popup about a newer version that has been found.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicences: () -> Unit, onShowUpdate: () -> Unit) {
    val context = LocalContext.current
    val update by Updater.state.collectAsStateWithLifecycle()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val engine = remember { Engine.version(context) }
    val name = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackLink(text = "Settings", onClick = onBack, color = PaperText)
        Heading("ABOUT")

        // The app's name and version, as a block of its own.
        Row(
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(Ink)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name.uppercase(),
                    color = Color.White,
                    fontFamily = BarlowCondensed,
                    fontSize = 44.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = "Version $version", color = InkMuted, fontSize = 14.sp, lineHeight = 18.sp)
            }
            Text(text = "Growix Studio", color = InkMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }

        val look: () -> Unit = { Updater.check(context) }
        when (val state = update) {
            UpdateState.NotSetUp -> AboutRow(
                label = "Updates",
                caption = "This build has nowhere to ask for a newer version yet.",
                value = "Not set up",
            )
            UpdateState.Idle -> AboutRow(
                label = "Check for updates",
                caption = "Looks for a newer version of $name and offers to install it.",
                onClick = look,
            )
            UpdateState.Checking -> AboutRow(label = "Check for updates", caption = "Looking for a newer version.", value = "Looking")
            UpdateState.UpToDate -> AboutRow(
                label = "Check for updates",
                caption = "You have the newest version. Tap to look again.",
                value = "Up to date",
                onClick = look,
            )
            is UpdateState.Available -> AboutRow(
                label = "Update to ${state.offer.version}",
                caption = "A newer version is out. Tap to see what is new.",
                onClick = onShowUpdate,
            )
            is UpdateState.Downloading -> AboutRow(
                label = "Update to ${state.offer.version}",
                caption = "Downloading it now.",
                value = "${(state.progress * 100).toInt()}%",
                onClick = onShowUpdate,
            )
            is UpdateState.Ready -> AboutRow(
                label = "Update to ${state.offer.version}",
                caption = "Downloaded. Tap to install it.",
                value = "Ready",
                onClick = onShowUpdate,
            )
            is UpdateState.Failed -> AboutRow(
                label = "Check for updates",
                caption = state.words,
                // A download that failed is tried again from the popup; a look that failed, from here.
                onClick = if (state.offer != null) onShowUpdate else look,
            )
        }

        AboutRow(
            label = "Download engine",
            caption = "yt-dlp. It keeps itself up to date, so links go on working.",
            value = engine ?: "As it came",
        )

        GroupLabel("RECENT UPDATES")
        for (release in Releases) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val thickness = 1.dp.toPx()
                        drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
                    }
                    .padding(top = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "${release.version} · ${release.date}",
                    color = PaperText,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                for (note in release.notes) {
                    Text(text = note, color = PaperMuted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
        }

        GroupLabel("MORE")
        AboutRow(label = "Open-source licences", caption = "The free software this app is built on.", onClick = onOpenLicences)
    }
}

/** The free software the app is built on, each with what it does here and its licence. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackLink(text = "About", onClick = onBack, color = PaperText)
        Heading("LICENCES")
        Text(
            text = "This app is built on free software. Because its download engine is under the GNU General " +
                "Public License, the app as a whole is released under that licence, version 3.",
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            color = PaperMuted,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        for (part in Parts) {
            AboutRow(label = part.name, caption = part.does, value = part.licence)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp),
        color = PaperText,
        fontFamily = BarlowCondensed,
        fontSize = 46.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 24.dp, bottom = 2.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

/** One line of About: a name, a line under it, and a value or a way further at the right. */
@Composable
private fun AboutRow(label: String, caption: String, value: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind {
                val thickness = 1.dp.toPx()
                drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, color = PaperText, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(text = caption, color = PaperMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.weight(0.8f, fill = false),
                color = PaperMuted,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            )
        }
        if (onClick != null) {
            Icon(SpineIcons.ChevronRight, contentDescription = null, tint = PaperMuted, modifier = Modifier.size(16.dp))
        }
    }
}
