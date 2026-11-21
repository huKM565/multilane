package org.olcbox.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class BondSchedulerTest {
    @Test
    fun expectedUsesAtMostFourRooms() {
        for ((count, expected) in mapOf(1 to 1, 4 to 4, 8 to 4, 12 to 4)) {
            val samples = List(count) { sample(it, 100.0) }
            assertEquals(expected, bondScheduling(samples).expected)
            assertEquals(List(count) { 1.0 }, bondScheduling(samples).costs)
        }
        assertEquals(BondScheduling(0, emptyList()), bondScheduling(emptyList()))
    }

    @Test
    fun fasterRoomsGetProportionallyLowerCosts() {
        val scheduling = bondScheduling(listOf(
            sample(0, 400.0),
            sample(1, 100.0),
            sample(2, 200.0),
            sample(3, 200.0),
        ))

        assertEquals(listOf(0.5, 2.0, 1.0, 1.0), scheduling.costs)
    }

    @Test
    fun extremeMeasurementsAreClamped() {
        val scheduling = bondScheduling(listOf(
            sample(0, 10000.0),
            sample(1, 100.0),
            sample(2, 1.0),
        ))

        assertEquals(listOf(0.5, 1.0, 4.0), scheduling.costs)
    }

    @Test
    fun allFailedMeasurementsFallBackToNeutralCosts() {
        assertEquals(
            BondScheduling(4, List(4) { 1.0 }),
            bondScheduling(List(4) { null }),
        )
    }

    @Test
    fun oneFailedRoomIsDeprioritizedWithoutAffectingOthers() {
        val scheduling = bondScheduling(listOf(
            sample(0, 100.0),
            null,
            sample(2, 400.0),
            sample(3, 200.0),
        ))

        assertEquals(listOf(2.0, 4.0, 0.5, 1.0), scheduling.costs)
    }

    @Test
    fun invalidThroughputIsTreatedAsMissingMeasurement() {
        val invalid = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)
        val samples = invalid.mapIndexed { index, speed -> sample(index, speed) } + sample(5, 100.0)

        assertEquals(listOf(4.0, 4.0, 4.0, 4.0, 4.0, 1.0), bondScheduling(samples).costs)
        assertEquals(BondScheduling(5, List(5) { 1.0 }), bondScheduling(invalid.mapIndexed(::sample)))
    }

    @Test
    fun medianHandlesOddAndEvenCounts() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertFailsWith<IllegalArgumentException> { median(emptyList()) }
    }

    @Test
    fun measurementsRunWithBoundedConcurrencyAndKeepTargetOrder() = runTest {
        val targets = List(8) { BondRoomTarget(it, 21081 + it) }
        val entered = List(targets.size) { CompletableDeferred<Unit>() }
        val release = List(targets.size) { CompletableDeferred<Unit>() }
        var active = 0
        var maxActive = 0
        val probe = object : BondRoomProbe {
            override suspend fun measure(target: BondRoomTarget): BondRoomSample? {
                active++
                maxActive = maxOf(maxActive, active)
                entered[target.index].complete(Unit)
                return try {
                    release[target.index].await()
                    if (target.index == 6) null else sample(target.index, 100.0 + target.index)
                } finally {
                    active--
                }
            }
        }

        val running = async { measureBondRooms(targets, probe) }
        entered.take(4).forEach { it.await() }
        yield()
        assertEquals(4, maxActive)
        assertFalse(entered[4].isCompleted)

        release.take(4).forEach { it.complete(Unit) }
        entered.drop(4).forEach { it.await() }
        release.drop(4).forEach { it.complete(Unit) }
        val result = running.await()

        assertEquals(4, maxActive)
        assertEquals(targets.indices.map { if (it == 6) null else it }, result.map { it?.index })
        assertEquals(null, result[6])
    }

    @Test
    fun adaptivePlanKeepsCostsAlignedWithCompactedActivePorts() = runTest {
        val probe = object : BondRoomProbe {
            override suspend fun measure(target: BondRoomTarget): BondRoomSample? = when (target.index) {
                0 -> sample(0, 100.0)
                2 -> sample(2, 200.0)
                else -> fail("inactive room ${target.index} was measured")
            }
        }

        val plan = planAdaptiveBondCore(listOf(0, 2), listOf(21081, 21082, 21083), probe)

        assertEquals(listOf(0, 2), plan.roomIndexes)
        assertEquals(listOf(21081, 21083), plan.roomPorts)
        assertEquals(listOf(0, 2), plan.samples.map { it?.index })
        assertEquals(BondScheduling(2, listOf(1.5, 0.75)), plan.scheduling)
    }

    @Test
    fun measurementLogsContainOnlyPublicIndexesAndNumericMetrics() {
        val plan = BondCorePlan(
            roomIndexes = listOf(0, 2),
            roomPorts = listOf(21081, 21083),
            scheduling = BondScheduling(2, listOf(1.0, 4.0)),
            samples = listOf(sample(0, 1_000_000.0), null),
        )

        assertEquals(
            listOf(
                "olcRTC bond room 1: RTT 50 ms, 8.0 Mbit/s",
                "olcRTC bond room 3: measurement failed",
            ),
            bondPlanLogLines(plan),
        )
    }

    @Test
    fun invalidActiveIndexesAndDuplicatePortsAreRejected() = runTest {
        val probe = object : BondRoomProbe {
            override suspend fun measure(target: BondRoomTarget): BondRoomSample? =
                fail("invalid input must not reach the probe")
        }
        val ports = listOf(21081, 21082, 21083)

        assertFailsWith<IllegalArgumentException> { planAdaptiveBondCore(emptyList(), ports, probe) }
        assertFailsWith<IllegalArgumentException> { planAdaptiveBondCore(listOf(0, 0), ports, probe) }
        assertFailsWith<IllegalArgumentException> { planAdaptiveBondCore(listOf(0, 3), ports, probe) }
        assertFailsWith<IllegalArgumentException> { planAdaptiveBondCore(listOf(2, 0), ports, probe) }
        assertFailsWith<IllegalArgumentException> {
            planAdaptiveBondCore(listOf(0, 1), listOf(21081, 21081), probe)
        }
        assertFailsWith<IllegalArgumentException> {
            measureBondRooms(listOf(BondRoomTarget(0, 21081), BondRoomTarget(1, 21081)), probe)
        }
    }

    private fun sample(index: Int, bytesPerSecond: Double) = BondRoomSample(
        index = index,
        latencyMillis = 50,
        bytesPerSecond = bytesPerSecond,
    )
}
