package app.spine.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.ui.theme.Barlow
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.DangerOnInk
import app.spine.player.ui.theme.DangerOnPaper
import app.spine.player.ui.theme.GhostLine
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkHairline
import app.spine.player.ui.theme.InkLine
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.Play

class MenuAction(
    val label: String,
    val destructive: Boolean = false,
    /** Quiet text at the right of the row, such as a song count. */
    val detail: String? = null,
    /** Marks the row that is the current choice, in a sheet that picks one of several. */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A sheet of actions. For a long-press menu, [bounds] is where the pressed item sits, so
 * it can stay lit; a sheet that is not about one item (the sleep timer) passes null.
 *
 * [onInk] is whether the screen underneath is a dark one; the sheet takes the opposite
 * colour so it reads as a layer on top.
 */
class MenuState(
    val bounds: Rect?,
    val onInk: Boolean,
    val actions: List<MenuAction>,
    /** A small heading over the rows, for a sheet that asks a question such as "Add to playlist". */
    val title: String? = null,
)

/**
 * Dims the screen except for the pressed item, outlines that item, and slides a sheet of
 * actions up from the bottom. Pass null to close; the closing animation still plays.
 */
@Composable
fun ItemMenu(menu: MenuState?, onDismiss: () -> Unit) {
    // Keep the last menu so its content is still there to draw while it animates away.
    var last by remember { mutableStateOf(menu) }
    if (menu != null && menu !== last) last = menu
    val shown = menu ?: last ?: return
    val dismiss by rememberUpdatedState(onDismiss)

    BackHandler(enabled = menu != null, onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = menu != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            val scrim = if (shown.onInk) Color.Black.copy(alpha = 0.62f) else Paper.copy(alpha = 0.80f)
            val outline = if (shown.onInk) Color.White else Ink
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            ) {
                val hole = shown.bounds?.inflate(3.dp.toPx())
                if (hole == null) {
                    drawRect(scrim)
                } else {
                    clipPath(Path().apply { addRect(hole) }, clipOp = ClipOp.Difference) {
                        drawRect(scrim)
                    }
                    drawRect(outline, topLeft = hole.topLeft, size = hole.size, style = Stroke(2.dp.toPx()))
                }
            }
        }

        AnimatedVisibility(
            visible = menu != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it },
        ) {
            val sheet = if (shown.onInk) Paper else Ink
            val text = if (shown.onInk) PaperText else Color.White
            val line = if (shown.onInk) Hairline else InkHairline
            val danger = if (shown.onInk) DangerOnPaper else DangerOnInk
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sheet)
                    // Swallow taps on the sheet's own padding so they do not close it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 12.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(if (shown.onInk) GhostLine else InkLine),
                )
                val quiet = if (shown.onInk) PaperMuted else InkMuted
                if (shown.title != null) {
                    Text(
                        text = shown.title.uppercase(),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        color = quiet,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                }
                shown.actions.forEachIndexed { index, action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable(role = Role.Button) {
                                dismiss()
                                action.onClick()
                            }
                            .drawBehind {
                                if (index < shown.actions.lastIndex) {
                                    val thickness = 1.dp.toPx()
                                    drawRect(line, Offset(0f, size.height - thickness), Size(size.width, thickness))
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = action.label,
                            modifier = Modifier.weight(1f),
                            color = when {
                                action.destructive -> danger
                                // The play colour reads on a light sheet; on a dark one weight alone marks it.
                                action.selected && shown.onInk -> Play
                                else -> text
                            },
                            fontSize = 17.sp,
                            fontWeight = if (action.destructive || action.selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (action.detail != null) {
                            Text(text = action.detail, color = quiet, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** What the delete popup is asking about. */
class DeleteRequest(
    /** "Delete this song?" */
    val question: String,
    val name: String,
    val detail: String,
    /** "The file will be removed from your phone. This cannot be undone." */
    val warning: String,
    val onConfirm: () -> Unit,
)

/** The "are you sure" popup before anything is removed from the phone. Pass null to close. */
@Composable
fun DeletePopup(request: DeleteRequest?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf(request) }
    if (request != null && request !== last) last = request
    val shown = request ?: last ?: return
    val dismiss by rememberUpdatedState(onDismiss)
    val feel = rememberFeel()

    BackHandler(enabled = request != null, onBack = onDismiss)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = request != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
        }
        AnimatedVisibility(
            visible = request != null,
            enter = fadeIn(tween(220)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.92f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .background(Paper)
                    // A thin edge: on dark paper the popup would otherwise melt into the dimmed screen.
                    .border(1.dp, Hairline)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = shown.question.uppercase(),
                    color = PaperText,
                    fontFamily = BarlowCondensed,
                    fontSize = 36.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ink)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = shown.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shown.detail,
                        color = InkMuted,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(text = shown.warning, color = PaperMuted, fontSize = 15.sp, lineHeight = 21.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockButton(
                        text = "Keep it",
                        onClick = onDismiss,
                        onInk = false,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .clickable(role = Role.Button) {
                                // Taking something away for good feels different from a tap.
                                feel.warn()
                                dismiss()
                                shown.onConfirm()
                            }
                            .background(DangerOnPaper),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "Delete", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** What the name popup is asking for. */
class NameRequest(
    /** "New playlist" */
    val title: String,
    val confirmLabel: String,
    /** What the field holds when the popup opens, for changing a name that exists. */
    val initial: String = "",
    /** Whether an empty field may be confirmed, for taking a name away. */
    val allowEmpty: Boolean = false,
    val onConfirm: (name: String) -> Unit,
)

/** A popup with one text field, for naming something. Pass null to close. */
@Composable
fun NamePopup(request: NameRequest?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf(request) }
    if (request != null && request !== last) last = request
    val shown = request ?: last ?: return
    val dismiss by rememberUpdatedState(onDismiss)
    val feel = rememberFeel()

    BackHandler(enabled = request != null, onBack = onDismiss)

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = request != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
        }
        AnimatedVisibility(
            visible = request != null,
            enter = fadeIn(tween(220)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.92f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
        ) {
            // A fresh field each time the popup opens.
            // It holds the cursor too, which starts after a name that is already there.
            var name by remember(shown) { mutableStateOf(TextFieldValue(shown.initial, TextRange(shown.initial.length))) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(shown) { focus.requestFocus() }
            val confirm = {
                if (name.text.isNotBlank() || shown.allowEmpty) {
                    feel.done()
                    dismiss()
                    shown.onConfirm(name.text)
                }
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .background(Paper)
                    // A thin edge: on dark paper the popup would otherwise melt into the dimmed screen.
                    .border(1.dp, Hairline)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = shown.title.uppercase(),
                    color = PaperText,
                    fontFamily = BarlowCondensed,
                    fontSize = 36.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "NAME",
                        color = PaperMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .drawBehind {
                                val thickness = 2.dp.toPx()
                                drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                            }
                            .padding(vertical = 10.dp),
                        singleLine = true,
                        textStyle = TextStyle(color = PaperText, fontFamily = Barlow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Play),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockButton(text = "Cancel", onClick = onDismiss, onInk = false, modifier = Modifier.weight(1f))
                    BlockButton(
                        text = shown.confirmLabel,
                        onClick = confirm,
                        onInk = false,
                        modifier = Modifier.weight(1f),
                        filled = true,
                    )
                }
            }
        }
    }
}
