package com.evan.lazlo.proxy.net

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpRequestEncoder
import io.netty.handler.codec.http.HttpResponseDecoder
import io.netty.handler.codec.http.HttpResponseEncoder

/** A captured request in enough detail to actually resend it — the traffic log's "Replay" action fires this straight back out via OkHttp. */
data class ReplayableRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * The actual "rewrite" in "rewrite rules": decodes a client's request (or
 * a server's response) bytes into a real [FullHttpRequest]/
 * [FullHttpResponse], applies every matching enabled [RewriteRule],
 * re-encodes it, and hands back the resulting bytes for
 * [ConnectionRelay] to forward instead of the original.
 *
 * Uses [EmbeddedChannel] deliberately, unlike the rest of `proxy/net/` —
 * see `ConnectionRelay`'s own doc for why `EmbeddedChannel` was rejected
 * *there*: that was a long-lived bidirectional stream fed from one
 * coroutine and drained from another, which is exactly the concurrent
 * use `EmbeddedChannel` isn't safe for. This is different in kind, not
 * degree: one call in, one call out, entirely on the calling coroutine,
 * no other producer or consumer ever touches this channel, and it's
 * discarded the moment the call returns — precisely the synchronous,
 * single-caller shape `EmbeddedChannel` is built for.
 *
 * Fails open, always: a chunk that isn't a complete, well-formed single
 * HTTP message (split across TCP segments, HTTP/2, a non-HTTP protocol
 * on this port, a decode error) passes through completely unmodified
 * rather than risk forwarding corrupted bytes. Real-traffic correctness
 * matters more here than rewrite coverage — this rewrites what it can
 * safely parse, and leaves everything else alone.
 */
internal object RewriteEngine {

    private const val MAX_AGGREGATED_BYTES = 4 * 1024 * 1024

    fun rewriteRequest(bytes: ByteArray, host: String, rules: List<RewriteRule>): ByteArray {
        val applicable = rules.filter { it.enabled && it.appliesToRequest && it.matchesHost(host) }
        if (applicable.isEmpty()) return bytes
        return runCatching {
            val channel = EmbeddedChannel(HttpRequestDecoder(), HttpObjectAggregator(MAX_AGGREGATED_BYTES))
            try {
                if (!channel.writeInbound(Unpooled.wrappedBuffer(bytes))) return bytes
                val request = channel.readInbound<FullHttpRequest>() ?: return bytes
                try {
                    if (!request.decoderResult().isSuccess) return bytes
                    applicable.forEach { rule -> applyToRequest(request, rule) }
                    encode(HttpRequestEncoder(), request)
                } finally {
                    request.release()
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }.getOrDefault(bytes)
    }

    fun rewriteResponse(bytes: ByteArray, host: String, rules: List<RewriteRule>): ByteArray {
        val applicable = rules.filter { it.enabled && it.appliesToResponse && it.matchesHost(host) }
        if (applicable.isEmpty()) return bytes
        return runCatching {
            val channel = EmbeddedChannel(HttpResponseDecoder(), HttpObjectAggregator(MAX_AGGREGATED_BYTES))
            try {
                if (!channel.writeInbound(Unpooled.wrappedBuffer(bytes))) return bytes
                val response = channel.readInbound<FullHttpResponse>() ?: return bytes
                try {
                    if (!response.decoderResult().isSuccess) return bytes
                    applicable.forEach { rule -> applyToResponse(response, rule) }
                    encode(HttpResponseEncoder(), response)
                } finally {
                    response.release()
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }.getOrDefault(bytes)
    }

    /**
     * Best-effort decode of a captured request's first chunk into
     * something [com.evan.lazlo.proxy.RewriteRuleStore] doesn't need at
     * all but the traffic log's **replay** action does: method, full
     * URL, headers, and body, independent of whether any rewrite rule
     * exists. Read-only — never mutates or re-encodes anything, so a
     * decode failure just means "no replay available for this entry"
     * rather than any risk to the traffic itself.
     */
    fun captureForReplay(bytes: ByteArray, scheme: String, host: String): ReplayableRequest? {
        val channel = EmbeddedChannel(HttpRequestDecoder(), HttpObjectAggregator(MAX_AGGREGATED_BYTES))
        return try {
            if (!channel.writeInbound(Unpooled.wrappedBuffer(bytes))) return null
            val request = channel.readInbound<FullHttpRequest>() ?: return null
            try {
                if (!request.decoderResult().isSuccess) return null
                val headers = LinkedHashMap<String, String>()
                request.headers().forEach { headers[it.key] = it.value }
                val body = ByteArray(request.content().readableBytes())
                request.content().getBytes(request.content().readerIndex(), body)
                ReplayableRequest(
                    method = request.method().name(),
                    url = "$scheme://$host${request.uri()}",
                    headers = headers,
                    body = body,
                )
            } finally {
                request.release()
            }
        } catch (_: Exception) {
            null
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun applyToRequest(request: FullHttpRequest, rule: RewriteRule) {
        applyHeaderEdits(request.headers(), rule)
        applyBodyEdit(request, rule)
    }

    private fun applyToResponse(response: FullHttpResponse, rule: RewriteRule) {
        applyHeaderEdits(response.headers(), rule)
        applyBodyEdit(response, rule)
    }

    private fun applyHeaderEdits(headers: io.netty.handler.codec.http.HttpHeaders, rule: RewriteRule) {
        val name = rule.setHeaderName
        if (!name.isNullOrBlank()) headers.set(name, rule.setHeaderValue.orEmpty())
        val removeName = rule.removeHeaderName
        if (!removeName.isNullOrBlank()) headers.remove(removeName)
    }

    /** String-substring find/replace on the body — not a regex engine, deliberately: predictable behavior against arbitrary captured bodies beats a footgun. */
    private fun applyBodyEdit(message: io.netty.handler.codec.http.FullHttpMessage, rule: RewriteRule) {
        val find = rule.bodyFind
        if (find.isNullOrEmpty()) return
        val original = message.content().toString(Charsets.UTF_8)
        if (!original.contains(find)) return
        val replaced = original.replace(find, rule.bodyReplace.orEmpty())
        val newContent = Unpooled.copiedBuffer(replaced, Charsets.UTF_8)
        message.content().clear().writeBytes(newContent)
        newContent.release()
        // Content-Length no longer matches the original body size once
        // it's been edited — HttpObjectAggregator built this as a single
        // fixed-length message, so this must be corrected or a client/
        // server reading by that header would truncate or hang reading
        // past the real end of the (now different-sized) body.
        io.netty.handler.codec.http.HttpUtil.setContentLength(message, message.content().readableBytes().toLong())
    }

    private fun <T> encode(encoder: io.netty.channel.ChannelHandler, message: T): ByteArray
        where T : io.netty.handler.codec.http.HttpObject, T : io.netty.buffer.ByteBufHolder {
        val channel = EmbeddedChannel(encoder)
        try {
            channel.writeOutbound(message.retainedDuplicate())
            val out = Unpooled.buffer()
            while (true) {
                val piece = channel.readOutbound<io.netty.buffer.ByteBuf>() ?: break
                out.writeBytes(piece)
                piece.release()
            }
            val result = ByteArray(out.readableBytes())
            out.readBytes(result)
            out.release()
            return result
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
