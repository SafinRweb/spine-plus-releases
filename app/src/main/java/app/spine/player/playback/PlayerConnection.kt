package app.spine.player.playback

import android.content.ComponentName
import android.content.Context
import android.view.TextureView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.spine.player.data.Song
import app.spine.player.data.songUri
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepeatSetting { Off, All, One }

/** What the UI needs to know about the song that is loaded in the player. */
data class NowPlaying(
    /** MediaStore id of the loaded song, or null when nothing is loaded. */
    val songId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** 0 until the player has read the file. */
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatSetting = RepeatSetting.Off,
    /**
     * Set when what is loaded is not a song from the library but a file played by its
     * address: a downloaded video, or a file another app handed over. [songId] is then null.
     */
    val videoUri: String? = null,
    /** The picture's size, 0 until the player has it and for a file with no picture. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** True while the player is still getting the file ready or catching up. */
    val buffering: Boolean = false,
    /** How fast it plays: 1 is as recorded. */
    val speed: Float = 1f,
    /** Whether the file carries subtitles, and whether they are being shown. */
    val hasSubtitles: Boolean = false,
    val subtitlesOn: Boolean = false,
) {
    /** True when a video (or a loose file) is loaded instead of a song. */
    val isVideo: Boolean get() = videoUri != null

    /** True when anything at all is loaded: what the strip at the bottom goes by. */
    val loaded: Boolean get() = songId != null || videoUri != null

    /** How much of the song has played, from 0 to 1. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** One song waiting in the queue. [index] is its slot in the player's playlist. */
data class QueueEntry(val index: Int, val songId: Long)

/** One video waiting in the queue, for "Up next" under the video player. */
data class QueueVideo(val index: Int, val uri: String, val title: String, val artist: String)

/** A video (or a loose file) as the player is given it. */
data class PlayableVideo(val uri: String, val title: String, val artist: String)

/** The id the player knows a video by: a mark that it is no song, then its address. */
const val VIDEO_ID_PREFIX = "video:"

/**
 * The UI's link to [PlaybackService]. All calls must come from the main thread.
 *
 * Connecting is asynchronous; commands sent before the link is up are ignored.
 */
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    /** The loaded song first, then what follows it in playing order (which shuffle changes). */
    private val _upcoming = MutableStateFlow<List<QueueEntry>>(emptyList())
    val upcoming: StateFlow<List<QueueEntry>> = _upcoming.asStateFlow()

    /** The videos after the loaded one, in playing order. */
    private val _upcomingVideos = MutableStateFlow<List<QueueVideo>>(emptyList())
    val upcomingVideos: StateFlow<List<QueueVideo>> = _upcomingVideos.asStateFlow()

    /** The subtitle lines to show right now, empty when there are none. */
    private val _cues = MutableStateFlow("")
    val cues: StateFlow<String> = _cues.asStateFlow()

    private var controller: MediaController? = null

    private val ready = CompletableDeferred<Unit>()

    /**
     * Waits until the link to the player is up. Commands sent before that are dropped, which
     * matters for the one command that can come as the app starts: playing a file another
     * app opened with it.
     */
    suspend fun awaitReady() = ready.await()

    /** How loud the player itself is, from 0 to 1, apart from the phone's volume. */
    fun setVolume(volume: Float) {
        controller?.volume = volume
    }

    private val listener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                publishQueue()
            }
        }
    }

    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).buildAsync()

    init {
        controllerFuture.addListener(
            {
                controller = runCatching { controllerFuture.get() }.getOrNull()
                    ?.also { it.addListener(listener) }
                publish()
                publishQueue()
                ready.complete(Unit)
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /**
     * Replaces the queue with [songs] and starts playing the one at [startIndex], from
     * [startPositionMs] into it (a video carries on where it was left).
     */
    fun play(songs: List<Song>, startIndex: Int, startPositionMs: Long = 0L) {
        val player = controller ?: return
        if (songs.isEmpty()) return
        player.setMediaItems(songs.map { it.toMediaItem() }, startIndex.coerceIn(songs.indices), startPositionMs)
        player.prepare()
        player.play()
    }

    /**
     * Replaces the queue with [videos] and starts the one at [startIndex], from
     * [startPositionMs] into it (where it was left off last time).
     */
    fun playVideos(videos: List<PlayableVideo>, startIndex: Int, startPositionMs: Long = 0L) {
        val player = controller ?: return
        if (videos.isEmpty()) return
        player.shuffleModeEnabled = false
        player.setMediaItems(videos.map { it.toMediaItem() }, startIndex.coerceIn(videos.indices), startPositionMs)
        player.prepare()
        player.play()
    }

    private fun PlayableVideo.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(VIDEO_ID_PREFIX + uri)
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
        .build()

    /** Shows the picture on [view], or stops showing it anywhere when [view] is null. The sound is not touched. */
    fun showVideoOn(view: TextureView?) {
        val player = controller ?: return
        if (view != null) player.setVideoTextureView(view) else player.clearVideoSurface()
    }

    fun pause() {
        controller?.pause()
    }

    /** Moves [byMs] forward (or back, when negative) in what is playing. */
    fun skip(byMs: Long) {
        val player = controller ?: return
        val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + byMs).coerceIn(0, end))
        publish()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        publish()
    }

    fun setSubtitles(on: Boolean) {
        val player = controller ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .build()
        if (!on) _cues.value = ""
        publish()
    }

    /** Replaces the queue with [songs] in random order. */
    fun playShuffled(songs: List<Song>) {
        val player = controller ?: return
        if (songs.isEmpty()) return
        player.shuffleModeEnabled = true
        play(songs, songs.indices.random())
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
            return
        }
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
            else -> Unit
        }
        player.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Restarts the song, or goes to the previous one when pressed near the start. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        publish()
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    /** Off, then repeat the queue, then repeat one song. */
    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Jumps to a queue slot and plays it. */
    fun jumpTo(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }

    /** Empties the queue except for the song that is loaded. */
    fun clearUpcoming() {
        val player = controller ?: return
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
        if (current > 0) player.removeMediaItems(0, current)
    }

    /** Puts [songs] right after the loaded one, or starts them if nothing is loaded. */
    fun playNext(songs: List<Song>) {
        val player = controller ?: return
        if (songs.isEmpty()) return
        if (player.mediaItemCount == 0) {
            play(songs, 0)
        } else {
            player.addMediaItems(player.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        }
    }

    /** Puts [songs] at the end of the queue, or starts them if nothing is loaded. */
    fun addToQueue(songs: List<Song>) {
        val player = controller ?: return
        if (songs.isEmpty()) return
        if (player.mediaItemCount == 0) {
            play(songs, 0)
        } else {
            player.addMediaItems(songs.map { it.toMediaItem() })
        }
    }

    /** Drops every queued copy of these songs, for when their files have been deleted. */
    fun removeSongs(songIds: Set<Long>) {
        val player = controller ?: return
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId.toLongOrNull() in songIds) {
                player.removeMediaItem(index)
            }
        }
    }

    /**
     * Gives queued copies of [songs] their new names after their details were edited. The
     * file is the same, so the player takes the new item without interrupting the music.
     */
    fun renameSongs(songs: List<Song>) {
        val player = controller ?: return
        val byId = songs.associateBy { it.id }
        for (index in 0 until player.mediaItemCount) {
            val song = byId[player.getMediaItemAt(index).mediaId.toLongOrNull()] ?: continue
            player.replaceMediaItem(index, song.toMediaItem())
        }
        publish()
    }

    /** Re-reads the playback position. The player only reports events, not ticks. */
    fun refreshPosition() = publish()

    fun release() {
        controller?.removeListener(listener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
    }

    private fun publish() {
        val player = controller
        val item = player?.currentMediaItem
        _state.value = if (player == null || item == null) {
            NowPlaying()
        } else {
            // A saved video goes by a negative id; a file opened from elsewhere, by its address.
            val id = item.mediaId.toLongOrNull()
            val video = if (id != null && id < 0) {
                songUri(id).toString()
            } else {
                item.mediaId.takeIf { it.startsWith(VIDEO_ID_PREFIX) }?.removePrefix(VIDEO_ID_PREFIX)
            }
            NowPlaying(
                videoUri = video,
                videoWidth = if (video != null) player.videoSize.width else 0,
                videoHeight = if (video != null) player.videoSize.height else 0,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                speed = player.playbackParameters.speed,
                hasSubtitles = video != null && player.currentTracks.containsType(C.TRACK_TYPE_TEXT),
                subtitlesOn = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                songId = item.mediaId.toLongOrNull(),
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                shuffle = player.shuffleModeEnabled,
                repeat = when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatSetting.All
                    Player.REPEAT_MODE_ONE -> RepeatSetting.One
                    else -> RepeatSetting.Off
                },
            )
        }
    }

    private fun publishQueue() {
        val player = controller
        if (player == null || player.mediaItemCount == 0) {
            _upcoming.value = emptyList()
            _upcomingVideos.value = emptyList()
            return
        }
        val timeline = player.currentTimeline
        val entries = ArrayList<QueueEntry>()
        val videos = ArrayList<QueueVideo>()
        var index = player.currentMediaItemIndex
        // Follow the player's own order so a shuffled queue is listed as it will play.
        while (index != C.INDEX_UNSET && index < player.mediaItemCount && entries.size + videos.size < MAX_QUEUE_SHOWN) {
            val item = player.getMediaItemAt(index)
            val songId = item.mediaId.toLongOrNull()
            if (songId != null) {
                entries += QueueEntry(index, songId)
            } else if (item.mediaId.startsWith(VIDEO_ID_PREFIX) && index != player.currentMediaItemIndex) {
                videos += QueueVideo(
                    index = index,
                    uri = item.mediaId.removePrefix(VIDEO_ID_PREFIX),
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                )
            }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        _upcoming.value = entries
        _upcomingVideos.value = videos
    }

    private companion object {
        /** A queue can hold the whole library; the screen only needs what is coming up. */
        const val MAX_QUEUE_SHOWN = 300
    }
}
