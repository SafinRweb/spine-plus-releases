package app.spine.player.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.PaperText

/**
 * A screen's title with the user's name on it: "SAFIN'S SHELF", or "SHELF" with no name.
 * It is always one line: a long name makes the type smaller, down to a size that still
 * reads as a title, and past that the end is cut with dots.
 */
@Composable
fun OwnedTitle(name: String, word: String, modifier: Modifier = Modifier) {
    BasicText(
        text = owned(name, word).uppercase(),
        modifier = modifier,
        style = TextStyle(
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 46.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 46.sp, stepSize = 2.sp),
    )
}
