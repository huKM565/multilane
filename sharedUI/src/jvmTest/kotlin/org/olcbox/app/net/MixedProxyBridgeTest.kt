package org.olcbox.app.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedProxyBridgeTest {
    @Test
    fun connectAndSocks5ShareOnePortWithoutAuth() {
        val echo = startEchoServer()
        val socksBackend = startMockSocksBackend(expectedLogin = null)
        val bridge = MixedProxyBridge(
            listenPort = 0,
            backendPort = socksBackend.localPort
        )
        bridge.start()
        try {
            Socket("127.0.0.1", bridge.boundPort).use { client ->
                val out = client.getOutputStream()
                val input = client.getInputStream()
                out.write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echo.localPort}\r\n\r\n".encodeToByteArray())
                out.flush()
                val statusLine = readHeader(input)
                assertTrue(statusLine.startsWith("HTTP/1.1 200 Connection Established"), statusLine)
                out.write("hello-connect".encodeToByteArray())
                out.flush()
                client.shutdownOutput()
                assertEquals("hello-connect", input.readBytes().decodeToString())
            }

            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", bridge.boundPort))).use { socksClient ->
                socksClient.connect(InetSocketAddress("127.0.0.1", echo.localPort), 2_000)
                socksClient.getOutputStream().write("hello-socks".encodeToByteArray())
                socksClient.getOutputStream().flush()
                socksClient.shutdownOutput()
                assertEquals("hello-socks", socksClient.getInputStream().readBytes().decodeToString())
            }
        } finally {
            bridge.stop()
            socksBackend.close()
            echo.close()
        }
    }

    @Test
    fun wildcardBindServesBothHttpAndSocks5LocallyWithoutAuth() {
        val echo = startEchoServer()
        val socksBackend = startMockSocksBackend(expectedLogin = null)
        val bridge = MixedProxyBridge(
            listenHost = "0.0.0.0",
            listenPort = 0,
            backendHost = "127.0.0.1",
            backendPort = socksBackend.localPort,
            clientLogin = null
        )
        bridge.start()
        try {
            Socket("127.0.0.1", bridge.boundPort).use { client ->
                val out = client.getOutputStream()
                val input = client.getInputStream()
                out.write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echo.localPort}\r\n\r\n".encodeToByteArray())
                out.flush()
                assertTrue(readHeader(input).startsWith("HTTP/1.1 200 Connection Established"))
                out.write("ping".encodeToByteArray())
                out.flush()
                client.shutdownOutput()
                assertEquals("ping", input.readBytes().decodeToString())
            }
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", bridge.boundPort))).use { socksClient ->
                socksClient.connect(InetSocketAddress("127.0.0.1", echo.localPort), 2_000)
                socksClient.getOutputStream().write("pong".encodeToByteArray())
                socksClient.getOutputStream().flush()
                socksClient.shutdownOutput()
                assertEquals("pong", socksClient.getInputStream().readBytes().decodeToString())
            }
        } finally {
            bridge.stop()
            socksBackend.close()
            echo.close()
        }
    }

    @Test
    fun enforcesClientHttpAndSocksAuthAndTranslatesBackendLogin() {
        val echo = startEchoServer()
        val backendLogin = SocksLogin("internal", "secret-backend")
        val clientLogin = SocksLogin("local", "pass:123")
        val socksBackend = startMockSocksBackend(expectedLogin = backendLogin)
        val bridge = MixedProxyBridge(
            listenPort = 0,
            backendPort = socksBackend.localPort,
            clientLogin = clientLogin,
            backendLogin = backendLogin
        )
        bridge.start()
        try {
            Socket("127.0.0.1", bridge.boundPort).use { unauth ->
                unauth.getOutputStream().write(
                    "CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echo.localPort}\r\n\r\n".encodeToByteArray()
                )
                unauth.getOutputStream().flush()
                val response = readHeader(unauth.getInputStream())
                assertTrue(response.startsWith("HTTP/1.1 407 Proxy Authentication Required"), response)
                assertTrue(response.contains("Proxy-Authenticate: Basic"), response)
            }

            val badCreds = Base64.getEncoder().encodeToString("local:wrong".encodeToByteArray())
            Socket("127.0.0.1", bridge.boundPort).use { wrongAuth ->
                wrongAuth.getOutputStream().write(
                    "CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echo.localPort}\r\nProxy-Authorization: Basic $badCreds\r\n\r\n".encodeToByteArray()
                )
                wrongAuth.getOutputStream().flush()
                val response = readHeader(wrongAuth.getInputStream())
                assertTrue(response.startsWith("HTTP/1.1 407 Proxy Authentication Required"), response)
            }

            val goodCreds = Base64.getEncoder().encodeToString("local:pass:123".encodeToByteArray())
            Socket("127.0.0.1", bridge.boundPort).use { authed ->
                authed.getOutputStream().write(
                    "CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echo.localPort}\r\nProxy-Authorization: Basic $goodCreds\r\n\r\n".encodeToByteArray()
                )
                authed.getOutputStream().flush()
                val response = readHeader(authed.getInputStream())
                assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"), response)
                authed.getOutputStream().write("tunneled".encodeToByteArray())
                authed.getOutputStream().flush()
                authed.shutdownOutput()
                assertEquals("tunneled", authed.getInputStream().readBytes().decodeToString())
            }

            Socket("127.0.0.1", bridge.boundPort).use { socksClient ->
                val sIn = DataInputStream(socksClient.getInputStream())
                val sOut = DataOutputStream(socksClient.getOutputStream())
                sOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                sOut.flush()
                assertEquals(0x05, sIn.readUnsignedByte())
                assertEquals(0x02, sIn.readUnsignedByte())
                sOut.write(byteArrayOf(0x01, 5) + "local".encodeToByteArray() + byteArrayOf(8) + "pass:123".encodeToByteArray())
                sOut.flush()
                assertEquals(0x01, sIn.readUnsignedByte())
                assertEquals(0x00, sIn.readUnsignedByte())
                sOut.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, ((echo.localPort ushr 8) and 0xFF).toByte(), (echo.localPort and 0xFF).toByte()))
                sOut.flush()
                assertEquals(0x05, sIn.readUnsignedByte())
                assertEquals(0x00, sIn.readUnsignedByte())
                sIn.readUnsignedByte()
                sIn.readUnsignedByte()
                sIn.readFully(ByteArray(6))
                sOut.write("socks-authed".encodeToByteArray())
                sOut.flush()
                socksClient.shutdownOutput()
                assertEquals("socks-authed", sIn.readBytes().decodeToString())
            }
        } finally {
            bridge.stop()
            socksBackend.close()
            echo.close()
        }
    }

    @Test
    fun forwardsPlainHttpGetAndPostAndStripsProxyHeaders() {
        var capturedRequest = ""
        val httpOrigin = ServerSocket(0).also { server ->
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        client.use {
                            val raw = readHeader(it.getInputStream())
                            capturedRequest = raw
                            val body = "origin-ok"
                            it.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".encodeToByteArray()
                            )
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        val socksBackend = startMockSocksBackend(expectedLogin = null)
        val creds = Base64.getEncoder().encodeToString("u1:p1".encodeToByteArray())
        val bridge = MixedProxyBridge(
            listenPort = 0,
            backendPort = socksBackend.localPort,
            clientLogin = SocksLogin("u1", "p1")
        )
        bridge.start()
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", bridge.boundPort))
            val url = URL("http://127.0.0.1:${httpOrigin.localPort}/hello?x=1")
            val conn = url.openConnection(proxy)
            conn.setRequestProperty("Proxy-Authorization", "Basic $creds")
            val response = conn.getInputStream().readBytes().decodeToString()
            assertEquals("origin-ok", response)
            assertTrue(capturedRequest.startsWith("GET /hello?x=1 HTTP/1.1\r\n"), capturedRequest)
            assertTrue(!capturedRequest.contains("Proxy-Authorization", ignoreCase = true), capturedRequest)
            assertTrue(!capturedRequest.contains("Proxy-Connection", ignoreCase = true), capturedRequest)
        } finally {
            bridge.stop()
            socksBackend.close()
            httpOrigin.close()
        }
    }

    private fun readHeader(input: java.io.InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            val s = buf.toString()
            if (s.endsWith("\r\n\r\n")) return s
        }
        return buf.toString()
    }

    private fun startEchoServer(): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use {
                        it.getInputStream().copyTo(it.getOutputStream())
                    }
                }
            }
        }
    }

    private fun startMockSocksBackend(expectedLogin: SocksLogin?): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        if (input.readUnsignedByte() != 0x05) return@use
                        val count = input.readUnsignedByte()
                        val methods = ByteArray(count)
                        input.readFully(methods)
                        if (expectedLogin == null) {
                            output.write(byteArrayOf(0x05, 0x00))
                            output.flush()
                        } else {
                            if (!methods.contains(0x02)) {
                                output.write(byteArrayOf(0x05, 0xFF.toByte()))
                                output.flush()
                                return@use
                            }
                            output.write(byteArrayOf(0x05, 0x02))
                            output.flush()
                            if (input.readUnsignedByte() != 0x01) return@use
                            val u = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                            val p = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                            if (u != expectedLogin.username || p != expectedLogin.password) {
                                output.write(byteArrayOf(0x01, 0x01))
                                output.flush()
                                return@use
                            }
                            output.write(byteArrayOf(0x01, 0x00))
                            output.flush()
                        }
                        if (input.readUnsignedByte() != 0x05) return@use
                        if (input.readUnsignedByte() != 0x01) return@use
                        input.readUnsignedByte()
                        val host = when (val atyp = input.readUnsignedByte()) {
                            0x01 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
                            0x03 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                            0x04 -> ByteArray(16).also { input.readFully(it) }.let { "127.0.0.1" }
                            else -> return@use
                        }
                        val port = input.readUnsignedShort()
                        Socket(host, port).use { target ->
                            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                            output.flush()
                            val c2t = thread(isDaemon = true) {
                                runCatching { socket.getInputStream().copyTo(target.getOutputStream()) }
                                runCatching { target.shutdownOutput() }
                            }
                            val t2c = thread(isDaemon = true) {
                                runCatching { target.getInputStream().copyTo(socket.getOutputStream()) }
                                runCatching { socket.shutdownOutput() }
                            }
                            c2t.join()
                            t2c.join()
                        }
                    }
                }
            }
        }
    }
}
