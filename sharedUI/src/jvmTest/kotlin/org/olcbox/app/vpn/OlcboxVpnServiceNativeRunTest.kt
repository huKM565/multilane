package org.olcbox.app.vpn

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// NativeTunnelRunTest drives the type, and nothing on the JVM drives the
// Android service that has to use it: a service that went back to a thread of
// its own would start a second hev beside the first with every test of the
// type still passing. So the service is read as text and held to the places
// where it could. jvmTest names the file as an input (sharedUI/build.gradle.kts):
// without that, a change to the service alone finds these tests up to date, or
// in the build cache, and they do not run.
class OlcboxVpnServiceNativeRunTest {
    // Without its comments: several of them name what is looked for here.
    private val service = File("src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt")
        .readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""(?m)(^|[ \t])//.*$"""), "")

    /** The service's own function [name], from its header to the brace that closes it. */
    private fun member(name: String): String {
        val headers = Regex("""\n    (?:\w+ )*fun $name\(""").findAll(service).toList()
        assertEquals(1, headers.size, "functions of the service named $name")
        val start = headers.single().range.first
        val end = service.indexOf("\n    }\n", start)
        assertTrue(end > start, "$name does not end where a function of the service ends")
        return service.substring(start, end)
    }

    /** What the `val` [name] nearest above [before] is set to. It has to be one of the same function. */
    private fun valueOf(name: String, before: Int): String {
        val declared = Regex("""\bval $name = ([^\n]+)\n""").findAll(service).lastOrNull { it.range.last < before }
            ?: fail("tun2socksRun.waited is told \"$name\", which is no val set above it")
        assertFalse(
            "\n    }\n" in service.substring(declared.range.first, before),
            "tun2socksRun.waited is told \"$name\", which is no val of the function that says it"
        )
        return declared.groupValues[1]
    }

    // Asked first, a start that is refused has taken nothing. Asked after, it
    // holds a duplicate of the tun's descriptor that nobody closes.
    @Test
    fun aStartAsksBeforeItDuplicatesTheDescriptor() {
        val start = member("startTun2socks")
        val asked = start.indexOf("if (!tun2socksRun.mayStart()) {")
        val duplicated = start.indexOf("ParcelFileDescriptor.dup(")
        assertTrue(asked >= 0, "startTun2socks does not ask NativeTunnelRun whether a run may start")
        assertTrue(duplicated >= 0, "startTun2socks duplicates no descriptor: what this test holds has moved")
        assertTrue(asked < duplicated, "startTun2socks duplicates the descriptor before it asks whether a run may start")
        assertTrue(
            "return false" in start.substring(asked, duplicated),
            "a start that may not start does not return before the descriptor is duplicated"
        )
    }

    // The stops cleared the thread after their wait whatever the wait had
    // seen, and one of them without a wait. Each way the answer is made has to
    // be a wait's own result, or the thread's own word that it has ended.
    @Test
    fun aRunIsForgottenOnlyOnWhatAWaitSaw() {
        val calls = Regex("""tun2socksRun\.waited\(\w+, (?:ended = )?([^\n]+)\)\n""").findAll(service).toList()
        assertTrue(calls.isNotEmpty(), "the service tells NativeTunnelRun of no wait")
        assertEquals(
            Regex("""tun2socksRun\.waited\(""").findAll(service).count(),
            calls.size,
            "a call of tun2socksRun.waited is written in a way this test cannot read"
        )
        for (call in calls) {
            val said = call.groupValues[1]
            val made = if (Regex("""\w+""").matches(said)) valueOf(said, before = call.range.first) else said
            for (way in made.split(Regex("""\belse\b"""))) {
                assertTrue(
                    "waitForTun2socksStopped(" in way || ".isAlive" in way,
                    "tun2socksRun.waited is told \"${way.trim()}\", which is neither what a wait returned nor whether the thread lives"
                )
            }
        }
    }

