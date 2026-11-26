package org.olcbox.app.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

class MixedProxyBridge(
    private val listenHost: String = "127.0.0.1",
    private val listenPort: Int,
    private val backendHost: String = "127.0.0.1",
    private val backendPort: Int,
    private val clientLogin: SocksLogin? = null,
    private val backendLogin: SocksLogin? = null,
    private val onLog: (String) -> Unit = {}
) {
    @Volatile
    private var stopped = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null
    private val activeSockets = mutableSetOf<Socket>()

    val boundPort: Int
        get() = serverSocket?.localPort ?: listenPort

    val isRunning: Boolean
        get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

    fun start() {
        stopped = false
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(listenHost, listenPort))
        }
        serverSocket = server
        acceptThread = thread(name = "OlcboxMixedProxy", isDaemon = true) {
            acceptLoop(server)
        }
        onLog("Mixed proxy listening on $listenHost:${server.localPort}")
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        synchronized(activeSockets) {
            activeSockets.forEach { socket -> runCatching { socket.close() } }
            activeSockets.clear()
        }
        acceptThread?.interrupt()
        acceptThread = null
        serverSocket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val client = runCatching { server.accept() }
                .onFailure { if (!stopped) onLog("Mixed proxy accept failed: ${it.message}") }
                .getOrNull() ?: continue
            runCatching { client.tcpNoDelay = true }
            trackSocket(client)
            thread(name = "OlcboxMixedClient", isDaemon = true) {
                try {
                    handleClient(client)
                } finally {
                    untrackAndClose(client)
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val rawIn = client.getInputStream()
        val firstByte = rawIn.read()
        if (firstByte < 0) return
        if (firstByte == SOCKS5_VERSION) {
            handleSocks5(client, DataInputStream(rawIn), DataOutputStream(client.getOutputStream()))
        } else {
            handleHttp(client, firstByte, rawIn, client.getOutputStream())
        }
    }

    private fun handleSocks5(client: Socket, clientIn: DataInputStream, clientOut: DataOutputStream) {
        val methodCount = clientIn.readUnsignedByte()
        val methods = ByteArray(methodCount)
        clientIn.readFully(methods)

        val expectedLogin = clientLogin
        if (expectedLogin == null) {
            if (!methods.contains(SOCKS_METHOD_NO_AUTH.toByte())) {
                clientOut.write(byteArrayOf(SOCKS5_VERSION.toByte(), SOCKS_METHOD_NO_ACCEPTABLE.toByte()))
                clientOut.flush()
                return
            }
            clientOut.write(byteArrayOf(SOCKS5_VERSION.toByte(), SOCKS_METHOD_NO_AUTH.toByte()))
            clientOut.flush()
        } else {
            if (!methods.contains(SOCKS_METHOD_USER_PASS.toByte())) {
                clientOut.write(byteArrayOf(SOCKS5_VERSION.toByte(), SOCKS_METHOD_NO_ACCEPTABLE.toByte()))
                clientOut.flush()
                return
            }
            clientOut.write(byteArrayOf(SOCKS5_VERSION.toByte(), SOCKS_METHOD_USER_PASS.toByte()))
            clientOut.flush()
            if (!verifySocksUserPass(clientIn, clientOut, expectedLogin)) return
        }

        val backend = openBackendSocket() ?: return
        trackSocket(backend)
        try {
            val backendIn = DataInputStream(backend.getInputStream())
            val backendOut = DataOutputStream(backend.getOutputStream())
            if (!negotiateBackendSocks5(backendIn, backendOut)) return
            val c2b = relay(client.getInputStream(), backend.getOutputStream(), backend::shutdownOutput)
            val b2c = relay(backend.getInputStream(), client.getOutputStream(), client::shutdownOutput)
            c2b.join()
            b2c.join()
        } finally {
            untrackAndClose(backend)
        }
    }

    private fun verifySocksUserPass(
        clientIn: DataInputStream,
        clientOut: DataOutputStream,
        expected: SocksLogin
    ): Boolean {
        if (clientIn.readUnsignedByte() != SOCKS_AUTH_VERSION) return false
        val userLen = clientIn.readUnsignedByte()
        val userBytes = ByteArray(userLen)
        clientIn.readFully(userBytes)
        val passLen = clientIn.readUnsignedByte()
        val passBytes = ByteArray(passLen)
        clientIn.readFully(passBytes)
        val ok = userBytes.decodeToString() == expected.username &&
            passBytes.decodeToString() == expected.password
        clientOut.write(byteArrayOf(SOCKS_AUTH_VERSION.toByte(), if (ok) 0x00 else 0x01))
        clientOut.flush()
        return ok
    }

    private fun handleHttp(
        client: Socket,
        firstByte: Int,
        clientIn: InputStream,
        clientOut: java.io.OutputStream
    ) {
        val head = readHttpHead(firstByte, clientIn) ?: return
        val lines = head.headerText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            writeHttpResponse(clientOut, "400 Bad Request")
            return
        }
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val version = parts[2]
        val headers = lines.drop(1).filter { it.isNotEmpty() }

        val requiredLogin = clientLogin
        if (requiredLogin != null && !verifyHttpProxyAuth(headers, requiredLogin)) {
            writeHttpResponse(
                clientOut,
                "407 Proxy Authentication Required",
                listOf("Proxy-Authenticate: Basic realm=\"MultiLane\"")
            )
            return
        }

        if (method == "CONNECT") {
            val target = parseHostPort(rawTarget, 443)
            if (target == null) {
                writeHttpResponse(clientOut, "400 Bad Request")
                return
            }
            val backend = connectUpstreamSocksTarget(target.first, target.second)
            if (backend == null) {
                writeHttpResponse(clientOut, "502 Bad Gateway")
                return
            }
            trackSocket(backend)
            try {
                clientOut.write("$version 200 Connection Established\r\n\r\n".encodeToByteArray())
                clientOut.flush()
                if (head.trailing.isNotEmpty()) {
                    backend.getOutputStream().write(head.trailing)
                    backend.getOutputStream().flush()
                }
                val c2b = relay(clientIn, backend.getOutputStream(), backend::shutdownOutput)
                val b2c = relay(backend.getInputStream(), clientOut, client::shutdownOutput)
                c2b.join()
                b2c.join()
            } finally {
                untrackAndClose(backend)
            }
            return
        }

        val parsed = parseHttpRequestTarget(rawTarget, headers)
        if (parsed == null) {
            writeHttpResponse(clientOut, "400 Bad Request")
            return
        }
        val backend = connectUpstreamSocksTarget(parsed.host, parsed.port)
        if (backend == null) {
            writeHttpResponse(clientOut, "502 Bad Gateway")
            return
        }
        trackSocket(backend)
        try {
            val forwardedHeader = buildForwardedHttpRequest(method, parsed.path, version, parsed.hostHeader, headers)
            val backendOut = backend.getOutputStream()
            backendOut.write(forwardedHeader.encodeToByteArray())
            if (head.trailing.isNotEmpty()) {
                backendOut.write(head.trailing)
            }
            backendOut.flush()
            val c2b = relay(clientIn, backendOut, backend::shutdownOutput)
            val b2c = relay(backend.getInputStream(), clientOut, client::shutdownOutput)
            b2c.join()
            untrackAndClose(backend)
            untrackAndClose(client)
            c2b.join(RELAY_CLOSE_JOIN_MS)
        } finally {
            untrackAndClose(backend)
        }
    }

    private fun readHttpHead(firstByte: Int, input: InputStream): HttpHead? {
        val raw = ByteArrayOutputStream()
        raw.write(firstByte)
        val chunk = ByteArray(HTTP_READ_CHUNK_BYTES)
        while (raw.size() <= MAX_HTTP_HEADER_BYTES) {
            val bytes = raw.toByteArray()
            val headerEnd = findHeaderEnd(bytes)
            if (headerEnd >= 0) {
                val headerText = bytes.copyOfRange(0, headerEnd).decodeToString()
                val trailingStart = headerEnd + 4
                val trailing = if (trailingStart < bytes.size) {
                    bytes.copyOfRange(trailingStart, bytes.size)
                } else {
                    ByteArray(0)
                }
                return HttpHead(headerText, trailing)
            }
            val read = input.read(chunk)
            if (read < 0) return null
            raw.write(chunk, 0, read)
        }
        return null
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun verifyHttpProxyAuth(headers: List<String>, expected: SocksLogin): Boolean {
        val headerValue = headers.firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@firstNotNullOfOrNull null
            if (line.substring(0, colon).trim().equals("Proxy-Authorization", ignoreCase = true)) {
                line.substring(colon + 1).trim()
            } else {
                null
            }
        } ?: return false
        val space = headerValue.indexOf(' ')
        if (space <= 0) return false
        if (!headerValue.substring(0, space).equals("Basic", ignoreCase = true)) return false
        val encoded = headerValue.substring(space + 1).trim()
        val decoded = runCatching { Base64.getDecoder().decode(encoded).decodeToString() }.getOrNull() ?: return false
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        val user = decoded.substring(0, colon)
        val pass = decoded.substring(colon + 1)
        return user == expected.username && pass == expected.password
    }

    private fun parseHttpRequestTarget(rawTarget: String, headers: List<String>): ParsedHttpTarget? {
        if (rawTarget.startsWith("/")) {
            val hostHeader = headers.firstNotNullOfOrNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null
                else if (line.substring(0, colon).trim().equals("Host", ignoreCase = true)) {
                    line.substring(colon + 1).trim()
                } else null
            } ?: return null
            val hostPort = parseHostPort(hostHeader, 80) ?: return null
            return ParsedHttpTarget(hostPort.first, hostPort.second, rawTarget, hostHeader)
        }
        if (!rawTarget.startsWith("http://", ignoreCase = true)) return null
        val rest = rawTarget.substring(7)
        val slash = rest.indexOf('/')
        val query = rest.indexOf('?')
        val splitIdx = listOf(slash, query).filter { it >= 0 }.minOrNull() ?: rest.length
        val authority = rest.substring(0, splitIdx)
        val path = when {
            splitIdx == rest.length -> "/"
            rest[splitIdx] == '?' -> "/${rest.substring(splitIdx)}"
            else -> rest.substring(splitIdx)
        }
        val hostPort = parseHostPort(authority, 80) ?: return null
        return ParsedHttpTarget(hostPort.first, hostPort.second, path, authority)
    }

    private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket <= 1) return null
            val host = trimmed.substring(1, closeBracket)
            val after = trimmed.substring(closeBracket + 1)
            if (after.isEmpty()) return host to defaultPort
            if (!after.startsWith(":")) return null
            val port = after.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon < 0) return trimmed to defaultPort
        val host = trimmed.substring(0, colon)
        if (host.isEmpty() || host.contains(':')) return null
        val port = trimmed.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    private fun buildForwardedHttpRequest(
        method: String,
        path: String,
        version: String,
        hostHeader: String,
        headers: List<String>
    ): String {
        val filtered = headers.filterNot { line ->
            val name = line.substringBefore(':', "").trim()
            name.equals("Proxy-Authorization", ignoreCase = true) ||
                name.equals("Proxy-Connection", ignoreCase = true) ||
                name.equals("Connection", ignoreCase = true)
        }
        val hasHost = filtered.any { it.substringBefore(':', "").trim().equals("Host", ignoreCase = true) }
        return buildString {
            append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            if (!hasHost) {
                append("Host: ").append(hostHeader).append("\r\n")
            }
            filtered.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
    }

    private fun writeHttpResponse(
        output: java.io.OutputStream,
        status: String,
        extraHeaders: List<String> = emptyList()
    ) {
        val payload = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(payload.encodeToByteArray())
            output.flush()
        }
    }

    private fun openBackendSocket(): Socket? =
        runCatching {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(backendHost, backendPort), SOCKET_CONNECT_TIMEOUT_MS)
            }
        }.getOrNull()

    private fun negotiateBackendSocks5(backendIn: DataInputStream, backendOut: DataOutputStream): Boolean {
        val login = backendLogin
        if (login == null) {
            backendOut.write(byteArrayOf(SOCKS5_VERSION.toByte(), 0x01, SOCKS_METHOD_NO_AUTH.toByte()))
            backendOut.flush()
            if (backendIn.readUnsignedByte() != SOCKS5_VERSION) return false
            return backendIn.readUnsignedByte() == SOCKS_METHOD_NO_AUTH
        }
        backendOut.write(byteArrayOf(SOCKS5_VERSION.toByte(), 0x01, SOCKS_METHOD_USER_PASS.toByte()))
        backendOut.flush()
        if (backendIn.readUnsignedByte() != SOCKS5_VERSION) return false
        if (backendIn.readUnsignedByte() != SOCKS_METHOD_USER_PASS) return false
        val userBytes = login.username.encodeToByteArray()
        val passBytes = login.password.encodeToByteArray()
        backendOut.write(SOCKS_AUTH_VERSION)
        backendOut.write(userBytes.size)
        backendOut.write(userBytes)
        backendOut.write(passBytes.size)
        backendOut.write(passBytes)
        backendOut.flush()
        if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION) return false
        return backendIn.readUnsignedByte() == 0x00
    }

    private fun connectUpstreamSocksTarget(targetHost: String, targetPort: Int): Socket? {
        val backend = openBackendSocket() ?: return null
        val success = runCatching {
            val backendIn = DataInputStream(backend.getInputStream())
            val backendOut = DataOutputStream(backend.getOutputStream())
            if (!negotiateBackendSocks5(backendIn, backendOut)) return@runCatching false

            val ipv4 = parseIpv4Literal(targetHost)
            val req = ByteArrayOutputStream()
            req.write(SOCKS5_VERSION)
            req.write(SOCKS_CMD_CONNECT)
            req.write(0x00)
            if (ipv4 != null) {
                req.write(SOCKS_ATYP_IPV4)
                req.write(ipv4)
            } else if (isIpv6Literal(targetHost)) {
                val ipv6 = InetAddress.getByName(targetHost).address
                req.write(SOCKS_ATYP_IPV6)
                req.write(ipv6)
            } else {
                val hostBytes = targetHost.encodeToByteArray()
                if (hostBytes.size > 255) return@runCatching false
                req.write(SOCKS_ATYP_DOMAIN)
                req.write(hostBytes.size)
                req.write(hostBytes)
            }
            req.write((targetPort ushr 8) and 0xFF)
            req.write(targetPort and 0xFF)
            backendOut.write(req.toByteArray())
            backendOut.flush()

            if (backendIn.readUnsignedByte() != SOCKS5_VERSION) return@runCatching false
            val reply = backendIn.readUnsignedByte()
            backendIn.readUnsignedByte()
            val atyp = backendIn.readUnsignedByte()
            when (atyp) {
                SOCKS_ATYP_IPV4 -> backendIn.readFully(ByteArray(4))
                SOCKS_ATYP_IPV6 -> backendIn.readFully(ByteArray(16))
                SOCKS_ATYP_DOMAIN -> {
                    val len = backendIn.readUnsignedByte()
                    backendIn.readFully(ByteArray(len))
                }
                else -> return@runCatching false
            }
            backendIn.readFully(ByteArray(2))
            reply == 0x00
        }.getOrDefault(false)

        if (!success) {
            runCatching { backend.close() }
            return null
        }
        return backend
    }

    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val octet = parts[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            out[i] = octet.toByte()
        }
        return out
    }

    private fun isIpv6Literal(host: String): Boolean =
        host.contains(':') && host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    private fun relay(
        from: InputStream,
        to: java.io.OutputStream,
        onEofShutdownOutput: () -> Unit
    ): Thread = thread(name = "OlcboxMixedRelay", isDaemon = true) {
        runCatching {
            val buffer = ByteArray(RELAY_BUFFER_SIZE)
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
            }
        }
        runCatching { onEofShutdownOutput() }
    }

    private fun trackSocket(socket: Socket) {
        synchronized(activeSockets) {
            activeSockets.add(socket)
        }
    }

    private fun untrackAndClose(socket: Socket) {
        synchronized(activeSockets) {
            activeSockets.remove(socket)
        }
        runCatching { socket.close() }
    }

    private data class HttpHead(val headerText: String, val trailing: ByteArray)

    private data class ParsedHttpTarget(
        val host: String,
        val port: Int,
        val path: String,
        val hostHeader: String
    )

    private companion object {
        const val SOCKS5_VERSION = 0x05
        const val SOCKS_AUTH_VERSION = 0x01
        const val SOCKS_METHOD_NO_AUTH = 0x00
        const val SOCKS_METHOD_USER_PASS = 0x02
        const val SOCKS_METHOD_NO_ACCEPTABLE = 0xFF
        const val SOCKS_CMD_CONNECT = 0x01
        const val SOCKS_ATYP_IPV4 = 0x01
        const val SOCKS_ATYP_DOMAIN = 0x03
        const val SOCKS_ATYP_IPV6 = 0x04
        const val SOCKET_CONNECT_TIMEOUT_MS = 2_000
        const val HTTP_READ_CHUNK_BYTES = 4096
        const val MAX_HTTP_HEADER_BYTES = 64 * 1024
        const val RELAY_BUFFER_SIZE = 64 * 1024
        const val RELAY_CLOSE_JOIN_MS = 500L
    }
}
