package app.spine.player.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.spine.player.MainActivity
import app.spine.player.ui.DownloadButton
import app.spine.player.ui.DownloadProgress
import app.spine.player.ui.ExtraChoices
import app.spine.player.ui.FormatChoice
import app.spine.player.ui.KindChoice
import app.spine.player.ui.PanelLabel
import app.spine.player.ui.SongCard
import app.spine.player.ui.SongPickerHead
import app.spine.player.ui.SongPickerRow
import app.spine.player.ui.sizeInMegabytes
import app.spine.player.ui.watch
import app.spine.player.ui.Working
import app.spine.player.ui.rememberDownloadGate
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.rememberFeel
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.SpineTheme

/**
 * The address in what another app shared: the first link among the shared words, or the
 * page it was showing. Null when there is no link to act on.
 */
fun sharedLink(intent: Intent?): String? = when (intent?.action) {
    Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("""https?://\S+""").find(it)?.value }
    Intent.ACTION_VIEW -> intent.dataString
    else -> null
}

/**
 * "Quick download" in other apps' Share lists. It is a panel over whatever app the link
 * was shared from: it says what the link is, lets the download be adjusted, and starts it.
 * The download itself carries on in [Downloads] after the panel is gone.
 */
class QuickDownloadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = clear, navigationBarStyle = clear)
        val link = sharedLink(intent)
        if (link == null) {
            finish()
            return
        }
        setContent {
            SpineTheme {
                QuickDownload(
                    link = link,
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickDownload(link: String, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val context = LocalContext.current
    val feel = rememberFeel()

    // What the link points to: null while it is being read.
    var songs by remember { mutableStateOf<List<Found>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(link, attempt) {
        songs = null
        problem = null
        try {
            // The quick answer first, so the song is on screen at once; the engine then
            // works out the rest, which the download will use.
            val quick = Peek.youtube(link)
            if (quick != null) {
                songs = listOf(quick)
                Engine.prepare(context, quick).await()?.let { full -> songs = listOf(full) }
            } else {
                songs = Engine.read(context, link)
            }
        } catch (fault: EngineFault) {
            problem = fault.message
        } catch (fault: Exception) {
            problem = "Could not read that link. Check the internet and try again."
        }
    }

    var options by remember { mutableStateOf(Downloads.preset(context)) }
    val choose: (GetOptions) -> Unit = { chosen ->
        options = chosen
        // What is picked here is what "as usual" means next time.
        Downloads.setPreset(context, chosen)
    }

    val getting by Downloads.items.collectAsStateWithLifecycle()
    val found = songs.orEmpty()
    val asked = remember(found, getting) { found.mapNotNull { song -> getting.firstOrNull { it.song.id == song.id } } }

    // Of a link's several songs, the ones ticked out: not to be fetched.
    var leftOut by remember { mutableStateOf(emptySet<String>()) }
    val picked = found.filter { it.id !in leftOut }
    // Every sound a single song's link offers, known once the engine has worked it out.
    val one = found.singleOrNull()
    val sounds by produceState<List<SoundFormat>?>(initialValue = null, one) {
        value = one?.let { withContext(Dispatchers.IO) { Engine.sounds(it) } }
    }
    val pictures by produceState<List<PictureFormat>?>(initialValue = null, one) {
        value = one?.let { withContext(Dispatchers.IO) { Engine.pictures(it) } }
    }

    // Before the download: ask for whatever it needs and does not have, then go ahead.
    val gate = rememberDownloadGate()
    val download: () -> Unit = {
        if (picked.isNotEmpty()) {
            gate {
                feel.done()
                // Something picked for one song does not apply to several.
                picked.forEach { Downloads.add(context, it, if (found.size == 1) options else options.copy(formatId = null)) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The other app shows through, dimmed; a tap on it puts the panel away.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Taps on the panel itself must not fall through to the dimmed area.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .background(Paper)
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PanelLabel(if (asked.isNotEmpty()) "SPINE+ IS GETTING" else "QUICK DOWNLOAD")
            when {
                problem != null -> {
                    Text(text = problem.orEmpty(), color = PaperText, fontSize = 16.sp, lineHeight = 22.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BlockButton(text = "Cancel", onClick = onClose, onInk = false, modifier = Modifier.weight(1f))
                        BlockButton(text = "Try again", onClick = { attempt++ }, onInk = false, modifier = Modifier.weight(1.4f), filled = true)
                    }
                }

                songs == null -> {
                    Working(words = "Reading the link", detail = link)
                    BlockButton(text = "Cancel", onClick = onClose, onInk = false, modifier = Modifier.fillMaxWidth())
                }

                found.isEmpty() -> {
                    Text(text = "There is nothing at that link to download.", color = PaperText, fontSize = 16.sp, lineHeight = 22.sp)
                    BlockButton(text = "Close", onClick = onClose, onInk = false, modifier = Modifier.fillMaxWidth())
                }

                else -> {
                    if (found.size == 1) {
                        SongCard(song = found.single(), onInk = false)
                    } else {
                        // A link to an album or a playlist: what it is, then each song to tick in or out.
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Heading(songCount(found.size))
                            Text(
                                text = listOfNotNull(
                                    formatLength(found.sumOf { it.durationMs }).takeIf { found.any { song -> song.durationMs > 0 } },
                                    found.take(2).joinToString(", ") { it.title } + if (found.size > 2) " and ${found.size - 2} more" else "",
                                ).joinToString(" · "),
                                color = PaperMuted,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (asked.isEmpty()) {
                        if (found.size > 1) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 250.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SongPickerHead(
                                    count = found.size,
                                    picked = picked.size,
                                    onAll = { tick -> leftOut = if (tick) emptySet() else found.mapTo(HashSet()) { it.id } },
                                )
                                found.forEachIndexed { index, song ->
                                    val out = song.id in leftOut
                                    SongPickerRow(
                                        place = index + 1,
                                        song = song,
                                        onPhone = false,
                                        ticked = !out,
                                        onClick = { leftOut = if (out) leftOut - song.id else leftOut + song.id },
                                    )
                                }
                            }
                        }
                        KindChoice(options = options, onChange = choose)
                        FormatChoice(options = options, onChange = choose, sounds = sounds, pictures = pictures, canChoose = found.size == 1)
                        ExtraChoices(options = options, onChange = choose)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BlockButton(text = "Cancel", onClick = onClose, onInk = false, modifier = Modifier.weight(1f))
                            DownloadButton(
                                text = when {
                                    found.size == 1 -> listOf(
                                        "Download",
                                        sizeInMegabytes(
                                            (if (options.video) pictures?.firstOrNull { it.id == options.formatId }?.bytes
                                            else sounds?.firstOrNull { it.id == options.formatId }?.bytes) ?: 0,
                                        ),
                                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                                    picked.isEmpty() -> "Nothing ticked"
                                    else -> "Get ${songCount(picked.size)}"
                                },
                                onClick = download,
                                modifier = Modifier.weight(1.4f),
                            )
                        }
                    } else {
                        DownloadProgress(asked)
                        // A finished video can be watched from here, in the phone's own player.
                        asked.singleOrNull()?.takeIf { it.state == GetState.Done }?.videoUri?.let { saved ->
                            Row { BlockButton(text = "Watch it", onClick = { watch(context, saved) }, onInk = false, filled = true) }
                        }
                        Text(
                            text = if (options.video) {
                                "It carries on by itself. You can go back to what you were doing; it will be in your phone's Movies, and listed in the Downloader."
                            } else {
                                "It carries on by itself. You can go back to what you were doing; it will be on your Shelf."
                            },
                            color = PaperMuted,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BlockButton(text = "Open Spine+", onClick = onOpenApp, onInk = false, modifier = Modifier.weight(1f))
                            BlockButton(text = "Done", onClick = onClose, onInk = false, modifier = Modifier.weight(1.4f), filled = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text.uppercase(),
        color = PaperText,
        fontFamily = BarlowCondensed,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
    )
}
