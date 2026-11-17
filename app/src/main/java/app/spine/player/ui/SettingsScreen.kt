package app.spine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.PaperShades
import app.spine.player.ui.theme.Play
import app.spine.player.ui.theme.ThemeChoice
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.playback.AudioSettings
import app.spine.player.playback.EqSettings
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.SpineSwitch
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PlayColour
import app.spine.player.ui.theme.ShelfColour

/** The pages Settings is split into. Permissions and About are screens of their own. */
enum class SettingsPage(val title: String) {
    Look("LOOK"),
    NowPlaying("NOW PLAYING"),
    Playback("PLAYBACK"),
    Library("LIBRARY"),
}

/**
 * Settings: the user's name, then one box for each kind of setting, each saying in a line
 * how things stand. A box opens its own page of plain rows: a setting should be quick to
 * find and quick to read, not clever. [page] is the page on show, null for the boxes.
 */
@Composable
fun SettingsScreen(
    page: SettingsPage?,
    onPageChange: (SettingsPage?) -> Unit,
    /** What the user asked to be called, or empty, and the way to change it. */
    name: String,
    onChangeName: () -> Unit,
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    eq: EqSettings,
    fadeSeconds: Int,
    normalize: Boolean,
    videoKeepsPlaying: Boolean,
    onVideoKeepsPlayingChange: (Boolean) -> Unit,
    songsInLibrary: Int,
    thingsToFix: Int,
    onOpenFix: () -> Unit,
    startTab: ShelfTab,
    showEqualizer: Boolean,
    showSleep: Boolean,
    sleepLabel: String?,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleep: () -> Unit,
    onFadeChange: (seconds: Int) -> Unit,
    onNormalizeChange: (Boolean) -> Unit,
    onChooseStartTab: () -> Unit,
    playColour: PlayColour,
    shelfColour: ShelfColour,
    onChoosePlayColour: () -> Unit,
    onChooseShelfColour: () -> Unit,
    onShowEqualizerChange: (Boolean) -> Unit,
    onShowSleepChange: (Boolean) -> Unit,
    showLyrics: Boolean,
    onShowLyricsChange: (Boolean) -> Unit,
    animations: Boolean,
    onAnimationsChange: (Boolean) -> Unit,
    vibration: Boolean,
    onVibrationChange: (Boolean) -> Unit,
    onRescan: () -> Unit,
    /** "All allowed" or how many are not, and the way to the list of them. */
    permissionsSummary: String,
    onOpenPermissions: () -> Unit,
    /** The app's version, and the way to About. */
    version: String,
    onOpenAbout: () -> Unit,
) {
    val feel = rememberFeel()
    // Tapping anywhere on a row flips its switch, with the same feel as the switch itself.
    fun flip(now: Boolean, change: (Boolean) -> Unit): () -> Unit = {
        // Change first: turning vibration on should be felt, turning it off should not.
        change(!now)
        feel.switch(!now)
    }
    // Back from a page goes to the boxes, not out of Settings.
    BackHandler(enabled = page != null) { onPageChange(null) }
    val scroll = rememberScrollState()
    // Each page starts at its top.
    LaunchedEffect(page) { scroll.scrollTo(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .verticalScroll(scroll)
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        BackLink(
            text = if (page == null) "Shelf" else "Settings",
            onClick = { if (page == null) onBack() else onPageChange(null) },
            color = PaperText,
        )
        Text(
            text = page?.title ?: "SETTINGS",
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 46.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        )

        when (page) {
            null -> {
                NameBox(name = name, onClick = onChangeName)
                val shown = listOfNotNull(
                    "Lyrics".takeIf { showLyrics },
                    "Equalizer".takeIf { showEqualizer },
                    "Sleep timer".takeIf { showSleep },
                )
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxRow {
                        SettingsBox(
                            title = "LOOK",
                            state = "${theme.label} · ${playColour.label} · opens on ${startTab.label}",
                            onClick = { onPageChange(SettingsPage.Look) },
                        )
                        SettingsBox(
                            title = "NOW PLAYING",
                            state = if (shown.isEmpty()) "Nothing extra shown" else shown.joinToString(", ") + " shown",
                            onClick = { onPageChange(SettingsPage.NowPlaying) },
                        )
                    }
                    BoxRow {
                        SettingsBox(
                            title = "PLAYBACK",
                            state = listOf(
                                if (fadeSeconds == 0) "No fade" else "Fade $fadeSeconds s",
                                if (eq.enabled) "equalizer ${eq.preset.label}" else "equalizer off",
                            ).joinToString(" · "),
                            onClick = { onPageChange(SettingsPage.Playback) },
                        )
                        SettingsBox(
                            title = "LIBRARY",
                            state = songCount(songsInLibrary) + " · " + if (thingsToFix > 0) "$thingsToFix to fix" else "all in order",
                            onClick = { onPageChange(SettingsPage.Library) },
                        )
                    }
                    BoxRow {
                        SettingsBox(title = "PERMISSIONS", state = permissionsSummary, onClick = onOpenPermissions)
                        SettingsBox(title = "ABOUT", state = "Version $version", onClick = onOpenAbout)
                    }
                }
            }

            SettingsPage.Look -> {
                ThemeChooser(theme = theme, onChange = onThemeChange)
                SettingRow(
                    label = "Open on",
                    caption = "The Shelf tab you see first when the app starts.",
                    value = startTab.label,
                    onClick = onChooseStartTab,
                )
                SettingRow(
                    label = "Play colour",
                    caption = "The colour that rises as you play.",
                    value = playColour.label,
                    onClick = onChoosePlayColour,
                    trailing = { ColourSquare(playColour.colour) },
                )
                SettingRow(
                    label = "Shelf colour",
                    caption = "The colour of albums, songs and the dark screens.",
                    value = shelfColour.label,
                    onClick = onChooseShelfColour,
                    trailing = { ColourSquare(shelfColour.colour) },
                )
                SettingRow(
                    label = "Animations",
                    caption = "Screens, lists and colours move into place. Switch off to have everything appear at once.",
                    onClick = flip(animations, onAnimationsChange),
                    trailing = {
                        SpineSwitch(
                            checked = animations,
                            onCheckedChange = onAnimationsChange,
                            label = "Animations",
                            onInk = false,
                        )
                    },
                )
                SettingRow(
                    label = "Vibration",
                    caption = "Ticks and clicks under your finger as you tap, drag and switch things.",
                    onClick = flip(vibration, onVibrationChange),
                    trailing = {
                        SpineSwitch(
                            checked = vibration,
                            onCheckedChange = onVibrationChange,
                            label = "Vibration",
                            onInk = false,
                        )
                    },
                )
            }

            SettingsPage.NowPlaying -> {
                SettingRow(
                    label = "Lyrics on Now playing",
                    caption = "Switch off for a cleaner screen.",
                    onClick = flip(showLyrics, onShowLyricsChange),
                    trailing = {
                        SpineSwitch(
                            checked = showLyrics,
                            onCheckedChange = onShowLyricsChange,
                            label = "Show Lyrics on Now playing",
                            onInk = false,
                        )
                    },
                )
                SettingRow(
                    label = "Equalizer on Now playing",
                    caption = "Switch off for a cleaner screen. It stays here in Settings.",
                    onClick = flip(showEqualizer, onShowEqualizerChange),
                    trailing = {
                        SpineSwitch(
                            checked = showEqualizer,
                            onCheckedChange = onShowEqualizerChange,
                            label = "Show Equalizer on Now playing",
                            onInk = false,
                        )
                    },
                )
                SettingRow(
                    label = "Sleep timer on Now playing",
                    caption = "Switch off for a cleaner screen. It stays here in Settings.",
                    onClick = flip(showSleep, onShowSleepChange),
                    trailing = {
                        SpineSwitch(
                            checked = showSleep,
                            onCheckedChange = onShowSleepChange,
                            label = "Show Sleep timer on Now playing",
                            onInk = false,
                        )
                    },
                )
            }

            SettingsPage.Playback -> {
                SettingRow(
                    label = "Equalizer",
                    value = if (eq.enabled) eq.preset.label else "Off",
                    onClick = onOpenEqualizer,
                )
                SettingRow(
                    label = "Sleep timer",
                    value = sleepLabel ?: "Off",
                    onClick = onOpenSleep,
                )
                SettingRow(
                    label = "Fade between songs",
                    caption = "Each song fades out and the next fades in.",
                    value = if (fadeSeconds == 0) "Off" else "$fadeSeconds seconds",
                    // Each tap moves to the next length, then back to off.
                    onClick = {
                        val choices = AudioSettings.FADE_CHOICES
                        feel.step()
                        onFadeChange(choices[(choices.indexOf(fadeSeconds) + 1) % choices.size])
                    },
                )
                SettingRow(
                    label = "Even out volume",
                    caption = "Turns loud songs down, for files that carry a volume tag.",
                    onClick = flip(normalize, onNormalizeChange),
                    trailing = {
                        SpineSwitch(
                            checked = normalize,
                            onCheckedChange = onNormalizeChange,
                            label = "Even out volume",
                            onInk = false,
                        )
                    },
                )
                SettingRow(
                    label = "Videos keep playing",
                    caption = "When you leave a video its sound carries on, like a song. Switch off to have it pause.",
                    onClick = flip(videoKeepsPlaying, onVideoKeepsPlayingChange),
                    trailing = {
                        SpineSwitch(
                            checked = videoKeepsPlaying,
                            onCheckedChange = onVideoKeepsPlayingChange,
                            label = "Videos keep playing when you leave them",
                            onInk = false,
                        )
                    },
                )
                SettingRow(
                    label = "Gapless playback",
                    caption = "Songs that run into each other play without a pause.",
                    value = "Always on",
                )
            }

            SettingsPage.Library -> {
                SettingRow(
                    label = "Fix your library",
                    caption = "Tidy names, remove duplicates, sort artists and albums.",
                    value = if (thingsToFix > 0) "$thingsToFix to fix" else "All in order",
                    onClick = onOpenFix,
                )
                SettingRow(
                    label = "Rescan library",
                    caption = "Looks again for music on this phone.",
                    value = songCount(songsInLibrary),
                    onClick = onRescan,
                )
            }
        }
    }
}

