package ir.fm24.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ir.fm24.app.BuildConfig
import ir.fm24.app.FmApplication
import ir.fm24.app.R
import ir.fm24.app.store.StoreInfo
import ir.fm24.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the single ExoPlayer instance and its MediaSession. Runs independently of the
 * Activity and WebView, so playback survives screen-off, lock screen, app switching,
 * Activity destruction and page reloads. Media3 promotes it to a mediaPlayback foreground
 * service while audio is playing.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var player: LiveStreamPlayer
    private lateinit var stations: StationItems

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnect = ReconnectPolicy()
    private val retryRunnable = Runnable { retryNow() }
    private var retrying = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        stations = StationItems(this)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(false) // stay on HTTPS
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(ExoListener())

        player = LiveStreamPlayer(exo, getString(R.string.station_name) + " · " + getString(R.string.playback_live))
        player.onUserIntentChanged = { cancelRetry(); reconnect.reset() }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .setCallback(SessionCallback())
            .build()

        observeConnectivity()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing after the app is swiped away; otherwise shut down cleanly.
        if (!player.userWantsPlayback || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        cancelRetry()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ── Reconnection ─────────────────────────────────────────────────────────────

    private fun observeConnectivity() {
        val monitor = (application as FmApplication).connectivity
        scope.launch {
            monitor.state
                .map { it.available }
                .distinctUntilChanged()
                .collect { available ->
                    if (available && player.userWantsPlayback && exo.playerError != null) {
                        // Network is back: retry once immediately with a fresh budget.
                        reconnect.reset()
                        retryNow()
                    }
                }
        }
    }

    private fun onStreamError(error: PlaybackException) {
        if (!player.userWantsPlayback) return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        val network = (application as FmApplication).connectivity.currentState()
        if (!network.available) {
            // Don't burn attempts while offline; the connectivity observer retries on reconnect.
            setRetrying(true)
            return
        }
        val delay = reconnect.nextDelayMs()
        if (delay == null) {
            setRetrying(false) // give up: the error stays visible until the user presses play
            return
        }
        setRetrying(true)
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun retryNow() {
        mainHandler.removeCallbacks(retryRunnable)
        if (!player.userWantsPlayback) return
        exo.prepare()
        exo.play()
    }

    private fun cancelRetry() {
        mainHandler.removeCallbacks(retryRunnable)
        setRetrying(false)
    }

    private fun setRetrying(value: Boolean) {
        if (retrying == value) return
        retrying = value
        mediaSession?.setSessionExtras(Bundle().apply { putBoolean(EXTRA_RETRYING, value) })
    }

    private inner class ExoListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Stream error ${error.errorCodeName}", error)
            onStreamError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                reconnect.reset()
                cancelRetry()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Permanent focus loss (another music app) or headphones unplugged: the system
            // paused us. Release the connection and never auto-resume.
            if (!playWhenReady && (
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                    )
            ) {
                player.releaseAfterSystemPause()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val item = mediaItem ?: return
            val url = item.localConfiguration?.uri?.toString() ?: return
            stations.remember(Station(url, item.mediaMetadata.station?.toString().orEmpty()))
        }
    }

    // ── Session callback ─────────────────────────────────────────────────────────

    private inner class SessionCallback : MediaSession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { stations.resolve(it) }
            return if (resolved.size == mediaItems.size && resolved.isNotEmpty()) {
                Futures.immediateFuture(resolved.toMutableList())
            } else {
                Futures.immediateFailedFuture(IllegalArgumentException("Rejected media item"))
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = stations.lastStation()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("No station yet"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(stations.build(last)), 0, C.TIME_UNSET),
            )
        }
    }

    companion object {
        const val EXTRA_RETRYING = "ir.fm24.app.extra.RETRYING"
        private const val TAG = "PlaybackService"
        private const val USER_AGENT = "24FM-Android/" + BuildConfig.VERSION_NAME + " (" + StoreInfo.ID + "; Media3)"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 30_000
        private const val START_BUFFER_MS = 2_500
        private const val REBUFFER_MS = 5_000
    }
}
