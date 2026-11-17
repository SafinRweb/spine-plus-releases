package app.spine.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.spine.player.playback.NowPlaying
import app.spine.player.playback.RepeatSetting
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.components.rotateVertically
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Play

/** What identifies the song on show; a change of this is what the titles animate on. */
private data class SongHeading(val songId: Long?, val title: String, val artist: String, val album: String)

/** Room the controls at the bottom need; the timer never drops below this. */
private val ControlsHeight = 250.dp

/** A text link in the top corner of Now playing. [detail] is quiet text after it, such as time left. */
class NowPlayingLink(val label: String, val detail: String? = null, val onClick: () -> Unit)

/** The timer's own height, with the gap that keeps it off the blue's surface. */
private val TimerHeight = 64.dp

/**
 * The whole screen is the song: blue fills it from the bottom as the song plays, and
 * dragging up or down anywhere moves the level, which seeks.
 */
@Composable
fun NowPlayingScreen(
    nowPlaying: NowPlaying,
    showSeekHint: Boolean,
    onBack: () -> Unit,
    links: List<NowPlayingLink>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    onSeekUsed: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
) {
    // The blue level has three sources, in this order of priority:
    //  1. the finger, while it is down;
    //  2. the spot the finger let go at, until the player confirms it has jumped there;
    //  3. the player's own position, glided between its reports.
    // Without step 2 the level would snap back to the old position for a moment after
    // every seek, which showed up as a glitch on fast drags.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var seekTarget by remember { mutableStateOf<Float?>(null) }
    val level = remember { Animatable(nowPlaying.progress) }
    val scope = rememberCoroutineScope()
    val feel = rememberFeel()

    val durationMs by rememberUpdatedState(nowPlaying.durationMs)
    val seek by rememberUpdatedState(onSeek)
    val seekUsed by rememberUpdatedState(onSeekUsed)

    LaunchedEffect(nowPlaying.progress, dragFraction == null, seekTarget) {
        if (dragFraction != null) return@LaunchedEffect
        val pending = seekTarget
        if (pending != null) {
            level.snapTo(pending)
            if (abs(nowPlaying.progress - pending) < 0.03f) seekTarget = null
            return@LaunchedEffect
        }
        val target = nowPlaying.progress
        // A steady tick glides at constant speed; a jump (new song, cancelled drag) eases.
        val jump = abs(target - level.value) > 0.02f
        level.animateTo(
            target,
            if (jump) tween(280, easing = FastOutSlowInEasing) else tween(300, easing = LinearEasing),
        )
    }
    // If the player never lands near the target (the song changed meanwhile), stop waiting.
    LaunchedEffect(seekTarget) {
        if (seekTarget != null) {
            delay(800)
            seekTarget = null
        }
    }

    // Which way the titles slide on a song change: 1 for the next song, -1 for the previous.
    var songChangeDirection by remember { mutableIntStateOf(1) }

    val fraction = dragFraction ?: seekTarget ?: level.value
    val shownFraction by rememberUpdatedState(fraction)
    val shownPositionMs = (dragFraction ?: seekTarget)?.let { (it * nowPlaying.durationMs).toLong() }
        ?: nowPlaying.positionMs

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .drawBehind {
                val filled = size.height * fraction
                drawRect(Play, Offset(0f, size.height - filled), Size(size.width, filled))
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    // Start from the level as drawn, so it does not jump under the finger.
                    onDragStart = {
                        dragFraction = shownFraction
                        feel.grab()
                    },
                    onDragEnd = {
                        val target = dragFraction
                        if (target != null && durationMs > 0) {
                            seekTarget = target
                            seek((target * durationMs).toLong())
                        }
                        dragFraction = null
                        feel.letGo()
                        seekUsed()
                    },
                    // The system took the gesture over (a swipe from a screen edge, say):
                    // nothing is sought, and the level glides back instead of snapping.
                    onDragCancel = {
                        val left = dragFraction
                        scope.launch {
                            if (left != null) level.snapTo(left)
                            dragFraction = null
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // Dragging up moves forward in the song.
                        val before = dragFraction ?: shownFraction
                        val now = (before - dragAmount / size.height).coerceIn(0f, 1f)
                        dragFraction = now
                        // A tick for every fortieth of the song the finger passes.
                        if ((now * SEEK_TICKS).toInt() != (before * SEEK_TICKS).toInt()) feel.tick()
                    },
                )
            },
    ) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val fillHeight = maxHeight * fraction
        // The timer rides just above the level. It never drops into the controls, and it
        // stops under the links at the top: late in a song the blue simply rises past it.
        // Room the back arrow and the links take at the top, with a little air under them.
        val linksHeight = 80.dp + 44.dp * links.size
        val highestLift = maxHeight - insets.calculateTopPadding() - linksHeight - TimerHeight
        val lift = max(fillHeight, ControlsHeight + insets.calculateBottomPadding())
            .coerceAtMost(highestLift)
        val timerOnBlue = fillHeight > lift + TimerHeight / 2

        // When the song changes, the old title slides off and the new one slides on. The
        // queue plays from the bottom up, so the next song arrives from above; going back
        // to the previous song runs the other way.
        AnimatedContent(
            targetState = SongHeading(nowPlaying.songId, nowPlaying.title, nowPlaying.artist, nowPlaying.album),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(start = 10.dp, top = 24.dp, bottom = 24.dp)
                .fillMaxHeight()
                .width(128.dp),
            transitionSpec = {
                val from = songChangeDirection
                (slideInVertically(tween(460, easing = FastOutSlowInEasing)) { -from * it / 3 } +
                    fadeIn(tween(320, delayMillis = 80))) togetherWith
                    (slideOutVertically(tween(360, easing = FastOutSlowInEasing)) { from * it / 3 } +
                        fadeOut(tween(220)))
            },
            contentAlignment = Alignment.BottomStart,
            label = "song title",
        ) { heading ->
            VerticalTitle(title = heading.title, artist = heading.artist, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(top = 20.dp, end = 12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            IconTap(SpineIcons.ChevronDown, label = "Back", onClick = onBack, tint = Color.White)
            for (link in links) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.Button, onClick = link.onClick)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = link.label,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = if (link.detail != null) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    if (link.detail != null) {
                        Text(text = link.detail, color = InkMuted, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        if (showSeekHint && fillHeight < maxHeight - linksHeight - 296.dp) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(top = linksHeight + 26.dp, end = 20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    SpineIcons.UpDown,
                    contentDescription = null,
                    tint = InkMuted,
                    modifier = Modifier.size(width = 24.dp, height = 44.dp),
                )
                Text(
                    text = "Drag up or down to seek",
                    color = InkMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp)
                .offset(y = -(lift + 8.dp)),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatDuration(shownPositionMs),
                color = Color.White,
                fontFamily = BarlowCondensed,
                fontSize = with(LocalDensity.current) { 60.dp.toSp() },
                lineHeight = with(LocalDensity.current) { 56.dp.toSp() },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "of ${formatDuration(nowPlaying.durationMs)}",
                modifier = Modifier.padding(bottom = 10.dp),
                color = if (timerOnBlue) OnPlayMuted else InkMuted,
                fontSize = 15.sp,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(start = 150.dp, end = 20.dp, bottom = 24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedContent(
                targetState = SongHeading(nowPlaying.songId, nowPlaying.title, nowPlaying.artist, nowPlaying.album),
                transitionSpec = {
                    val from = songChangeDirection
                    (slideInVertically(tween(380, easing = FastOutSlowInEasing)) { -from * it / 2 } +
                        fadeIn(tween(300, delayMillis = 80))) togetherWith
                        (slideOutVertically(tween(260)) { from * it / 2 } + fadeOut(tween(180)))
                },
                label = "song artist",
            ) { heading ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Names wrap instead of being cut with "...". Three lines is far more
                    // than a real artist or album needs; it only stops a freak name from
                    // pushing the controls up the screen.
                    Text(
                        text = heading.artist,
                        color = Color.White,
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                    )
                    // A single's "album" is just the song's own name again, already shown
                    // large on the left, so it is left out.
                    if (heading.album.isNotBlank() && !heading.album.equals(heading.title, ignoreCase = true)) {
                        Text(
                            text = heading.album,
                            color = OnPlayMuted,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTap(
                    icon = SpineIcons.Previous,
                    label = "Previous song",
                    onClick = {
                        songChangeDirection = -1
                        onPrevious()
                        // Songs that follow on their own go forwards again.
                        scope.launch {
                            delay(900)
                            songChangeDirection = 1
                        }
                    },
                    tint = Color.White,
                    iconSize = 26.dp,
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onPlayPause)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (nowPlaying.isPlaying) SpineIcons.Pause else SpineIcons.Play,
                        contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                        tint = PaperText,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconTap(
                    icon = SpineIcons.Next,
                    label = "Next song",
                    onClick = {
                        songChangeDirection = 1
                        onNext()
                    },
                    tint = Color.White,
                    iconSize = 26.dp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTap(
                    icon = SpineIcons.Shuffle,
                    label = if (nowPlaying.shuffle) "Shuffle is on" else "Shuffle is off",
                    onClick = {
                        feel.switch(!nowPlaying.shuffle)
                        onToggleShuffle()
                    },
                    tint = Color.White.copy(alpha = if (nowPlaying.shuffle) 1f else 0.45f),
                    iconSize = 22.dp,
                )
                IconTap(
                    icon = if (nowPlaying.repeat == RepeatSetting.One) SpineIcons.RepeatOne else SpineIcons.Repeat,
                    label = when (nowPlaying.repeat) {
                        RepeatSetting.Off -> "Repeat is off"
                        RepeatSetting.All -> "Repeating the queue"
                        RepeatSetting.One -> "Repeating this song"
                    },
                    onClick = {
                        feel.step()
                        onCycleRepeat()
                    },
                    tint = Color.White.copy(alpha = if (nowPlaying.repeat == RepeatSetting.Off) 0.45f else 1f),
                    iconSize = 22.dp,
                )
                IconTap(
                    icon = if (isFavourite) SpineIcons.HeartFilled else SpineIcons.Heart,
                    label = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    onClick = {
                        feel.switch(!isFavourite)
                        onToggleFavourite()
                    },
                    tint = Color.White.copy(alpha = if (isFavourite) 1f else 0.45f),
                    iconSize = 22.dp,
                )
            }
        }
    }
}

/** Type sizes tried for the title, largest first, in dp. */
private val TitleSizes = listOf(120, 108, 96, 86, 78, 70, 62, 56, 50, 45, 40, 36, 32, 28, 25, 22, 19, 16, 14)

/** The smallest size at which the artist may still ride along on the title's line. */
private const val SMALLEST_SIZE_WITH_ARTIST = 86

/** How many ticks are felt dragging through a whole song. */
private const val SEEK_TICKS = 40

/**
 * The song title running up the left edge, always shown in full: it is never cut short
 * with "...". The largest type that fits is used, so a short title is one big line, a long
 * one wraps onto two lines side by side, and a very long one onto three or more.
 *
 * A short title would leave the column half empty, so the artist continues along the same
 * line, but only if the whole artist name fits there at a large size.
 */
@Composable
private fun VerticalTitle(title: String, artist: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.BottomStart) {
        // The text is turned on its side: it runs along the height and stacks across the width.
        val runLength = constraints.maxHeight
        val columnWidth = constraints.maxWidth

        val quiet = OnPlayMuted
        val fitted = remember(title, artist, runLength, columnWidth, density, quiet) {
            val titleText = AnnotatedString(title.uppercase())
            val withArtist = buildAnnotatedString {
                append(title.uppercase())
                withStyle(SpanStyle(color = quiet, fontWeight = FontWeight.Medium)) {
                    append("  ")
                    append(artist.uppercase())
                }
            }
            fun measure(text: AnnotatedString, sizeDp: Int, oneLine: Boolean) = measurer.measure(
                text = text,
                style = titleStyle(with(density) { sizeDp.dp.toSp() }),
                softWrap = !oneLine,
                maxLines = if (oneLine) 1 else Int.MAX_VALUE,
                constraints = Constraints(maxWidth = runLength),
            )
            fun fits(text: AnnotatedString, sizeDp: Int, oneLine: Boolean): Boolean {
                val result = measure(text, sizeDp, oneLine)
                return !result.hasVisualOverflow && result.size.height <= columnWidth
            }

            val titleSize = TitleSizes.firstOrNull { fits(titleText, it, oneLine = false) } ?: TitleSizes.last()
            val isShort = titleSize == TitleSizes.first() &&
                measure(titleText, titleSize, oneLine = true).size.width < runLength * 0.62f
            val sizeWithArtist = if (isShort && artist.isNotBlank()) {
                TitleSizes.takeWhile { it >= SMALLEST_SIZE_WITH_ARTIST }
                    .firstOrNull { fits(withArtist, it, oneLine = true) }
            } else {
                null
            }
            if (sizeWithArtist != null) withArtist to sizeWithArtist else titleText to titleSize
        }

        Text(
            text = fitted.first,
            modifier = Modifier.rotateVertically(),
            style = titleStyle(with(density) { fitted.second.dp.toSp() }),
            color = Color.White,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun titleStyle(size: TextUnit) = TextStyle(
    fontFamily = BarlowCondensed,
    fontSize = size,
    lineHeight = size,
    fontWeight = FontWeight.Bold,
)
