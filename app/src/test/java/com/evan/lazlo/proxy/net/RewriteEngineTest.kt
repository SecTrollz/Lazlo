package com.evan.lazlo.proxy.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Exercises [RewriteEngine] against real HTTP/1.x byte layouts — not
 * mocked Netty objects — the same discipline as `NettyTlsTerminationTest`:
 * build a genuine request/response, run it through the engine, and check
 * the actual re-encoded bytes rather than trusting the implementation.
 */
class RewriteEngineTest {

    private fun rule(
        host: String = "",
        request: Boolean = true,
        response: Boolean = false,
        setHeaderName: String? = null,
        setHeaderValue: String? = null,
        removeHeaderName: String? = null,
        bodyFind: String? = null,
        bodyReplace: String? = null,
    ) = RewriteRule(
        id = UUID.randomUUID().toString(),
        hostContains = host,
        appliesToRequest = request,
        appliesToResponse = response,
        setHeaderName = setHeaderName,
        setHeaderValue = setHeaderValue,
        removeHeaderName = removeHeaderName,
        bodyFind = bodyFind,
        bodyReplace = bodyReplace,
    )

    private fun rawRequest(headers: String = "", body: String = ""): ByteArray {
        val bytes = body.toByteArray()
        return (
            "GET /api HTTP/1.1\r\nHost: example.com\r\n$headers" +
                "Content-Length: ${bytes.size}\r\n\r\n$body"
            ).toByteArray()
    }

    private fun rawResponse(body: String): ByteArray {
        val bytes = body.toByteArray()
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n$body".toByteArray()
    }

    @Test
    fun `no rules leaves bytes completely untouched`() {
        val original = rawRequest()
        assertTrue(RewriteEngine.rewriteRequest(original, "example.com", emptyList()) === original)
    }

    @Test
    fun `a non-matching host leaves bytes untouched`() {
        val original = rawRequest()
        val result = RewriteEngine.rewriteRequest(original, "example.com", listOf(rule(host = "nope.test", setHeaderName = "X-Test", setHeaderValue = "1")))
        assertTrue(result === original)
    }

    @Test
    fun `sets a header on a matching request`() {
        val result = RewriteEngine.rewriteRequest(
            rawRequest(),
            "example.com",
            listOf(rule(host = "example.com", setHeaderName = "X-Injected", setHeaderValue = "yes")),
        )
        val text = String(result)
        assertTrue(text.contains("X-Injected: yes"))
    }

    @Test
    fun `removes a header on a matching request`() {
        val result = RewriteEngine.rewriteRequest(
            rawRequest(headers = "Authorization: secret\r\n"),
            "example.com",
            listOf(rule(host = "example.com", removeHeaderName = "Authorization")),
        )
        val text = String(result)
        assertTrue(!text.contains("Authorization"))
    }

    @Test
    fun `rewrites a response body and fixes Content-Length to match`() {
        val result = RewriteEngine.rewriteResponse(
            rawResponse("""{"status":"blocked"}"""),
            "example.com",
            listOf(rule(host = "example.com", request = false, response = true, bodyFind = "blocked", bodyReplace = "allowed")),
        )
        val text = String(result)
        assertTrue(text.contains("\"status\":\"allowed\""))
        // Netty's HttpUtil.setContentLength writes the header via its
        // canonical lowercase constant name, replacing whatever case the
        // original request used — case-insensitive match here on purpose.
        val declaredLength = Regex("""content-length: (\d+)""", RegexOption.IGNORE_CASE).find(text)!!.groupValues[1].toInt()
        val actualBodyBytes = text.substringAfter("\r\n\r\n").toByteArray().size
        assertEquals(declaredLength, actualBodyBytes)
    }

    @Test
    fun `malformed bytes fail open instead of throwing`() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(garbage, RewriteEngine.rewriteRequest(garbage, "example.com", listOf(rule(setHeaderName = "X", setHeaderValue = "1"))))
    }

    @Test
    fun `captures a full request for replay`() {
        val captured = RewriteEngine.captureForReplay(
            rawRequest(headers = "Authorization: Bearer token\r\n", body = "hello"),
            "https",
            "example.com",
        )
        assertEquals("GET", captured?.method)
        assertEquals("https://example.com/api", captured?.url)
        assertEquals("Bearer token", captured?.headers?.get("Authorization"))
        assertEquals("hello", captured?.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `capture returns null for unparseable bytes instead of throwing`() {
        assertEquals(null, RewriteEngine.captureForReplay(byteArrayOf(9, 9, 9), "https", "example.com"))
    }
}
