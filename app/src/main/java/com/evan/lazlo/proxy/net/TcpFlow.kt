package com.evan.lazlo.proxy.net

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

internal enum class TcpFlowState { SYN_RECEIVED, ESTABLISHED, CLOSED }

/**
 * Mutable per-connection state for one TCP flow, read and written from two
 * threads: [TcpIpStack]'s packet-processing loop and the flow's own relay
 * coroutine (which calls back into [TcpIpStack] to send data toward the
 * client, serialized against the TUN by its write mutex).
 *
 * The two sequence counters are `@Volatile` for that reason. Each has a
 * single writer at any point in the flow's life — [clientNextSeq] is only
 * advanced by the packet loop as client bytes arrive, [serverSeq] only by
 * the relay side as bytes are sent back (and by the loop during the
 * handshake, before any relay exists) — so volatile publication is enough;
 * there's no read-modify-write to make atomic. Without it the other side
 * could keep reading a stale value indefinitely and ACK or sequence a
 * segment against a number that's already moved.
 */
internal class TcpFlow(val key: FlowKey) {
    @Volatile var state: TcpFlowState = TcpFlowState.SYN_RECEIVED

    /** Next sequence number this stack will use when sending data to the client. */
    @Volatile var serverSeq: Long = 0L

    /** Next sequence number expected from the client — this stack's next ACK number. */
    @Volatile var clientNextSeq: Long = 0L

    /** Decoded, in-order payload bytes arriving from the client app. */
    val fromClient = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    /** The relay coroutine driving this flow (upstream dial + byte shuffling). */
    var relayJob: Job? = null

    @Volatile var localFinSent = false
}
