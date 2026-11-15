package ir.fm24.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun `24fm https pages stay inside the app`() {
        assertEquals(UrlPolicy.Decision.Internal("https://24fm.ir/", false), UrlPolicy.classify("https://24fm.ir/"))
        assertEquals(
            UrlPolicy.Decision.Internal("https://www.24fm.ir/x?y=1", false),
            UrlPolicy.classify("https://www.24fm.ir/x?y=1"),
        )
        assertTrue(UrlPolicy.isInternal("https://24FM.ir/"))
    }

    @Test
    fun `plain http on 24fm is upgraded to https`() {
        assertEquals(UrlPolicy.Decision.Internal("https://24fm.ir/a", true), UrlPolicy.classify("http://24fm.ir/a"))
        assertFalse(UrlPolicy.isInternal("http://24fm.ir/a"))
    }

    @Test
    fun `lookalike domains are not internal`() {
        assertEquals(UrlPolicy.Decision.External("https://24fm.ir.evil.com/"), UrlPolicy.classify("https://24fm.ir.evil.com/"))
        assertEquals(UrlPolicy.Decision.External("https://evil24fm.ir/"), UrlPolicy.classify("https://evil24fm.ir/"))
        assertEquals(UrlPolicy.Decision.Blocked, UrlPolicy.classify("https://user:pass@24fm.ir/"))
    }

    @Test
    fun `other sites and apps open externally`() {
        assertEquals(UrlPolicy.Decision.External("https://t.me/BrooklynWasHere"), UrlPolicy.classify("https://t.me/BrooklynWasHere"))
        assertEquals(UrlPolicy.Decision.External("tg://resolve?domain=x"), UrlPolicy.classify("tg://resolve?domain=x"))
        assertEquals(UrlPolicy.Decision.External("mailto:a@b.c"), UrlPolicy.classify("mailto:a@b.c"))
    }

    @Test
    fun `dangerous schemes are blocked`() {
        listOf(
            "javascript:alert(1)",
            "file:///sdcard/x",
            "content://x/y",
            "intent://scan/#Intent;scheme=zxing;end",
            "data:text/html,hi",
            "",
            null,
            "https://24fm.ir/" + "a".repeat(3000),
        ).forEach { assertEquals(it, UrlPolicy.Decision.Blocked, UrlPolicy.classify(it)) }
    }

    @Test
    fun `stream urls must be https without credentials`() {
        assertTrue(UrlPolicy.isAcceptableStreamUrl("https://stream.radiox.pro/listen/rapfa/radio.mp3"))
        assertTrue(UrlPolicy.isAcceptableStreamUrl("https://radio.9craft.ir:7443/pop"))
        assertTrue(UrlPolicy.isAcceptableStreamUrl("https://api.radiomusic.app/v3/Dance/radio_live.m3u8"))
        assertFalse(UrlPolicy.isAcceptableStreamUrl("http://radio.9craft.ir:7443/pop"))
        assertFalse(UrlPolicy.isAcceptableStreamUrl("https://u:p@radio.9craft.ir/pop"))
        assertFalse(UrlPolicy.isAcceptableStreamUrl("file:///etc/passwd"))
        assertFalse(UrlPolicy.isAcceptableStreamUrl("not a url"))
        assertFalse(UrlPolicy.isAcceptableStreamUrl(null))
    }
}
