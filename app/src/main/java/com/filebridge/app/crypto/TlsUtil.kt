package com.filebridge.app.crypto

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Generates a fresh self-signed RSA certificate at each server start and
 * turns it into an SSLServerSocketFactory. The key lives only in memory for
 * this process lifetime, so there is no long-lived private key to steal.
 */
object TlsUtil {

    fun createServerSocketFactory(): SSLServerSocketFactory {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 5 * 60 * 1000L)
        val notAfter = Date(now + 4L * 365 * 24 * 3600 * 1000L)

        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "FileBridge")
            .build()

        val certBuilder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            notBefore,
            notAfter,
            name,
            keyPair.public
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            addExtension(
                Extension.extendedKeyUsage, true,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            )
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
        certificate.checkValidity(Date(now))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("filebridge", keyPair.private, CharArray(0), arrayOf(certificate))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, CharArray(0))

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext.serverSocketFactory
    }

    /** A tiny sanity probe so TLS is only advertised when available. */
    fun isAvailable(): Boolean = runCatching { Cipher.getInstance("AES/GCM/NoPadding") }.isSuccess
}