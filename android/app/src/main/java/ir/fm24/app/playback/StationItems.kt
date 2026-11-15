package ir.fm24.app.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import ir.fm24.app.R
import ir.fm24.app.web.UrlPolicy

/** A 24FM station as chosen on the website. */
data class Station(val url: String, val label: String)

/** Builds playable media items and remembers the last station for playback resumption. */
class StationItems(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pre-rendered 512px station artwork; Media3 decodes it off the main thread. */
    private val artworkUri: Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(R.raw.station_artwork.toString())
        .build()

    /** Lightweight item a controller sends to the session (no artwork bytes). */
    fun request(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.url)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(station.url.toUri())
                    .build(),
            )
            .setMediaMetadata(MediaMetadata.Builder().setStation(station.label).build())
            .build()

    /** Full item resolved inside the service, or null when the URL is not acceptable. */
    fun resolve(requested: MediaItem): MediaItem? {
        val url = requested.requestMetadata.mediaUri?.toString()
            ?: requested.localConfiguration?.uri?.toString()
            ?: requested.mediaId
        if (!UrlPolicy.isAcceptableStreamUrl(url)) return null
        val label = requested.mediaMetadata.station?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.playback_default_genre)
        return build(Station(url, label))
    }

    fun build(station: Station): MediaItem {
        val isHls = Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(station.url)
        return MediaItem.Builder()
            .setMediaId(station.url)
            .setUri(station.url)
            .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setStation(station.label)
                    .setAlbumTitle(context.getString(R.string.station_name))
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build(),
            )
            .build()
    }

    fun remember(station: Station) {
        prefs.edit {
            putString(KEY_URL, station.url)
            putString(KEY_LABEL, station.label)
        }
    }

    fun lastStation(): Station? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        if (!UrlPolicy.isAcceptableStreamUrl(url)) return null
        return Station(url, prefs.getString(KEY_LABEL, null).orEmpty())
    }

    private companion object {
        const val PREFS = "playback"
        const val KEY_URL = "last_url"
        const val KEY_LABEL = "last_label"
    }
}
