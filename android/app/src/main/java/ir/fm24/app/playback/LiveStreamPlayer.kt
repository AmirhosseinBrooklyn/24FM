package ir.fm24.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Live-radio semantics on top of ExoPlayer:
 * - pause releases the network connection (a live stream cannot be resumed from a paused
 *   position anyway); play reconnects at the live edge;
 * - seeking, skipping, speed, repeat and shuffle are not offered, because they don't apply
 *   to the 24FM stations;
 * - the notification title falls back to the station name when the stream sends no title.
 */
@OptIn(UnstableApi::class)
class LiveStreamPlayer(
    private val exo: ExoPlayer,
    private val liveSubtitle: String,
) : ForwardingPlayer(exo) {

    /** True while the user (not the system) wants audio to play. */
    var userWantsPlayback: Boolean = false
        private set

    /** Invoked whenever the user explicitly starts or stops playback. */
    var onUserIntentChanged: ((wantsPlayback: Boolean) -> Unit)? = null

    override fun play() {
        setUserIntent(true)
        if (exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) {
            exo.prepare()
        }
        exo.play()
    }

    override fun pause() {
        setUserIntent(false)
        exo.pause()
        exo.stop() // drop the connection; keeps the media item so play() can reconnect
    }

    override fun stop() {
        setUserIntent(false)
        exo.stop()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    /** Called by the service when the system (audio focus, noisy audio) paused playback. */
    fun releaseAfterSystemPause() {
        if (!exo.playWhenReady) {
            setUserIntent(false)
            exo.stop()
        }
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().removeAll(*REMOVED_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command !in REMOVED_COMMANDS && super.isCommandAvailable(command)

    override fun getMediaMetadata(): MediaMetadata {
        val merged = super.getMediaMetadata()
        val station = merged.station?.toString()?.takeIf { it.isNotBlank() } ?: return merged
        val streamTitle = merged.title?.toString()?.trim().orEmpty()
        return if (streamTitle.isEmpty() || streamTitle == station) {
            merged.buildUpon()
                .setTitle(station)
                .setArtist(liveSubtitle)
                .build()
        } else {
            merged.buildUpon()
                .setArtist(merged.artist?.takeIf { it.isNotBlank() } ?: station)
                .build()
        }
    }

    private fun setUserIntent(wantsPlayback: Boolean) {
        userWantsPlayback = wantsPlayback
        onUserIntentChanged?.invoke(wantsPlayback)
    }

    private companion object {
        val REMOVED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,
        )
    }
}
