package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.time.TimeSource

internal const val BOND_PROBE_URL = "https://speed.cloudflare.com/__down?bytes=262144"
internal const val BOND_PROBE_BYTES = 262_144

internal data class BondTransferMeasurement(
    val latencyMillis: Long,
    val bytesPerSecond: Double,
)

internal suspend fun measureBondDownload(client: HttpClient, url: String): BondTransferMeasurement? {
    val requestStarted = TimeSource.Monotonic.markNow()
    return try {
        val response = client.get(url)
        val latency = requestStarted.elapsedNow().inWholeMilliseconds
        if (response.status.value !in 200..299) return null
        val bodyStarted = TimeSource.Monotonic.markNow()
        val body = response.bodyAsBytes()
        if (body.size != BOND_PROBE_BYTES) return null
        val bodyMillis = bodyStarted.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        BondTransferMeasurement(latency, body.size * 1000.0 / bodyMillis)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

internal class HttpBondRoomProbe(
    private val username: String,
    private val password: String,
    private val attempts: Int = 2,
    private val timeoutMillis: Long = 5_000,
    private val probeUrl: String = BOND_PROBE_URL,
    private val clientFactory: (SubscriptionFetchProxy, Long, Long, Long) -> HttpClient = {
            proxy, connectTimeout, requestTimeout, socketTimeout ->
        createProxyHttpClient(
            subscriptionProxy = proxy,
            connectTimeoutMs = connectTimeout,
            requestTimeoutMs = requestTimeout,
            socketTimeoutMs = socketTimeout,
        )
    },
) : BondRoomProbe {
    override suspend fun measure(target: BondRoomTarget): BondRoomSample? {
        val successful = mutableListOf<BondTransferMeasurement>()
        repeat(attempts) {
            val proxy = SubscriptionFetchProxy("127.0.0.1", target.port, username, password)
            val client = clientFactory(proxy, timeoutMillis, timeoutMillis, timeoutMillis)
            try {
                val measurement = withProxyAuthentication(proxy) { measureBondDownload(client, probeUrl) }
                if (measurement != null) successful += measurement
            } finally {
                client.close()
            }
        }
        if (successful.isEmpty()) return null
        return BondRoomSample(
            index = target.index,
            latencyMillis = median(successful.map { it.latencyMillis.toDouble() }).toLong(),
            bytesPerSecond = median(successful.map { it.bytesPerSecond }),
        )
    }
}
