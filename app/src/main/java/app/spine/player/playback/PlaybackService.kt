package app.spine.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.spine.player.MainActivity
import app.spine.player.data.LibraryStore
import app.spine.player.data.MusicLibrary
import app.spine.player.data.hasAudioPermission
import app.spine.player.data.songUri
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player and keeps music going when the app is not on screen.
 *
 * The media session is what gives the notification, lock-screen controls, headset and
 * Bluetooth buttons; Media3 builds all of those from it.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var audioShaper: AudioShaper? = null
    private lateinit var store: PlaybackStore
    private val scope: CoroutineScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    /** While playing, the position is saved on a timer so a killed app loses a few seconds at most. */
    private val savePositionTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStore(this)

        val player = ExoPlayer.Builder(this)
            // Usage and content type let the system handle audio focus: pause for calls,
            // duck for navigation prompts, stop when another player starts.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when headphones are unplugged instead of blasting the speaker.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // A fixed audio session lets the equalizer attach before anything has played.
        val audioSessionId = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        player.audioSessionId = audioSessionId
        audioShaper = AudioShaper(player, AudioSettings.get(this), audioSessionId, scope)

        // The sleep timer: pause at a set time, or let the player stop itself at the end of the song.
        scope.launch {
            SleepTimer.state.collect { sleep ->
                handler.removeCallbacks(sleepNow)
                player.pauseAtEndOfMediaItems = sleep == SleepState.EndOfSong
                if (sleep is SleepState.After) {
                    handler.postDelayed(sleepNow, (sleep.atElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                }
            }
        }

        // A tap on the notification or the lock-screen player opens the app on Now playing,
        // not wherever the app happened to be.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        restore(player)
    }

    private val sleepNow = Runnable {
        mediaSession?.player?.pause()
        SleepTimer.off()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** Swiping the app away stops the service unless music is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        countPlay()
        handler.removeCallbacks(savePositionTick)
        handler.removeCallbacks(sleepNow)
        SleepTimer.off()
        audioShaper?.release()
        audioShaper = null
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Loads the saved queue, paused at the saved position. Songs that are no longer on the
     * phone are dropped. If the app started playing something before this finishes, it wins.
     */
    private fun restore(player: Player) {
        val saved = store.load()
        if (saved == null || !hasAudioPermission()) {
            player.addListener(saver)
            return
        }
        scope.launch {
            val songsById = MusicLibrary.loadSongs(this@PlaybackService).associateBy { it.id }
            if (player.mediaItemCount == 0) {
                val currentId = saved.songIds.getOrNull(saved.index)
                val songs = saved.songIds.mapNotNull { songsById[it] }
                if (songs.isNotEmpty()) {
                    // The saved song may have shifted or gone if files were deleted.
                    val index = songs.indexOfFirst { it.id == currentId }
                    player.shuffleModeEnabled = saved.shuffle
                    player.repeatMode = saved.repeatMode
                    player.setMediaItems(
                        songs.map { it.toMediaItem() },
                        index.coerceAtLeast(0),
                        if (index >= 0) saved.positionMs else 0L,
                    )
                    player.prepare()
                }
            }
            player.addListener(saver)
        }
    }

    private fun savePosition() {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount > 0) {
            store.savePosition(player.currentMediaItemIndex, player.currentPosition)
        }
    }

    // --- Play counting, for the stats and the "Most played" playlist. ---
    // What counts is time actually spent listening, measured by the clock, so seeking
    // around or leaving a song paused does not inflate it.

    private var countedSongId: Long? = null
    private var countedDurationMs = 0L
    private var listenedMs = 0L
    private var listeningSince: Long? = null

    private fun stopListeningClock() {
        listeningSince?.let { listenedMs += SystemClock.elapsedRealtime() - it }
        listeningSince = null
    }

    /** Logs the song being counted if at least half of it, or four minutes, was heard. */
    private fun countPlay() {
        stopListeningClock()
        val songId = countedSongId ?: return
        val heard = listenedMs
        // A song counts as played once half of it was heard. A video (a negative id) is logged
        // for however long it was watched: its stats are about time, not about plays.
        val enough = when {
            songId < 0 -> MIN_LISTEN_MS
            countedDurationMs > 0 -> minOf(countedDurationMs / 2, FOUR_MINUTES_MS)
            else -> FOUR_MINUTES_MS
        }
        if (heard >= enough && heard >= MIN_LISTEN_MS) {
            val library = LibraryStore.get(this)
            // Not tied to the service's own scope: the write must finish even if the service is closing.
            CoroutineScope(Dispatchers.IO).launch { library.recordPlay(songId, heard) }
        }
        listenedMs = 0L
    }

    /** Writes playback state to [store] as it changes. Attached only after [restore] is done. */
    private val saver = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Only when the queue itself changed, not when a song's duration became known.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                val player = mediaSession?.player ?: return
                store.saveQueue(
                    (0 until player.mediaItemCount).mapNotNull {
                        player.getMediaItemAt(it).mediaId.toLongOrNull()
                    },
                )
                savePosition()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            savePosition()
            // The previous song is over, one way or another: log it if it was really heard.
            countPlay()
            countedSongId = mediaItem?.mediaId?.toLongOrNull()
            countedDurationMs = 0L
            listenedMs = 0L
            listeningSince = if (mediaSession?.player?.isPlaying == true) SystemClock.elapsedRealtime() else null
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            savePosition()
            handler.removeCallbacks(savePositionTick)
            if (isPlaying) {
                handler.postDelayed(savePositionTick, POSITION_SAVE_MS)
                listeningSince = SystemClock.elapsedRealtime()
            } else {
                stopListeningClock()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // The length is only known once the file has been read.
            if (countedDurationMs == 0L && player.duration != C.TIME_UNSET) {
                countedDurationMs = player.duration
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // The player stopped itself at the end of the song: that was the sleep timer, now spent.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.off()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveModes()

        override fun onRepeatModeChanged(repeatMode: Int) = saveModes()

        private fun saveModes() {
            val player = mediaSession?.player ?: return
            store.saveModes(player.shuffleModeEnabled, player.repeatMode)
        }
    }

    private class SessionCallback : MediaSession.Callback {
        /**
         * Items can arrive without a playable URI (it is dropped when they cross a process
         * boundary), so rebuild it from the media id, which is the MediaStore audio id.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val playable = mediaItems.map { item ->
                val id = item.mediaId.toLongOrNull()
                when {
                    item.localConfiguration != null -> item
                    id != null -> item.buildUpon().setUri(songUri(id)).build()
                    // A video's id is its address, after a mark that says it is no song.
                    item.mediaId.startsWith(VIDEO_ID_PREFIX) ->
                        item.buildUpon().setUri(item.mediaId.removePrefix(VIDEO_ID_PREFIX)).build()
                    else -> item
                }
            }
            return Futures.immediateFuture(playable.toMutableList())
        }
    }

    private companion object {
        const val POSITION_SAVE_MS = 10_000L
        const val FOUR_MINUTES_MS = 240_000L
        const val MIN_LISTEN_MS = 15_000L
    }
}
