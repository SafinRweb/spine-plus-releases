package app.spine.player.ui

import app.spine.player.data.Library
import app.spine.player.data.LyricLine
import app.spine.player.data.Lyrics
import app.spine.player.data.LyricsOrigin
import app.spine.player.data.PlayTotal
import app.spine.player.data.Playlist
import app.spine.player.data.Song
import app.spine.player.data.DownloadDay
import app.spine.player.data.DownloadStats
import app.spine.player.data.VideoStats
import app.spine.player.data.VideoWatched
import app.spine.player.data.buildLibrary
import app.spine.player.playback.NowPlaying
import app.spine.player.ui.theme.PlayColour
import app.spine.player.ui.theme.ShelfColour

/**
 * A made-up music collection for taking screenshots: store listings should not show
 * anyone's own library, or real artists. Only a debug build can switch it on, by starting
 * the app with the extra "demo" set to true:
 *
 *     adb shell am start -n app.spine.player/.MainActivity --ez demo true
 *
 * Further extras pick what is shown, so no taps are needed: `--es screen fix` starts on
 * "Fix your library", and `--es play Orange --es shelf Navy` shows those colours without
 * saving them.
 *
 * The screens then show this library with a song part-way through. Nothing here can be
 * played: the songs do not exist as files.
 */
object DemoMode {

    var enabled = false

    /** Colours to show instead of the saved ones (extras "play" and "shelf", such as "Orange" and "Navy"). */
    var playColour: PlayColour? = null
    var shelfColour: ShelfColour? = null

    /**
     * A screen to start on instead of the Shelf (extra "screen"): "fix", "settings",
     * "play-colour" and "shelf-colour" for Settings with that chooser open, "album", "artist"
     * and "playlist" for one of each, "edit-album", "edit-artist", "edit-playlist", and
     * "welcome" or "welcome-denied" for the first-run screen.
     */
    var screen: String? = null

    /** Animations on or off instead of the saved choice (extra "animations", a boolean). */
    var animations: Boolean? = null

    /**
     * Text size to show the app at instead of the phone's own (extra "fontScale", a float such
     * as 1.3 or 2.0), to check a screen at large text without changing the phone's settings.
     */
    var fontScale: Float? = null

    /**
     * A screen width to show the app at, in dp (extra "width", a whole number such as 360),
     * to check a screen as a smaller phone would show it.
     */
    var widthDp: Int? = null

    /** Dark or light instead of the saved choice (extra "dark", a boolean). */
    var dark: Boolean? = null

    /** A name to head the Shelf with instead of the saved one (extra "name"). */
    var name: String? = null

    /**
     * For trying the video player on a phone in someone's hand without disturbing them:
     * "quiet" plays what another app hands over with no sound, and "wide" turns the video
     * player sideways as it opens (extras "quiet" and "wide", booleans). They work without
     * the made-up library, since the point is to play a real file.
     */
    var quiet = false
    var wide = false

    /** Makes and saves the stats picture as the Stats screen opens, without a tap (extra "savestats", a boolean). */
    var saveStats = false

    /** Which stats to show on the Stats screen (extra "stats": "songs", "videos" or "downloads"). */
    var statsKind: String? = null

    /** Made-up watching and downloading, so the Stats screen has something to draw. */
    val videoStats: VideoStats by lazy {
        val hour = 3_600_000L
        VideoStats(
            watchedMs = (6.2 * hour).toLong(),
            sittings = 23,
            videos = 9,
            longestMs = 58 * 60_000L,
            byDay = listOf(0.6, 0.4, 0.9, 0.5, 1.1, 1.6, 1.1).map { (it * hour).toLong() },
            top = library.songs.take(3).zip(listOf(174, 96, 35)) { song, minutes -> VideoWatched(song, minutes * 60_000L) },
        )
    }
    val downloadStats: DownloadStats by lazy {
        val counts = listOf(3 to 0, 0 to 0, 12 to 1, 5 to 0, 0 to 0, 41 to 2, 9 to 0, 2 to 1, 0 to 0, 18 to 0, 7 to 3, 0 to 0, 26 to 1, 4 to 0)
        val days = counts.mapIndexed { index, (songs, videos) -> DownloadDay("${index + 3} Nov", songs, videos) }
        DownloadStats(
            songs = 312,
            videos = 18,
            bytes = (4.2 * 1024 * 1024 * 1024).toLong(),
            playMs = (21 * 60 + 40) * 60_000L,
            days = days,
            busiest = days.maxByOrNull { it.total },
            topKind = "M4A",
            neverPlayed = 47,
        )
    }

