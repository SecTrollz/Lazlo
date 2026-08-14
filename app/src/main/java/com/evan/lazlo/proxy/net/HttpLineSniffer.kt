package com.evan.lazlo.proxy.net

/**
 * Best-effort scrape of an HTTP/1.x request or status line from the
 * first chunk of a plaintext byte stream, for the traffic log's summary
 * line. Doesn't attempt to parse HTTP/2 (its binary preface won't match
 * either pattern, so it just comes back empty) or reassemble headers
 * split across TCP chunks beyond the first one that's fed to it — this
 * is a log-line scraper, not a real HTTP parser.
 */
internal class HttpLineSniffer {
    var requestLine: String? = null
        private set
    var statusCode: Int? = null
        private set

    private var sniffed = false

    fun feed(buffer: ByteArray, length: Int) {
        if (sniffed || length <= 0) return
        sniffed = true
        val text = runCatching { String(buffer, 0, minOf(length, 2048), Charsets.US_ASCII) }.getOrNull() ?: return
        val firstLine = text.substringBefore('\n').trimEnd('\r')
        REQUEST_LINE.find(firstLine)?.let { requestLine = it.value }
        STATUS_LINE.find(firstLine)?.let { statusCode = it.groupValues[1].toIntOrNull() }
    }

    private companion object {
        val REQUEST_LINE = Regex("""^[A-Z]{3,9} \S+ HTTP/\d\.\d$""")
        val STATUS_LINE = Regex("""^HTTP/\d\.\d (\d{3})""")
    }
}
