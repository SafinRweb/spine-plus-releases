package app.spine.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, drawn from the same SVG paths as the design canvas.
 * They are white; tint them where they are used.
 */
object SpineIcons {
    val ChevronLeft = stroked("ChevronLeft", "M15 5l-7 7 7 7")
    val ChevronRight = stroked("ChevronRight", "M9 5l7 7-7 7")
    val ChevronDown = stroked("ChevronDown", "M5 9l7 7 7-7")
    val Search = stroked("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4")
    val Close = stroked("Close", "M6 6l12 12", "M18 6L6 18")
    val Download = stroked("Download", "M12 4v12", "M6 11l6 6 6-6", "M5 20h14")
    val Pencil = stroked("Pencil", "M4 20h4L19 9l-4-4L4 16v4z", "M13 7l4 4")
    val Check = stroked("Check", "M4 12.5l5.5 5.5L20 6.5", width = 2.5f)
    val Previous = stroked("Previous", "M19 5v14L8 12z", "M5 5v14")
    val Next = stroked("Next", "M5 5v14l11-7z", "M19 5v14")
    val Pause = stroked("Pause", "M8 5v14", "M16 5v14", width = 3f)
    val SkipBack = stroked("SkipBack", "M11 5L4 12l7 7", "M4 12h9a7 7 0 0 1 7 7")
    val SkipForward = stroked("SkipForward", "M13 5l7 7-7 7", "M20 12h-9a7 7 0 0 0-7 7")
    val FullScreen = stroked("FullScreen", "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")
    val Shuffle = stroked(
        "Shuffle",
        "M16 3h5v5",
        "M4 20L21 3",
        "M21 16v5h-5",
        "M15 15l6 6",
        "M4 4l5 5",
    )
    val Repeat = stroked("Repeat", *REPEAT_PATHS)
    val RepeatOne = stroked("RepeatOne", *REPEAT_PATHS, "M11 10.5l1.5-1v5")

    val Stats = stroked("Stats", "M6 20V10", "M12 20V4", "M18 20v-7")
    val Settings = stroked(
        "Settings",
        "M4 7h10",
        "M18 7h2",
        "M4 17h2",
        "M10 17h10",
        "M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
        "M8 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
    )
    val Heart = stroked("Heart", HEART_PATH)

    val HeartFilled: ImageVector = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(HEART_PATH), fill = SolidColor(Color.White))
        .build()

    val Play: ImageVector = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes("M8 5v14l11-7z"), fill = SolidColor(Color.White))
        .build()

    /** A tall two-headed arrow for the "drag to seek" hint. */
    val UpDown: ImageVector = ImageVector.Builder("UpDown", 24.dp, 44.dp, 24f, 44f)
        .strokePath("M5 11l7-7 7 7", 2f)
        .strokePath("M12 4v36", 2f)
        .strokePath("M5 33l7 7 7-7", 2f)
        .build()
}

private const val HEART_PATH =
    "M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2z"

private val REPEAT_PATHS = arrayOf(
    "M17 2l4 4-4 4",
    "M3 11V9a3 3 0 0 1 3-3h15",
    "M7 22l-4-4 4-4",
    "M21 13v2a3 3 0 0 1-3 3H3",
)

private fun stroked(name: String, vararg paths: String, width: Float = 2f): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    paths.forEach { builder.strokePath(it, width) }
    return builder.build()
}

private fun ImageVector.Builder.strokePath(path: String, width: Float): ImageVector.Builder =
    addPath(
        pathData = addPathNodes(path),
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