/** The user's name as the first thing in Settings, outlined, with the way to change it. */
@Composable
private fun NameBox(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .border(2.dp, PaperText)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (name.isEmpty()) "Add your name, and the Shelf and your stats carry it" else "Your name, on the Shelf and on your stats",
                color = PaperMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Text(
                text = if (name.isEmpty()) "NO NAME YET" else name.uppercase(),
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = 30.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = if (name.isEmpty()) "Add" else "Change", color = PaperText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Two boxes side by side, both as tall as the taller needs to be. */
@Composable
private fun BoxRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One kind of setting as a square block: how things stand in a line, and its name large under it. */
@Composable
private fun RowScope.SettingsBox(title: String, state: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .defaultMinSize(minHeight = 124.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .background(Ink)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = state,
            modifier = Modifier.padding(bottom = 10.dp),
            color = InkMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        // A line for each word and no more, the type shrinking to fit: on a narrow phone a
        // long word gets smaller instead of breaking in two.
        BasicText(
            text = title,
            style = TextStyle(
                color = Color.White,
                fontFamily = BarlowCondensed,
                fontSize = 30.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = title.count { it == ' ' } + 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp, stepSize = 1.sp),
        )
    }
}

/** "Light or dark": three small pictures of the Shelf, the chosen one ringed in the play colour. */
@Composable
private fun ThemeChooser(theme: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    val feel = rememberFeel()
    Text(
        text = "LIGHT OR DARK",
        modifier = Modifier.padding(bottom = 6.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (choice in ThemeChoice.entries) {
            val chosen = choice == theme
            Column(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = chosen, role = Role.RadioButton) {
                        feel.step()
                        onChange(choice)
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The pictures are of light and dark themselves, so their colours are fixed.
                val light = PaperShades.Light.paper
                val dark = PaperShades.Dark.paper
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .drawBehind {
                            when (choice) {
                                ThemeChoice.Light -> drawRect(light)
                                ThemeChoice.Dark -> drawRect(dark)
                                ThemeChoice.Phone -> {
                                    drawRect(light, size = Size(size.width / 2, size.height))
                                    drawRect(dark, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                                }
                            }
                            // Three spines standing on the page.
                            val widths = listOf(8.dp.toPx(), 12.dp.toPx(), 8.dp.toPx())
                            var x = 10.dp.toPx()
                            for (width in widths) {
                                val onDark = choice == ThemeChoice.Dark || (choice == ThemeChoice.Phone && x >= size.width / 2)
                                drawRect(
                                    if (onDark) Color(0xFF2A2A30) else Color(0xFF111113),
                                    Offset(x, 10.dp.toPx()),
                                    Size(width, size.height - 20.dp.toPx()),
                                )
                                x += width + 2.dp.toPx()
                                // On the split picture the third spine stands on the dark half.
                                if (choice == ThemeChoice.Phone && width == widths[1]) x = size.width / 2 + 10.dp.toPx()
                            }
                        }
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) Play else Hairline),
                )
                Text(
                    text = choice.label,
                    color = PaperText,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
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

/** The colour a setting is on, as a small square beside its name. */
@Composable
private fun ColourSquare(colour: Color) {
    val shown by animateColorAsState(colour, tween(300), label = "colour square")
    // The thin edge keeps a dark colour visible on dark paper.
    Box(Modifier.size(28.dp).background(shown).border(1.dp, Hairline))
}

/** One setting: its name, an optional line of explanation, and its value or a switch at the right. */
@Composable
private fun SettingRow(
    label: String,
    caption: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
            if (caption != null) {
                Text(text = caption, color = PaperMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
        if (value != null) {
            Text(text = value, color = PaperMuted, fontSize = 15.sp)
        }
        trailing?.invoke()
    }
}
