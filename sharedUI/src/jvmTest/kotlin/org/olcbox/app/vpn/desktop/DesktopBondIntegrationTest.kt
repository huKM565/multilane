package org.olcbox.app.vpn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.isBondedDesktopTun
import org.olcbox.app.vpn.windowsTunCachePath

class DesktopBondIntegrationTest {
    private val manager = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt").readText()

    @Test
    fun bondedPoolIsForMarkedDesktopProfiles() {
        val bonded = LocationConfig(kind = LocationKind.Olcrtc, bondVlessLink = "vless://test")
        val plain = bonded.copy(bondVlessLink = null)
        assertTrue(isBondedDesktopTun(bonded, DesktopMode.WindowsTun))
        assertTrue(isBondedDesktopTun(bonded, DesktopMode.LinuxTun))
        assertTrue(isBondedDesktopTun(bonded, DesktopMode.MacTun))
        assertTrue(isBondedDesktopTun(bonded, DesktopMode.SystemProxy))
        assertFalse(isBondedDesktopTun(plain, DesktopMode.WindowsTun))
        assertFalse(isBondedDesktopTun(plain, DesktopMode.LinuxTun))
        assertFalse(isBondedDesktopTun(plain, DesktopMode.SystemProxy))
    }

    @Test
    fun linuxPoolUsesOneSupervisorAndSharedCorePath() {
        val body = section("private suspend fun startDesktopBond", "private suspend fun refreshDesktopBond")
        assertEquals(1, Regex("LinuxBondSupervisor\\(").findAll(body).count())
        assertTrue("singleProcessPool = true" in body)
        assertFalse("LinuxPrivilege.command" in body)
        assertTrue("supervisor.isRoomAlive(index)" in body)
        assertTrue("supervisor.logPath(index)" in body)
        assertTrue("XrayConfig.buildVlessBond" in body)
        assertTrue("SingBoxConfig.buildSocksChain" in body)
        assertTrue("parseOlcRtcRoomParticipants" in body)
        assertTrue("parseOlcRtcRoomState" in body)
    }

    @Test
    fun stopSupervisorPrecedesTunCleanup() {
        val body = section("private suspend fun stopDesktopMode", "private fun cancelProcessJobs")
        val supervisor = body.indexOf("stopDesktopBond()")
        val tun = body.indexOf("linuxTunController.stop")
        assertTrue(supervisor >= 0)
        assertTrue(tun >= 0)
        assertTrue(supervisor < tun)
        assertEquals(1, Regex("stopDesktopBond\\(\\)").findAll(body).count())
    }

    @Test
    fun zeroReadyRoomsCleansPoolBeforeXray() {
        val body = section("private suspend fun startDesktopBond", "private suspend fun refreshDesktopBond")
        assertTrue(body.indexOf("if (!bond.isRunning()) error") in 0 until body.indexOf("xrayCore.start"))
        assertEquals(1, Regex("supervisor.start\\(\\)").findAll(body).count())
        val catch = body.substringAfter("catch (error: Throwable)")
        assertTrue("supervisor.stop" in catch)
        assertFalse("supervisor.start()" in catch)
    }

    @Test
    fun failedSupervisorStopRemainsAvailableToTunFallback() {
        val body = section("private fun stopDesktopBond", "private suspend fun startDesktopCore")
        assertTrue("val stopped = runCatching { bond.stop() }.isSuccess" in body)
        assertTrue("if (stopped) bond.deleteConfigs()" in body)
        assertTrue("linuxBondSupervisor = if (stopped) null else supervisor" in body)
    }

    @Test
    fun disconnectedStopStillCleansRetainedSupervisor() {
        val body = section("private suspend fun stopDesktopMode", "private fun cancelProcessJobs")
        assertTrue("linuxBondSupervisor == null" in body.substringBefore("cancelProcessJobs()"))
    }

    @Test
    fun restartedRoomReturnsOnlyAfterSocksReady() {
        val body = section("private suspend fun recoverDesktopBondRooms", "private suspend fun rebalanceDesktopBond")
        assertTrue(body.indexOf("bond.markNotReady(index)") in 0 until body.indexOf("bond.recover()"))
    }

    @Test
    fun managerStartsAndStopsTheWholePool() {
        assertTrue("startDesktopBond(" in manager)
        assertTrue("DesktopOlcRtcBond(" in manager)
        assertTrue("udpEnabled = false" in manager)
        assertTrue("XrayConfig.buildVlessBond" in manager)
        assertTrue("SingBoxConfig.buildSocksChain" in manager)
        assertTrue("stopDesktopBond()" in manager)
        assertTrue("isBondedDesktopTun(" in manager)
    }

