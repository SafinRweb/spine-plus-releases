package app.spine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Play
import app.spine.player.update.UpdateOffer
import app.spine.player.update.UpdateState

/** The version a state is about, if it is about one. */
private val UpdateState.offer: UpdateOffer?
    get() = when (this) {
        is UpdateState.Available -> offer
        is UpdateState.Downloading -> offer
        is UpdateState.Ready -> offer
        is UpdateState.Failed -> offer
        else -> null
    }

/**
 * "A new version is out", over whatever screen is showing: what it is, what it brings, and
 * the way to get it. The two buttons become a strip that fills while it downloads, and then
 * the way to install it.
 *
 * [onHide] puts the popup away and leaves things as they are (a download carries on);
 * [onLater] also means "do not show me this version again".
 */
@Composable
fun UpdatePopup(
    visible: Boolean,
    state: UpdateState,
    installed: String,
    onUpdate: () -> Unit,
    onStop: () -> Unit,
    onInstall: () -> Unit,
    onLater: (version: String) -> Unit,
    onHide: () -> Unit,
) {
    // The last offer is kept so the popup still has its words while it fades out.
    var last by remember { mutableStateOf(state.offer) }
    state.offer?.let { if (it != last) last = it }
    val offer = last ?: return
    val show = visible && state.offer != null
    val hide by rememberUpdatedState(onHide)

    BackHandler(enabled = show, onBack = onHide)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = show, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .pointerInput(Unit) { detectTapGestures { hide() } },
            )
        }
        AnimatedVisibility(
            visible = show,
            enter = fadeIn(tween(220)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.92f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
        ) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(horizontal = 32.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    .background(Paper)
                    // A thin edge: on dark paper the popup would otherwise melt into the dimmed screen.
                    .border(1.dp, Hairline)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PanelLabel("SPINE+ UPDATE")
                    Text(
                        text = "${offer.version} IS OUT",
                        color = PaperText,
                        fontFamily = BarlowCondensed,
                        fontSize = 40.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = listOfNotNull(
                            "You have $installed".takeIf { installed.isNotEmpty() },
                            "${sizeInMegabytes(offer.bytes)} to download".takeIf { offer.bytes > 0 },
                        ).joinToString(" · "),
                        color = PaperMuted,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                }

                if (offer.notes.isNotEmpty()) {
                    Column(
                        // A long list scrolls inside the popup, so the buttons stay in reach.
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PanelLabel("WHAT IS NEW")
                        for (note in offer.notes) {
                            Text(text = note, color = PaperText, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }

                when (state) {
                    is UpdateState.Downloading -> UpdateStrip(words = "Downloading", progress = state.progress)
                    is UpdateState.Ready -> UpdateStrip(words = "Downloaded", progress = 1f)
                    is UpdateState.Failed -> Text(
                        text = state.words,
                        color = Play,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    else -> Unit
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state is UpdateState.Downloading) {
                        // It carries on out of sight, and the popup comes back when it is ready.
                        BlockButton(text = "Hide", onClick = onHide, onInk = false, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp))
                        BlockButton(text = "Stop", onClick = onStop, onInk = false, modifier = Modifier.weight(1.4f).defaultMinSize(minHeight = 48.dp))
                    } else {
                        BlockButton(
                            text = "Later",
                            onClick = { onLater(offer.version) },
                            onInk = false,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        )
                        BlockButton(
                            text = when (state) {
                                is UpdateState.Ready -> "Install"
                                is UpdateState.Failed -> "Try again"
                                else -> "Update"
                            },
                            onClick = if (state is UpdateState.Ready) onInstall else onUpdate,
                            onInk = false,
                            modifier = Modifier.weight(1.4f).defaultMinSize(minHeight = 48.dp),
                            filled = true,
                        )
                    }
                }
            }
        }
    }
}

/** A dark strip that fills from the bottom with the play colour as the update arrives. */
@Composable
private fun UpdateStrip(words: String, progress: Float) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Ink)
            .clearAndSetSemantics { contentDescription = "$words, $percent percent" },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(progress.coerceIn(0f, 1f))
                .background(Play),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = words, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "$percent%", color = Color.White, fontSize = 15.sp)
        }
    }
}
