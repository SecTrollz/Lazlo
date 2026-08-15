package com.evan.lazlo.proxy.net

import com.evan.lazlo.proxy.LeafCertificateFactory
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Exercises [ConnectionRelay.relay]'s *termination* on the JVM against a
 * real loopback server — the part that can be checked off-device, unlike
 * the VPN/TUN plumbing that feeds it.
 *
 * This is the regression guard for a relay that never finished: every
 * direction was joined unconditionally, so a flow whose client hadn't
 * sent its FIN parked forever even after the upstream was long gone. A
 * parked relay never returns to [TcpIpStack.finishFlow], which means no
 * FIN back to the app and a flow's coroutines, channels, and upstream
 * socket held for as long as the inspector runs — the kind of leak that
 * only shows up after a few hundred connections, i.e. after a few minutes
 * of ordinary browsing.
 *
 * The client side here deliberately never closes [TcpFlow.fromClient]:
 * that's an app holding a keep-alive connection open, which is the exact
 * case the old code couldn't finish.
 */
class ConnectionRelayTerminationTest {

    private lateinit var eventLoopGroup: NioEventLoopGroup
    private lateinit var relay: ConnectionRelay
    private lateinit var leafCertificateFactory: LeafCertificateFactory
    private lateinit var caCertificate: X509Certificate

    @Before
    fun setUp() {
        Security.addProvider(BouncyCastleProvider())
        eventLoopGroup = NioEventLoopGroup(2)
        relay = ConnectionRelay(eventLoopGroup)
        // Unused on the passthrough path this test drives, but relay() takes
        // them for the :443 path — real objects rather than mocks, same as the
        // rest of this codebase's tests.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Lazlo Test CA")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date(now - 60_000),
            Date(now + 3_600_000),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        caCertificate = JcaX509CertificateConverter().getCertificate(holder)
        leafCertificateFactory = LeafCertificateFactory(caCertificate, keyPair.private)
    }

    @After
    fun tearDown() {
        relay.shutdown()
        eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
    }

    @Test
    fun `a flow finishes once the upstream closes, even though the client never sends FIN`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestSeen = CountDownLatch(1)
        val serverThread = thread(name = "relay-test-upstream") {
            server.accept().use { socket ->
                val request = ByteArray(4)
                socket.getInputStream().read(request)
                if (String(request) == "ping") requestSeen.countDown()
                socket.getOutputStream().apply {
                    write("pong".toByteArray())
                    flush()
                }
            } // closing here is what ends the relay
        }

        val flow = TcpFlow(
            FlowKey(
                sourceAddress = byteArrayOf(10, 0, 0, 2).toIpInt(),
                sourcePort = 51_000,
                destinationAddress = byteArrayOf(127, 0, 0, 1).toIpInt(),
                destinationPort = server.localPort,
            ),
        )
        flow.fromClient.trySend("ping".toByteArray())

        val toClient = mutableListOf<Byte>()
        runBlocking {
            // Ten seconds is far more than a loopback exchange needs; it's here
            // so the old never-terminating behavior fails the test instead of
            // hanging the build.
            withTimeout(10_000) {
                relay.relay(
                    flow = flow,
                    destinationAddress = byteArrayOf(127, 0, 0, 1),
                    destinationPort = server.localPort,
                    protectSocket = { true },
                    leafCertificateFactory = leafCertificateFactory,
                    caCertificate = caCertificate,
                    sendToClient = { bytes -> toClient.addAll(bytes.toList()) },
                    onHttpExchange = {},
                )
            }
        }

        serverThread.join(5_000)
        server.close()
        assertEquals(true, requestSeen.await(1, TimeUnit.SECONDS))
        assertEquals("pong", String(toClient.toByteArray()))
        // Nothing closed it; the relay still has to return.
        assertEquals(false, flow.fromClient.isClosedForSend)
    }
}
