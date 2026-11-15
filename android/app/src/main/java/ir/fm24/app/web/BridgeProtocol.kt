package ir.fm24.app.web

import org.json.JSONException
import org.json.JSONObject

/** Messages the injected page script may send. Anything else is rejected. */
sealed interface BridgeMessage {
    /** Script is ready and wants the current native playback state. */
    data object Hello : BridgeMessage

    data class Play(val url: String, val label: String) : BridgeMessage
    data object Pause : BridgeMessage

    /** The site's power-off button: stop and dismiss the media notification. */
    data object Stop : BridgeMessage
    data class Volume(val value: Float) : BridgeMessage
    data class Ui(val canRefresh: Boolean, val popupOpen: Boolean) : BridgeMessage
}

/** Native playback states mirrored into the page's fake Audio element. */
enum class BridgeState(val wire: String) {
    IDLE("idle"),
    BUFFERING("buffering"),
    PLAYING("playing"),
    PAUSED("paused"),
    ERROR("error"),
}

object BridgeProtocol {

    private const val MAX_MESSAGE_CHARS = 4_096
    private const val MAX_LABEL_CHARS = 80

    fun parse(raw: String?): BridgeMessage? {
        if (raw.isNullOrEmpty() || raw.length > MAX_MESSAGE_CHARS) return null
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return null
        }
        return when (json.optString("t")) {
            "hello" -> BridgeMessage.Hello
            "play" -> {
                val url = json.optString("url").trim()
                if (!UrlPolicy.isAcceptableStreamUrl(url)) return null
                BridgeMessage.Play(url, sanitizeLabel(json.optString("label")))
            }
            "pause" -> BridgeMessage.Pause
            "stop" -> BridgeMessage.Stop
            "volume" -> {
                val v = json.optDouble("v", Double.NaN)
                if (v.isNaN()) return null
                BridgeMessage.Volume(v.toFloat().coerceIn(0f, 1f))
            }
            "ui" -> BridgeMessage.Ui(
                canRefresh = json.optBoolean("canRefresh", true),
                popupOpen = json.optBoolean("popupOpen", false),
            )
            else -> null
        }
    }

    fun stateMessage(state: BridgeState, url: String?, label: String?): String =
        JSONObject()
            .put("t", "state")
            .put("state", state.wire)
            .put("url", url ?: JSONObject.NULL)
            .put("label", label ?: JSONObject.NULL)
            .toString()

    private fun sanitizeLabel(label: String): String =
        label.filterNot { it.isISOControl() }.trim().take(MAX_LABEL_CHARS)
}
