package com.pulsedrop.app.server

import java.net.InetAddress
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's https port is the only way a browser on a PC gets a camera, so an identity that
 * cannot be built is not a detail — it is the whole feature missing, silently. These tests drive a
 * real handshake instead of trusting that the objects were constructed.
 */
class WebTlsTest {

    /** Trusts exactly the certificate this phone generated, and nothing else. */
    private fun trusting(leaf: X509Certificate): SSLContext {
        val verifier = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val matched = chain?.find { it.serialNumber == leaf.serialNumber }
                assertNotNull("server presented a certificate nobody asked for", matched)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(verifier), null) }
    }

    @Test
    fun `the lan identity completes a real tls handshake`() {
        val (factory, cert) = WebTls.identity(listOf("192.168.1.35"))
        val server = factory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val acceptor = Thread {
            val leg = server.accept() as SSLSocket
            leg.startHandshake()
            leg.outputStream.use { it.write("ok".toByteArray()) }
            leg.close()
        }.apply { isDaemon = true; start() }
        try {
            val client = trusting(cert).socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
            client.soTimeout = 15_000
            client.startHandshake()
            val answer = client.inputStream.readBytes().toString(Charsets.UTF_8)
            val peers = client.session.peerCertificates.filterIsInstance<X509Certificate>()
            client.close()

            // A key manager that cannot hand over the private key builds an SSLContext happily and
            // then fails here, on the first visitor who tries — which is exactly how this was lost.
            assertEquals("ok", answer)
            assertTrue(
                "handshake presented ${peers.map { it.serialNumber }}",
                peers.any { it.serialNumber == cert.serialNumber }
            )
        } finally {
            server.close()
            acceptor.join(15_000)
        }
    }

    @Test
    fun `the certificate names the address the browser will type`() {
        val (_, cert) = WebTls.identity(listOf("192.168.1.35", "pulsedrop.local", ""))
        // Type 2 = DNS name, type 7 = IP address. A browser checks this list and nothing else: an
        // address missing from it is a hard certificate error the visitor cannot click past.
        val san = cert.subjectAlternativeNames.orEmpty().map { (it[0] as Int) to it[1] }
        assertTrue("DNS name missing: $san", san.contains(2 to "pulsedrop.local"))
        assertTrue("localhost missing: $san", san.contains(2 to "localhost"))
        assertTrue(
            "LAN address missing from the SAN list: $san",
            san.any { it.first == 7 && it.second.toString().contains("192.168.1.35") }
        )
    }

    @Test
    fun `a platform whose default keystore is read only still gets a working identity`() {
        // Android's `KeyStore.getDefaultType()` resolves to `AndroidCAStore`, the read-only system CA
        // list, where writing a key entry throws. That is what stopped this phone's secure port from
        // ever starting, so building the identity must not go near the platform default keystore.
        val previous = Security.getProperty("keystore.type")
        Security.setProperty("keystore.type", "AndroidCAStore")
        try {
            val (factory, _) = WebTls.identity(listOf("192.168.1.35"))
            (factory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).close()
        } finally {
            Security.setProperty("keystore.type", previous)
        }
    }
}
