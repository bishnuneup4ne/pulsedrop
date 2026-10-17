package com.pulsedrop.app.server

import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every browser-call complaint on a PC traced back to this: the phone's https port never bound, so
 * the tab stayed on http, where a browser will not hand over a camera or a screen. A desktop JVM
 * cannot see the cause, because it is an Android platform behaviour — Conscrypt and the system CA
 * store are what the app actually runs against. These tests run on the device.
 */
class WebTlsOnDeviceTest {

    /** A TLS client on this device completes a handshake with the identity the port serves. */
    @Test
    fun browserCanShakeHandsWithTheSelfSignedIdentity() {
        val (factory, cert) = WebTls.identity(listOf("192.168.1.35"))
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = factory.createServerSocket(0, 16, loopback) as SSLServerSocket
        var handshakeFailure: Throwable? = null
        val acceptor = Thread {
            try {
                val leg = server.accept() as SSLSocket
                leg.startHandshake()
                leg.outputStream.use { it.write("ok".toByteArray()) }
                leg.close()
            } catch (e: Throwable) {
                // Closing the server from `finally` is what ends a parked accept; that is not a failure.
                if (!server.isClosed) handshakeFailure = e
            }
        }.apply { isDaemon = true; start() }
        val answer: String
        val presented: X509Certificate?
        try {
            val verifier = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                    assertTrue("unexpected chain size: ${chain?.size}", chain?.size == 1)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(verifier), null) }
            val client = ctx.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
            client.soTimeout = 20_000
            client.startHandshake()
            answer = client.inputStream.readBytes().toString(Charsets.UTF_8)
            presented = client.session.peerCertificates.firstOrNull() as? X509Certificate
            client.close()
        } finally {
            server.close()
            acceptor.join(20_000)
        }

        // Reaching this line means the platform's TLS stack took the private key and certificate out
        // of the key manager — the one thing the secure port needs in order to serve a browser tab.
        assertNull("server leg failed: ${handshakeFailure?.message}", handshakeFailure)
        assertEquals("ok", answer)
        assertEquals(cert.serialNumber, presented?.serialNumber)
    }
}
