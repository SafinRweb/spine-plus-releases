package app.spine.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.IconTap
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.components.SpineLoader
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.InkRaised
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Play
import kotlinx.coroutines.delay

/** What the video player can ask of the player behind it. */
class VideoActions(
    val showOn: (TextureView?) -> Unit,
    val playPause: () -> Unit,
    val seekTo: (positionMs: Long) -> Unit,
    val skip: (byMs: Long) -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val setSpeed: (Float) -> Unit,
    val setSubtitles: (Boolean) -> Unit,
    val jumpTo: (queueIndex: Int) -> Unit,
)

/** The speeds "Speed" steps through, each tap moving to the next. */
private val Speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)

private fun speedLabel(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "Speed $text×"
}

/**
 * The video player. Upright, the picture is on top with the app's own controls under it:
 * the title, a wide strip that fills with the play colour as the video runs and is dragged
 * to move through it, the round pause button with ten seconds back and forward beside
 * previous and next, then speed, subtitles and how the picture fills its space, and what
 * is up next. Turned sideways, the picture fills the screen and the same controls lie over
 * it, coming and going with a tap.
 *
 * The video plays through the same player as the songs, so leaving this screen does not
 * stop the sound: the strip at the bottom of the Shelf carries on showing it.
 */
@Composable
fun VideoPlayerScreen(
    nowPlaying: NowPlaying,
    /** What plays after this, songs and videos alike, each with its slot in the queue. */
    upNext: List<QueueSong>,
    cues: String,
    backLabel: String,
    actions: VideoActions,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fill by rememberSaveable { mutableStateOf(false) }

    // The screen stays lit while the video runs, as it would not be touched for minutes.
    DisposableEffect(nowPlaying.isPlaying) {
        view.keepScreenOn = nowPlaying.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    // Sideways, the clock and the buttons at the screen's edges get out of the picture's way.
    DisposableEffect(wide) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (wide) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Leaving the player lets the phone turn the app as it likes again.
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // A debug build can open the player already sideways, to look at it without a tap.
    LaunchedEffect(Unit) {
        if (DemoMode.wide) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    val turn: () -> Unit = {
        activity?.requestedOrientation =
            if (wide) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // A swipe on the picture: sideways moves through the video, up or down changes the
    // phone's volume. What it is doing is said in the middle of the picture while the finger
    // is down.
    var hint by remember { mutableStateOf<String?>(null) }
    val gestures = Modifier.videoSwipes(
        nowPlaying = nowPlaying,
        onSeek = actions.seekTo,
        onHint = { hint = it },
    )

    // The picture is not there yet, or the file has none.
    val opening = nowPlaying.buffering || !nowPlaying.isVideo
    val soundOnly = !opening && nowPlaying.videoWidth == 0 && nowPlaying.durationMs > 0

    if (wide) {
        var controls by remember { mutableStateOf(true) }
        // Counted up by every press on a control, so the wait below starts again.
        var touched by remember { mutableStateOf(0) }
        // Left alone while playing, the controls step back after a few seconds.
        LaunchedEffect(controls, nowPlaying.isPlaying, touched, nowPlaying.speed, nowPlaying.subtitlesOn, fill) {
            if (controls && nowPlaying.isPlaying) {
                delay(4_000)
                controls = false
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Picture(nowPlaying, fill, actions.showOn, Modifier.fillMaxSize())
            PictureCover(opening, soundOnly, cues, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { controls = !controls } }
                    .then(gestures),
            )
            SwipeHint(hint, Modifier.align(Alignment.Center))
            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.62f),
                                0.34f to Color.Transparent,
                                0.56f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.7f),
                            ),
                        )
                        .pointerInput(Unit) { detectTapGestures { controls = false } }
                        .then(gestures)
                        .safeDrawingPadding()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconTap(SpineIcons.ChevronLeft, label = "Back to upright", onClick = turn, tint = Color.White)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nowPlaying.title.uppercase(),
                                color = Color.White,
                                fontFamily = BarlowCondensed,
                                fontSize = 24.sp,
                                lineHeight = 26.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (nowPlaying.artist.isNotBlank()) {
                                Text(text = nowPlaying.artist, color = OnPlayMuted, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1)
                            }
                        }
                        Options(nowPlaying, fill, { fill = !fill }, actions, spacing = 22.dp)
                    }
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(56.dp),
                    ) {
                        IconTap(
                            SpineIcons.SkipBack,
                            label = "Back ten seconds",
                            onClick = {
                                touched++
                                actions.skip(-10_000)
                            },
                            tint = Color.White,
                            iconSize = 26.dp,
                        )
                        PauseButton(nowPlaying.isPlaying, actions.playPause, size = 72.dp)
                        IconTap(
                            SpineIcons.SkipForward,
                            label = "Forward ten seconds",
                            onClick = {
                                touched++
                                actions.skip(10_000)
                            },
                            tint = Color.White,
                            iconSize = 26.dp,
                        )
                    }
                    SeekStrip(
                        nowPlaying = nowPlaying,
                        onSeek = { position ->
                            touched++
                            actions.seekTo(position)
                        },
                        height = 48.dp,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 20.dp, end = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackLink(text = backLabel, onClick = onBack, color = Color.White)
            IconTap(SpineIcons.FullScreen, label = "Turn sideways and fill the screen", onClick = turn, tint = Color.White, iconSize = 22.dp)
        }
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            Picture(nowPlaying, fill, actions.showOn, Modifier.fillMaxSize())
            PictureCover(opening, soundOnly, cues, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().then(gestures))
            SwipeHint(hint, Modifier.align(Alignment.Center))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = nowPlaying.title.uppercase(),
                    color = Color.White,
                    fontFamily = BarlowCondensed,
                    fontSize = 36.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nowPlaying.artist.isNotBlank()) {
                    Text(text = nowPlaying.artist, color = InkMuted, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SeekStrip(nowPlaying, actions.seekTo, height = 64.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTap(SpineIcons.SkipBack, label = "Back ten seconds", onClick = { actions.skip(-10_000) }, tint = Color.White, iconSize = 22.dp)
                IconTap(SpineIcons.Previous, label = "Previous", onClick = actions.previous, tint = Color.White, iconSize = 26.dp)
                PauseButton(nowPlaying.isPlaying, actions.playPause, size = 64.dp)
                IconTap(SpineIcons.Next, label = "Next", onClick = actions.next, tint = Color.White, iconSize = 26.dp)
                IconTap(SpineIcons.SkipForward, label = "Forward ten seconds", onClick = { actions.skip(10_000) }, tint = Color.White, iconSize = 22.dp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Options(nowPlaying, fill, { fill = !fill }, actions, spacing = null)
            }
            if (upNext.isNotEmpty()) {
                Text(
                    text = "UP NEXT",
                    color = InkMuted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(upNext, key = { it.index }) { video ->
                        VideoRow(
                            title = video.song.title,
                            line = listOfNotNull(
                                video.song.artist.takeIf { it.isNotBlank() },
                                formatDuration(video.song.durationMs).takeIf { video.song.durationMs > 0 },
                            ).joinToString(" · "),
                            onClick = { actions.jumpTo(video.index) },
                            background = InkRaised,
                        )
                    }
                }
            }
        }
    }
}

