package app.spine.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.spine.player.data.DownloadDay
import app.spine.player.data.DownloadStats
import app.spine.player.data.StatsKind
import app.spine.player.data.VideoStats
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.components.SpineIcons
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.InkOnPaper
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spine.player.data.Artist
import app.spine.player.data.Song
import app.spine.player.data.Stats
import app.spine.player.data.StatsPeriod
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.OwnedTitle
import app.spine.player.ui.components.formatLength
import app.spine.player.ui.components.pressable
import app.spine.player.ui.components.songCount
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Ink
import app.spine.player.ui.theme.InkMuted
import app.spine.player.ui.theme.OnPlayMuted
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.Play

/** The tallest an artist's strip gets; the most played artist has exactly this height. */
private val TallestStrip = 96.dp
private val ShortestStrip = 52.dp

/**
 * What was listened to over a period. Artists are strips whose height follows how long
 * they were listened to, so the list reads as a ranking and long names have room to wrap.
 * The top artist is in the play colour.
 */
@Composable
fun StatsScreen(
    /** What the user asked to be called, or empty: it heads the screen as "NAME'S STATS". */
    name: String,
    /** Which of the three is on show, and the way to choose another. */
    kind: StatsKind,
    onChooseKind: () -> Unit,
    stats: Stats,
    videoStats: VideoStats,
    downloadStats: DownloadStats,
    period: StatsPeriod,
    onPeriodChange: (StatsPeriod) -> Unit,
    onBack: () -> Unit,
    onOpenArtist: (artist: Artist, from: Rect?) -> Unit,
    onPlaySong: (songs: List<Song>, index: Int) -> Unit,
    /** Saves the picture of the stats to the phone, and gives where it went, or null if it could not. */
    onSaveImage: suspend (ImageBitmap) -> Uri?,
    /** Hands a saved picture to Android's Share list, for posting it as a story or sending it on. */
    onShareImage: (Uri) -> Unit,
) {
    val longestMs = stats.topArtists.maxOfOrNull { it.listenedMs }?.coerceAtLeast(1) ?: 1

    // "Save as an image" makes one picture of all the stats, shaped like a story (see
    // StatsCard). It is laid out and recorded for a moment without ever being shown.
    val picture = rememberGraphicsLayer()
    var capturing by remember { mutableStateOf(DemoMode.saveStats) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(capturing) {
        if (!capturing) return@LaunchedEffect
        // One frame for the picture to be laid out, one for it to be recorded.
        withFrameNanos { }
        withFrameNanos { }
        val where = runCatching { onSaveImage(picture.toImageBitmap()) }.getOrNull()
        saved = where
        failed = where == null
        capturing = false
    }
    // The button offers to share what was saved for a while, then goes back to saving.
    LaunchedEffect(saved, failed) {
        if (saved != null || failed) {
            delay(if (failed) 3_500 else 12_000)
            saved = null
            failed = false
        }
    }
    val context = LocalContext.current
    val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                run {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackLink(text = "Shelf", onClick = onBack, color = PaperText)
                        // Which stats: across from Back, where a thumb finds it.
                        Row(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable(role = Role.DropdownList, onClickLabel = "Choose which stats", onClick = onChooseKind)
                                .border(1.dp, InkOnPaper)
                                .padding(start = 14.dp, end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(text = kind.label, color = PaperText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Icon(SpineIcons.ChevronDown, contentDescription = null, tint = PaperText, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                OwnedTitle(name = name, word = "Stats", modifier = Modifier.fillMaxWidth())
                PeriodTabs(selected = period, onSelect = onPeriodChange)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when (kind) {
                            StatsKind.Songs -> formatLength(stats.listenedMs).uppercase()
                            StatsKind.Videos -> formatLength(videoStats.watchedMs).uppercase()
                            StatsKind.Downloads -> downloadStats.total.toString()
                        },
                        color = PaperText,
                        fontFamily = BarlowCondensed,
                        fontSize = 60.sp,
                        lineHeight = 60.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = buildString {
                            append(
                                when (kind) {
                                    StatsKind.Songs -> "listened"
                                    StatsKind.Videos -> "watched"
                                    StatsKind.Downloads -> "downloaded"
                                },
                            )
                            append(
                                when (period) {
                                    StatsPeriod.Week -> " in the last 7 days"
                                    StatsPeriod.Month -> " in the last month"
                                    StatsPeriod.Year -> " in the last year"
                                    StatsPeriod.All -> " in total"
                                },
                            )
                            if (kind == StatsKind.Songs && stats.plays > 0) {
                                append(" · ")
                                append(if (stats.plays == 1) "1 play" else "${stats.plays} plays")
                                append(" · ")
                                append(songCount(stats.songs))
                            }
                        },
                        color = PaperMuted,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        when (kind) {
            StatsKind.Songs -> {
            if (stats.plays == 0) {
                item(key = "empty") {
                    Text(
                        text = "Nothing counted in this period yet. A song counts once you have heard " +
                            "half of it, or four minutes.",
                        modifier = Modifier.padding(top = 24.dp),
                        color = PaperMuted,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    )
                }
            } else {
                item(key = "artists-label") { Label("MOST PLAYED ARTISTS") }
                itemsIndexed(stats.topArtists, key = { _, row -> "artist:${row.name}" }) { index, row ->
                    val height = (TallestStrip * (row.listenedMs.toFloat() / longestMs)).coerceAtLeast(ShortestStrip)
                    StatStrip(
                        name = row.name,
                        detail = null,
                        value = formatLength(row.listenedMs),
                        largeValue = true,
                        minHeight = height,
                        highlighted = index == 0,
                        onClick = row.artist?.let { artist -> { bounds: Rect? -> onOpenArtist(artist, bounds) } },
                        modifier = Modifier
                            .padding(bottom = 3.dp)
                            .animateItem(),
                    )
                }

                item(key = "songs-label") { Label("MOST PLAYED SONGS") }
                itemsIndexed(stats.topSongs, key = { _, row -> "song:${row.song.id}" }) { index, row ->
                    StatStrip(
                        name = row.song.title,
                        detail = row.song.artist,
                        value = if (row.plays == 1) "1 play" else "${row.plays} plays",
                        largeValue = false,
                        minHeight = ShortestStrip,
                        highlighted = false,
                        onClick = { onPlaySong(stats.topSongs.map { it.song }, index) },
                        modifier = Modifier
                            .padding(bottom = 3.dp)
                            .animateItem(),
                    )
                }
            }
            }

            StatsKind.Videos -> {
                if (videoStats.watchedMs == 0L) {
                    item(key = "no-videos") {
                        Text(
                            text = "Nothing watched in this period yet. Watching is counted from the day " +
                                "this was added, for the videos you download.",
                            modifier = Modifier.padding(top = 24.dp),
                            color = PaperMuted,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                        )
                    }
                } else {
                    item(key = "days-label") { Label("BY DAY OF THE WEEK") }
                    item(key = "days") { WeekPie(videoStats.byDay) }
                    if (videoStats.top.isNotEmpty()) {
                        item(key = "watched-label") { Label("MOST WATCHED") }
                        itemsIndexed(videoStats.top, key = { _, row -> "watched:${row.video.id}" }) { index, row ->
                            FactRow(row.video.title, formatLength(row.watchedMs), last = index == videoStats.top.lastIndex)
                        }
                    }
                    item(key = "watch-line") {
                        Text(
                            text = listOf(
                                if (videoStats.sittings == 1) "1 sitting" else "${videoStats.sittings} sittings",
                                if (videoStats.videos == 1) "1 video" else "${videoStats.videos} videos",
                                "longest sitting ${formatLength(videoStats.longestMs)}",
                            ).joinToString(" · "),
                            modifier = Modifier.padding(top = 14.dp),
                            color = PaperMuted,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            StatsKind.Downloads -> {
                item(key = "spines-label") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "THE LAST 14 DAYS", color = PaperMuted, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).background(InkOnPaper))
                            Text(text = "songs", color = PaperMuted, fontSize = 13.sp)
                            Box(Modifier.padding(start = 6.dp).size(10.dp).background(Play))
                            Text(text = "videos", color = PaperMuted, fontSize = 13.sp)
                        }
                    }
                }
                item(key = "spines") { DaySpines(downloadStats.days, downloadStats.busiest) }
                item(key = "facts") {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        val facts = buildList {
                            add("Songs and videos" to "${downloadStats.songs} and ${downloadStats.videos}")
                            add("Room they take" to dataSize(downloadStats.bytes))
                            add("Played back to back" to formatLength(downloadStats.playMs))
                            if (downloadStats.shelfCm > 0) {
                                val cm = downloadStats.shelfCm
                                add("As CDs on a real shelf" to if (cm >= 100) "${"%.1f".format(cm / 100f)} m wide" else "$cm cm wide")
                            }
                            if (downloadStats.topKind.isNotEmpty()) add("Kind you pick most" to downloadStats.topKind)
                            if (downloadStats.songs > 0) {
                                add("Never played yet" to if (downloadStats.neverPlayed == 0) "None" else songCount(downloadStats.neverPlayed))
                            }
                        }
                        facts.forEachIndexed { index, (label, value) -> FactRow(label, value, last = index == facts.lastIndex) }
                    }
                }
            }
        }

        item(key = "save") {
            val where = saved
            BlockButton(
                text = when {
                    failed -> "Could not save the picture"
                    where != null -> "Saved in your Pictures. Share it"
                    capturing -> "Making the picture"
                    else -> "Save as an image"
                },
                onClick = {
                    when {
                        where != null -> onShareImage(where)
                        !capturing && !failed -> capturing = true
                    }
                },
                onInk = false,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                filled = where != null,
            )
        }
    }
    if (capturing) {
        // Three pixels to each unit whatever the phone, so the picture is always 1080 by 1920.
        // It is recorded, and nothing of it is drawn on the screen.
        val today = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date()) }
        CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) {
            StatsCard(
                name = name,
                period = period,
                stats = stats,
                videoStats = videoStats,
                downloadStats = downloadStats,
                appName = appName,
                day = today,
                modifier = Modifier
                    .requiredSize(CardWidth, CardHeight)
                    .drawWithContent { picture.record { this@drawWithContent.drawContent() } },
            )
        }
    }
    }
}

