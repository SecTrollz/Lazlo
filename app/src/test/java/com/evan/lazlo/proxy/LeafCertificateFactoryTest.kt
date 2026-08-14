package com.evan.lazlo.proxy

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Exercises [LeafCertificateFactory]'s actual X.509-building logic end
 * to end on the JVM, without touching Android Keystore: the CA here is
 * an ordinary software keypair rather than [CertificateAuthority]'s
 * hardware-backed one, but the signing/verification path exercised is
 * identical — [LeafCertificateFactory] only ever receives a
 * [java.security.PrivateKey] handle and never cares where it came from.
 */
class LeafCertificateFactoryTest {

    private lateinit var caCert: X509Certificate
    private lateinit var factory: LeafCertificateFactory

    @Before
    fun setUp() {
        Security.addProvider(BouncyCastleProvider())
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

        factory = LeafCertificateFactory(caCert, caKeyPair.private)
    }

    @Test
    fun `leaf is signed by the CA and verifies against its public key`() {
        val leaf = factory.leafFor("example.com")
        leaf.certificate.verify(caCert.publicKey) // throws on failure — the assertion is "doesn't throw"
    }

    @Test
    fun `leaf subject and issuer match the requested host and the CA`() {
        val leaf = factory.leafFor("example.com")
        assertEquals("CN=example.com", leaf.certificate.subjectX500Principal.name)
        assertEquals(caCert.subjectX500Principal.name, leaf.certificate.issuerX500Principal.name)
    }

    @Test
    fun `leaf is not itself a CA`() {
        val leaf = factory.leafFor("example.com")
        // X509Certificate#getBasicConstraints() returns -1 when the
        // extension is absent or present-but-not-a-CA; either way this
        // leaf must not be usable to sign further certificates.
        assertTrue(leaf.certificate.basicConstraints < 0)
    }

    @Test
    fun `leaf carries a matching subject alternative name`() {
        val leaf = factory.leafFor("example.com")
        val sans = leaf.certificate.subjectAlternativeNames
        assertTrue(sans.any { entry -> entry[1] == "example.com" })
    }

    @Test
    fun `repeated requests for the same host return the cached leaf`() {
        val first = factory.leafFor("example.com")
        val second = factory.leafFor("example.com")
        assertSame(first, second)
    }

    @Test
    fun `different hosts get different leaves`() {
        val a = factory.leafFor("a.example.com")
        val b = factory.leafFor("b.example.com")
        assertEquals("CN=a.example.com", a.certificate.subjectX500Principal.name)
        assertEquals("CN=b.example.com", b.certificate.subjectX500Principal.name)
    }
}
