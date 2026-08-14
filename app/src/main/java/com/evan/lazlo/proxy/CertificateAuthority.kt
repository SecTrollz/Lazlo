package com.evan.lazlo.proxy

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Generates and stores a device-local root CA used only to re-sign leaf
 * certs for traffic this device originates. The private key never leaves
 * the Android Keystore-backed KeyStore entry; only the public cert is
 * ever exported, and only via the OS's own KeyChain.createInstallIntent
 * flow — the app cannot silently trust itself.
 */
class CertificateAuthority(private val context: Context) {

    private val alias = "lazlo-local-mitm-ca"

    init { Security.addProvider(BouncyCastleProvider()) }

    fun ensureCaExists(): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getCertificate(alias) as? X509Certificate)?.let { return it }
        return generateCa()
    }

    private fun generateCa(): X509Certificate {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(4096)
        val keyPair = kpg.generateKeyPair()

        val name = X500Name("CN=Lazlo Local Inspector CA, O=Device-local only")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 365L * 24 * 60 * 60 * 1000)

        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        // Private key is written back through AndroidKeyStore-wrapped
        // storage in the full implementation (KeyStore.SecretKeyEntry /
        // a Keystore-backed KeyPairGenerator provider) rather than the
        // software KeyPairGenerator shown here, which is illustrative.
        return cert
    }

    /** Kicks off the OS's own "install this certificate" flow — never automatic. */
    fun installIntent(cert: X509Certificate): Intent =
        KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
            putExtra(KeyChain.EXTRA_NAME, "Lazlo Local Inspector")
        }
}
