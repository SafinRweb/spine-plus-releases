package app.spine.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.spine.player.ui.DemoMode
import androidx.compose.ui.platform.LocalHapticFeedback
import app.spine.player.ui.UiSettings
import app.spine.player.ui.components.LocalAnimations
import app.spine.player.ui.components.PressIn
import app.spine.player.ui.components.SwitchableHaptics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.spine.player.R

/** Browsing screens: the "paper" the shelf sits on, off-white in light and near-black in dark. */
val Paper: Color get() = Palette.paper.paper

/** Text, icons and thin rules on paper. Whatever the shelf colour is, it is the paper's opposite. */
val PaperText: Color get() = Palette.paper.text

/** Spines, strips and the screens inside the music: the user's shelf colour. */
val Ink: Color get() = Palette.shelf.ink

/**
 * The shelf colour where it is drawn as a line on paper: the outline of an empty box, of a
 * button, of a song that is not there yet. On light paper that is the shelf colour itself;
 * on dark paper the shelf colour would be lost, so its lighter outline shade is used.
 */
val InkOnPaper: Color get() = if (Palette.dark) Palette.shelf.line else Palette.shelf.ink

/** The play colour: whatever has been played fills with this. The user picks it. */
val Play: Color get() = Palette.play

/** Secondary text on paper. */
val PaperMuted: Color get() = Palette.paper.muted

/** Secondary text on ink. */
val InkMuted: Color get() = Palette.shelf.muted

/** Secondary text that may sit on the play colour. */
val OnPlayMuted: Color get() = lerp(Palette.play, Color.White, 0.873f)

/** Outlines of empty placeholders on paper. */
val GhostLine: Color get() = Palette.paper.ghost

/** Strips and spines that sit on ink screens. */
val InkRaised: Color get() = Palette.shelf.raised

/** Outlines on ink screens. */
val InkLine: Color get() = Palette.shelf.line

/** Row dividers on paper. */
val Hairline: Color get() = Palette.paper.hairline

/** Row dividers on ink. */
val InkHairline: Color get() = Palette.shelf.hairline

/** Destructive actions, such as deleting a file: one shade for each kind of background. */
val DangerOnPaper: Color get() = Palette.paper.danger
val DangerOnInk = Color(0xFFFF7A6E)

/** Everything that is read at normal size: names, labels, buttons. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

/** The narrow cut for anything large or sideways: spines, screen titles, the timer. */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** Material's type scale with Barlow in every slot, so plain Text picks it up by default. */
private val SpineTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Barlow),
        displayMedium = displayMedium.copy(fontFamily = Barlow),
        displaySmall = displaySmall.copy(fontFamily = Barlow),
        headlineLarge = headlineLarge.copy(fontFamily = Barlow),
        headlineMedium = headlineMedium.copy(fontFamily = Barlow),
        headlineSmall = headlineSmall.copy(fontFamily = Barlow),
        titleLarge = titleLarge.copy(fontFamily = Barlow),
        titleMedium = titleMedium.copy(fontFamily = Barlow),
        titleSmall = titleSmall.copy(fontFamily = Barlow),
        bodyLarge = bodyLarge.copy(fontFamily = Barlow),
        bodyMedium = bodyMedium.copy(fontFamily = Barlow),
        bodySmall = bodySmall.copy(fontFamily = Barlow),
        labelLarge = labelLarge.copy(fontFamily = Barlow),
        labelMedium = labelMedium.copy(fontFamily = Barlow),
        labelSmall = labelSmall.copy(fontFamily = Barlow),
    )
}

/** Material's colours, for the few places its own parts are drawn; they follow the paper. */
private val SpineColors
    get() = lightColorScheme(
        primary = Palette.play,
        onPrimary = Color.White,
        background = Paper,
        onBackground = PaperText,
        surface = Paper,
        onSurface = PaperText,
        onSurfaceVariant = PaperMuted,
    )

/**
 * The largest text the app ever follows the phone to: one and a half times the usual size.
 * See [textLimit] for why there is a limit, and why a narrow phone gets a lower one.
 */
const val MAX_TEXT_SCALE = 1.5f

/**
 * How large the phone's text setting may make the app's text on a screen [widthDp] wide.
 *
 * The app's blocks have set sizes that mean something (a song's strip is as tall as the
 * song is long, a spine as wide as its album), and its type is large to begin with. Text
 * can grow until it fills the room the blocks leave it; past that, buttons break in two
 * and names are cut. How much room there is depends on the width: tried on the phone at
 * 320, 360 and 460 wide, every screen kept its shape up to about the width divided by 300.
 * So a small phone follows the setting a little way, a large one up to [MAX_TEXT_SCALE].
 */
fun textLimit(widthDp: Float): Float = (widthDp / 300f).coerceIn(1f, MAX_TEXT_SCALE)

/**
 * The sizes everything is drawn at: the phone's own, with its text size held to
 * [textLimit]. A debug build taking screenshots can ask for another text size or a
 * narrower screen, to see a screen as another phone would show it.
 */
@Composable
private fun shownDensity(): Density {
    val phone = LocalDensity.current
    val widthPx = LocalContext.current.resources.displayMetrics.widthPixels
    val pixelsPerDp = DemoMode.widthDp?.let { widthPx.toFloat() / it } ?: phone.density
    val scale = DemoMode.fontScale ?: phone.fontScale
    val limit = textLimit(widthPx / pixelsPerDp)
    // Left as the phone gave it when nothing changes, since Android's own object also
    // knows how to grow large text less than small text.
    return if (DemoMode.widthDp == null && DemoMode.fontScale == null && scale <= limit) {
        phone
    } else {
        Density(pixelsPerDp, scale.coerceAtMost(limit))
    }
}

@Composable
fun SpineTheme(content: @Composable () -> Unit) {
    // Read before anything is drawn: this is also what puts the saved colours in the Palette.
    val settings = UiSettings.get(LocalContext.current)
    val playColour by settings.playColour.collectAsState()
    val shelfColour by settings.shelfColour.collectAsState()
    val theme by settings.theme.collectAsState()
    val dark = DemoMode.dark ?: when (theme) {
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
        ThemeChoice.Phone -> isSystemInDarkTheme()
    }
    // A newly picked colour, or light and dark changing places, blends in across the whole app.
    LaunchedEffect(playColour, shelfColour, dark) { Palette.moveTo(playColour, shelfColour, dark) }
    MaterialTheme(colorScheme = SpineColors, typography = SpineTypography) {
        // Vibration goes through one gate, so the switch in Settings silences all of it,
        // including what Compose does by itself on a long press.
        val phone = LocalHapticFeedback.current
        val haptics = remember(phone, settings) { SwitchableHaptics(phone) { settings.vibration.value } }
        CompositionLocalProvider(
            LocalDensity provides shownDensity(),
            // Every tap in the app presses in and springs back instead of flashing grey.
            LocalIndication provides PressIn,
            LocalHapticFeedback provides haptics,
            LocalAnimations provides settings.animations.collectAsState().value,
            content = content,
        )
    }
}
