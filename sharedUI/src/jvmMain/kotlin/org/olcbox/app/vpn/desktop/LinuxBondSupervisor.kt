package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class LinuxBondSupervisor(
    private val binary: Path,
    private val configPaths: List<Path>,
    private val runtimeDir: Path,
    private val privilege: (List<String>) -> List<String> = LinuxPrivilege::command,
    private val launcher: (List<String>) -> Process = defaultProcessLauncher,
    private val singleProcessPool: Boolean = false,
) {
    val scriptPath: Path = runtimeDir.resolve("supervisor.sh")
    private var process: Process? = null

    init {
        require(configPaths.size == 4 || configPaths.size == 8 || configPaths.size == 12 || configPaths.size == 16) {
            "a Linux bond requires four, eight, twelve or sixteen configs"
        }
        require(configPaths.distinct().size == configPaths.size) { "bond configs must be distinct" }
    }

    fun start(): Process {
        check(process == null) { "supervisor is already started" }
        Files.createDirectories(runtimeDir)
        val command = if (singleProcessPool) {
            listOf(
                binary.toString(),
                "-pool",
                runtimeDir.toString(),
            ) + configPaths.map { it.toString() }
        } else {
            Files.writeString(scriptPath, SCRIPT)
            listOf(
                "bash",
                scriptPath.toString(),
                runtimeDir.toString(),
                binary.toString(),
            ) + configPaths.map { it.toString() }
        }
        return launcher(privilege(command)).also { process = it }
    }

    fun stop(timeoutMillis: Long = 5000) {
        val running = process ?: return
        Files.createDirectories(runtimeDir)
        Files.writeString(runtimeDir.resolve("stop"), "")
        if (!running.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Linux bond supervisor did not stop in time")
        }
    }

    fun logPath(index: Int): Path = runtimeDir.resolve("room-${index + 1}.log")
    fun statePath(index: Int): Path = runtimeDir.resolve("room-${index + 1}.state")

    fun isRoomAlive(index: Int): Boolean {
        if (process?.isAlive != true) return false
        return runCatching {
            Files.readString(statePath(index)).trim() != "down"
        }.getOrDefault(true)
    }

    fun deleteRuntime() {
        if (process?.isAlive == true) error("cannot delete a running supervisor runtime")
        if (!Files.exists(runtimeDir)) return
        Files.walk(runtimeDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        val defaultProcessLauncher: (List<String>) -> Process = { command ->
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    environment()["NO_PROXY"] = "127.0.0.1,localhost"
                    environment()["no_proxy"] = "127.0.0.1,localhost"
                }
                .start()
        }

        val SCRIPT = """
            #!/usr/bin/env bash
            set -u
            runtime="${'$'}1"
            binary="${'$'}2"
            shift 2
            configs=("${'$'}@")
            pids=()
            stopping=false

            stop_children() {
              if [ "${'$'}stopping" = true ]; then return; fi
              stopping=true
              trap - TERM INT
              for pid in "${'$'}{pids[@]}"; do kill "${'$'}pid" 2>/dev/null || true; done
              deadline=$((SECONDS + 3))
              all_done=false
              while [ "${'$'}SECONDS" -lt "${'$'}deadline" ]; do
                all_done=true
                for pid in "${'$'}{pids[@]}"; do
                  if kill -0 "${'$'}pid" 2>/dev/null; then all_done=false; fi
                done
                if [ "${'$'}all_done" = true ]; then break; fi
                sleep 0.1
              done
              if [ "${'$'}all_done" != true ]; then
                for pid in "${'$'}{pids[@]}"; do kill -KILL "${'$'}pid" 2>/dev/null || true; done
              fi
              for pid in "${'$'}{pids[@]}"; do wait "${'$'}pid" 2>/dev/null || true; done
            }

            start_room() {
              index="${'$'}1"
              printf 'up\n' > "${'$'}runtime/room-$((index + 1)).state"
              "${'$'}binary" "${'$'}{configs[${'$'}index]}" >> "${'$'}runtime/room-$((index + 1)).log" 2>&1 &
              pids[${'$'}index]=${'$'}!
              printf '%s\n' "${'$'}{pids[${'$'}index]}" > "${'$'}runtime/room-$((index + 1)).pid"
            }

            trap stop_children TERM INT
            for index in "${'$'}{!configs[@]}"; do start_room "${'$'}index"; done
            while true; do
              if [ "${'$'}stopping" = true ] || [ -f "${'$'}runtime/stop" ]; then break; fi
              for index in "${'$'}{!configs[@]}"; do
                if ! kill -0 "${'$'}{pids[${'$'}index]}" 2>/dev/null; then
                  wait "${'$'}{pids[${'$'}index]}" 2>/dev/null || true
                  printf 'down\n' > "${'$'}runtime/room-$((index + 1)).state"
                  sleep 2
                  if [ -f "${'$'}runtime/stop" ]; then break; fi
                  start_room "${'$'}index"
                fi
              done
              if [ -f "${'$'}runtime/stop" ]; then break; fi
              sleep 1
            done
            stop_children
        """.trimIndent()
    }
}
