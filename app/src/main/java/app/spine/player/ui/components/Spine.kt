package app.spine.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkOnPaper
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted

/**
 * An album standing on the shelf. The text runs up the spine: [subtitle] (the artist) in a
 * quieter colour, then [title]. A name too long for one line continues on a second line
 * beside the first, in smaller type. [fill] is how much of the album has played.
 *
 * The caller sets the height through [modifier].
 */
@Composable
fun Spine(
    title: String,
    width: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    fill: Float = 0f,
    background: Color = Ink,
    onClick: ((bounds: Rect?) -> Unit)? = null,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
) {
    val playing = fill > 0f
    val weight = if (playing) FontWeight.Bold else FontWeight.SemiBold
    // Keyed on the colour too, so the artist's name follows a change of play or shelf colour.
    val quiet = if (playing) OnPlayMuted else InkMuted
    val text = remember(title, subtitle, quiet) {
        buildAnnotatedString {
            if (subtitle != null) {
                withStyle(SpanStyle(color = quiet)) {
                    append(subtitle.uppercase())
                }
                append("   ")
            }
            append(title.uppercase())
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // One line gets large type; two lines must share the spine's width, so they are smaller.
    val oneLineSize = with(density) { (width * 0.46f).coerceIn(19.dp, 36.dp).toSp() }
    val twoLineSize = with(density) { ((width - 10.dp) / 2.3f).coerceIn(13.dp, 28.dp).toSp() }

    BoxWithConstraints(
        modifier = modifier
            .width(width)
            .then(if (onClick != null) Modifier.pressable(onClick, onLongPress) else Modifier)
            .background(background)
            .risingFill(smoothProgress(fill))
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val available = constraints.maxHeight
        val fitsOneLine = remember(text, oneLineSize, available) {
            measurer.measure(
                text = text,
                style = TextStyle(
                    fontFamily = BarlowCondensed,
                    fontSize = oneLineSize,
                    fontWeight = weight,
                    letterSpacing = 0.5.sp,
                ),
                softWrap = false,
                maxLines = 1,
            ).size.width <= available
        }
        val fontSize = if (fitsOneLine) oneLineSize else twoLineSize
        Text(
            text = text,
            modifier = Modifier.rotateVertically(),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = fontSize,
            lineHeight = fontSize * 1.1f,
            fontWeight = weight,
            letterSpacing = 0.5.sp,
            maxLines = if (fitsOneLine) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A record-shop divider: an outlined tab with the artist's name, standing a little taller
 * than the albums filed behind it. The caller sets the height through [modifier].
 */
@Composable
fun ArtistDivider(
    name: String,
    count: Int,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    onClick: ((bounds: Rect?) -> Unit)? = null,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
) {
    val fontSize = with(LocalDensity.current) { (width * 0.5f).coerceIn(20.dp, 36.dp).toSp() }
    Box(
        modifier = modifier
            .width(width)
            .then(if (onClick != null) Modifier.pressable(onClick, onLongPress) else Modifier)
            .border(2.dp, InkOnPaper)
            .padding(vertical = 12.dp),
    ) {
        if (count > 0) {
            Text(
                text = count.toString(),
                modifier = Modifier.align(Alignment.TopCenter),
                color = PaperText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = name.uppercase(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = 24.dp)
                .rotateVertically(),
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
