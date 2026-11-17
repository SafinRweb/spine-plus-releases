package app.spine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.DownloadStats
import app.spine.player.data.Stats
import app.spine.player.data.StatsPeriod
import app.spine.player.data.VideoStats
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.owned
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Play

/** How many artists the picture lists: one fewer than the screen, so everything fits one page. */
const val CARD_ARTISTS = 5

/** The picture's size in the units it is laid out in: nine wide to sixteen tall, the shape of a story. */
val CardWidth = 360.dp
val CardHeight = 640.dp

/**
 * The stats as one picture, for "Save as an image": tall and narrow like a phone held
 * upright, so it can be posted as a story as it is. It holds all three kinds at once: the
 * listening and the artists it went to, then the watching and the downloading side by
 * side, with the user's name at the top and the app's at the foot.
 *
 * It is laid out at [CardWidth] by [CardHeight] and drawn at three pixels to each unit,
 * which gives a picture 1080 by 1920 whatever phone it is made on.
 */
@Composable
fun StatsCard(
    name: String,
    period: StatsPeriod,
    stats: Stats,
    videoStats: VideoStats,
    downloadStats: DownloadStats,
    appName: String,
    day: String,
    modifier: Modifier = Modifier,
) {
    val artists = stats.topArtists.take(CARD_ARTISTS)
    val longest = artists.maxOfOrNull { it.listenedMs }?.coerceAtLeast(1) ?: 1
    Column(
        modifier = modifier
            .background(Paper)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when (period) {
                    StatsPeriod.Week -> "THE LAST 7 DAYS"
                    StatsPeriod.Month -> "THE LAST MONTH"
                    StatsPeriod.Year -> "THE LAST YEAR"
                    StatsPeriod.All -> "ALL TIME"
                } + " · " + day.uppercase(),
                color = PaperMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = owned(name, "Stats").uppercase(),
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = if (name.length > 10) 34.sp else 44.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatLength(stats.listenedMs).uppercase(),
                color = PaperText,
                fontFamily = BarlowCondensed,
                fontSize = 62.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append("of music")
                    if (stats.plays > 0) {
                        append(" · ")
                        append(if (stats.plays == 1) "1 play" else "${stats.plays} plays")
                        append(" · ")
                        append(songCount(stats.songs))
                    }
                },
                color = PaperMuted,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
            )
        }

        // The artists, as on the screen: the more they were played, the taller they stand.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "MOST PLAYED ARTISTS",
                modifier = Modifier.padding(bottom = 3.dp),
                color = PaperMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            if (artists.isEmpty()) {
                Text(text = "Nothing played in this time yet.", color = PaperMuted, fontSize = 15.sp, lineHeight = 20.sp)
            }
            artists.forEachIndexed { index, row ->
                // Each takes a share of the room by how long it was played, with a floor so a name still fits.
                val share = (row.listenedMs.toFloat() / longest).coerceAtLeast(0.42f)
                Row(
                    modifier = Modifier
                        .weight(share)
                        .fillMaxWidth()
                        .background(if (index == 0) Play else Ink)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = row.name,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatLength(row.listenedMs),
                        color = Color.White,
                        fontFamily = BarlowCondensed,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        // Watching and downloading, side by side.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CardBox(
                label = "WATCHED",
                figure = if (videoStats.watchedMs > 0) formatLength(videoStats.watchedMs).uppercase() else "NOTHING YET",
                line = if (videoStats.videos == 1) "1 video" else "${videoStats.videos} videos",
                modifier = Modifier.weight(1f),
            )
            CardBox(
                label = "DOWNLOADED",
                figure = downloadStats.total.toString(),
                line = "${songCount(downloadStats.songs)} · " + if (downloadStats.videos == 1) "1 video" else "${downloadStats.videos} videos",
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = appName.uppercase(),
            color = PaperMuted,
            fontFamily = BarlowCondensed,
            fontSize = 20.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

/** One of the two blocks at the foot of the picture: what it counts, the figure, and a line under it. */
@Composable
private fun CardBox(label: String, figure: String, line: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Ink)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(text = label, color = InkMuted, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        Text(
            text = figure,
            color = Color.White,
            fontFamily = BarlowCondensed,
            fontSize = 30.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = line, color = InkMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
