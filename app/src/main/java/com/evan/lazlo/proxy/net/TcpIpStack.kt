package com.evan.lazlo.proxy.net

import android.os.ParcelFileDescriptor
import com.evan.lazlo.proxy.TrafficEntry
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * The actual packet pump: reads raw IPv4 packets off the VPN's TUN file
 * descriptor, drives a minimal per-flow TCP state machine, and hands
 * each newly-ESTABLISHED flow to [ConnectionRelay] for the real
 * proxying work (TLS termination + re-encryption on :443, plain relay
 * on :80, raw passthrough elsewhere). UDP — mainly DNS — is relayed
 * through its own `protect()`-ed socket rather than a TCP-style flow
 * table (see [handleUdp]); every UDP datagram is logged to the traffic
 * log the same as TCP exchanges are, best-effort decoded as a DNS query
 * name when it's port 53 traffic. Nothing on this pump ever skips
 * capture to reach its destination faster — UDP was never routed around
 * the local relay, only left out of the *visible* log before this.
 *
 * Scope and honesty note: this targets *one local, well-behaved TCP
 * peer* — the device's own apps talking through a VPN TUN — not a
 * general-purpose internet-facing TCP/IP stack. A segment that arrives
 * out of order is dropped rather than buffered and reordered (the
 * sending app's own TCP stack retransmits, same as after any other
 * dropped packet); there's no congestion control, no window scaling,
 * no SACK. That's a deliberate, documented simplification appropriate
 * to "capture this device's own traffic," not an oversight — see
 * ARCHITECTURE.md's notes on this component, and treat this class as
 * needing real on-device validation before relying on it: none of its
 * TUN/VPN-facing behavior can be exercised outside a real Android
 * device or emulator.
 */