    @Test
    fun managerUsesPerPathSettingsAndKeepsRefreshSafetyInvariants() {
        assertTrue("planBondPaths(config, ::allocateBondPort)" in manager)
        assertTrue("setDesktopBondRooms(paths.indices.toList())" in manager)
        assertTrue("path.provider" in manager)
        assertTrue("path.transport" in manager)
        assertTrue("path.room" in manager)
        assertTrue("path.key" in manager)
        assertFalse("rooms.size == 4" in manager)
        assertTrue("expectedSize = bond?.rooms?.size ?: 0" in manager)
        assertTrue("val frontPort = line.frontPort ?: line.corePort" in manager)
        val refreshBody = section("private suspend fun refreshDesktopBond", "private suspend fun recoverDesktopBondRooms")
        val recoverPos = refreshBody.indexOf("recoverDesktopBondRooms(bond)")
        val emptyGuardPos = refreshBody.indexOf("if (activeRooms.isEmpty()) return false")
        val rebalancePos = refreshBody.indexOf("rebalanceDesktopBond(")
        assertTrue(
            recoverPos in 0 until emptyGuardPos && emptyGuardPos < rebalancePos,
            "post-recovery empty active set must avoid an empty Xray config"
        )
    }

    @Test
    fun initialBondUsesTheSessionRouting() {
        assertTrue("coreRouting = coreRouting" in manager)
        assertTrue("routing = coreRouting" in manager)
    }

    @Test
    fun initialBondMeasuresActiveRoomsAndPassesOneAdaptivePlanToXray() {
        val body = section("private suspend fun startDesktopBond", "private suspend fun refreshDesktopBond")
        val planner = body.indexOf("val activePlan = planFullPoolAdaptiveBondCore(")
        val xray = body.indexOf("xrayCore.start")

        assertTrue(planner >= 0, "desktop bond does not measure its initial active rooms")
        assertTrue(xray > planner, "desktop bond starts Xray before measuring its rooms")
        assertTrue("roomPorts = activePlan.roomPorts" in body)
        assertTrue("adaptive = activePlan.scheduling" in body)
        assertTrue("desktopBalancedPlan = null" in section("private fun stopDesktopBond", "private suspend fun startDesktopCore"))
    }

    @Test
    fun refreshKeepsRunningFullPoolCoresWithoutRestartingOnRoomChanges() {
        val body = section("private suspend fun refreshDesktopBond", "private suspend fun recoverDesktopBondRooms")

        assertTrue("val fullPoolRunning = desktopBalancedPlan?.roomPorts == bond.rooms.map { it.port }" in body)
        assertTrue("activeRooms.all { it in desktopMeasuredRooms }" in body)
        assertTrue("if (fullPoolRunning) return true" in body)
        assertEquals(1, Regex("rebalanceDesktopBond\\(").findAll(body).count())
        assertTrue(body.indexOf("if (fullPoolRunning) return true") < body.indexOf("rebalanceDesktopBond("))
    }

    @Test
    fun rebalanceUsesAnActiveRoomSnapshotAndRestoresTheLastWorkingPlan() {
        val body = section("private suspend fun rebalanceDesktopBond", "private fun allocateBondPort")

        assertTrue("val previousPlan = desktopBalancedPlan" in body)
        assertEquals(1, Regex("planFullPoolAdaptiveBondCore\\(").findAll(body).count())
        assertTrue("suspend fun startCores(plan: BondCorePlan" in body)
        assertTrue("startCores(activePlan, \"rebalanced\")" in body)
        assertTrue("startCores(previousPlan, \"restored\")" in body)
        assertFalse("roomPorts = bond.ports()" in body)
    }

    @Test
    fun everyTunAttemptHasItsOwnCacheFile() {
        val oldOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val first = windowsTunCachePath("session", 1, 0)
            val retry = windowsTunCachePath("session", 1, 1)
            assertNotEquals(first, retry)
            assertTrue(first.fileName.toString().startsWith("windows-tun-cache-"))
            assertTrue(first.parent.toString().contains("MultiLane"))
        } finally {
            System.setProperty("os.name", oldOs)
        }
    }

    @Test
    fun packetMuxModeRunsBondMuxBehindSingBoxWithUnifiedWatchdog() {
        val startBody = section("private suspend fun startDesktopBond", "private suspend fun refreshDesktopBond")
        val refreshBody = section("private suspend fun refreshDesktopBond", "private suspend fun recoverDesktopBondRooms")
        val stopCoresBody = section("private fun stopDesktopCores", "private fun CoreShape.alive")

        assertTrue("bondMuxCore.start(" in startBody, "startDesktopBond does not start bondMuxCore")
        assertTrue("bondPacketMux" in startBody, "startDesktopBond does not branch on bondPacketMux")
        assertTrue("isBondCarrierRunning()" in manager, "DesktopVpnManager missing isBondCarrierRunning()")
        assertTrue("isBondCarrierRunning()" in refreshBody, "refreshDesktopBond does not use isBondCarrierRunning()")
        assertTrue("if (bondPacketMuxActive && singBoxCore.isRunning() && isBondCarrierRunning()) return true" in refreshBody)
        assertTrue("if (bondPacketMuxActive) return restartDesktopBondMux(line, bond)" in refreshBody)
        assertTrue("bondMuxCore.stopNow()" in stopCoresBody, "stopDesktopCores does not stop bondMuxCore")
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = manager.indexOf(startMarker)
        val end = manager.indexOf(endMarker, start)
        assertTrue(start >= 0 && end > start, "missing section $startMarker")
        return manager.substring(start, end)
    }
}
