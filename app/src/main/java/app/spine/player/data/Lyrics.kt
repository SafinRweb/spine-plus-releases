package app.spine.player.data

/** One line of lyrics. [timeMs] is when it is sung, or null in lyrics that carry no timing. */
data class LyricLine(val timeMs: Long?, val text: String)

/** Where a song's lyrics came from. */
enum class LyricsOrigin {
    /** Chosen or pasted by the user and kept in the app. */
    Added,

    /** A .lrc file with the song's name, in the song's folder. */
    LrcFile,

    /** Stored inside the song file itself. */
    SongFile,
}

/**
 * A song's lyrics. When [synced], every line has a time and they are in order, so the
 * screen can follow the song; otherwise the lines are plain text, with an empty line
 * between verses.
 */
data class Lyrics(val lines: List<LyricLine>, val origin: LyricsOrigin) {
    val synced: Boolean = lines.isNotEmpty() && lines.all { it.timeMs != null }

    /** The line being sung at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (lines[middle].timeMs!! <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}

/** "[01:23.45]" or "[1:23]" at the start of a line, possibly several in a row. */
private val TimeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/** "[ar:Artist]", "[offset:+250]" and the like: information about the file, not a line. */
private val InfoTag = Regex("""^\[([A-Za-z#]+):(.*)]$""")

/** "<01:23.45>" marks inside a line, used by some files to time single words. */
private val WordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

/**
 * Reads lyrics text. Text in the LRC format ("[01:23.45] words") becomes timed lines;
 * anything else is taken as plain lyrics. Returns no lines if there is nothing to show.
 */
fun parseLyrics(text: String): List<LyricLine> {
    val timed = mutableListOf<LyricLine>()
    val plain = mutableListOf<String>()
    var offsetMs = 0L

    for (raw in text.lineSequence()) {
        val line = raw.trim().trim('﻿')
        val info = InfoTag.matchEntire(line)
        if (info != null) {
            // A positive offset means the lyrics should appear that much sooner.
            if (info.groupValues[1].equals("offset", ignoreCase = true)) {
                offsetMs = info.groupValues[2].trim().toLongOrNull() ?: 0L
            }
            continue
        }

        val times = mutableListOf<Long>()
        var rest = line
        while (true) {
            val tag = TimeTag.matchAt(rest, 0) ?: break
            val (minutes, seconds, fraction) = tag.destructured
            val fractionMs = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.toLong()
            }
            times += minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
            rest = rest.substring(tag.range.last + 1).trimStart()
        }
        val words = rest.replace(WordTag, "").trim()

        if (times.isEmpty()) {
            plain += words
        } else if (words.isNotEmpty()) {
            times.forEach { timed += LyricLine(it, words) }
        }
    }

    if (timed.isNotEmpty()) {
        return timed
            .map { it.copy(timeMs = (it.timeMs!! - offsetMs).coerceAtLeast(0)) }
            .sortedBy { it.timeMs }
    }

    // Plain lyrics: keep one empty line between verses, none at the ends.
    val lines = mutableListOf<LyricLine>()
    for (words in plain) {
        if (words.isEmpty() && (lines.isEmpty() || lines.last().text.isEmpty())) continue
        lines += LyricLine(null, words)
    }
    while (lines.isNotEmpty() && lines.last().text.isEmpty()) lines.removeAt(lines.lastIndex)
    return lines
}
