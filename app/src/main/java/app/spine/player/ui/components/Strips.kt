package app.spine.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.Ref
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Song
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted

/** How a strip relates to the player. */
enum class StripState { Idle, Playing, Paused }

fun stripState(song: Song, nowPlaying: NowPlaying): StripState = when {
    song.id != nowPlaying.songId -> StripState.Idle
    nowPlaying.isPlaying -> StripState.Playing
    else -> StripState.Paused
}

/**
 * A song lying down: its name on up to two lines, a second line under it, and its length.
 * Taller for a longer song, and taller still if a long name needs the room.
 *
 * The loaded song fills with blue to [fill] and shows a pause or play mark, because
 * tapping it pauses or resumes. Long-press opens the song menu.
 */
@Composable
fun SongStrip(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
    secondLine: String? = song.artist,
    leading: String? = null,
    state: StripState = StripState.Idle,
    fill: Float = 0f,
    background: Color = Ink,
    /** True when [background] is the play colour, so the quiet text is tinted to stay readable. */
    onBlue: Boolean = false,
    minHeight: Dp = stripHeight(song.durationMs),
    /**
     * For a strip in a stack that plays upwards: how far the blue has risen through this
     * strip, 0 or less for none and 1 or more for full. It is read while drawing and takes
     * the place of [fill], so the blue flows through the stack instead of jumping.
     */
    level: (() -> Float)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val isCurrent = state != StripState.Idle
    // Only flips as the blue passes the middle of the strip, not on every frame.
    val covered by remember(level) { derivedStateOf { level != null && level() >= 0.5f } }
    val secondary = if (isCurrent || onBlue || covered) OnPlayMuted else InkMuted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .pressable({ onClick() }, onLongPress)
            .background(background)
            .then(
                if (level != null) Modifier.risingFill(fraction = level) else Modifier.risingFill(smoothProgress(fill)),
            )
            .padding(start = 14.dp, top = 8.dp, end = if (trailing != null) 0.dp else 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) {
            Text(text = leading, color = secondary, fontSize = 13.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondLine != null) {
                Text(
                    text = secondLine,
                    color = secondary,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Icon(
                imageVector = if (state == StripState.Playing) SpineIcons.Pause else SpineIcons.Play,
                contentDescription = if (state == StripState.Playing) "Playing, tap to pause" else "Paused, tap to play",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        if (song.isVideo) {
            Text(
                text = "VIDEO",
                modifier = Modifier
                    .border(1.dp, secondary)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = secondary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
        }
        Text(text = formatDuration(song.durationMs), color = secondary, fontSize = 13.sp)
        trailing?.invoke()
    }
}

/**
 * The loaded song as a strip along the bottom of the browsing screens, filling with blue
 * as it plays. Tapping it opens Now playing, which grows out of where this strip sits.
 */
@Composable
fun MiniPlayer(
    nowPlaying: NowPlaying,
    onOpen: (bounds: Rect?) -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    /** On a dark screen the strip needs the raised shade to stand out from it. */
    background: Color = Ink,
) {
    val coordinates = remember { Ref<LayoutCoordinates>() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .onGloballyPositioned { coordinates.value = it }
            .background(background)
            .risingFill(smoothProgress(nowPlaying.progress)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClickLabel = "Open now playing") {
                    onOpen(coordinates.value?.takeIf { it.isAttached }?.boundsInRoot())
                }
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = nowPlaying.title,
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A small mark says the strip is holding a video, which opens the video player.
                if (nowPlaying.isVideo) {
                    Text(
                        text = "VIDEO",
                        modifier = Modifier
                            .border(1.dp, OnPlayMuted)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        color = OnPlayMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                    )
                }
                Text(
                    text = nowPlaying.artist,
                    color = OnPlayMuted,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconTap(
            icon = if (nowPlaying.isPlaying) SpineIcons.Pause else SpineIcons.Play,
            label = if (nowPlaying.isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            tint = Color.White,
            size = 64.dp,
        )
    }
}
