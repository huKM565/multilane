package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinuxBondSupervisorTest {
    init {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
    }

    @Test
    fun startsEveryRoomAndRestartsOnlyTheDeadOne() = withRunningSupervisor { supervisor, _, pids ->
        val before = pids.toList()
        ProcessHandle.of(before[1]).orElseThrow().destroy()

        awaitCondition {
            val current = readPids(supervisor.runtimeDirForTest())
            current[1] != before[1] && current.indices.all { index ->
                index == 1 || current[index] == before[index]
            }
        }

        val after = readPids(supervisor.runtimeDirForTest())
        assertNotEquals(before[1], after[1])
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
        assertEquals(before[3], after[3])
    }

    @Test
    fun stopFileEndsSupervisorAndEveryRoom() = withRunningSupervisor { supervisor, process, pids ->
        supervisor.stop()

        assertFalse(process.isAlive)
        pids.forEach { pid ->
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
    }

    @Test
    fun missingStateIsAliveWhileSupervisorIsStarting() {
        val root = Files.createTempDirectory("ilyukhaline-supervisor-starting")
        val process = ProcessBuilder("bash", "-c", "sleep 30").start()
        val supervisor = LinuxBondSupervisor(
            binary = fakeEngine(root),
            configPaths = configs(root),
            runtimeDir = root.resolve("runtime"),
            privilege = { it },
            launcher = { process },
        )
        try {
            supervisor.start()

            assertTrue(supervisor.isRoomAlive(0))
            Files.writeString(supervisor.statePath(0), "down\n")
            assertFalse(supervisor.isRoomAlive(0))
        } finally {
            process.destroyForcibly()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun startUsesOnePrivilegedInvocation() {
        val root = Files.createTempDirectory("ilyukhaline-supervisor-command")
        val commands = mutableListOf<List<String>>()
        try {
            val configs = configs(root)
            val supervisor = LinuxBondSupervisor(
                binary = fakeEngine(root),
                configPaths = configs,
                runtimeDir = root.resolve("runtime"),
                privilege = { it },
                launcher = { command ->
                    commands += command
                    FakeProcess()
                },
            )

            supervisor.start()

            assertEquals(1, commands.size)
            assertEquals(
                listOf("bash", supervisor.scriptPath.toString(), supervisor.runtimeDirForTest().toString()) +
                    listOf(supervisor.binaryForTest().toString()) + configs.map { it.toString() },
                commands.single(),
            )
            supervisor.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun scriptEmbedsNoRoomOrSocksSecrets() {
        val root = Files.createTempDirectory("ilyukhaline-supervisor-secrets")
        try {
            val configs = listOf("room-secret.yaml", "room-2.yaml", "room-3.yaml", "room-4.yaml").map { name ->
                root.resolve(name).also { Files.writeString(it, "") }
            }
            val supervisor = LinuxBondSupervisor(
                binary = fakeEngine(root),
                configPaths = configs,
                runtimeDir = root.resolve("runtime"),
                privilege = { it },
                launcher = { FakeProcess() },
            )

            supervisor.start()
            val script = Files.readString(supervisor.scriptPath)

            assertFalse(script.contains("room-secret"))
            assertFalse(script.contains("socks-secret"))
            supervisor.stop()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun singleProcessPoolStartsPoolBinaryWithoutBashScript() {
        val root = Files.createTempDirectory("ilyukhaline-supervisor-pool")
        val commands = mutableListOf<List<String>>()
        try {
            val configs = configs(root)
            val runtime = root.resolve("runtime")
            val binary = root.resolve("olcrtc")
            val supervisor = LinuxBondSupervisor(
                binary = binary,
                configPaths = configs,
                runtimeDir = runtime,
                privilege = { it },
                launcher = { command ->
                    commands += command
                    FakeProcess()
                },
                singleProcessPool = true,
            )

            supervisor.start()

            assertEquals(1, commands.size)
            assertEquals(
                listOf(binary.toString(), "-pool", runtime.toString()) + configs.map { it.toString() },
                commands.single(),
            )
            assertFalse(Files.exists(supervisor.scriptPath))
            supervisor.stop()
            assertTrue(Files.exists(runtime.resolve("stop")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun withRunningSupervisor(block: (LinuxBondSupervisor, Process, List<Long>) -> Unit) {
        val root = Files.createTempDirectory("ilyukhaline-supervisor-live")
        val supervisor = LinuxBondSupervisor(
            binary = fakeEngine(root),
            configPaths = configs(root),
            runtimeDir = root.resolve("runtime"),
            privilege = { it },
            launcher = { command -> ProcessBuilder(command).redirectErrorStream(true).start() },
        )
        var process: Process? = null
        try {
            process = supervisor.start()
            awaitCondition { readPids(supervisor.runtimeDirForTest()).all(::alive) }
            block(supervisor, process, readPids(supervisor.runtimeDirForTest()))
        } finally {
            runCatching { supervisor.stop(1000) }
            if (process?.isAlive == true) {
                Files.writeString(supervisor.runtimeDirForTest().resolve("stop"), "")
                process.destroyForcibly()
            }
            runCatching {
                readPids(supervisor.runtimeDirForTest()).forEach { pid ->
                    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                }
            }
            root.toFile().deleteRecursively()
        }
    }

    private fun configs(root: Path): List<Path> = (1..4).map { index ->
        root.resolve("room-$index.yaml").also { Files.writeString(it, "") }
    }

    private fun fakeEngine(root: Path): Path = root.resolve("fake-olcrtc").also { path ->
        Files.writeString(
            path,
            "#!/bin/sh\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n",
        )
        path.toFile().setExecutable(true)
    }

    private fun readPids(runtime: Path): List<Long> = (1..4).map { index ->
        Files.readString(runtime.resolve("room-$index.pid")).trim().toLong()
    }

    private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(50)
        }
        throw AssertionError("condition was not met before timeout")
    }

    private fun LinuxBondSupervisor.runtimeDirForTest(): Path = scriptPath.parent
    private fun LinuxBondSupervisor.binaryForTest(): Path = scriptPath.parent.resolveSibling("fake-olcrtc")

    private class FakeProcess : Process() {
        override fun destroy() {}
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun isAlive(): Boolean = false
        override fun exitValue(): Int = 0
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
    }
}
