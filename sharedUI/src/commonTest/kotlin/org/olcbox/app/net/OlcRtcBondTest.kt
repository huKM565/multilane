package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OlcRtcBondTest {
    @Test
    fun bondRequiresFourEightOrTwelveRuntimes() {
        assertFailsWith<IllegalArgumentException> { OlcRtcBond(fakes(3)) }
        assertFailsWith<IllegalArgumentException> { OlcRtcBond(fakes(5)) }
        assertFailsWith<IllegalArgumentException> { OlcRtcBond(fakes(13)) }
        assertFailsWith<IllegalArgumentException> { OlcRtcBond(fakes(17)) }
        OlcRtcBond(fakes(4))
        OlcRtcBond(fakes(8))
        OlcRtcBond(fakes(12))
        OlcRtcBond(fakes(16))
    }

    @Test
    fun startLaunchesAllButWaitsOnlyForTheFirstReadyRuntime() {
        val events = mutableListOf<String>()
        val runtimes = fakes(4, events)
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(0), active)
        assertEquals(listOf("start-1", "start-2", "start-3", "start-4", "ready-1-12000"), events)
        assertTrue(bond.isRunning())
    }

    @Test
    fun twelveRuntimeStartLaunchesEverythingAndReturnsAtTheFirstReadyRoom() {
        val events = mutableListOf<String>()
        val runtimes = fakes(12, events)
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(0), active)
        assertEquals((1..12).map { "start-$it" } + "ready-1-12000", events)
    }

    @Test
    fun twelveRuntimeRecoveryRestoresEveryRoom() {
        val runtimes = fakes(12)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)

        val active = bond.recover(12000)

        assertEquals((0..11).toList(), active)
        assertEquals(List(12) { true }, runtimes.map { it.running })
    }

    @Test
    fun twelveRuntimeBondStaysUpUntilTheLastRoomStops() {
        val runtimes = fakes(12)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)

        runtimes.drop(1).forEach { it.running = false }
        assertEquals(listOf(0), bond.activeIndexes())
        assertTrue(bond.isRunning())
        assertFalse(bond.needsFullRecovery())

        runtimes[0].running = false
        assertFalse(bond.isRunning())
        assertTrue(bond.needsFullRecovery())
    }

    @Test
    fun failedRoomBeforeFirstSuccessIsStoppedAndExcluded() {
        val runtimes = fakes(4)
        runtimes[0].failReady = true
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(1), active)
        assertEquals(listOf(false, true, true, true), runtimes.map { it.running })
        assertEquals(1, runtimes[0].stopCalls)
        assertTrue(bond.isRunning())
        assertEquals(listOf(1, 2, 3), bond.recover(12000))
    }

    @Test
    fun twoFailedRoomsLeaveTwoRunning() {
        val runtimes = fakes(4)
        runtimes[0].failReady = true
        runtimes[1].failReady = true
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(2), active)
        assertTrue(bond.isRunning())
        assertEquals(listOf(2, 3), bond.recover(12000))
    }

    @Test
    fun firstReadyRoomStartsTheTunnelWithoutWaitingForTheRest() {
        val events = mutableListOf<String>()
        val runtimes = fakes(4, events)
        runtimes[1].failReady = true
        runtimes[2].failReady = true
        runtimes[3].failReady = true
        val bond = OlcRtcBond(runtimes)

        val active = bond.start(12000)

        assertEquals(listOf(0), active)
        assertEquals(
            listOf("start-1", "start-2", "start-3", "start-4", "ready-1-12000"),
            events,
        )
        assertTrue(bond.isRunning())
        assertFalse(bond.needsFullRecovery())
    }

    @Test
    fun noReadyRoomsStopTheWholePool() {
        val runtimes = fakes(4)
        runtimes.forEach { it.failReady = true }
        val bond = OlcRtcBond(runtimes)

        val error = assertFailsWith<IllegalStateException> { bond.start(12000) }

        assertEquals("ready-4", error.message)
        assertTrue(runtimes.none { it.running })
        assertFalse(bond.isRunning())
    }

    @Test
    fun recoverReturnsAnExcludedRoomToThePool() {
        val runtimes = fakes(4)
        runtimes[2].failReady = true
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        runtimes[2].failReady = false

        val active = bond.recover(12000)

        assertEquals(listOf(0, 1, 2, 3), active)
        assertEquals(listOf(true, true, true, true), runtimes.map { it.running })
    }

    @Test
    fun cancelledRecoveryStopsTheCurrentRoomAndStartsNoLaterRooms() {
        val runtimes = fakes(4)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)
        runtimes.take(3).forEach { it.running = false }
        var cancelled = false
        runtimes[0].onReady = { cancelled = true }

        val active = bond.recover(12000) { cancelled }

        assertEquals(listOf(3), active)
        assertFalse(runtimes[0].running)
        assertEquals(2, runtimes[0].startCalls)
        assertEquals(listOf(1, 1), runtimes.drop(1).take(2).map { it.startCalls })
    }

    @Test
    fun oneStoppedRuntimeIsExcludedWithoutKillingThePool() {
        val runtimes = fakes(4)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)

        runtimes[3].running = false

        assertEquals(listOf(0, 1, 2), bond.activeIndexes())
        assertTrue(bond.isRunning())
        assertFalse(bond.needsFullRecovery())
    }

    @Test
    fun stopAttemptsEveryRuntimeWhenOneFails() {
        val runtimes = fakes(4)
        runtimes.forEach { it.running = true }
        runtimes[1].failStop = true
        val bond = OlcRtcBond(runtimes)

        val error = assertFailsWith<IllegalStateException> { bond.stop(5000) }

        assertEquals("stop-2", error.message)
        assertEquals(listOf(5000, 5000, 5000, 5000), runtimes.map { it.stopTimeouts })
        assertTrue(runtimes.none { it.running })
    }

    @Test
    fun dnsUpdateAttemptsEveryRuntimeWhenOneFails() {
        val runtimes = fakes(4)
        runtimes[1].failDns = true
        val bond = OlcRtcBond(runtimes)

        val error = assertFailsWith<IllegalStateException> { bond.setDNS("1.1.1.1:53") }

        assertEquals("dns-2", error.message)
        assertEquals(List(4) { "1.1.1.1:53" }, runtimes.map { it.dns })
    }

    @Test
    fun occupancyReplayRequestsOnlyActiveRooms() {
        val runtimes = fakes(4)
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)
        runtimes[3].running = false

        bond.requestOccupancy()

        assertEquals(listOf(1, 1, 1, 0), runtimes.map { it.occupancyRequests })
    }

    @Test
    fun onlyTheLastStoppedRuntimeMakesTheWholeBondNotRunning() {
        val runtimes = fakes(4)
        runtimes.forEach { it.running = true }
        val bond = OlcRtcBond(runtimes)
        bond.start(12000)
        bond.recover(12000)

        runtimes[2].running = false
        runtimes[3].running = false
        assertTrue(bond.isRunning())

        runtimes[0].running = false
        runtimes[1].running = false
        assertFalse(bond.isRunning())
    }

    private fun fakes(count: Int, events: MutableList<String> = mutableListOf()) =
        List(count) { FakeRuntime(it + 1, events) }

    private class FakeRuntime(
        private val id: Int,
        private val events: MutableList<String>,
    ) : OlcRtcBondRuntime {
        var running = false
        var failReady = false
        var failStop = false
        var failDns = false
        var stopCalls = 0
        var startCalls = 0
        var stopTimeouts = 0
        var occupancyRequests = 0
        var dns = ""
        var onReady: (() -> Unit)? = null

        override fun start() {
            events += "start-$id"
            startCalls++
            running = true
        }

        override fun waitReady(timeoutMillis: Int) {
            events += "ready-$id-$timeoutMillis"
            onReady?.invoke()
            if (failReady) throw IllegalStateException("ready-$id")
        }

        override fun stop(timeoutMillis: Int) {
            stopCalls++
            stopTimeouts = timeoutMillis
            running = false
            if (failStop) throw IllegalStateException("stop-$id")
        }

        override fun isRunning(): Boolean = running

        override fun requestOccupancy() {
            occupancyRequests++
        }

        override fun setDNS(servers: String) {
            dns = servers
            if (failDns) throw IllegalStateException("dns-$id")
        }
    }
}
