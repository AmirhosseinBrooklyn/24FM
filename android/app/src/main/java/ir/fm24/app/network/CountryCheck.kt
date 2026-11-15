package ir.fm24.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface CountryResult {
    /** Reliable ISO-3166 alpha-2 country code. */
    data class Known(val countryCode: String) : CountryResult {
        val isIran: Boolean get() = countryCode == "IR"
    }

    /** Lookup failed, was blocked, or returned something we cannot trust. */
    data object Unknown : CountryResult
}

/**
 * Public-IP country lookup through IPinfo Lite (approved by the project owner). The device's
 * public IP is visible to ipinfo.io; nothing else is sent. Any non-200 response, including the
 * 403 ipinfo returns for Iranian networks, is treated as [CountryResult.Unknown].
 */
class CountryCheck(
    private val token: String,
    private val endpoint: String = "https://api.ipinfo.io/lite/me",
    private val timeoutMs: Int = 5_000,
) {
    val isEnabled: Boolean get() = token.isNotBlank()

    suspend fun lookup(): CountryResult {
        if (!isEnabled) return CountryResult.Unknown
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    useCaches = false
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext CountryResult.Unknown
                val body = connection.inputStream.bufferedReader().use { it.readText().take(MAX_BODY_CHARS) }
                parse(body)
            } catch (_: IOException) {
                CountryResult.Unknown
            } finally {
                connection?.disconnect()
            }
        }
    }

    companion object {
        private const val MAX_BODY_CHARS = 4_096
        private val COUNTRY_CODE = Regex("^[A-Z]{2}$")

        fun parse(body: String): CountryResult = try {
            val code = JSONObject(body).optString("country_code", "").trim()
            if (COUNTRY_CODE.matches(code)) CountryResult.Known(code) else CountryResult.Unknown
        } catch (_: JSONException) {
            CountryResult.Unknown
        }
    }
}