/** How much of the video one swipe across the whole picture moves through, at most. */
private const val SWIPE_SPAN_MS = 120_000L

/**
 * Swipes on the picture. Sideways moves through the video: the whole width is two minutes,
 * or the whole video if it is shorter; the new place is shown while the finger moves and
 * the video goes there when it lifts. Up or down changes the phone's own media volume, the
 * whole height being the whole range. Which of the two a swipe is, is settled by the way
 * it first moves, and it stays that until the finger lifts.
 */
@Composable
private fun Modifier.videoSwipes(nowPlaying: NowPlaying, onSeek: (Long) -> Unit, onHint: (String?) -> Unit): Modifier {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val playing by rememberUpdatedState(nowPlaying)
    val seek by rememberUpdatedState(onSeek)
    val say by rememberUpdatedState(onHint)
    return pointerInput(Unit) {
        var sideways: Boolean? = null
        var moved = Offset.Zero
        var fromMs = 0L
        var toMs = 0L
        var fromVolume = 0
        detectDragGestures(
            onDragStart = {
                sideways = null
                moved = Offset.Zero
                fromMs = playing.positionMs
                toMs = fromMs
                fromVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            },
            onDragEnd = {
                if (sideways == true && playing.durationMs > 0) seek(toMs)
                say(null)
            },
            onDragCancel = { say(null) },
            onDrag = { change, by ->
                change.consume()
                moved += by
                if (sideways == null) sideways = kotlin.math.abs(moved.x) >= kotlin.math.abs(moved.y)
                if (sideways == true) {
                    val length = playing.durationMs
                    if (length > 0) {
                        val span = minOf(length, SWIPE_SPAN_MS)
                        toMs = (fromMs + (moved.x / size.width * span).toLong()).coerceIn(0, length)
                        val by = toMs - fromMs
                        say("${formatDuration(toMs)}  ${if (by < 0) "−" else "+"}${formatDuration(kotlin.math.abs(by))}")
                    }
                } else {
                    val most = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    // Up is louder. The whole height of the picture is the whole range.
                    val level = (fromVolume - moved.y / size.height * most).toInt().coerceIn(0, most)
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                    say("VOLUME ${level * 100 / most.coerceAtLeast(1)}%")
                }
            },
        )
    }
}