/** Bytes as "4.2 GB" or "310 MB". */
private fun dataSize(bytes: Long): String {
    val megabytes = bytes / (1024f * 1024f)
    return if (megabytes >= 1024f) "${"%.1f".format(megabytes / 1024f)} GB" else "${megabytes.toInt()} MB"
}

/**
 * Watching time by day of the week, as a pie: the day with the most is in the play
 * colour, the rest in shades of the page's ink, fading as they get smaller. The list
 * beside it gives each day's time, Monday first.
 */
@Composable
private fun WeekPie(byDay: List<Long>) {
    val total = byDay.sum().coerceAtLeast(1)
    // Largest first decides the shade; the slices themselves run Monday to Sunday.
    val rank = byDay.indices.sortedByDescending { byDay[it] }.withIndex().associate { (place, day) -> day to place }
    val fades = listOf(0f, 0f, 0.18f, 0.36f, 0.5f, 0.62f, 0.72f)
    val shades = byDay.indices.map { day ->
        val place = rank.getValue(day)
        if (place == 0) Play else lerp(PaperText, Paper, fades[place])
    }
    val gap = Paper
    // Side by side where there is room for both; on a narrow phone or with large text the
    // list goes under the pie, so a day's name is never squeezed to a letter a line.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val beside = maxWidth >= 340.dp
    val pie: @Composable () -> Unit = {
        Canvas(
            modifier = Modifier
                .size(180.dp)
                .semantics {
                    contentDescription = "Watching time by day of the week. " +
                        "${VideoStats.DayNames[byDay.indices.maxBy { byDay[it] }]} has the most."
                },
        ) {
            var start = -90f
            for (day in byDay.indices) {
                val sweep = 360f * byDay[day] / total
                if (sweep > 0f) drawArc(shades[day], start, sweep, useCenter = true)
                start += sweep
            }
            // A thin line of the page between slices, so neighbours of a like shade stay apart.
            start = -90f
            for (day in byDay.indices) {
                val sweep = 360f * byDay[day] / total
                if (sweep > 0f && sweep < 360f) {
                    val angle = Math.toRadians(start.toDouble())
                    val edge = Offset(
                        center.x + (size.minDimension / 2) * kotlin.math.cos(angle).toFloat(),
                        center.y + (size.minDimension / 2) * kotlin.math.sin(angle).toFloat(),
                    )
                    drawLine(gap, center, edge, strokeWidth = 2.dp.toPx())
                }
                start += sweep
            }
        }
    }
    val list: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in byDay.indices) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).background(if (byDay[day] > 0) shades[day] else Hairline))
                    Text(
                        text = VideoStats.DayNames[day],
                        modifier = Modifier.weight(1f),
                        color = PaperText,
                        fontSize = 14.sp,
                        fontWeight = if (rank.getValue(day) == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = if (byDay[day] == 0L) "none" else formatLength(byDay[day]),
                        color = PaperMuted,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
    if (beside) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            pie()
            list(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            pie()
            list(Modifier.fillMaxWidth())
        }
    }
    }
}

