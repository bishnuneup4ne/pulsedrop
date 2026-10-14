package com.pulsedrop.app.server

import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509TrustManager

class WebTlsTest {

    @Test
    fun factoryFromWebTlsCompletesTlsHandshake() {
        val (factory, _) = WebTls.identity(listOf("10.0.0.5", "192.168.1.7"))
        val server = factory.createServerSocket(0) as SSLServerSocket
        var serverError: Throwable? = null
        val serverThread = Thread {
            try {
                server.accept().use { peer ->
                    val input = peer.inputStream.reader().let { r ->
                        val buf = CharArray(512)
                        val sb = StringBuilder()
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0 || sb.append(buf, 0, n).contains("\r\n\r\n")) break
                        }
                        sb.toString()
                    }
                    check(input.startsWith("GET ")) { "server read: $input" }
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\npong".toByteArray())
                        flush()
                    }
                }
            } catch (e: Throwable) {
                serverError = e
            }
        }.apply { isDaemon = true }
        serverThread.start()

        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        try {
            ctx.socketFactory.createSocket("localhost", server.localPort).use { peer ->
                peer.soTimeout = 10_000
                peer.getOutputStream().apply {
                    write("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val text = peer.inputStream.reader().readText()
                serverThread.join(2000)
                assertTrue("response was: $text / server: ${serverError?.toString() ?: "ok"}",
                    text.startsWith("HTTP/1.1 200 OK"))
                assertTrue("response was: $text / server: ${serverError?.toString() ?: "ok"}",
                    text.endsWith("pong"))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun certificateCarriesLanAddressesAsSans() {
        val (_, cert) = WebTls.identity(listOf("10.0.0.5", "", "172.16.0.9"))
        val names = cert.subjectAlternativeNames!!
        val dns = names.filter { it[0] == 2 }.map { it[1] as String }
        val ips = names.filter { it[0] == 7 }.map { it[1] as String }
        assertTrue("dns=$dns ips=$ips", dns.contains("localhost"))
        assertTrue("dns=$dns ips=$ips", ips.contains("10.0.0.5"))
        assertTrue("dns=$dns ips=$ips", ips.contains("172.16.0.9"))
    }
}
