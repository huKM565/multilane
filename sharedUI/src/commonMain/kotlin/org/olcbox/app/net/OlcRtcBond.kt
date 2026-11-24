package org.olcbox.app.net

import kotlin.concurrent.Volatile

interface OlcRtcBondRuntime {
    fun start()
    fun waitReady(timeoutMillis: Int)
    fun stop(timeoutMillis: Int)
    fun isRunning(): Boolean
    fun requestOccupancy()
    fun requestRoomState() {}
    fun setDNS(servers: String)
}

class OlcRtcBond(private val runtimes: List<OlcRtcBondRuntime>) {
    @Volatile
    private var readyIndexes: Set<Int> = emptySet()

    @Volatile
    private var stopped = false

    init {
        require(runtimes.size == 4 || runtimes.size == 8 || runtimes.size == 12) { "an olcRTC bond requires four, eight or twelve runtimes" }
    }

    fun start(timeoutMillis: Int): List<Int> {
        stopped = false
        readyIndexes = emptySet()
        var lastError: Throwable? = null
        runtimes.forEachIndexed { index, runtime ->
            if (stopped) {
                runCatching { stop(timeoutMillis) }
                return emptyList()
            }
            runCatching { runtime.start() }.onFailure { error ->
                lastError = error
                readyIndexes = readyIndexes - index
                runCatching { runtime.stop(timeoutMillis) }
            }
        }
        runtimes.forEachIndexed { index, runtime ->
            if (stopped) {
                runCatching { stop(timeoutMillis) }
                return emptyList()
            }
            if (!runtime.isRunning()) return@forEachIndexed
            try {
                runtime.waitReady(timeoutMillis)
                if (stopped) {
                    runCatching { runtime.stop(timeoutMillis) }
                    readyIndexes = readyIndexes - index
                    return emptyList()
                }
                readyIndexes = readyIndexes + index
                return activeIndexes()
            } catch (error: Throwable) {
                lastError = error
                readyIndexes = readyIndexes - index
                runCatching { runtime.stop(timeoutMillis) }
            }
        }
        if (activeIndexes().isEmpty()) {
            runCatching { stop(timeoutMillis) }
            throw lastError ?: IllegalStateException("no olcRTC rooms are ready")
        }
        return activeIndexes()
    }

    fun recover(timeoutMillis: Int, isCancelled: () -> Boolean = { false }): List<Int> {
        runtimes.forEachIndexed { index, runtime ->
            if (stopped || isCancelled()) return activeIndexes()
            if (index in readyIndexes && runtime.isRunning()) return@forEachIndexed
            readyIndexes = readyIndexes - index
            if (stopped || isCancelled()) return activeIndexes()
            if (!runtime.isRunning()) {
                runCatching { runtime.stop(timeoutMillis) }
                if (stopped || isCancelled()) return activeIndexes()
                runCatching { runtime.start() }
                if (stopped || isCancelled()) {
                    runCatching { runtime.stop(timeoutMillis) }
                    return activeIndexes()
                }
            }
            runCatching {
                runtime.waitReady(timeoutMillis)
            }.onSuccess {
                if (stopped || isCancelled()) {
                    runCatching { runtime.stop(timeoutMillis) }
                    readyIndexes = readyIndexes - index
                } else {
                    readyIndexes = readyIndexes + index
                }
            }.onFailure {
                readyIndexes = readyIndexes - index
                runCatching { runtime.stop(timeoutMillis) }
            }
            if (stopped || isCancelled()) return activeIndexes()
        }
        return activeIndexes()
    }

    fun activeIndexes(): List<Int> = readyIndexes.filter { runtimes[it].isRunning() }.sorted()

    fun requestOccupancy() {
        activeIndexes().forEach { index ->
            runtimes[index].requestOccupancy()
            runtimes[index].requestRoomState()
        }
    }

    fun stop(timeoutMillis: Int) {
        stopped = true
        readyIndexes = emptySet()
        var firstError: Throwable? = null
        runtimes.forEach { runtime ->
            try {
                runtime.stop(timeoutMillis)
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
            }
        }
        readyIndexes = emptySet()
        firstError?.let { throw it }
    }

    fun needsFullRecovery(): Boolean = activeIndexes().isEmpty()

    fun isRunning(): Boolean = !needsFullRecovery()

    fun setDNS(servers: String) {
        var firstError: Throwable? = null
        runtimes.forEach { runtime ->
            try {
                runtime.setDNS(servers)
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
            }
        }
        firstError?.let { throw it }
    }
}
