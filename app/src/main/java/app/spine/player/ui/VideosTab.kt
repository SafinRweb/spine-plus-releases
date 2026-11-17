package app.spine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.spine.player.data.Library
import app.spine.player.data.Song
import app.spine.player.data.VideoProgress
import app.spine.player.download.Downloads
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.components.MenuAction
import app.spine.player.ui.components.formatDuration
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.theme.PaperMuted

/**
 * The Videos tab: every video the Downloader has saved, newest first. A tap plays one in
 * the app's own player, with the rest of the list lined up after it; the play colour in a
 * row shows how far that video has been watched. Holding one gives what holding a song
 * gives (play next, the queue, a playlist), and the ways to pass the file on, to go back
 * to where it came from, and to delete it.
 */
@Composable
fun VideoList(
    library: Library,
    nowPlaying: NowPlaying,
    actions: LibraryActions,
    /** Plays the list from the video at [index] and opens the player. */
    onPlay: (videos: List<Song>, index: Int) -> Unit,
) {
    val context = LocalContext.current
    val list = library.videos
    // Counted up each time the app comes back into view, so the rows read again how far each was watched.
    var looked by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        looked++
        onPauseOrDispose { }
    }

    if (list.isEmpty()) {
        Text(
            text = "No videos yet. In the Downloader, paste a link and choose Video; it will show here.",
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            color = PaperMuted,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
        return
    }

    // Where each video came from, for "Copy the link" and "Open the page".
    val sources = remember(list) { Downloads.recentVideos(context).associate { it.uri to it.source } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(key = "count") {
            Text(
                text = "${if (list.size == 1) "1 VIDEO" else "${list.size} VIDEOS"} · ${formatLength(list.sumOf { it.durationMs }).uppercase()}",
                modifier = Modifier.padding(bottom = 5.dp),
                color = PaperMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        itemsIndexed(list, key = { _, video -> video.id }) { index, video ->
            val address = video.uri.toString()
            val playing = video.id == nowPlaying.songId
            // The one that is playing shows where it is now; the others, where they were left.
            val watched = if (playing) nowPlaying.progress else remember(video.id, looked, nowPlaying.songId) {
                VideoProgress.fraction(context, address)
            }
            VideoRow(
                title = video.title,
                line = listOfNotNull(
                    video.artist.takeIf { it.isNotBlank() },
                    formatDuration(video.durationMs).takeIf { video.durationMs > 0 },
                ).joinToString(" · "),
                onClick = { onPlay(list, index) },
                modifier = Modifier.animateItem(),
                watched = watched,
                onLongPress = { bounds ->
                    actions.songMenuWith(
                        video,
                        bounds,
                        buildList {
                            add(MenuAction("Share the file") { shareFile(context, video.uri, "video/*") })
                            val source = sources[address].orEmpty()
                            if (source.isNotBlank()) {
                                add(MenuAction("Copy the link") { copyText(context, source) })
                                add(MenuAction("Open the page it came from") { openPage(context, source) })
                            }
                            add(MenuAction("Open in another player") { watch(context, address) })
                            add(
                                MenuAction(
                                    "Details",
                                    detail = listOf(video.fileName.substringAfterLast('.', "").uppercase(), sizeInMegabytes(video.sizeBytes))
                                        .filter { it.isNotEmpty() }.joinToString(" · "),
                                ) { },
                            )
                        },
                    )
                },
            )
        }
    }
}