    /** Words to put in the Get music box (extra "get", with `--es screen get`), so its results can be seen without typing. */
    var getWords: String? = null

    /** Reads the extras a debug build was started with. */
    fun start(
        demo: Boolean,
        play: String?,
        shelf: String?,
        screen: String?,
        animations: Boolean?,
        fontScale: Float? = null,
        getWords: String? = null,
        widthDp: Int? = null,
        dark: Boolean? = null,
        statsKind: String? = null,
        name: String? = null,
    ) {
        enabled = demo
        if (!demo) return
        this.animations = animations
        this.fontScale = fontScale
        this.getWords = getWords
        this.widthDp = widthDp
        this.dark = dark
        this.statsKind = statsKind
        this.name = name
        playColour = PlayColour.entries.firstOrNull { it.name.equals(play, ignoreCase = true) }
        shelfColour = ShelfColour.entries.firstOrNull { it.name.equals(shelf, ignoreCase = true) }
        this.screen = screen
    }

    /** What "Fix your library" would find in a library that needs it: the made-up one is tidy. */
    val findings = FixFindings(
        namesSure = 23,
        namesToCheck = 5,
        duplicateCopies = 4,
        duplicateBytes = 22L * 1024 * 1024,
        artistsToSort = 3,
        albumsToClear = 8,
    )

    private class DemoAlbum(val artist: String, val title: String, vararg val tracks: Pair<String, Int>)

    private val albums = listOf(
        DemoAlbum(
            "June Arcade", "Paper Sun",
            "First Light" to 190, "Paper Sun" to 264, "Small Hours" to 222, "Interlude" to 156,
            "Long Way Round" to 302, "Glass" to 210, "Slow Tide" to 250, "Open Water" to 170, "Last Train" to 276,
        ),
        DemoAlbum(
            "June Arcade", "Harbour Lights",
            "Harbour Lights" to 231, "Salt Air" to 198, "Low Tide Radio" to 244, "The Long Pier" to 305, "Gulls" to 142,
        ),
        DemoAlbum(
            "Iris Hale", "Blue Hour",
            "Blue Hour" to 200, "Violet Street" to 236, "Before the Rain" to 188, "Night Swimming Pool" to 274,
            "Cassette" to 162, "Slow Morning" to 221, "Afterglow" to 318,
        ),
        DemoAlbum(
            "Hollow Pine", "Night Bus",
            "Night Bus" to 247, "Kites" to 170, "Window Seat" to 205, "Last Stop" to 289, "Depot" to 133, "Home by Three" to 226,
        ),
        DemoAlbum(
            "Mara Vell", "Glasshouse",
            "Glasshouse" to 362, "Out of the Blue" to 268, "Fern" to 181, "Condensation" to 240,
            "Warm Room" to 215, "Seedling" to 149, "Greenhouse Rain" to 296, "Open the Roof" to 233,
        ),
        DemoAlbum(
            "Delta Nine", "Northbound",
            "Northbound" to 250, "Mile Marker" to 212, "Rest Stop" to 176, "Headlights" to 283, "Border Town" to 241,
        ),
        DemoAlbum(
            "Vessel", "Low Sun",
            "Low Sun" to 228, "Bluebird" to 302, "Amber" to 195, "Long Shadows" to 264, "Dusk Patrol" to 207, "Embers" to 322,
        ),
        DemoAlbum(
            "Sol Marlowe", "Field Notes",
            "Field Notes" to 184, "Paper Map" to 219, "Tall Grass" to 253, "Creek" to 147,
            "Weathervane" to 236, "Dry Stone" to 201, "Homeward" to 288,
        ),
        DemoAlbum(
            "The Lanterns", "Quiet Machines",
            "Quiet Machines" to 242, "Standby" to 196, "Soft Reset" to 268, "Dial Tone" to 159, "Overnight" to 311, "Sleep Mode" to 224,
        ),
    )

    private val singles = listOf(
        Triple("Mara Vell", "Tell Me Where You've Been", 248),
        Triple("Iris Hale", "Lighthouse (Live at the Old Harbour Hall)", 341),
        Triple("Delta Nine", "Overpass", 187),
        Triple("Hollow Pine", "Paper Boats", 213),
        Triple("Vessel", "Wild Honey", 232),
        Triple("Sol Marlowe", "Riverbed", 266),
        Triple("The Lanterns", "Afterimage", 205),
        Triple("June Arcade", "Postcard", 178),
    )

