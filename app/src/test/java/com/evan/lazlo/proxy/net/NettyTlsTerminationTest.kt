package com.evan.lazlo.proxy.net

import com.evan.lazlo.proxy.LeafCertificateFactory
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SniHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.Mapping
import io.netty.util.ReferenceCountUtil
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
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
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Exercises the actual mechanism [ConnectionRelay]'s TLS path relies on
 * — SNI-based dynamic leaf-certificate selection via Netty's
 * [SniHandler], backed by [LeafCertificateFactory] — end to end on the
 * JVM: a real [javax.net.ssl.SSLSocket] (a genuine TLS client, same
 * shape as any real app) connects over a real loopback TCP socket to a
 * Netty server pipeline built the same way [ConnectionRelay] builds
 * one. This checks the handshake actually succeeds, the leaf
 * certificate presented is the one [LeafCertificateFactory] minted for
 * the requested host, and bytes round-trip correctly through the
 * decrypted pipeline.
 *
 * This validates the interception mechanism itself; it doesn't (and
 * can't, on the JVM) exercise the VPN/TUN plumbing that feeds real
 * traffic into it — see [TcpIpStack] and [ConnectionRelay]'s own class
 * docs for that limitation.
 */
class NettyTlsTerminationTest {

    private lateinit var group: NioEventLoopGroup
    private lateinit var caCert: X509Certificate
    private lateinit var leafFactory: LeafCertificateFactory

    @Before
    fun setUp() {
        Security.addProvider(BouncyCastleProvider())
        group = NioEventLoopGroup(1)

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val caKeyPair = kpg.generateKeyPair()
        val name = X500Name("CN=Test Local Inspector CA, O=Device-local only")
        val notBefore = Date(System.currentTimeMillis() - 60_000)
        val notAfter = Date(System.currentTimeMillis() + 60_000 * 60)
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, notBefore, notAfter, name, caKeyPair.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.private)
        caCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        leafFactory = LeafCertificateFactory(caCert, caKeyPair.private)
    }

    @After
    fun tearDown() {
        group.shutdownGracefully()
    }

    @Test
    fun `SNI-selected leaf cert completes a real TLS handshake and round-trips bytes`() {
        val received = ArrayBlockingQueue<ByteArray>(4)

        val serverChannel = ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        SniHandler(
                            Mapping<String, SslContext> { host ->
                                val leaf = leafFactory.leafFor(host ?: "fallback.invalid")
                                SslContextBuilder.forServer(leaf.privateKey, leaf.certificate, caCert).build()
                            }
                        )
                    )
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is ByteBuf) {
                                val bytes = ByteArray(msg.readableBytes())
                                msg.readBytes(bytes)
                                msg.release()
                                received.offer(bytes)
                                ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes)) // echo, so the client can confirm the round trip
                            } else {
                                ReferenceCountUtil.release(msg)
                            }
                        }
                    })
                }
            })
            .bind(InetAddress.getLoopbackAddress(), 0)
            .sync()
            .channel()

        try {
            val port = (serverChannel.localAddress() as InetSocketAddress).port

            // A real JSSE client trusting only our CA — exactly what a
            // real app on the device does once the CA is installed.
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("test-ca", caCert)
            }
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(trustStore)
            val clientContext = SSLContext.getInstance("TLS")
            clientContext.init(null, trustManagerFactory.trustManagers, SecureRandom())

            val socket = clientContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(), port) as SSLSocket
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName("example.test"))
            socket.sslParameters = params

            socket.startHandshake()

            val presentedLeaf = socket.session.peerCertificates[0] as X509Certificate
            assertEquals(leafFactory.leafFor("example.test").certificate, presentedLeaf)

            socket.outputStream.write("ping".toByteArray())
            socket.outputStream.flush()

            val echoedToServer = received.poll(5, TimeUnit.SECONDS)
            assertEquals("ping", echoedToServer?.let { String(it) })

            val buffer = ByteArray(4)
            var readTotal = 0
            while (readTotal < 4) {
                val n = socket.inputStream.read(buffer, readTotal, 4 - readTotal)
                if (n < 0) break
                readTotal += n
            }
            assertEquals("ping", String(buffer, 0, readTotal))

            socket.close()
        } finally {
            serverChannel.close().sync()
        }
    }
}