/**
 * The last fourteen days of downloading, a standing spine for each: as tall as the day
 * was busy, songs in the shelf colour with the videos in the play colour at its foot. A
 * day with nothing is a thin line.
 */
@Composable
private fun DaySpines(days: List<DownloadDay>, busiest: DownloadDay?) {
    val most = days.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    // The shelf colour, or its lighter outline shade on dark paper, where the colour itself would be lost.
    val ink = InkOnPaper
    val play = Play
    val empty = Hairline
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics {
                    contentDescription = "Downloads on each of the last 14 days." +
                        if (busiest != null) " The busiest was ${busiest.label}, with ${busiest.total}." else " None."
                },
        ) {
            val gap = 4.dp.toPx()
            val width = (size.width - gap * (days.size - 1)) / days.size.coerceAtLeast(1)
            days.forEachIndexed { index, day ->
                val x = index * (width + gap)
                if (day.total == 0) {
                    drawRect(empty, Offset(x, size.height - 2.dp.toPx()), Size(width, 2.dp.toPx()))
                } else {
                    val height = (size.height * day.total / most).coerceAtLeast(4.dp.toPx())
                    val videos = height * day.videos / day.total
                    drawRect(ink, Offset(x, size.height - height), Size(width, height - videos))
                    if (videos > 0f) drawRect(play, Offset(x, size.height - videos), Size(width, videos))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = days.firstOrNull()?.label.orEmpty(), color = PaperMuted, fontSize = 12.sp)
            Text(
                text = if (busiest != null) "busiest: ${busiest.label}, ${busiest.total}" else "nothing in these days",
                color = PaperMuted,
                fontSize = 12.sp,
            )
            Text(text = days.lastOrNull()?.label.orEmpty(), color = PaperMuted, fontSize = 12.sp)
        }
    }
}

