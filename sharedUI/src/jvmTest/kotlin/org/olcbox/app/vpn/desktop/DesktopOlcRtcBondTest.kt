package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOlcRtcBondTest {
    @Test
    fun bondRequiresFourOrTwelveRoomsAndKeepsRunningUntilTheLastRoomStops() {
        val rooms = rooms(4)
        assertFailsWith<IllegalArgumentException> { DesktopOlcRtcBond(rooms(3)) }
        assertFailsWith<IllegalArgumentException> { DesktopOlcRtcBond(rooms(5)) }
        val bond = DesktopOlcRtcBond(rooms)
        assertTrue(bond.isRunning())
        (rooms[3].process as FakeProcess).alive = false
        assertTrue(bond.isRunning())
        assertEquals(listOf(21081, 21082, 21083), bond.ports())
        assertEquals(listOf(0, 1, 2), bond.activeIndexes())
        (rooms[2].process as FakeProcess).alive = false
        assertTrue(bond.isRunning())
        rooms.take(2).forEach { (it.process as FakeProcess).alive = false }
        assertFalse(bond.isRunning())
    }

    @Test
    fun bondSupportsSixteenRooms() {
        val bond = DesktopOlcRtcBond(rooms(16))
        assertTrue(bond.isRunning())
        assertEquals(16, bond.ports().size)
    }

    @Test
    fun twelveRoomBondKeepsDistinctPortsAndRestartsADeadProcess() {
        val rooms = rooms(12)
        val replacement = FakeProcess(99)
        val stopped = rooms[11].copy(restart = { replacement })
        val bond = DesktopOlcRtcBond(rooms.dropLast(1) + stopped)
        (stopped.process as FakeProcess).alive = false

        assertEquals(11, bond.ports().size)
        assertTrue(bond.isRunning())
        val active = bond.recover()

        assertEquals((0..11).toList(), active)
        assertEquals(replacement, stopped.process)
        assertEquals((21081..21092).toList(), bond.ports())
        assertEquals(12, bond.ports().toSet().size)
    }

    @Test
    fun onlyReadyRoomsAreActive() {
        val bond = DesktopOlcRtcBond(rooms(4), readyIndexes = setOf(1))
        assertEquals(listOf(1), bond.activeIndexes())
        bond.markReady(3)
        assertEquals(listOf(1, 3), bond.activeIndexes())
        bond.stop()
        assertTrue(bond.activeIndexes().isEmpty())
    }

    @Test
    fun watcherRecoversAnIncompleteBondBackToItsExpectedSize() {
        assertTrue(desktopBondNeedsRefresh(listOf(0, 1, 2), listOf(0, 1, 2, 3), expectedSize = 4))
        assertTrue(desktopBondNeedsRefresh(listOf(0, 1, 2), listOf(0, 1, 2), expectedSize = 4))
        assertFalse(desktopBondNeedsRefresh(listOf(0, 1, 2, 3), listOf(0, 1, 2, 3), expectedSize = 4))
        assertTrue(desktopBondNeedsRefresh((0..10).toList(), (0..10).toList(), expectedSize = 12))
        assertFalse(desktopBondNeedsRefresh((0..11).toList(), (0..11).toList(), expectedSize = 12))
    }

    @Test
    fun watcherContinuesOnlyAfterAHealthyRefreshAndRestartsAfterFailure() {
        assertEquals(
            DesktopBondWatchDecision.Continue,
            desktopBondWatchDecisionAfterRefresh(Result.success(true), coresAlive = true),
        )
        assertEquals(
            DesktopBondWatchDecision.Restart,
            desktopBondWatchDecisionAfterRefresh(Result.success(true), coresAlive = false),
        )
        assertEquals(
            DesktopBondWatchDecision.Restart,
            desktopBondWatchDecisionAfterRefresh(Result.success(false), coresAlive = true),
        )
        assertEquals(
            DesktopBondWatchDecision.Restart,
            desktopBondWatchDecisionAfterRefresh(Result.failure(IllegalStateException("rebalance failed")), coresAlive = true),
        )
    }

    @Test
    fun parsesOnlyStructuredRoomOccupancyEvents() {
        assertEquals(6, parseOlcRtcRoomParticipants("2026/11/16 16:02:32 jitsi: room participants=6"))
        assertEquals(2, parseOlcRtcRoomParticipants("jitsi: room participants=2"))
        assertEquals(null, parseOlcRtcRoomParticipants("jitsi: bridge open sctp"))
        assertEquals(0, parseOlcRtcRoomParticipants("jitsi: room participants=0"))
        assertEquals(null, parseOlcRtcRoomParticipants("jitsi: room participants=-1"))
    }

    @Test
    fun parsesOnlyStructuredRoomStateEvents() {
        assertEquals("joined", parseOlcRtcRoomState("2026/11/17 01:00:00 client: room state=joined"))
        assertEquals("relay-ready", parseOlcRtcRoomState("client: room state=relay-ready"))
        assertEquals("relay-failed", parseOlcRtcRoomState("client: room state=relay-failed"))
        assertEquals(null, parseOlcRtcRoomState("client: room state=unknown"))
        assertEquals(null, parseOlcRtcRoomState("session opened"))
    }

    @Test
    fun recoverRestartsAndReturnsTheMissingRoom() {
        val rooms = rooms(4)
        val replacement = FakeProcess(44)
        val stopped = rooms[3].copy(restart = { replacement })
        val bond = DesktopOlcRtcBond(rooms.take(3) + stopped)
        (stopped.process as FakeProcess).alive = false

        val active = bond.recover()

        assertEquals(listOf(0, 1, 2, 3), active)
        assertEquals(replacement, stopped.process)
        assertEquals(listOf(21081, 21082, 21083, 21084), bond.ports())
    }

    @Test
    fun singleEngineIsMissingOnlyWithoutBothProcessAndBond() {
        assertTrue(desktopOlcRtcProcessMissing(null, null))
        assertFalse(desktopOlcRtcProcessMissing(FakeProcess(1), null))
        assertFalse(desktopOlcRtcProcessMissing(null, DesktopOlcRtcBond(rooms(4))))
    }

    @Test
    fun stopAttemptsEveryProcessAndDeletesEveryConfig() {
        val rooms = rooms(4)
        (rooms[1].process as FakeProcess).failOnDestroy = true
        val bond = DesktopOlcRtcBond(rooms)

        val error = assertFailsWith<IllegalStateException> { bond.stop() }
        assertEquals("stop-2", error.message)
        assertTrue(rooms.all { !(it.process as FakeProcess).alive })
        bond.deleteConfigs()
        assertTrue(rooms.all { !Files.exists(it.configPath) })
    }

    @Test
    fun linuxRoomUsesStateAndNeverRestartsFromKotlin() {
        val unavailable = rooms(1).single().copy(isAlive = { false }, restart = null)
        val bond = DesktopOlcRtcBond(listOf(unavailable) + rooms(3).mapIndexed { index, room ->
            room.copy(configPath = unavailable.configPath.resolveSibling("other-$index.yaml"), port = 21082 + index)
        })

        assertEquals(listOf(1, 2, 3), bond.activeIndexes())
        assertEquals(listOf(1, 2, 3), bond.recover())
        assertEquals(listOf(1, 2, 3), bond.activeIndexes())
    }

    @Test
    fun sharedSupervisorStopsExactlyOnce() {
        val rooms = rooms(4)
        var stops = 0
        var cleanups = 0
        val bond = DesktopOlcRtcBond(
            rooms = rooms,
            stopSupervisor = { stops++ },
            runtimeCleanup = { cleanups++ },
        )

        bond.stop()
        bond.deleteConfigs()

        assertEquals(1, stops)
        assertEquals(1, cleanups)
        assertTrue(rooms.all { (it.process as FakeProcess).alive })
        assertTrue(rooms.all { !Files.exists(it.configPath) })
    }

    @Test
    fun restartedRoomDoesNotReturnBeforeMarkedReady() {
        val replacement = FakeProcess(44)
        val stopped = rooms(4)[3].copy(restart = { replacement })
        val bond = DesktopOlcRtcBond(rooms(3) + stopped)
        (stopped.process as FakeProcess).alive = false

        bond.markNotReady(3)
        bond.recover()

        assertEquals(listOf(0, 1, 2), bond.activeIndexes())
        bond.markReady(3)
        assertEquals(listOf(0, 1, 2, 3), bond.activeIndexes())
    }

    private fun rooms(count: Int): List<DesktopOlcRtcBond.Room> = (1..count).map { index ->
        val path = Files.createTempFile("ilyukhaline-room-$index", ".yaml")
        Files.writeString(path, "test")
        DesktopOlcRtcBond.Room(FakeProcess(index), path, 21080 + index)
    }

    private class FakeProcess(private val id: Int) : Process() {
        var alive = true
        var failOnDestroy = false

        override fun destroy() {
            alive = false
            if (failOnDestroy) throw IllegalStateException("stop-$id")
        }

        override fun destroyForcibly(): Process = apply { alive = false }
        override fun isAlive(): Boolean = alive
        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int = 0
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
    }
}
