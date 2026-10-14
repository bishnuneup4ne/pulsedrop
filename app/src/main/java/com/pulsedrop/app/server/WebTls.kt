package com.pulsedrop.app.server

import fi.iki.elonen.NanoHTTPD
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Self-signed TLS identity for the browser leg. A PC's browser only exposes
 * getUserMedia/getDisplayMedia on a secure origin, so the LAN HTTP port alone
 * can never carry a video call. The visitor accepts the certificate warning
 * once; the certificate itself lives only for this process.
 */
object WebTls {
    private val serial = AtomicLong(System.currentTimeMillis())

    /** Host names/IPs to bake into the certificate. localhost is always included. */
    fun socketFactory(hosts: List<String>): javax.net.ssl.SSLServerSocketFactory = identity(hosts).first

    fun identity(hosts: List<String>): Pair<javax.net.ssl.SSLServerSocketFactory, java.security.cert.X509Certificate> {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=PulseDrop LAN")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(serial.incrementAndGet()),
            Date(now - 24L * 3600_000L),
            Date(now + 3650L * 24L * 3600_000L),
            subject,
            pair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val names = (listOf("localhost", "127.0.0.1") + hosts.filter { it.isNotBlank() }).distinct()
            .map { host ->
                val ip = host.toAddressOrNull()
                if (ip != null) GeneralName(GeneralName.iPAddress, org.bouncycastle.asn1.DEROctetString(ip.address))
                else GeneralName(GeneralName.dNSName, host)
            }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(pair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))

        val password = CharArray(0)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, password)
        ks.setKeyEntry("pulsedrop", pair.private, password, arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return Pair(ctx.serverSocketFactory, cert)
    }

    /** Literal-IP parse only: a name that would trigger DNS is not an address. */
    private fun String.toAddressOrNull(): InetAddress? {
        val numeric = matches(Regex("^[0-9.]+$")) || contains(':')
        if (!numeric) return null
        return runCatching { InetAddress.getByName(this) }.getOrNull()
    }
}