class TcpIpStack(
    private val tunFd: ParcelFileDescriptor,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean,
    private val caCertificate: X509Certificate,
    caPrivateKey: PrivateKey,
    private val onHttpExchange: (TrafficEntry) -> Unit,
    /** Fired once per outbound UDP datagram — DNS queries get a decoded question name, everything else a bare host:port label. Same TrafficLog sink as [onHttpExchange] in practice; kept as its own callback since it isn't an HTTP exchange. */
    private val onUdpDatagram: (TrafficEntry) -> Unit,
    private val scope: CoroutineScope,
) {
    private val leafCertificateFactory = com.evan.lazlo.proxy.LeafCertificateFactory(caCertificate, caPrivateKey)
    private val input = FileInputStream(tunFd.fileDescriptor)
    private val output = FileOutputStream(tunFd.fileDescriptor)
    private val writeMutex = Mutex()
    private val flows = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udpSockets = ConcurrentHashMap<FlowKey, DatagramSocket>()
    private val random = SecureRandom()

    // Shared across every flow this stack ever relays — one small event
    // loop rather than one per connection. NETTY_THREADS is deliberately
    // modest: this is a client-side proxy for one device's own traffic,
    // not a server sized for concurrent load.
    private val eventLoopGroup = NioEventLoopGroup(NETTY_THREADS)
    private val connectionRelay = ConnectionRelay(eventLoopGroup)

    @Volatile private var running = true

    suspend fun pump() {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (running) {
            val n = withContext(Dispatchers.IO) {
                runCatching { input.read(buffer) }.getOrDefault(-1)
            }
            if (n <= 0) {
                if (!running) break
                continue
            }
            val packet = IpV4Packet.parse(buffer, n) ?: continue
            when (packet.protocol) {
                IpV4Packet.PROTOCOL_TCP -> handleTcp(packet)
                IpV4Packet.PROTOCOL_UDP -> handleUdp(packet)
                else -> Unit // ICMP and anything else: neither forwarded nor inspected.
            }
        }
    }

    fun shutdown() {
        running = false
        flows.values.forEach { flow ->
            flow.relayJob?.cancel()
            flow.fromClient.close()
        }
        flows.clear()
        udpSockets.values.forEach { runCatching { it.close() } }
        udpSockets.clear()
        connectionRelay.shutdown()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    // ---- TCP ----

    private suspend fun handleTcp(packet: IpV4Packet) {
        val segment = TcpSegment.parse(packet.payload) ?: return
        val key = FlowKey.of(packet, segment.sourcePort, segment.destinationPort)

        if (segment.rst) {
            flows.remove(key)?.let { flow ->
                flow.relayJob?.cancel()
                flow.fromClient.close()
            }
            return
        }

        val existing = flows[key]
        if (existing == null) {
            // A non-SYN segment for a flow we have no record of (we
            // restarted, or already tore the flow down): nothing useful
            // to do with it beyond dropping it.
            if (segment.syn && !segment.ack) handleNewSyn(segment, key)
            return
        }

        when (existing.state) {
            TcpFlowState.SYN_RECEIVED -> handleHandshakeAck(segment, existing)
            TcpFlowState.ESTABLISHED -> handleEstablishedSegment(segment, existing)
            TcpFlowState.CLOSED -> flows.remove(key)
        }
    }

    private suspend fun handleNewSyn(segment: TcpSegment, key: FlowKey) {
        val flow = TcpFlow(key)
        flow.clientNextSeq = segment.sequenceNumber + 1
        flow.serverSeq = random.nextInt().toLong() and 0xFFFFFFFFL
        flows[key] = flow

        sendControlSegment(flow, seq = flow.serverSeq, ack = flow.clientNextSeq, flags = TcpSegment.SYN or TcpSegment.ACK)
        flow.serverSeq += 1 // SYN consumes one sequence number
    }

    private suspend fun handleHandshakeAck(segment: TcpSegment, flow: TcpFlow) {
        if (!segment.ack || segment.acknowledgmentNumber != flow.serverSeq) return
        flow.state = TcpFlowState.ESTABLISHED
        val destinationPort = flow.key.destinationPort
        val destinationAddress = flow.key.destinationAddress.toIpBytes()
        flow.relayJob = scope.launch(Dispatchers.IO) {
            try {
                connectionRelay.relay(
                    flow = flow,
                    destinationAddress = destinationAddress,
                    destinationPort = destinationPort,
                    protectSocket = protectSocket,
                    leafCertificateFactory = leafCertificateFactory,
                    caCertificate = caCertificate,
                    sendToClient = { data -> sendData(flow, data) },
                    onHttpExchange = onHttpExchange,
                )
            } catch (_: Throwable) {
                // A dead upstream, a failed TLS handshake, or the client
                // vanishing mid-flow all land here; either way the flow is
                // done, so fall through to tearing it down below rather
                // than leak it.
            } finally {
                finishFlow(flow)
            }
        }
    }

    private suspend fun handleEstablishedSegment(segment: TcpSegment, flow: TcpFlow) {
        if (segment.sequenceNumber != flow.clientNextSeq) {
            // Not the next in-order byte: either a pure retransmission of
            // data already acked (re-ACK to help the client's TCP resync;
            // this also naturally covers a retransmitted FIN, since a FIN
            // is one past clientNextSeq once we've already processed it
            // once) or a genuinely out-of-order future segment, which is
            // dropped — see the class doc for why that's an accepted
            // simplification here rather than buffering/reordering it.
            if (segment.sequenceNumber < flow.clientNextSeq) {
                sendControlSegment(flow, flow.serverSeq, flow.clientNextSeq, TcpSegment.ACK)
            }
            return
        }

        if (segment.payload.isNotEmpty()) {
            flow.clientNextSeq += segment.payload.size
            flow.fromClient.trySend(segment.payload)
            sendControlSegment(flow, flow.serverSeq, flow.clientNextSeq, TcpSegment.ACK)
        }

        if (segment.fin) {
            flow.clientNextSeq += 1
            flow.fromClient.close()
            sendControlSegment(flow, flow.serverSeq, flow.clientNextSeq, TcpSegment.ACK)
        }
    }

    /** Sends application data to the client, chunked to a safe segment size, ACK+PSH flagged. */
    private suspend fun sendData(flow: TcpFlow, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunkSize = minOf(MAX_SEGMENT_SIZE, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + chunkSize)
            sendControlSegment(flow, flow.serverSeq, flow.clientNextSeq, TcpSegment.ACK or TcpSegment.PSH, chunk)
            flow.serverSeq += chunkSize
            offset += chunkSize
        }
    }

    /** Called once a flow's relay coroutine finishes (success, error, or cancellation): send our FIN and forget the flow. */
    private suspend fun finishFlow(flow: TcpFlow) {
        if (!flow.localFinSent) {
            flow.localFinSent = true
            sendControlSegment(flow, flow.serverSeq, flow.clientNextSeq, TcpSegment.FIN or TcpSegment.ACK)
            flow.serverSeq += 1
        }
        flow.state = TcpFlowState.CLOSED
        flows.remove(flow.key)
    }

    private suspend fun sendControlSegment(flow: TcpFlow, seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0)) {
        // Replies are addressed from the flow's perspective as the app
        // sees it: source = the real remote host we're impersonating,
        // destination = the app's own TUN-assigned address.
        val replySource = flow.key.destinationAddress.toIpBytes()
        val replyDestination = flow.key.sourceAddress.toIpBytes()
        val tcpSegment = TcpSegment.build(
            sourceAddress = replySource,
            destinationAddress = replyDestination,
            sourcePort = flow.key.destinationPort,
            destinationPort = flow.key.sourcePort,
            sequenceNumber = seq,
            acknowledgmentNumber = ack,
            flags = flags,
            window = RECEIVE_WINDOW,
            payload = payload,
        )
        writePacket(IpV4Packet.build(replySource, replyDestination, IpV4Packet.PROTOCOL_TCP, tcpSegment))
    }

    // ---- UDP (relayed through its own protected socket — mainly DNS) ----

    private suspend fun handleUdp(packet: IpV4Packet) {
        val datagram = UdpDatagram.parse(packet.payload) ?: return
        val key = FlowKey.of(packet, datagram.sourcePort, datagram.destinationPort)
        val destinationAddress = packet.destinationAddress.copyOf()
        val socket = udpSockets.getOrPut(key) {
            DatagramSocket().also { s ->
                protectDatagramSocket(s)
                scope.launch(Dispatchers.IO) { pumpUdpReplies(key, s) }
            }
        }
        val sent = withContext(Dispatchers.IO) {
            runCatching {
                socket.send(DatagramPacket(datagram.payload, datagram.payload.size, InetAddress.getByAddress(destinationAddress), key.destinationPort))
            }
        }.isSuccess
        if (sent) logUdpDatagram(key, datagram)
    }

    private fun logUdpDatagram(key: FlowKey, datagram: UdpDatagram) {
        val isDns = key.destinationPort == DNS_PORT
        val host = (if (isDns) DnsMessage.parseQuestionName(datagram.payload) else null)
            ?: "${key.destinationAddress.toIpString()}:${key.destinationPort}"
        onUdpDatagram(
            TrafficEntry(
                method = if (isDns) "DNS" else "UDP",
                host = host,
                path = "",
                status = null,
                bytes = datagram.payload.size.toLong(),
            ),
        )
    }

    private suspend fun pumpUdpReplies(key: FlowKey, socket: DatagramSocket) {
        val buffer = ByteArray(65535)
        socket.soTimeout = UDP_IDLE_TIMEOUT_MS
        while (running && !socket.isClosed) {
            val length = withContext(Dispatchers.IO) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    packet.length
                } catch (_: SocketTimeoutException) {
                    null // idle timeout: this UDP "flow" is done, not an error
                } catch (_: IOException) {
                    null // socket closed or otherwise dead
                }
            } ?: break

            val reply = UdpDatagram.build(key.destinationPort, key.sourcePort, buffer.copyOf(length))
            writePacket(IpV4Packet.build(key.destinationAddress.toIpBytes(), key.sourceAddress.toIpBytes(), IpV4Packet.PROTOCOL_UDP, reply))
        }
        udpSockets.remove(key)
        runCatching { socket.close() }
    }

    // ---- shared TUN writer ----

    private suspend fun writePacket(packet: ByteArray) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { output.write(packet) }
            }
        }
    }

    private companion object {
        const val MAX_PACKET_SIZE = 32 * 1024
        const val MAX_SEGMENT_SIZE = 1400 // safely under a typical TUN's MTU minus headers
        const val RECEIVE_WINDOW = 65535
        const val UDP_IDLE_TIMEOUT_MS = 30_000
        const val NETTY_THREADS = 2
        const val DNS_PORT = 53
    }
}
