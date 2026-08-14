package com.evan.lazlo.proxy.net

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

internal enum class TcpFlowState { SYN_RECEIVED, ESTABLISHED, CLOSED }

/**
 * Mutable per-connection state for one TCP flow. Owned and mutated only
 * by [TcpIpStack]'s single packet-processing loop, so the fields aren't
 * synchronized — nothing outside that loop writes to them. The relay
 * coroutine in [ConnectionRelay] only reads bytes from [fromClient] and
 * calls back into [TcpIpStack] (which serializes TUN writes with its
 * own mutex) to send data toward the client.
 */
internal class TcpFlow(val key: FlowKey) {
    var state: TcpFlowState = TcpFlowState.SYN_RECEIVED

    /** Next sequence number this stack will use when sending data to the client. */
    var serverSeq: Long = 0L

    /** Next sequence number expected from the client — this stack's next ACK number. */
    var clientNextSeq: Long = 0L

    /** Decoded, in-order payload bytes arriving from the client app. */
    val fromClient = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    /** The relay coroutine driving this flow (upstream dial + byte shuffling). */
    var relayJob: Job? = null

    var localFinSent = false
}
