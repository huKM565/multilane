package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BondRoomProbeTest {
    @Test
    fun completeDownloadReturnsLatencyAndFiniteThroughput() = runTest { withContext(Dispatchers.Default) {
        val client = HttpClient(MockEngine {
            respond(ByteArray(262144), HttpStatusCode.OK)
        })
        try {
            val result = measureBondDownload(client, BOND_PROBE_URL)

            assertNotNull(result)
            assertTrue(result.latencyMillis >= 0)
            assertTrue(result.bytesPerSecond.isFinite() && result.bytesPerSecond > 0.0)
        } finally {
            client.close()
        }
    } }

    @Test
    fun unsuccessfulStatusAndWrongLengthReturnNull() = runTest { withContext(Dispatchers.Default) {
        for (response in listOf(
            ByteArray(0) to HttpStatusCode.NoContent,
            ByteArray(262144) to HttpStatusCode.InternalServerError,
            ByteArray(3) to HttpStatusCode.OK,
        )) {
            val client = HttpClient(MockEngine { respond(response.first, response.second) })
            try {
                assertNull(measureBondDownload(client, BOND_PROBE_URL))
            } finally {
                client.close()
            }
        }
    } }

    @Test
    fun networkFailureReturnsNullButCancellationPropagates() = runTest { withContext(Dispatchers.Default) {
        val failed = HttpClient(MockEngine { throw IllegalStateException("offline") })
        try {
            assertNull(measureBondDownload(failed, BOND_PROBE_URL))
        } finally {
            failed.close()
        }

        val cancelled = HttpClient(MockEngine { throw CancellationException("superseded") })
        try {
            assertFailsWith<CancellationException> { measureBondDownload(cancelled, BOND_PROBE_URL) }
        } finally {
            cancelled.close()
        }
    } }

    @Test
    fun stalledBodyHonorsClientDeadline() = runTest { withContext(Dispatchers.Default) {
        val client = HttpClient(MockEngine { awaitCancellation() }) {
            install(HttpTimeout) {
                requestTimeoutMillis = 50
                connectTimeoutMillis = 50
                socketTimeoutMillis = 50
            }
        }
        try {
            assertNull(measureBondDownload(client, BOND_PROBE_URL))
        } finally {
            client.close()
        }
    } }

    @Test
    fun httpProbeRunsTwoAttemptsAndClosesEveryClient() = runTest { withContext(Dispatchers.Default) {
        val clients = mutableListOf<HttpClient>()
        val probe = probeWithFailures(clients) { false }

        val sample = probe.measure(BondRoomTarget(7, 21088))

        assertEquals(7, sample?.index)
        assertEquals(2, clients.size)
        assertTrue(clients.all { it.coroutineContext[Job]?.isCompleted == true })
    } }

    @Test
    fun cancellationStopsBeforeTheSecondAttemptAndClosesTheClient() = runTest { withContext(Dispatchers.Default) {
        val clients = mutableListOf<HttpClient>()
        val probe = HttpBondRoomProbe(
            username = "user",
            password = "pass",
            clientFactory = { _, _, _, _ ->
                HttpClient(MockEngine { throw CancellationException("cancelled") }).also { clients += it }
            },
        )

        assertFailsWith<CancellationException> { probe.measure(BondRoomTarget(0, 21081)) }
        assertEquals(1, clients.size)
        assertTrue(clients.single().coroutineContext[Job]?.isCompleted == true)
    } }

    @Test
    fun oneSuccessfulAttemptIsEnoughAndTwoFailuresReturnNull() = runTest { withContext(Dispatchers.Default) {
        val partialClients = mutableListOf<HttpClient>()
        val partial = probeWithFailures(partialClients) { it == 0 }

        assertNotNull(partial.measure(BondRoomTarget(3, 21084)))
        assertEquals(2, partialClients.size)
        assertTrue(partialClients.all { it.coroutineContext[Job]?.isCompleted == true })

        val failedClients = mutableListOf<HttpClient>()
        val failed = probeWithFailures(failedClients) { true }

        assertNull(failed.measure(BondRoomTarget(3, 21084)))
        assertEquals(2, failedClients.size)
        assertTrue(failedClients.all { it.coroutineContext[Job]?.isCompleted == true })
    } }

    private fun probeWithFailures(
        clients: MutableList<HttpClient>,
        fails: (Int) -> Boolean,
    ): HttpBondRoomProbe = HttpBondRoomProbe(
        username = "user",
        password = "pass",
        clientFactory = { _, _, _, _ ->
            val attempt = clients.size
            HttpClient(MockEngine {
                if (fails(attempt)) {
                    respond(ByteArray(0), HttpStatusCode.InternalServerError)
                } else {
                    respond(ByteArray(262144), HttpStatusCode.OK)
                }
            }).also { clients += it }
        },
    )
}
