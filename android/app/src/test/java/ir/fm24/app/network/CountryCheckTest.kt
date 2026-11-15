package ir.fm24.app.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryCheckTest {

    @Test
    fun `parses ipinfo lite response`() {
        val body = """{"ip":"203.0.113.9","asn":"AS1","as_name":"x","as_domain":"x.com","country_code":"DE","country":"Germany","continent_code":"EU","continent":"Europe"}"""
        val result = CountryCheck.parse(body)
        assertEquals(CountryResult.Known("DE"), result)
        assertFalse((result as CountryResult.Known).isIran)
    }

    @Test
    fun `iran is recognised`() {
        assertTrue((CountryCheck.parse("""{"country_code":"IR"}""") as CountryResult.Known).isIran)
    }

    @Test
    fun `anything unreliable is unknown`() {
        listOf(
            "",
            "<html>403 Forbidden</html>",
            """{"country_code":""}""",
            """{"country_code":"ir"}""",
            """{"country_code":"IRN"}""",
            """{"error":{"title":"Unknown token"}}""",
        ).forEach { assertEquals(it, CountryResult.Unknown, CountryCheck.parse(it)) }
    }

    @Test
    fun `disabled without a token and never touches the network`() = runTest {
        val check = CountryCheck(token = "")
        assertFalse(check.isEnabled)
        assertEquals(CountryResult.Unknown, check.lookup())
    }
}
