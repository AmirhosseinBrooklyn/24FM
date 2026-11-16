package ir.fm24.app.playback

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ActivityReporterTest {
    @Test fun reportsConfirmedPlaybackAcrossPausesRetriesAndStationChanges() {
        val messages = LinkedBlockingQueue<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/activity") { exchange ->
            messages.add(JSONObject(exchange.requestBody.bufferedReader().readText()))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        val reporter = ActivityReporter("http://127.0.0.1:${server.address.port}/activity", 100)
        fun await(state: String, after: Long = -1): JSONObject {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < until) {
                val message = messages.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (message.optString("state") == state && message.getLong("sequence") > after) return message
            }
            error("No $state report")
        }
        try {
            reporter.userIntent(true)
            reporter.update(true, "https://example.test/one", "First station", "iran")
            val first = await("live")
            assertEquals("android", first.getString("source"))
            assertEquals("iran", first.getString("genre"))
            assertEquals("First station", first.getString("genre_label"))
            val heartbeat = await("live", first.getLong("sequence"))
            assertEquals(first.getString("attempt_id"), heartbeat.getString("attempt_id"))
            assertEquals(first.getString("client_id"), heartbeat.getString("client_id"))

            reporter.update(false, "https://example.test/one", "First station", "iran")
            val buffering = await("off", heartbeat.getLong("sequence"))
            reporter.update(true, "https://example.test/one", "First station", "iran")
            val retry = await("live", buffering.getLong("sequence"))
            assertEquals(first.getString("attempt_id"), retry.getString("attempt_id"))

            reporter.userIntent(false)
            reporter.update(false, "https://example.test/one", "First station", "iran")
            val pause = await("off", retry.getLong("sequence"))
            reporter.userIntent(true)
            reporter.update(true, "https://example.test/one", "First station", "iran")
            val resumed = await("live", pause.getLong("sequence"))
            assertNotEquals(first.getString("attempt_id"), resumed.getString("attempt_id"))

            reporter.update(false, "https://example.test/two", "Second station", "traditional-1")
            val change = await("off", resumed.getLong("sequence"))
            reporter.update(true, "https://example.test/two", "Second station", "traditional-1")
            val second = await("live", change.getLong("sequence"))
            assertNotEquals(resumed.getString("attempt_id"), second.getString("attempt_id"))
            reporter.close()
            assertTrue(await("off", second.getLong("sequence")).getBoolean("closed"))
        } finally { reporter.close(); server.stop(0) }
    }
}
