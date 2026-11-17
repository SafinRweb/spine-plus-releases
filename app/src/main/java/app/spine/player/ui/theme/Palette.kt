package app.spine.player.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The colours "Play colour" offers. Each is dark enough for white text to sit on it. */
enum class PlayColour(val label: String, val colour: Color) {
    Blue("Blue", Color(0xFF2B4BFF)),
    Violet("Violet", Color(0xFF7C3AED)),
    Pink("Pink", Color(0xFFD6336C)),
    Red("Red", Color(0xFFC92A2A)),
    Orange("Orange", Color(0xFFC2410C)),
    Green("Green", Color(0xFF15803D)),
    Teal("Teal", Color(0xFF0F766E)),
}

/** A shelf colour and the shades that go with it on a dark screen. */
data class ShelfShades(
    /** Spines, strips and the dark screens themselves. */
    val ink: Color,
    /** Strips and spines that sit on a dark screen. */
    val raised: Color,
    /** Row dividers on a dark screen. */
    val hairline: Color,
    /** Outlines on a dark screen. */
    val line: Color,
    /** Secondary text on a dark screen. */
    val muted: Color,
) {
    fun towards(other: ShelfShades, fraction: Float) = ShelfShades(
        lerp(ink, other.ink, fraction),
        lerp(raised, other.raised, fraction),
        lerp(hairline, other.hairline, fraction),
        lerp(line, other.line, fraction),
        lerp(muted, other.muted, fraction),
    )
}

/** The shades for a dark colour, each a step from it towards white. */
private fun shadesOf(ink: Color) = ShelfShades(
    ink = ink,
    raised = lerp(ink, Color.White, 0.06f),
    hairline = lerp(ink, Color.White, 0.12f),
    line = lerp(ink, Color.White, 0.40f),
    muted = lerp(ink, Color.White, 0.64f),
)

/** The browsing screens' colours: the page itself and what is written on it. */
data class PaperShades(
    /** The page the shelf sits on. */
    val paper: Color,
    /** Text, icons and thin rules on the page. */
    val text: Color,
    /** Secondary text on the page. */
    val muted: Color,
    /** Outlines of empty placeholders on the page. */
    val ghost: Color,
    /** Row dividers on the page. */
    val hairline: Color,
    /** Destructive actions on the page. */
    val danger: Color,
) {
    fun towards(other: PaperShades, fraction: Float) = PaperShades(
        lerp(paper, other.paper, fraction),
        lerp(text, other.text, fraction),
        lerp(muted, other.muted, fraction),
        lerp(ghost, other.ghost, fraction),
        lerp(hairline, other.hairline, fraction),
        lerp(danger, other.danger, fraction),
    )

    companion object {
        /** Off-white paper with near-black writing. */
        val Light = PaperShades(
            paper = Color(0xFFF0F0EE),
            text = Color(0xFF111113),
            muted = Color(0xFF5C5C60),
            ghost = Color(0xFFA6A6A2),
            hairline = Color(0xFFC6C6C2),
            danger = Color(0xFFB3261E),
        )

        /** Near-black paper with off-white writing. */
        val Dark = PaperShades(
            paper = Color(0xFF0B0B0D),
            text = Color(0xFFF0F0EE),
            muted = Color(0xFFA9A9AE),
            ghost = Color(0xFF55555B),
            hairline = Color(0xFF34343A),
            danger = Color(0xFFFF7A6E),
        )
    }
}

/** "Light or dark": what the user can pick, the last being whatever the phone itself is set to. */
enum class ThemeChoice(val label: String) {
    Light("Light"),
    Dark("Dark"),
    Phone("As the phone is"),
}

/** The colours "Shelf colour" offers. All are dark: white text sits on every one. */
enum class ShelfColour(val label: String, val shades: ShelfShades) {
    Ink(
        "Ink",
        ShelfShades(
            ink = Color(0xFF111113),
            raised = Color(0xFF1F1F23),
            hairline = Color(0xFF2E2E33),
            line = Color(0xFF6E6E76),
            muted = Color(0xFFA9A9AE),
        ),
    ),
    Slate("Slate", shadesOf(Color(0xFF2F3640))),
    Navy("Navy", shadesOf(Color(0xFF14213D))),
    Forest("Forest", shadesOf(Color(0xFF1B3A2B))),
    Plum("Plum", shadesOf(Color(0xFF3B1F3B))),
    Oxblood("Oxblood", shadesOf(Color(0xFF4A1620))),
    Cocoa("Cocoa", shadesOf(Color(0xFF3A2A22)));

    val colour: Color get() = shades.ink

    /**
     * The shades to show: as they are on light paper, and a step lighter on dark paper.
     * A spine has to stand out from the page it is on, and the album it opens into is
     * the spine's own colour, so on dark paper the dark screens lift with it.
     */
    fun shadesOn(dark: Boolean): ShelfShades = if (dark) shadesOf(lerp(shades.ink, Color.White, 0.08f)) else shades
}

/**
 * The colours on screen right now. The names in Theme.kt ([Ink], [Play] and the rest) read
 * from here, so anything drawn with them follows the user's choice and redraws when it
 * changes.
 */
object Palette {

    var play by mutableStateOf(PlayColour.Blue.colour)
        private set

    var shelf by mutableStateOf(ShelfColour.Ink.shades)
        private set

    var paper by mutableStateOf(PaperShades.Light)
        private set

    /** Whether the browsing screens are dark. Read where a choice hangs on it, such as the status bar's icons. */
    var dark by mutableStateOf(false)
        private set

    /** Switches straight to the chosen colours: for when the app starts. */
    fun show(play: PlayColour, shelf: ShelfColour, dark: Boolean) {
        this.play = play.colour
        this.shelf = shelf.shadesOn(dark)
        this.paper = if (dark) PaperShades.Dark else PaperShades.Light
        this.dark = dark
    }

    /** Blends from whatever is showing to the chosen colours: for when the user picks one. */
    suspend fun moveTo(play: PlayColour, shelf: ShelfColour, dark: Boolean) {
        val fromPlay = this.play
        val fromShelf = this.shelf
        val fromPaper = this.paper
        val toShelf = shelf.shadesOn(dark)
        val toPaper = if (dark) PaperShades.Dark else PaperShades.Light
        if (fromPlay == play.colour && fromShelf == toShelf && fromPaper == toPaper) return
        // The status bar's icons change sides at the start, so they are never the page's own colour.
        this.dark = dark
        animate(0f, 1f, animationSpec = tween(450, easing = FastOutSlowInEasing)) { fraction, _ ->
            this.play = lerp(fromPlay, play.colour, fraction)
            this.shelf = fromShelf.towards(toShelf, fraction)
            this.paper = fromPaper.towards(toPaper, fraction)
        }
        show(play, shelf, dark)
    }
}
