package ir.fm24.app.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeProtocolTest {

    @Test
    fun `parses valid messages`() {
        assertEquals(BridgeMessage.Hello, BridgeProtocol.parse("""{"t":"hello"}"""))
        assertEquals(BridgeMessage.Pause, BridgeProtocol.parse("""{"t":"pause"}"""))
        assertEquals(BridgeMessage.Stop, BridgeProtocol.parse("""{"t":"stop"}"""))
        assertEquals(
            BridgeMessage.Play("https://radio.9craft.ir:7443/pop", "برگزیده‌های جهان"),
            BridgeProtocol.parse("""{"t":"play","url":"https://radio.9craft.ir:7443/pop","label":" برگزیده‌های جهان "}"""),
        )
        assertEquals(BridgeMessage.Ui(canRefresh = false, popupOpen = true), BridgeProtocol.parse("""{"t":"ui","canRefresh":false,"popupOpen":true}"""))
    }

    @Test
    fun `volume is clamped and must be numeric`() {
        assertEquals(BridgeMessage.Volume(1f), BridgeProtocol.parse("""{"t":"volume","v":7}"""))
        assertEquals(BridgeMessage.Volume(0f), BridgeProtocol.parse("""{"t":"volume","v":-1}"""))
        assertEquals(BridgeMessage.Volume(0.5f), BridgeProtocol.parse("""{"t":"volume","v":0.5}"""))
        assertNull(BridgeProtocol.parse("""{"t":"volume","v":"loud"}"""))
    }

    @Test
    fun `rejects unsafe or malformed input`() {
        assertNull(BridgeProtocol.parse("""{"t":"play","url":"http://insecure.example/a.mp3"}"""))
        assertNull(BridgeProtocol.parse("""{"t":"play","url":"file:///data/x"}"""))
        assertNull(BridgeProtocol.parse("""{"t":"exec","cmd":"rm"}"""))
        assertNull(BridgeProtocol.parse("not json"))
        assertNull(BridgeProtocol.parse(null))
        assertNull(BridgeProtocol.parse("""{"t":"hello","pad":"${"x".repeat(5000)}"}"""))
    }

    @Test
    fun `labels are trimmed and stripped of control characters`() {
        val message = BridgeProtocol.parse("""{"t":"play","url":"https://a.b/c","label":"a\u0000b${"z".repeat(200)}"}""") as BridgeMessage.Play
        assertEquals(80, message.label.length)
        assertEquals("ab", message.label.take(2))
    }

    @Test
    fun `state message is valid json`() {
        val json = JSONObject(BridgeProtocol.stateMessage(BridgeState.PLAYING, "https://a.b/c", "رپ"))
        assertEquals("state", json.getString("t"))
        assertEquals("playing", json.getString("state"))
        assertEquals("https://a.b/c", json.getString("url"))
        assertEquals("رپ", json.getString("label"))
        val empty = JSONObject(BridgeProtocol.stateMessage(BridgeState.IDLE, null, null))
        assertEquals(true, empty.isNull("url"))
    }
}
