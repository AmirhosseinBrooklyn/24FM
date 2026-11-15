package ir.fm24.app.web

import ir.fm24.app.web.SiteCache.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SiteCacheTest {

    @Test
    fun `unchanged site keeps the cache`() {
        assertEquals(Decision.USE_CACHE, SiteCache.decide(stored = "abc", fresh = "abc"))
    }

    @Test
    fun `changed site refreshes the cache`() {
        assertEquals(Decision.REFRESH, SiteCache.decide(stored = "abc", fresh = "def"))
    }

    @Test
    fun `offline with a cached copy shows the cache`() {
        assertEquals(Decision.UNREACHABLE, SiteCache.decide(stored = "abc", fresh = null))
    }

    @Test
    fun `first run loads normally whether or not the check worked`() {
        assertEquals(Decision.FIRST_LOAD, SiteCache.decide(stored = null, fresh = "abc"))
        assertEquals(Decision.FIRST_LOAD, SiteCache.decide(stored = null, fresh = null))
    }

    @Test
    fun `fingerprint is stable and sensitive to any header change`() {
        val probes = listOf("200||Thu, 04 Jun 2026 11:37:38 GMT|8240", "200||Thu, 04 Jun 2026 00:39:26 GMT|20100")
        assertEquals(SiteCache.fingerprintOf(probes), SiteCache.fingerprintOf(probes.toList()))
        assertEquals(64, SiteCache.fingerprintOf(probes).length)
        val cssChanged = listOf(probes[0], "200||Fri, 05 Jun 2026 09:00:00 GMT|20100")
        assertNotEquals(SiteCache.fingerprintOf(probes), SiteCache.fingerprintOf(cssChanged))
    }
}
