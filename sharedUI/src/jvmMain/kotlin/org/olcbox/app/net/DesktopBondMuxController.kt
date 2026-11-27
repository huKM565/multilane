package org.olcbox.app.net

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import java.nio.file.Path

class DesktopBondMuxController(
    binaryPath: () -> Path = { DesktopNativeAssets.resolveOlcRtcBinary() },
    private val onOutput: (String) -> Unit = {},
) {
    private val proc = DesktopCoreProcess(
        binaryPath = binaryPath,
        label = "bondmux",
        onOutput = { line -> onOutput(line) },
        argv = { bin, config -> listOf(bin, "-bondmux-client", config.toString()) },
    )

    suspend fun start(configJson: String) = proc.start(configJson)
    fun stopNow() = proc.stop()
    fun isRunning(): Boolean = proc.isRunning()
    internal fun runningProcess(): Process? = proc.runningProcess()
    fun exitCodeOrNull(): Int? = proc.exitCodeOrNull()

    companion object {
        fun buildConfigJson(
            listenAddr: String,
            socksUser: String,
            socksPass: String,
            targetAddr: String,
            uuid: String,
            roomPorts: List<Int>,
            roomUser: String,
            roomPass: String,
            verboseLogs: Boolean = false,
            readyTimeoutMs: Int = 15_000,
        ): String = buildJsonObject {
            put("listen_addr", listenAddr)
            put("socks_user", socksUser)
            put("socks_pass", socksPass)
            put("target_addr", targetAddr)
            put("uuid", uuid)
            putJsonArray("room_ports") {
                roomPorts.forEach { add(it) }
            }
            put("room_user", roomUser)
            put("room_pass", roomPass)
            put("ready_timeout_ms", readyTimeoutMs)
            put("debug", verboseLogs)
        }.toString()
    }
}