/** One line of a list of facts: what it is on the left, the figure on the right. */
@Composable
private fun FactRow(label: String, value: String, last: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .drawBehind {
                if (!last) {
                    val thickness = 1.dp.toPx()
                    drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
                }
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = PaperText,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = value, color = PaperMuted, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun PeriodTabs(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatsPeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .clickable(role = Role.Tab, onClick = { onSelect(period) })
                    .drawBehind {
                        if (isSelected) {
                            val thickness = 2.dp.toPx()
                            drawRect(PaperText, Offset(0f, size.height - thickness), Size(size.width, thickness))
                        }
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = period.label,
                    color = if (isSelected) PaperText else PaperMuted,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        color = PaperMuted,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

/** One row of the stats: a name that may wrap, an optional second line, and a value at the right. */
@Composable
private fun StatStrip(
    name: String,
    detail: String?,
    value: String,
    largeValue: Boolean,
    minHeight: Dp,
    highlighted: Boolean,
    onClick: ((bounds: Rect?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val quiet = if (highlighted) OnPlayMuted else InkMuted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .background(if (highlighted) Play else Ink)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = if (detail == null) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    color = quiet,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (largeValue) {
            Text(
                text = value,
                color = Color.White,
                fontFamily = BarlowCondensed,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text(text = value, color = quiet, fontSize = 13.sp)
        }
    }
}
