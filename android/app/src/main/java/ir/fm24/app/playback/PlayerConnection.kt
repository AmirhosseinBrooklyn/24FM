package ir.fm24.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import ir.fm24.app.web.BridgeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI and the web page need to know about native playback. */
data class PlaybackSnapshot(
    val state: BridgeState = BridgeState.IDLE,
    val url: String? = null,
    val label: String? = null,
    val retrying: Boolean = false,
    val hasError: Boolean = false,
)

/**
 * Activity-side handle to [PlaybackService] through a Media3 [MediaController]. Commands
 * issued before the controller connects are queued and replayed once it is ready.
 */
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext
    private val stations = StationItems(appContext)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    /** Rebuffering count while playing, used to detect a degraded connection. */
    var onRebuffer: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_BUFFERING && c.playWhenReady && wasReady) onRebuffer?.invoke()
            wasReady = playbackState == Player.STATE_READY
        }
    }
    private var wasReady = false

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = publish()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token).setListener(controllerListener).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish()
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        pending.clear()
    }

    fun play(station: Station) = withController { c ->
        val sameStation = c.mediaItemCount > 0 && c.currentMediaItem?.mediaId == station.url
        if (sameStation && c.currentMediaItem?.mediaMetadata?.station?.toString() != station.label) {
            c.replaceMediaItem(0, stations.build(station))
        }
        val active = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playerError == null
        when {
            sameStation && (active || isRetrying(c)) -> Unit // already playing or reconnecting
            sameStation -> c.play()
            else -> {
                c.setMediaItem(stations.request(station))
                c.prepare()
                c.play()
            }
        }
    }

    fun pause() = withController { it.pause() }

    fun stop() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun retry() = withController { it.play() }

    fun setVolume(volume: Float) = withController { it.volume = volume.coerceIn(0f, 1f) }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else {
            pending += action
            connect()
        }
    }

    private fun isRetrying(c: MediaController): Boolean =
        c.sessionExtras.getBoolean(PlaybackService.EXTRA_RETRYING, false)

    private fun publish() {
        val c = controller ?: return
        val retrying = isRetrying(c)
        val hasError = c.playerError != null
        val state = when {
            retrying -> BridgeState.BUFFERING
            hasError -> BridgeState.ERROR
            c.mediaItemCount == 0 -> BridgeState.IDLE
            !c.playWhenReady -> BridgeState.PAUSED
            c.playbackState == Player.STATE_BUFFERING -> BridgeState.BUFFERING
            c.playbackState == Player.STATE_IDLE -> BridgeState.BUFFERING
            c.isPlaying -> BridgeState.PLAYING
            else -> BridgeState.PAUSED
        }
        val item = c.currentMediaItem
        _snapshot.value = PlaybackSnapshot(
            state = state,
            url = item?.mediaId,
            label = item?.mediaMetadata?.station?.toString(),
            retrying = retrying,
            hasError = hasError && !retrying,
        )
    }
}
