package org.olcbox.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class BondRoomTarget(val index: Int, val port: Int)

data class BondRoomSample(
    val index: Int,
    val latencyMillis: Long,
    val bytesPerSecond: Double,
)

data class BondScheduling(
    val expected: Int,
    val costs: List<Double>,
    val fallbackIndex: Int = 0,
)

data class BondCorePlan(
    val roomIndexes: List<Int>,
    val roomPorts: List<Int>,
    val scheduling: BondScheduling,
    val samples: List<BondRoomSample?>,
)

fun bondPlanLogLines(plan: BondCorePlan): List<String> = plan.roomIndexes.mapIndexed { position, roomIndex ->
    val sample = plan.samples[position]
    if (sample == null) {
        "olcRTC bond room ${roomIndex + 1}: measurement failed"
    } else {
        val megabitsPerSecond = (sample.bytesPerSecond * 8.0 / 100_000.0).toInt() / 10.0
        "olcRTC bond room ${roomIndex + 1}: RTT ${sample.latencyMillis} ms, $megabitsPerSecond Mbit/s"
    }
}

interface BondRoomProbe {
    suspend fun measure(target: BondRoomTarget): BondRoomSample?
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median requires at least one value" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    if (sorted.size % 2 != 0) return sorted[middle]
    return (sorted[middle - 1] + sorted[middle]) / 2.0
}

fun bondScheduling(samples: List<BondRoomSample?>): BondScheduling {
    val valid = samples.map { sample ->
        sample?.takeIf { it.bytesPerSecond.isFinite() && it.bytesPerSecond > 0.0 }
    }
    val reference = valid.filterNotNull().map { it.bytesPerSecond }.takeIf { it.isNotEmpty() }?.let(::median)
        ?: return BondScheduling(samples.size, List(samples.size) { 1.0 })
    return BondScheduling(
        expected = samples.size,
        costs = valid.map { sample ->
            if (sample == null) return@map 4.0
            (reference / sample.bytesPerSecond).coerceIn(0.5, 4.0)
        },
    )
}

suspend fun measureBondRooms(targets: List<BondRoomTarget>, probe: BondRoomProbe): List<BondRoomSample?> {
    require(targets.map { it.index }.distinct().size == targets.size) { "duplicate room indexes" }
    require(targets.map { it.port }.distinct().size == targets.size) { "duplicate room ports" }
    val semaphore = Semaphore(4)
    return coroutineScope {
        targets.map { target ->
            async { semaphore.withPermit { probe.measure(target) } }
        }.awaitAll()
    }
}

suspend fun planAdaptiveBondCore(
    activeRooms: List<Int>,
    allPorts: List<Int>,
    probe: BondRoomProbe,
): BondCorePlan {
    require(activeRooms.isNotEmpty()) { "an adaptive bond requires an active room" }
    require(activeRooms.distinct().size == activeRooms.size) { "duplicate active room indexes" }
    require(activeRooms == activeRooms.sorted()) { "active room indexes must be sorted" }
    require(activeRooms.all { it in allPorts.indices }) { "active room index is outside the port list" }
    require(allPorts.distinct().size == allPorts.size) { "bond ports must be distinct" }
    val targets = activeRooms.map { BondRoomTarget(it, allPorts[it]) }
    val samples = measureBondRooms(targets, probe)
    return BondCorePlan(activeRooms, targets.map { it.port }, bondScheduling(samples), samples)
}

suspend fun planFullPoolAdaptiveBondCore(
    activeRooms: List<Int>,
    allPorts: List<Int>,
    probe: BondRoomProbe,
): BondCorePlan {
    require(activeRooms.isNotEmpty()) { "an adaptive bond requires an active room" }
    require(activeRooms.distinct().size == activeRooms.size) { "duplicate active room indexes" }
    require(activeRooms == activeRooms.sorted()) { "active room indexes must be sorted" }
    require(activeRooms.all { it in allPorts.indices }) { "active room index is outside the port list" }
    require(allPorts.distinct().size == allPorts.size) { "bond ports must be distinct" }
    val targets = activeRooms.map { BondRoomTarget(it, allPorts[it]) }
    val measured = measureBondRooms(targets, probe)
    val measuredCosts = bondScheduling(measured).costs
    val sampleByIndex = activeRooms.zip(measured).toMap()
    val costByIndex = activeRooms.zip(measuredCosts).toMap()
    val fullIndexes = allPorts.indices.toList()
    val fullSamples = fullIndexes.map { sampleByIndex[it] }
    val fullCosts = fullIndexes.map { costByIndex[it] ?: 1.0 }
    val scheduling = BondScheduling(
        expected = allPorts.size,
        costs = fullCosts,
        fallbackIndex = activeRooms.first(),
    )
    return BondCorePlan(fullIndexes, allPorts, scheduling, fullSamples)
}
