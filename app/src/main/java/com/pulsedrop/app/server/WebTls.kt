package com.pulsedrop.app.server

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
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.Principal
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager

/**
 * Self-signed TLS identity for the browser leg. A PC's browser only exposes
 * getUserMedia/getDisplayMedia on a secure origin, so the LAN HTTP port alone
 * can never carry a video call. The visitor accepts the certificate warning
 * once; the certificate itself lives only for this process.
 */
object WebTls {
    private val serial = AtomicLong(System.currentTimeMillis())
    private const val ALIAS = "pulsedrop"

    /**
     * Our own provider *instance*, passed to every BouncyCastle call, rather than the name `"BC"`.
     * Android registers a platform copy of BouncyCastle under that name before the app starts, so a
     * lookup by name builds half the certificate with the OS's older, trimmed-down copy while the
     * classes in this APK came from ours — and the mismatch throws. Passing the instance is not
     * optional here; on a real phone the name-based version never produced a key at all, which is
     * how every browser on the LAN ended up locked out of mic, camera and screen.
     */
    private val bc = BouncyCastleProvider()

    /** Host names/IPs to bake into the certificate. localhost is always included. */
    fun socketFactory(hosts: List<String>): javax.net.ssl.SSLServerSocketFactory = identity(hosts).first

    fun identity(hosts: List<String>): Pair<javax.net.ssl.SSLServerSocketFactory, X509Certificate> {
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
            .setProvider(bc)
            .build(pair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(bc)
            .getCertificate(builder.build(signer))

        // No KeyStore. `KeyStore.getDefaultType()` is the platform default, and on Android that
        // resolves to `AndroidCAStore` — the read-only system CA list, where `setKeyEntry` throws
        // instead of holding this keypair. A key manager that returns the key directly needs no
        // provider, no keystore type, and no password, so there is nothing left for the OS to
        // refuse. The TLS stack asks for a server key by type; only RSA exists here, by design.
        val chain = arrayOf(cert)
        val keyManager = object : X509KeyManager {
            private fun aliasesFor(keyType: String?): Array<String>? =
                if (keyType == null || keyType.equals("RSA", ignoreCase = true)) arrayOf(ALIAS) else null

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                aliasesFor(keyType)

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = aliasesFor(keyType)?.firstOrNull()

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) chain else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == ALIAS) pair.private else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf<KeyManager>(keyManager), null, null)
        return Pair(ctx.serverSocketFactory, cert)
    }

    /** Literal-IP parse only: a name that would trigger DNS is not an address. */
    private fun String.toAddressOrNull(): InetAddress? {
        val numeric = matches(Regex("^[0-9.]+$")) || contains(':')
        if (!numeric) return null
        return runCatching { InetAddress.getByName(this) }.getOrNull()
    }
}
