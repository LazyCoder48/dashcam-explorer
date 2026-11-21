package dev.dashcam.explorer.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Plays the front and rear clips as one (DESIGN.md section 7).
 *
 * Front is the master: it owns the clock and the sound. Rear follows with its audio
 * track switched off, so nudging its speed to stay in step is inaudible.
 * All calls must come from the main thread.
 */
@OptIn(UnstableApi::class)
class SyncedPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Set for a Wi-Fi camera source, so clips can play while they are still downloading. */
    private val dataSource: DataSource.Factory? = null,
) {

    class Slot(val player: ExoPlayer) {
        var active by mutableStateOf(false)      // has a clip loaded
        var error by mutableStateOf<String?>(null)
        var buffering by mutableStateOf(false)
        var videoSize by mutableStateOf(VideoSize.UNKNOWN)
        var droppedFrames = 0
    }

    val front = Slot(newPlayer(audio = true))
    val rear = Slot(newPlayer(audio = false))

    var wantPlay by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    var muted by mutableStateOf(false); private set
    /** Largest gap between the cameras seen in the last second, for the debug line and tests. */
    var driftMs by mutableLongStateOf(0L); private set
    var onFinished: () -> Unit = {}

    private var scrubbing = false
    private var loop: Job? = null
    private var finishedSent = false
    private var ticks = 0

    private fun newPlayer(audio: Boolean): ExoPlayer {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val builder = ExoPlayer.Builder(context, renderers)
        if (dataSource != null) builder.setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
        return builder.build().apply {
            setSeekParameters(SeekParameters.EXACT)
            if (!audio) {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }
        }
    }

    private fun listen(slot: Slot) {
        slot.player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                slot.error = e.errorCodeName
                slot.active = false
            }
            override fun onVideoSizeChanged(size: VideoSize) { slot.videoSize = size }
            override fun onPlaybackStateChanged(state: Int) { slot.buffering = state == Player.STATE_BUFFERING }
        })
    }

    init {
        listen(front); listen(rear)
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(200)
            }
        }
    }

    private val live: List<Slot> get() = listOf(front, rear).filter { it.active }
    private val master: Slot? get() = live.firstOrNull()

    fun load(frontUri: Uri?, rearUri: Uri?, autoPlay: Boolean) {
        finishedSent = false
        wantPlay = false
        positionMs = 0; durationMs = 0; driftMs = 0
        for ((slot, uri) in listOf(front to frontUri, rear to rearUri)) {
            slot.error = null
            slot.videoSize = VideoSize.UNKNOWN
            slot.player.playWhenReady = false
            if (uri == null) {
                slot.active = false
                slot.player.stop(); slot.player.clearMediaItems()
            } else {
                slot.active = true
                slot.player.setMediaItem(MediaItem.fromUri(uri))
                slot.player.playbackParameters = PlaybackParameters(speed)
                slot.player.prepare()
            }
        }
        front.player.volume = if (muted) 0f else 1f
        if (autoPlay) play()
    }

    fun play() {
        val m = master ?: return
        if (durationMs > 0 && m.player.currentPosition >= durationMs - 100) seekTo(0)
        finishedSent = false
        wantPlay = true
        tick()
    }

    fun pause() {
        wantPlay = false
        live.forEach { it.player.playWhenReady = false }
    }

    fun toggle() = if (wantPlay) pause() else play()

    fun seekTo(ms: Long) {
        val t = ms.coerceIn(0, (durationMs - 50).coerceAtLeast(0))
        live.forEach { it.player.seekTo(t) }
        positionMs = t
        finishedSent = false
    }

    fun seekBy(deltaMs: Long) = seekTo((master?.player?.currentPosition ?: 0) + deltaMs)

    /**
     * While the slider is dragged, jump to the nearest key frame (cheap) instead of decoding
     * to the exact frame on every movement; land exactly once the finger lifts. Drift
     * correction is held meanwhile.
     */
    fun scrub(ms: Long) {
        if (!scrubbing) { scrubbing = true; live.forEach { it.player.setSeekParameters(SeekParameters.CLOSEST_SYNC) } }
        seekTo(ms)
    }

    fun scrubDone() {
        if (!scrubbing) return
        scrubbing = false
        live.forEach { it.player.setSeekParameters(SeekParameters.EXACT) }
        seekTo(positionMs)
    }

    fun changeSpeed(s: Float) {
        speed = s
        live.forEach { it.player.playbackParameters = PlaybackParameters(s) }
    }

    fun toggleMute() {
        muted = !muted
        front.player.volume = if (muted) 0f else 1f
        // when only the rear clip exists it is the one with sound
        rear.player.volume = if (muted) 0f else 1f
    }

    private fun ready(s: Slot) = s.player.playbackState == Player.STATE_READY

    private fun tick() {
        val l = live
        val m = l.firstOrNull() ?: return
        // shorter of the two; they differ by a millisecond or so
        durationMs = l.map { it.player.duration }.filter { it != C.TIME_UNSET && it > 0 }.minOrNull() ?: 0
        if (!scrubbing) positionMs = m.player.currentPosition.coerceAtLeast(0)

        if (wantPlay && ++ticks % 5 == 0) android.util.Log.d("SyncedPlayer",
            "pos=${m.player.currentPosition} playing=${l.map { it.player.isPlaying }} buffered=${l.map { it.player.bufferedPosition }} drift=$driftMs")
        if (wantPlay) {
            // Start together; hold the ready one while the other buffers, and leave the
            // stalled one "playing" so it keeps loading at full speed.
            val ended = m.player.playbackState == Player.STATE_ENDED ||
                (durationMs > 0 && m.player.currentPosition >= durationMs - 50)
            if (ended) {
                pause()
                if (!finishedSent) { finishedSent = true; onFinished() }
                return
            }
            val stalled = l.filter { !ready(it) }
            l.forEach { s -> s.player.playWhenReady = stalled.isEmpty() || s in stalled }
        }

        // Keep the follower in step: jump when far off, ease with a small speed change when close.
        if (l.size == 2 && wantPlay && !scrubbing && l.all { ready(it) && it.player.isPlaying }) {
            val a = l[0].player
            val b = l[1].player
            val drift = b.currentPosition - a.currentPosition
            driftMs = abs(drift)
            when {
                abs(drift) > 300 -> b.seekTo(a.currentPosition)
                abs(drift) > 40 -> setSpeedOnce(b, speed * if (drift > 0) 0.94f else 1.06f)
                else -> setSpeedOnce(b, speed)
            }
        }
    }

    private fun setSpeedOnce(p: ExoPlayer, s: Float) {
        if (abs(p.playbackParameters.speed - s) > 0.001f) p.playbackParameters = PlaybackParameters(s)
    }

    fun release() {
        loop?.cancel()
        front.player.release()
        rear.player.release()
    }
}