    val songs: List<Song> = buildList {
        var id = 1_000L
        albums.forEachIndexed { albumIndex, album ->
            album.tracks.forEachIndexed { trackIndex, (title, seconds) ->
                add(
                    Song(
                        id = id++, title = title, artist = album.artist, album = album.title,
                        albumId = 10L + albumIndex, durationMs = seconds * 1000L, trackNumber = trackIndex + 1,
                        discNumber = 1, folder = "Music/${album.artist}/${album.title}", fileName = "$title.m4a",
                        dateAddedSec = id, sizeBytes = seconds * 32_000L,
                    ),
                )
            }
        }
        singles.forEachIndexed { index, (artist, title, seconds) ->
            add(
                Song(
                    id = id++, title = title, artist = artist, album = title, albumId = 500L + index,
                    durationMs = seconds * 1000L, trackNumber = 0, discNumber = 0, folder = "Music/Singles",
                    fileName = "$title.m4a", dateAddedSec = id, sizeBytes = seconds * 32_000L,
                ),
            )
        }
    }.sortedBy { it.title.lowercase() }

    val library: Library by lazy { buildLibrary(songs) }

    private fun song(title: String): Song = songs.first { it.title == title }

    /** "Small Hours", a third of the way through, playing from the album it is on. */
    private val playing: Song get() = song("Small Hours")

    val nowPlaying: NowPlaying
        get() = NowPlaying(
            songId = playing.id,
            title = playing.title,
            artist = playing.artist,
            album = playing.album,
            isPlaying = true,
            positionMs = 84_000,
            durationMs = playing.durationMs,
        )

    /** The rest of the playlist the song is playing from. */
    val queue: List<QueueSong>
        get() = lateNight.drop(1).mapIndexed { index, song -> QueueSong(index, song) }

    private val lateNight: List<Song>
        get() = listOf(
            "Blue Hour", "Small Hours", "First Light", "Bluebird", "Glass", "Out of the Blue",
            "Northbound", "Glasshouse", "Kites", "Night Bus", "Afterglow", "Overnight",
        ).map(::song)

    const val PLAYING_PLAYLIST = 2L

    val playlists: List<Playlist>
        get() = listOf(
            Playlist(
                1L, "Favourites",
                listOf(
                    "Glasshouse", "Kites", "Paper Sun", "Bluebird", "Afterglow", "Northbound", "Wild Honey",
                    "Harbour Lights", "Field Notes", "Soft Reset", "Lighthouse (Live at the Old Harbour Hall)",
                    "Last Train", "Violet Street", "Embers", "Tall Grass", "Window Seat",
                ).map(::song),
            ),
            Playlist(PLAYING_PLAYLIST, "Late night", lateNight),
            Playlist(3L, "Morning run", listOf("Headlights", "Mile Marker", "Standby", "Open Water", "Paper Map", "Amber").map(::song)),
            Playlist(-1L, "Most played", songs.filter { (playTotals[it.id]?.plays ?: 0) >= 12 }, rule = "played 3 times or more"),
            Playlist(-2L, "Recently added", songs.sortedByDescending { it.dateAddedSec }.take(9), rule = "added in the last 30 days"),
        )

    /** A listening history with clear favourites, so the stats have a shape. */
    val playTotals: Map<Long, PlayTotal> by lazy {
        val weight = mapOf(
            "June Arcade" to 9, "Mara Vell" to 6, "Iris Hale" to 5, "Vessel" to 4,
            "Hollow Pine" to 3, "Delta Nine" to 2, "Sol Marlowe" to 2, "The Lanterns" to 1,
        )
        songs.mapIndexed { index, song ->
            val plays = weight.getValue(song.artist) * (1 + index % 4)
            song.id to PlayTotal(plays, plays * song.durationMs * 9 / 10)
        }.toMap()
    }

    /** Words written for this demo; they belong to no real song. */
    val lyrics: Lyrics = Lyrics(
        listOf(
            40 to "The harbour bell is ringing",
            48 to "over water, over stone",
            56 to "Nobody's keeping score tonight",
            64 to "The street lights hum",
            72 to "a quiet kind of blue",
            80 to "We count the small hours",
            90 to "until the morning's through",
            98 to "Hold the line, hold the light",
            106 to "we are not sleeping any more",
            114 to "Small hours, small hours",
            122 to "tell me where you've been",
            130 to "Small hours, small hours",
            138 to "the tide is coming in",
        ).map { (seconds, words) -> LyricLine(seconds * 1000L, words) },
        LyricsOrigin.LrcFile,
    )
}
