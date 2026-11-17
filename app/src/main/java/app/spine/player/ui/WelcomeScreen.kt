package app.spine.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import app.spine.player.ui.components.owned
import app.spine.player.ui.theme.Barlow
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.spine.player.ui.components.rememberFeel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.LocalAnimations
import app.spine.player.ui.components.SystemBarIcons
import app.spine.player.ui.components.rotateVertically
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Play
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How large the app's name stands on the first screen, and how wide the column it stands in is. */
private val NAME_SIZE = 124.dp
private val NAME_COLUMN = 150.dp

/** Where the blue settles when the screen opens. */
private const val RESTING_LEVEL = 0.38f

/** How long the blue takes to climb the whole screen, as if a short song were playing. */
private const val FULL_CLIMB_MS = 60_000

/**
 * First-run screen. It shows the app's one unusual idea before asking for anything: the
 * blue rises on its own like a song playing, and dragging up or down moves it, exactly as
 * seeking does on the Now playing screen.
 */
@Composable
fun WelcomeScreen(
    permissionDenied: Boolean,
    onFindMusic: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SystemBarIcons(darkIcons = false)

    val level = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Runs whenever no finger is down, so a drag takes over at once and the climb resumes
    // from wherever the level was left.
    LaunchedEffect(dragging) {
        if (dragging) return@LaunchedEffect
        if (level.value < RESTING_LEVEL) {
            level.animateTo(RESTING_LEVEL, tween(1400, easing = FastOutSlowInEasing))
        }
        while (true) {
            val climbMs = ((1f - level.value) * FULL_CLIMB_MS).toInt().coerceAtLeast(1)
            level.animateTo(1f, tween(climbMs, easing = LinearEasing))
            delay(700)
            // The "song" ended: drain and start again.
            level.animateTo(0.1f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    val feel = rememberFeel()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .drawBehind {
                val filled = size.height * level.value
                drawRect(Play, Offset(0f, size.height - filled), Size(size.width, filled))
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        feel.grab()
                    },
                    onDragEnd = {
                        dragging = false
                        feel.letGo()
                    },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            level.snapTo((level.value - dragAmount / size.height).coerceIn(0f, 1f))
                        }
                    },
                )
            },
    ) {
        // The app's name stands where Now playing stands a song's title: the same size, the
        // same distance from the left edge and the bottom, with everything else to its right.
        val context = LocalContext.current
        val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
        val nameSize = with(LocalDensity.current) { NAME_SIZE.toSp() }
        Text(
            text = appName.uppercase(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(start = 10.dp, bottom = 24.dp)
                .rotateVertically(),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = nameSize,
            lineHeight = nameSize * 0.9f,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )

        // The words and the button share one column, so with large text the words push the
        // button down instead of running over it; if the two no longer fit the screen, the
        // column scrolls. It only takes the drag when it has somewhere to scroll to, so on
        // an ordinary screen a drag here still moves the blue.
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(start = NAME_COLUMN, end = 20.dp)
                .fillMaxSize()
                .verticalScroll(scroll, enabled = scroll.maxValue > 0)
                .padding(top = 72.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (rule in listOf("Albums stand up.", "Songs lie down.", "Blue rises as you play.")) {
                    Text(
                        text = rule,
                        color = Color.White,
                        fontSize = 21.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = "$appName plays the music on this phone, and downloads more when you ask. Nothing of yours is uploaded.",
                // Quiet white, not grey: on a small phone these lines reach down into the blue,
                // where grey would be hard to read.
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        }

        Column(
            modifier = Modifier
                .padding(top = 28.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val buttonSize = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
            if (permissionDenied) {
                Text(
                    text = "$appName can't see your music without permission.",
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
                BlockButton(text = "Ask again", onClick = onFindMusic, onInk = true, modifier = buttonSize, filled = true)
                BlockButton(text = "Open settings", onClick = onOpenSettings, onInk = true, modifier = buttonSize)
            } else {
                BlockButton(text = "Find my music", onClick = onFindMusic, onInk = true, modifier = buttonSize, filled = true)
                Text(
                    text = "Android will ask for permission to read your audio files.",
                    color = OnPlayMuted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        }
    }
}

/**
 * The second screen of the first run: what to call the user. The name heads the Shelf
 * ("SAFIN'S SHELF") and the stats, and nothing else; it is kept on the phone only, can be
 * skipped, and can be changed in Settings.
 */
@Composable
fun NameScreen(onDone: (name: String) -> Unit, onSkip: () -> Unit) {
    SystemBarIcons(darkIcons = false)
    val context = LocalContext.current
    val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
    var box by remember { mutableStateOf(TextFieldValue("")) }
    val name = box.text.replace(Regex("\\s+"), " ").trim()
    val focus = remember { FocusRequester() }
    // Once the keyboard has come up, the box is scrolled into the room left above it.
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        delay(450)
        inView.bringIntoView()
    }
    val scroll = rememberScrollState()
    // The blue carries on rising from where the first screen left it, runs out at the top
    // and starts again, as a song playing would. With animations off it stands still.
    val level = remember { Animatable(RESTING_LEVEL) }
    val moving = LocalAnimations.current
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        while (true) {
            val climbMs = ((1f - level.value) * FULL_CLIMB_MS).toInt().coerceAtLeast(1)
            level.animateTo(1f, tween(climbMs, easing = LinearEasing))
            delay(700)
            level.animateTo(0.1f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .drawBehind {
                val filled = size.height * level.value
                drawRect(Play, Offset(0f, size.height - filled), Size(size.width, filled))
            }
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(scroll)
            // Less room above than the first screen has: the keyboard is up from the start,
            // and the box to type in must stay in sight above it on a small phone.
            .padding(top = 36.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "ONE MORE THING", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text(
                    text = "WHAT SHOULD ${appName.uppercase()} CALL YOU?",
                    color = Color.White,
                    fontFamily = BarlowCondensed,
                    fontSize = 44.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.bringIntoViewRequester(inView),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = "YOUR NAME", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                BasicTextField(
                    value = box,
                    onValueChange = { if (it.text.length <= UiSettings.MAX_NAME) box = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .drawBehind {
                            val thickness = 2.dp.toPx()
                            drawRect(Color.White, Offset(0f, size.height - thickness), Size(size.width, thickness))
                        }
                        .padding(vertical = 10.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontFamily = Barlow, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isEmpty()) onSkip() else onDone(name) }),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "Your shelf will read", color = Color.White.copy(alpha = 0.74f), fontSize = 14.sp, lineHeight = 19.sp)
                Text(
                    text = owned(name, "Shelf").uppercase(),
                    color = Color.White,
                    fontFamily = BarlowCondensed,
                    fontSize = 40.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(top = 28.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val buttonSize = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
            BlockButton(
                text = "Continue",
                onClick = { if (name.isEmpty()) onSkip() else onDone(name) },
                onInk = true,
                modifier = buttonSize,
                filled = true,
            )
            Text(
                text = "Skip, just call it Shelf",
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .clickable(role = Role.Button, onClick = onSkip)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Kept on this phone only. You can change it in Settings.",
                color = OnPlayMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
