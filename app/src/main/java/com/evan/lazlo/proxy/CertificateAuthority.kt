package com.evan.lazlo.proxy

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Generates and stores a device-local root CA used only to re-sign leaf
 * certs for traffic this device originates.
 *
 * The CA's private key is generated *inside* Android Keystore
 * (the `AndroidKeyStore` JCA provider) with `PURPOSE_SIGN` only: it's
 * created non-extractable — hardware/TEE-backed where the device
 * supports it — and the raw key bytes are never available to this
 * process, to disk, to backups, or to a debugger. Only "sign this data"
 * operations are possible through the returned [PrivateKey] handle,
 * which is exactly what leaf-certificate signing needs (see
 * [LeafCertificateFactory]) and nothing more.
 *
 * AndroidKeyStore's own `KeyPairGenerator` only ever auto-issues a bare
 * self-signed placeholder certificate (no `BasicConstraints`/`KeyUsage`
 * extensions, so it can't function as a CA). The real CA certificate —
 * the one a browser or the OS's cert-chain validator actually needs to
 * accept anything signed under it — is built explicitly here with
 * BouncyCastle and signed using the Keystore-backed key, then persisted
 * back onto that *same* Keystore entry via [KeyStore.setKeyEntry]. That
 * call is a certificate-chain update for an already-existing
 * hardware-backed key, not a key import: no key material crosses out of
 * (or into) the Keystore at any point.
 */
class CertificateAuthority(private val context: Context) {

    private val alias = "lazlo-local-mitm-ca"
    private val androidKeyStore = "AndroidKeyStore"

    init { Security.addProvider(BouncyCastleProvider()) }

    /** Returns the existing CA cert if one is already provisioned, else generates one. */
    fun ensureCaExists(): X509Certificate {
        val ks = keyStore()
        (ks.getCertificate(alias) as? X509Certificate)?.let { existing ->
            if (isUsableCaCertificate(existing)) return existing
            // A cert without CA:true/keyCertSign can't have come from
            // generateAndPersistCa() below; treat it as stale and replace it
            // rather than fail every leaf-signing call down the line.
        }
        return generateAndPersistCa(ks)
    }

    /** The CA's Keystore-backed signing key, for leaf-certificate issuance. Call [ensureCaExists] first. */
    fun caPrivateKey(): PrivateKey =
        keyStore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("CA key missing; call ensureCaExists() first")

    /** Kicks off the OS's own "install this certificate" flow — never automatic. */
    fun installIntent(cert: X509Certificate): Intent =
        KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
            putExtra(KeyChain.EXTRA_NAME, "Lazlo Local Inspector")
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }

    // X509Certificate#getBasicConstraints() returns -1 when the
    // BasicConstraints extension is absent, per its own javadoc — the
    // one reliable way to tell "this is actually a CA cert" from a bare
    // placeholder without re-parsing extensions by hand.
    private fun isUsableCaCertificate(cert: X509Certificate): Boolean = cert.basicConstraints >= 0

    private fun generateAndPersistCa(ks: KeyStore): X509Certificate {
        val keyPair = generateKeystoreBackedKeyPair()
        val cert = buildSelfSignedCaCertificate(keyPair.public, keyPair.private)
        ks.setKeyEntry(alias, keyPair.private, null, arrayOf(cert))
        return cert
    }

    private fun generateKeystoreBackedKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, androidKeyStore)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(4096)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            // Placeholder subject/validity for the bare self-signed cert
            // AndroidKeyStore insists on minting alongside the key; it's
            // immediately discarded in favor of the real CA cert built
            // (and re-attached to this same entry) below.
            .setCertificateSubject(X500Principal("CN=Lazlo Local Inspector CA"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date())
            .setCertificateNotAfter(Date(System.currentTimeMillis() + CA_VALIDITY_MS))
            .build()
        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }

    private fun buildSelfSignedCaCertificate(publicKey: PublicKey, privateKey: PrivateKey): X509Certificate {
        val name = X500Name("CN=Lazlo Local Inspector CA, O=Device-local only")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + CA_VALIDITY_MS)

        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, publicKey)
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(publicKey))

        // An AndroidKeyStore-backed PrivateKey works transparently with a
        // plain JCA Signature under the hood (routed to the Keystore
        // provider's SPI), so BouncyCastle's signer builder needs no
        // special-casing here even though the key itself is non-extractable.
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private companion object {
        const val CA_VALIDITY_MS = 365L * 24 * 60 * 60 * 1000L
    }
}
