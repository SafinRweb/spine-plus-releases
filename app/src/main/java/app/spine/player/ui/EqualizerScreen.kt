package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.playback.EqBands
import app.spine.player.playback.EqPreset
import app.spine.player.playback.EqSettings
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.SpineSwitch
import app.spine.player.ui.components.rotateVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkLine
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.InkRaised
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Play
import kotlin.math.roundToInt

/**
 * The equalizer: each band is a spine, filled up to its level. Drag a band up or down, or
 * tap anywhere on it, to set it. Changing a band turns the equalizer on and switches to
 * the Custom preset.
 */
@Composable
fun EqualizerScreen(
    eq: EqSettings,
    bands: EqBands?,
    unavailable: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onChange: (EqSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 24.dp),
    ) {
        BackLink(text = backLabel, onClick = onBack, color = Color.White)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "EQUALIZER",
                color = Color.White,
                fontFamily = BarlowCondensed,
                fontSize = 40.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            if (bands != null) {
                SpineSwitch(
                    checked = eq.enabled,
                    onCheckedChange = { onChange(eq.copy(enabled = it)) },
                    label = "Equalizer",
                    onInk = true,
                )
            }
        }

        if (bands == null) {
            Text(
                text = if (unavailable) {
                    "This phone does not provide an equalizer the app can use."
                } else {
                    "Play a song once and the equalizer will be ready."
                },
                modifier = Modifier.padding(top = 16.dp),
                color = InkMuted,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            return@Column
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EqPreset.entries.forEach { preset ->
                val selected = preset == eq.preset
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.Tab) {
                            // Custom keeps the levels as they are; the others load their shape.
                            val levels = if (preset == EqPreset.Custom) eq.levelsMb else preset.levelsFor(bands.centerHz)
                            onChange(eq.copy(enabled = true, preset = preset, levelsMb = levels))
                        }
                        .drawBehind {
                            if (selected) {
                                val thickness = 2.dp.toPx()
                                drawRect(Color.White, Offset(0f, size.height - thickness), Size(size.width, thickness))
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preset.label,
                        color = if (selected) Color.White else InkMuted,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        val maxDb = bands.maxMb / 100
        val minDb = bands.minMb / 100
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The scale down the left edge.
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = "+$maxDb", color = InkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(text = "0 dB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(text = "−${-minDb}", color = InkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            bands.centerHz.forEachIndexed { index, hz ->
                BandSpine(
                    hz = hz,
                    levelMb = eq.levelsMb.getOrElse(index) { 0 },
                    bands = bands,
                    enabled = eq.enabled,
                    onLevel = { levelMb ->
                        val levels = List(bands.centerHz.size) { band ->
                            if (band == index) levelMb else eq.levelsMb.getOrElse(band) { 0 }
                        }
                        onChange(eq.copy(enabled = true, preset = EqPreset.Custom, levelsMb = levels))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** One band as a spine: filled from the bottom up to its level, with a line at 0 dB. */
@Composable
private fun BandSpine(
    hz: Int,
    levelMb: Int,
    bands: EqBands,
    enabled: Boolean,
    onLevel: (levelMb: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (bands.maxMb - bands.minMb).coerceAtLeast(1)
    // Quicker than the playback fill: this follows a finger.
    val fraction by animateFloatAsState(
        targetValue = (levelMb - bands.minMb).toFloat() / span,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "band level",
    )
    val zeroFraction = (0 - bands.minMb).toFloat() / span
    val current by rememberUpdatedState(levelMb)
    val feel = rememberFeel()
    // Each decibel the level lands on is felt as a step, like a notched dial.
    val report by rememberUpdatedState(onLevel)
    val setLevel: (Int) -> Unit = { level ->
        if (level != current) {
            feel.step()
            report(level)
        }
    }
    val labelSize = with(LocalDensity.current) { 20.dp.toSp() }
    val name = if (hz >= 1000) "${(hz / 100) / 10f} kHz".replace(".0 ", " ") else "$hz Hz"
    val db = (levelMb / 100f).roundToInt()

    // Where a touch is, from the bottom of the spine to the top, becomes the level.
    fun levelAt(y: Float, height: Int): Int {
        val touched = (1f - y / height).coerceIn(0f, 1f)
        // Snap to whole decibels so the number above does not jitter.
        return ((bands.minMb + touched * span) / 100f).roundToInt() * 100
    }

    BoxWithConstraints(
        modifier = modifier
            .background(InkRaised)
            .drawBehind {
                val filled = size.height * fraction
                drawRect(
                    color = if (enabled) Play else InkLine,
                    topLeft = Offset(0f, size.height - filled),
                    size = Size(size.width, filled),
                )
                val zeroY = size.height * (1f - zeroFraction)
                drawRect(Color.White, Offset(0f, zeroY), Size(size.width, 1.dp.toPx()))
            }
            .pointerInput(bands) {
                detectTapGestures { offset -> setLevel(levelAt(offset.y, size.height)) }
            }
            .pointerInput(bands) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    setLevel(levelAt(change.position.y, size.height))
                }
            }
            .semantics { contentDescription = "$name, ${if (db > 0) "plus " else ""}$db decibels" }
            .padding(vertical = 12.dp),
    ) {
        // The number rides just above the level, so it moves with the finger and is seen
        // changing. Near the bottom it stops short of the band's name.
        val inner = constraints.maxHeight
        Text(
            text = if (db > 0) "+$db" else if (db < 0) "−${-db}" else "0",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset {
                    val edge = 12.dp.roundToPx()
                    val levelTop = ((inner + 2 * edge) * (1f - fraction)).roundToInt() - edge
                    val lowest = (inner - 150.dp.roundToPx()).coerceAtLeast(0)
                    IntOffset(0, (levelTop - 30.dp.roundToPx()).coerceIn(0, lowest))
                },
            color = Color.White,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = name.uppercase(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .rotateVertically(),
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}
