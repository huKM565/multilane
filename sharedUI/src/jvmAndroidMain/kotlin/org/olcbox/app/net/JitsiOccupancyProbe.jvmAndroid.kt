package org.olcbox.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.olcbox.app.data.model.BondRoomConfig
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private data class JitsiHostConfig(
    val xmppDomain: String,
    val mucDomain: String,
    val wsUrl: String,
)

private val jitsiProbeHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
}

private val jitsiHostConfigCache = ConcurrentHashMap<String, JitsiHostConfig>()
private val jitsiHostConfigLock = Any()

actual fun createJitsiOccupancyProbe(): BondRoomOccupancyProbe = BondRoomOccupancyProbe { room ->
    probeJitsiRoomOccupancy(room)
}

private fun fetchJitsiHostConfig(host: String): JitsiHostConfig {
    jitsiHostConfigCache[host]?.let { return it }
    synchronized(jitsiHostConfigLock) {
        jitsiHostConfigCache[host]?.let { return it }
        var xmppDomain = host
        var mucDomain = "conference.$host"
        var wsUrl = "wss://$host/xmpp-websocket"
        runCatching {
            val req = Request.Builder().url("https://$host/config.js").get().build()
            jitsiProbeHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    extractJsField(body, "domain").takeIf { it.isNotEmpty() }?.let { xmppDomain = it }
                    extractJsField(body, "muc").takeIf { it.isNotEmpty() }?.let { mucDomain = it }
                    extractJsField(body, "websocket").takeIf { it.isNotEmpty() }?.let { raw ->
                        wsUrl = if (raw.startsWith("//")) "wss:$raw" else raw
                    }
                }
            }
        }
        return JitsiHostConfig(xmppDomain, mucDomain, wsUrl).also {
            jitsiHostConfigCache[host] = it
        }
    }
}

private suspend fun probeJitsiRoomOccupancy(room: BondRoomConfig): Int? = withContext(Dispatchers.IO) {
    val (host, roomName) = parseJitsiRoomUrl(room.room) ?: return@withContext null
    val cfg = fetchJitsiHostConfig(host)
    val encodedRoom = URLEncoder.encode(roomName, "UTF-8")
    val wsUrl = if ('?' in cfg.wsUrl) "${cfg.wsUrl}&room=$encodedRoom" else "${cfg.wsUrl}?room=$encodedRoom"
    val request = Request.Builder()
        .url(wsUrl)
        .header("Sec-WebSocket-Protocol", "xmpp")
        .header("Origin", "https://$host")
        .build()

    val result = CompletableDeferred<Int?>()
    val openStanza = """<open to="${cfg.xmppDomain}" version="1.0" xmlns="urn:ietf:params:xml:ns:xmpp-framing"/>"""
    val occupants = mutableSetOf<String>()
    val roomPrefix = "$roomName@"

    var stage = 0
    var nick = ""
    var roomJid = ""

    val ws = jitsiProbeHttpClient.newWebSocket(
        request,
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(openStanza)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (result.isCompleted) return
                when (stage) {
                    0 -> if ("features" in text) {
                        stage = 1
                        webSocket.send("""<auth mechanism="ANONYMOUS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"/>""")
                    }
                    1 -> when {
                        "<success" in text -> {
                            stage = 2
                            webSocket.send(openStanza)
                        }
                        "<failure" in text -> {
                            result.complete(null)
                            webSocket.close(1000, "")
                        }
                    }
                    2 -> if ("features" in text) {
                        stage = 3
                        webSocket.send("""<iq type="set" id="bind_1" xmlns="jabber:client"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"/></iq>""")
                    }
                    3 -> {
                        val jid = extractXmlTag(text, "jid")
                        if (jid.isNotEmpty()) {
                            nick = when {
                                '/' in jid -> jid.substringAfterLast('/')
                                '@' in jid -> jid.substringBefore('@')
                                else -> ""
                            }
                            if (nick.isEmpty()) {
                                result.complete(null)
                                webSocket.close(1000, "")
                                return
                            }
                            stage = 4
                            roomJid = "$roomName@${cfg.mucDomain}/$nick"
                            webSocket.send("""<presence to="$roomJid" xmlns="jabber:client"><x xmlns="http://jabber.org/protocol/muc"/></presence>""")
                        }
                    }
                    4 -> {
                        if (!text.startsWith("<presence")) return
                        val from = extractXmlAttribute(text, "from")
                        if (from.startsWith(roomPrefix) && '/' in from) {
                            val peer = from.substringAfterLast('/')
                            if (peer.isNotEmpty() && peer != "focus" && peer != nick) {
                                if ("type='unavailable'" in text || """type="unavailable"""" in text) {
                                    occupants.remove(peer)
                                } else {
                                    occupants.add(peer)
                                }
                            }
                        }
                        if ("""code="110"""" in text || "code='110'" in text) {
                            val count = occupants.size
                            webSocket.send("""<presence to="$roomJid" type="unavailable" xmlns="jabber:client"/>""")
                            result.complete(count)
                            webSocket.close(1000, "")
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                result.complete(null)
            }
        }
    )

    try {
        withTimeoutOrNull(3_000L) { result.await() }
    } finally {
        ws.cancel()
    }
}