/** What a swipe on the picture is doing, said large in the middle of it. */
@Composable
private fun SwipeHint(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White,
        fontFamily = BarlowCondensed,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** The video's picture, fitted inside its space or, with [fill], cut to cover all of it. */
@Composable
private fun Picture(nowPlaying: NowPlaying, fill: Boolean, showOn: (TextureView?) -> Unit, modifier: Modifier) {
    val show by rememberUpdatedState(showOn)
    BoxWithConstraints(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val picture = if (nowPlaying.videoWidth > 0 && nowPlaying.videoHeight > 0) {
            nowPlaying.videoWidth.toFloat() / nowPlaying.videoHeight
        } else {
            16f / 9f
        }
        val space = maxWidth / maxHeight
        // Fitting goes by the side that runs out first; filling, by the other.
        val byWidth = (picture > space) != fill
        val width = if (byWidth) maxWidth else maxHeight * picture
        val height = if (byWidth) maxWidth / picture else maxHeight
        AndroidView(
            factory = { context -> TextureView(context) },
            modifier = Modifier.requiredSize(width, height),
            // Asked for again whenever the video changes, as the player may have let go of the view.
            update = { texture -> if (nowPlaying.isVideo) show(texture) },
            onRelease = { show(null) },
        )
    }
}

/** What lies over the picture: the loader while it opens, a note when there is no picture, the subtitles. */
@Composable
private fun PictureCover(opening: Boolean, soundOnly: Boolean, cues: String, modifier: Modifier) {
    Box(modifier = modifier) {
        if (opening || soundOnly) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0B0D)),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (opening) SpineLoader(height = 56.dp, base = Color(0xFF2A2A30), label = "Opening")
                Text(
                    text = if (opening) "OPENING" else "SOUND ONLY",
                    color = Color.White,
                    fontFamily = BarlowCondensed,
                    fontSize = 30.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (cues.isNotBlank() && !opening) {
            Text(
                text = cues,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * How far the video has run, as a strip that fills with the play colour from the left.
 * A tap or a drag anywhere on it moves the video there; while dragging, the strip shows
 * where the finger is, and the video follows when it lifts.
 */
@Composable
private fun SeekStrip(nowPlaying: NowPlaying, onSeek: (Long) -> Unit, height: Dp, modifier: Modifier = Modifier) {
    var dragged by remember { mutableFloatStateOf(-1f) }
    val duration = nowPlaying.durationMs
    val shown = if (dragged >= 0f) dragged else nowPlaying.progress
    val shownMs = if (dragged >= 0f) (dragged * duration).toLong() else nowPlaying.positionMs
    val seek by rememberUpdatedState(onSeek)
    val length by rememberUpdatedState(duration)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(InkRaised)
            .drawBehind {
                val filled = size.width * shown.coerceIn(0f, 1f)
                drawRect(Play, size = Size(filled, size.height))
                if (filled > 0f) drawRect(Color.White, Offset(filled - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
            .pointerInput(Unit) {
                detectTapGestures { at -> if (length > 0) seek(((at.x / size.width).coerceIn(0f, 1f) * length).toLong()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { at -> dragged = (at.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        if (length > 0 && dragged >= 0f) seek((dragged * length).toLong())
                        dragged = -1f
                    },
                    onDragCancel = { dragged = -1f },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragged = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            }
            .semantics { contentDescription = "Position, ${formatDuration(shownMs)} of ${formatDuration(duration)}" }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDuration(shownMs),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = if (height > 50.dp) 34.sp else 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = "of ${formatDuration(duration)}", color = OnPlayMuted, fontSize = 15.sp)
    }
}

/** The round white button, as on Now playing. */
@Composable
private fun PauseButton(playing: Boolean, onClick: () -> Unit, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) SpineIcons.Pause else SpineIcons.Play,
            contentDescription = if (playing) "Pause" else "Play",
            tint = Color(0xFF111113),
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Speed, subtitles and how the picture fills its space, as words to tap. */
@Composable
private fun Options(nowPlaying: NowPlaying, fill: Boolean, onFill: () -> Unit, actions: VideoActions, spacing: Dp?) {
    val content: @Composable () -> Unit = {
        Option(speedLabel(nowPlaying.speed)) {
            val at = Speeds.indexOfFirst { kotlin.math.abs(it - nowPlaying.speed) < 0.01f }
            actions.setSpeed(Speeds[(at + 1).mod(Speeds.size)])
        }
        when {
            !nowPlaying.hasSubtitles -> Option("No subtitles", enabled = false) { }
            nowPlaying.subtitlesOn -> Option("Subtitles on") { actions.setSubtitles(false) }
            else -> Option("Subtitles off") { actions.setSubtitles(true) }
        }
        Option(if (fill) "Fit the picture" else "Fill the space", onClick = onFill)
    }
    if (spacing != null) Row(horizontalArrangement = Arrangement.spacedBy(spacing)) { content() } else content()
}

@Composable
private fun Option(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = if (enabled) Color.White else InkMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
