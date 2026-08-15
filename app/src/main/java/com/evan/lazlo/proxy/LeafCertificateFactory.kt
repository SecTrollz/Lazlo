package com.evan.lazlo.proxy

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Mints a fresh leaf certificate for a given hostname on the fly, signed
 * by the local CA, so TLS can be terminated locally for that host (see
 * [ConnectionRelay]'s TLS path). This is the actual "MITM" step: a
 * client that trusts [issuerCert] will accept this leaf for [Leaf.host]
 * without complaint, the same way it would accept a real CA-issued one.
 *
 * Results are cached per hostname for the process lifetime — generating
 * an RSA keypair and signing a certificate on every single connection
 * would make every new TLS handshake pay an avoidable few hundred ms.
 */
class LeafCertificateFactory(
    private val issuerCert: X509Certificate,
    private val issuerKey: PrivateKey,
) {
    class Leaf(val host: String, val certificate: X509Certificate, val privateKey: PrivateKey)

    private val cache = ConcurrentHashMap<String, Leaf>()

    /**
     * Returns a cached leaf for [host] if one exists, else generates, signs,
     * and caches a new one.
     *
     * `computeIfAbsent`, not Kotlin's `getOrPut`: the latter is get-then-put
     * with a gap in between, so concurrent first hits on the same host each
     * run the full generate-and-sign. That's exactly the shape of real
     * traffic — a browser opens several connections to a new host at once —
     * and the work is a 2048-bit RSA keygen plus a signature, on the TLS
     * handshake's critical path. `computeIfAbsent` makes the rest wait for
     * the first one instead of duplicating it.
     */
    fun leafFor(host: String): Leaf = cache.computeIfAbsent(host) { generateLeaf(it) }

    private fun generateLeaf(host: String): Leaf {
        val keyPair = generateLeafKeyPair()
        val cert = signLeafCertificate(host, keyPair.public)
        return Leaf(host, cert, keyPair.private)
    }

    private fun generateLeafKeyPair(): KeyPair {
        // Leaf keys are ordinary software keys, not Keystore-backed: they
        // exist only for this process's in-memory lifetime, are minted
        // per new host (potentially on a connection's critical path), and
        // never touch storage — so there's no reason to pay Keystore's
        // per-operation overhead for them, only the CA's own key needs
        // that protection.
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    private fun signLeafCertificate(host: String, leafPublicKey: PublicKey): X509Certificate {
        // Built from the raw DER bytes of the CA's subject, not from
        // X500Principal.getName()'s RFC 2253 *string* form: BouncyCastle's
        // X500Name(String) constructor doesn't treat that string's RDN
        // order the way Java's own X500Principal does, so round-tripping
        // through it silently reverses the issuer's RDN order in the
        // certificate this builds.
        val issuerName = X500Name.getInstance(issuerCert.subjectX500Principal.encoded)
        val subject = X500Name("CN=$host")
        val serial = BigInteger(63, SecureRandom())
        val notBefore = Date(System.currentTimeMillis() - CLOCK_SKEW_ALLOWANCE_MS)
        val notAfter = Date(System.currentTimeMillis() + LEAF_VALIDITY_MS)

        val builder = JcaX509v3CertificateBuilder(issuerName, serial, notBefore, notAfter, subject, leafPublicKey)
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNames(host))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(issuerCert))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(leafPublicKey))

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun subjectAlternativeNames(host: String): GeneralNames {
        // A bare IP-literal host (an app connecting straight to an IP,
        // SNI-less) needs GeneralName.iPAddress; everything else needs
        // GeneralName.dNSName — mixing the two up makes strict clients
        // reject the certificate outright.
        val nameType = if (isIpLiteral(host)) GeneralName.iPAddress else GeneralName.dNSName
        return GeneralNames(GeneralName(nameType, host))
    }

    private fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || host.contains(':')

    private companion object {
        const val LEAF_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000L
        const val CLOCK_SKEW_ALLOWANCE_MS = 5L * 60 * 1000L
    }
}