    // The thread of a run goes to NativeTunnelRun and is kept nowhere else,
    // and the NativeTunnelRun is the process's. A field of the service for the
    // thread is what every stop cleared; a NativeTunnelRun of the service
    // would be lost with the service while its run goes on.
    @Test
    fun theThreadOfARunIsKeptForTheProcessAndNowhereElse() {
        val start = member("startTun2socks")
        val started = Regex("""(\S+) = thread\(name = "OlcboxTun2Socks"""").findAll(service).toList()
        assertEquals(1, started.size, "places where the service starts the thread of a native run")
        val thread = Regex("""val (\w+) = thread\(name = "OlcboxTun2Socks"""").find(start)?.groupValues?.get(1)
            ?: fail("startTun2socks does not start the native run's thread into a val of its own")
        assertTrue(
            start.indexOf("tun2socksRun.started($thread)") > start.indexOf("val $thread = thread("),
            "startTun2socks does not give the thread it started to NativeTunnelRun"
        )
        assertEquals(
            emptyList(),
            Regex("""\n    (?:[@\w]+ )*var (\w+)\s*:\s*Thread\b""").findAll(service).map { it.groupValues[1] }.toList(),
            "the service keeps a thread in a var of its own"
        )

        assertEquals(1, Regex("""\bNativeTunnelRun\(\)""").findAll(service).count(), "NativeTunnelRun objects the service makes")
        val companionStart = service.indexOf("\n    companion object {\n")
        assertTrue(companionStart >= 0, "the service has no companion object")
        val companion = service.substring(companionStart, service.indexOf("\n    }\n", companionStart))
        assertTrue(
            "private val tun2socksRun = NativeTunnelRun()" in companion,
            "tun2socksRun is not the companion's: a run would be forgotten with the service that started it"
        )
    }

    // What a stop still has to take down is asked of the service: a run it
    // started and has not told to stop. The process's run can be one this
    // service told to stop and gave up waiting for, or the next service's.
    // Asked of that, a run that outlived its stop made every later stop of the
    // same service a whole stop again, and the stop of a destroyed service
    // said Disconnected over the session of the one after it.
    @Test
    fun aStopAsksOnlyForARunOfItsOwnService() {
        val cleanup = member("cleanup")
        assertFalse("tun2socksThread" in cleanup, "cleanup asks whether the process has a run")
        assertFalse("tun2socksRun" in cleanup, "cleanup asks NativeTunnelRun, which is the process's")
        assertTrue(
            Regex("""\btun2socksToStop\b""").findAll(cleanup).count() >= 2,
            "cleanup asks in two places whether this service has a run to stop: before it begins, and after the start it cancelled has ended"
        )
        val own = Regex("""\n    private val tun2socksToStop: Boolean\n\s+get\(\) = ([^\n]+)\n""").find(service)?.groupValues?.get(1)
            ?: fail("the service does not say what a run of its own still to stop is")
        assertEquals("tun2socksStarted && !tun2socksStopRequested.get()", own)
    }

    @Test
    fun bondStartUsesEveryConfiguredPathInsteadOfAFixedMambaFour() {
        val body = member("startMobileBond")

        assertTrue("planBondPaths(" in body, "bond start does not use the mixed-path planner")
        assertFalse("rooms.size == 4" in body, "bond start still requires exactly four rooms")
        assertFalse("ports.size < 4" in body, "bond start still allocates exactly four ports")
        assertFalse("listOf(0, 1, 2, 3)" in body, "bond start still exposes four fixed indicators")
        assertTrue("paths[index].provider" in body, "each runtime must get its own provider")
        assertTrue("paths[index].room" in body, "each runtime must get its own room")
        assertTrue("paths[index].key" in body, "each runtime must get its own key")
        assertTrue("paths[index].transport" in body, "each runtime must get its own transport")
    }

    @Test
    fun bondActivationReplaysCachedOccupancyAfterMarkingRoomsActive() {
        for (name in listOf("startMobileBond", "refreshMobileBond")) {
            val body = member(name)
            val activations = Regex("""OlcboxVpnState\.setBondRooms\(activeRooms\)""").findAll(body).toList()
            assertTrue(activations.isNotEmpty(), "$name does not activate bond rooms")
            activations.forEach { activation ->
                val replay = body.indexOf("bond.requestOccupancy()", activation.range.last)
                assertTrue(replay > activation.range.last, "$name does not replay occupancy after activating a room")
            }
        }
    }

    @Test
    fun adaptiveBondStartMeasuresActiveRoomsBeforeStartingTheCore() {
        val start = member("startMobileBond")
        val planner = start.indexOf("val activePlan = planFullPoolAdaptiveBondCore(")
        val core = start.indexOf("bondCorePlan = activePlan")
        val ensured = start.indexOf("coroutineContext.ensureActive()", planner)

        assertTrue(planner >= 0, "bond start does not measure its active rooms")
        assertTrue(core > planner, "the adaptive plan does not reach startCore")
        assertTrue(ensured > planner, "a cancelled start is not checked after its measurements")

        val coreBody = member("startCore")
        assertTrue("roomPorts = bondCorePlan.roomPorts" in coreBody)
        assertTrue("adaptive = bondCorePlan.scheduling" in coreBody)
        assertTrue("bondBalancedPlan = null" in member("stopMobile"))
    }

    @Test
    fun bondRefreshKeepsRunningFullPoolCoresAliveAcrossRoomChanges() {
        val body = member("refreshMobileBond")

        assertTrue("val fullPoolRunning = bondBalancedPlan?.roomPorts == bondRoomPorts" in body)
        assertTrue("if (fullPoolRunning) return@withLock" in body)
        assertEquals(1, Regex("rebalanceMobileBond\\(").findAll(body).count())
        assertTrue(body.indexOf("if (fullPoolRunning) return@withLock") < body.indexOf("rebalanceMobileBond("))
    }

    @Test
    fun bondRebalanceRestoresTheLastWorkingCoreAfterReplacementFailure() {
        val body = member("rebalanceMobileBond")

        assertTrue("val previousPlan = bondBalancedPlan" in body)
        val replacement = body.indexOf("bondCorePlan = activePlan")
        val rollback = body.indexOf("bondCorePlan = previousPlan")
        assertEquals(1, Regex("planFullPoolAdaptiveBondCore\\(").findAll(body).count())
        assertTrue(replacement >= 0, "rebalance does not try the new adaptive plan")
        assertTrue(rollback > replacement, "rebalance does not restore the previous working plan after a failure")
        assertTrue("ensureBridge()" in body.substring(rollback), "a restored core on a new port would leave tun2socks on the old one")
    }

    @Test
    fun cancelledBondRecoveryIsCooperative() {
        val body = member("refreshMobileBond")

        assertTrue("bond.recover(OLCRTC_READY_TIMEOUT_MS.toInt()) { !current() }" in body)
    }

    @Test
    fun cleanupWaitsForRecoveryBeforeStoppingTransportAndStopsItAgainAfterCancelledStart() {
        val cleanup = member("cleanup")
        val watchdogJoin = cleanup.indexOf("cancelledWatchdog?.join()")
        val startJoin = cleanup.indexOf("cancelledStart?.join()")
        val transportStops = Regex("""stopMobileAndWait\(\)""").findAll(cleanup).map { it.range.first }.toList()

        assertTrue(watchdogJoin >= 0, "cleanup does not wait for the bond watchdog it cancelled")
        assertTrue(startJoin >= 0, "cleanup does not wait for the start it cancelled")
        assertEquals(
            2,
            transportStops.size,
            "cleanup stops the mobile transport before joining the cancelled start and again after that start can no longer restart it",
        )
        assertTrue(watchdogJoin < transportStops[0], "cleanup stops the bond before its recovery has finished")
        assertTrue(transportStops[0] < startJoin, "cleanup joins the cancelled start before its final transport cleanup")
        assertTrue(startJoin < transportStops[1], "cleanup has no final mobile transport cleanup after the cancelled start ended")
    }

    @Test
    fun packetMuxModeUsesMobileBondMuxWithUnifiedWatchdogAndNoCoreRestart() {
        val coreBody = member("startCore")
        val refreshBody = member("refreshMobileBond")
        val stopCoresBody = member("stopCoreProcesses")
        val transportBody = member("isActiveTransportRunning")

        assertTrue("routingSettings.bondPacketMux" in coreBody, "startCore does not branch on routingSettings.bondPacketMux")
        assertTrue("startMobileBondMux(" in coreBody, "startCore does not invoke startMobileBondMux")
        assertTrue("isBondCarrierRunning()" in coreBody, "startCore alive lambda does not use isBondCarrierRunning()")
        assertTrue("isBondCarrierRunning()" in transportBody, "isActiveTransportRunning does not use isBondCarrierRunning()")
        assertTrue("if (bondPacketMuxActive && singBoxCore.isRunning() && isBondCarrierRunning()) return@withLock" in refreshBody)
        assertTrue("stopMobileBondMux()" in stopCoresBody, "stopCoreProcesses does not stop bondMux")
        val muxBody = member("startMobileBondMux")
        val stopPos = muxBody.indexOf("stopMobileBondMux()")
        val flagPos = muxBody.indexOf("bondPacketMuxActive = true")
        assertTrue(stopPos >= 0 && flagPos > stopPos, "startMobileBondMux must set bondPacketMuxActive = true AFTER stopMobileBondMux()")
    }
}
