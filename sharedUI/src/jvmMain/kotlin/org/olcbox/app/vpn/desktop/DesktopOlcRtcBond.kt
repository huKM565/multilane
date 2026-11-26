package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class DesktopBondWatchDecision {
    Continue,
    Refresh,
    Restart,
}

fun desktopBondNeedsRefresh(active: List<Int>, balanced: List<Int>, expectedSize: Int): Boolean =
    active != balanced || active.size < expectedSize

private val olcRtcRoomParticipants = Regex("""\bjitsi: room participants=(\d+)\s*$""")

fun parseOlcRtcRoomParticipants(line: String): Int? =
    olcRtcRoomParticipants.find(line)?.groupValues?.get(1)?.toIntOrNull()

private val olcRtcRoomState = Regex("""\bclient: room state=(joined|join-failed|relay-ready|relay-failed)\s*$""")

fun parseOlcRtcRoomState(line: String): String? =
    olcRtcRoomState.find(line)?.groupValues?.get(1)

fun desktopOlcRtcProcessMissing(process: Process?, bond: DesktopOlcRtcBond?): Boolean =
    process == null && bond == null

fun desktopBondWatchDecision(
    coresAlive: Boolean,
    activeRooms: List<Int>?,
    balancedRooms: List<Int>,
    expectedSize: Int,
): DesktopBondWatchDecision {
    if (activeRooms == null) return if (coresAlive) DesktopBondWatchDecision.Continue else DesktopBondWatchDecision.Restart
    if (desktopBondNeedsRefresh(activeRooms, balancedRooms, expectedSize)) return DesktopBondWatchDecision.Refresh
    return if (coresAlive) DesktopBondWatchDecision.Continue else DesktopBondWatchDecision.Restart
}

fun desktopBondWatchDecisionAfterRefresh(
    refresh: Result<Boolean>,
    coresAlive: Boolean,
): DesktopBondWatchDecision = when {
    refresh.isSuccess && refresh.getOrThrow() && coresAlive -> DesktopBondWatchDecision.Continue
    else -> DesktopBondWatchDecision.Restart
}

class DesktopOlcRtcBond(
    val rooms: List<Room>,
    readyIndexes: Set<Int> = rooms.indices.toSet(),
    private val stopSupervisor: (() -> Unit)? = null,
    private val runtimeCleanup: () -> Unit = {},
) {
    private val readyIndexes = readyIndexes.toMutableSet()

    init {
        require(rooms.size == 4 || rooms.size == 8 || rooms.size == 12 || rooms.size == 16) { "a desktop olcRTC bond requires four, eight, twelve or sixteen rooms" }
        require(rooms.map { it.port }.distinct().size == rooms.size) { "bonded rooms need distinct ports" }
    }

    data class Room(
        @Volatile var process: Process,
        val configPath: Path,
        val port: Int,
        val isAlive: (() -> Boolean)? = null,
        val restart: (() -> Process)? = null,
    ) {
        fun alive(): Boolean = isAlive?.invoke() ?: process.isAlive
    }

    fun markReady(index: Int) {
        readyIndexes += index
    }

    fun markNotReady(index: Int) {
        readyIndexes -= index
    }

    fun activeIndexes(): List<Int> = readyIndexes.filter { rooms[it].alive() }.sorted()

    fun recover(): List<Int> {
        rooms.forEach { room ->
            if (room.alive()) return@forEach
            runCatching { room.restart?.invoke() }
                .onSuccess { process -> if (process != null) room.process = process }
        }
        return activeIndexes()
    }

    fun isRunning(): Boolean = activeIndexes().isNotEmpty()

    fun stop() {
        var firstError: Throwable? = null
        val stopShared = stopSupervisor
        if (stopShared != null) {
            runCatching(stopShared).onFailure { firstError = it }
        } else {
            rooms.forEach { room ->
                try {
                    val process = room.process
                    if (process.isAlive) {
                        process.destroy()
                        if (!process.waitFor(3, TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                        }
                    }
                } catch (error: Throwable) {
                    if (firstError == null) firstError = error
                }
            }
        }
        readyIndexes.clear()
        firstError?.let { throw it }
    }

    fun deleteConfigs() {
        rooms.forEach { room ->
            runCatching { Files.deleteIfExists(room.configPath) }
        }
        runtimeCleanup()
    }

    fun ports(): List<Int> = activeIndexes().map { rooms[it].port }
}
