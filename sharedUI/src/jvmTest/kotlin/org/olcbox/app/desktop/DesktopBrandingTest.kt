package org.olcbox.app.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopBrandingTest {
    @Test
    fun windowsPackageHasItsOwnIdentity() {
        val build = File("../desktopApp/build.gradle.kts").readText()
        assertTrue("val desktopPackageName = \"MultiLane\"" in build)
        assertTrue("upgradeUuid = \"b3c2e0cb-9b3e-4f6c-9e2a-6a2c47e3c815\"" in build)
        assertNotEquals(
            "6f0aaf78-dbed-4745-9d95-9e63f10a30de",
            Regex("upgradeUuid = \"([0-9a-f-]+)\"").find(build)?.groupValues?.get(1),
        )
    }

    @Test
    fun desktopForkUsesMultiLaneUpdates() {
        val main = File("../desktopApp/src/main/kotlin/main.kt").readText()
        val updateService = File("src/commonMain/kotlin/org/olcbox/app/update/AppUpdateService.kt").readText()
        assertTrue("https://github.com/huKM565/multilane" in updateService)
        assertTrue("showUpdates = true" in main.substringAfter("ApplicationSettingsSheet("))
    }

    @Test
    fun windowsDataDoesNotShareTheGhostlaneDirectory() {
        val oldOs = System.getProperty("os.name")
        val oldHome = System.getProperty("user.home")
        val home = Files.createTempDirectory("multilane-home")
        try {
            System.setProperty("os.name", "Windows 11")
            System.setProperty("user.home", home.toString())
            val root = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?.let { java.nio.file.Path.of(it) }
                ?: home.resolve("AppData").resolve("Roaming")
            assertEquals(root.resolve("MultiLane"), DesktopPaths.appDataDir())
        } finally {
            System.setProperty("os.name", oldOs)
            System.setProperty("user.home", oldHome)
        }
    }

    @Test
    fun linuxDataDoesNotShareTheGhostlaneDirectory() = withLinuxHome { home ->
        assertEquals(home.resolve(".multilane"), DesktopPaths.appDataDir())
    }

    @Test
    fun linuxMigrationCopiesOnlyApprovedFilesOnce() = withLinuxHome { home ->
        val source = home.resolve(".olcbox")
        approvedFiles.forEach { name ->
            Files.createDirectories(source)
            Files.writeString(source.resolve(name), "data-$name")
        }
        Files.createDirectories(source.resolve("runtime"))
        Files.writeString(source.resolve("runtime/secret.yaml"), "secret")

        val target = DesktopPaths.appDataDir()

        approvedFiles.forEach { name ->
            assertEquals("data-$name", Files.readString(target.resolve(name)))
            assertEquals("data-$name", Files.readString(source.resolve(name)))
        }
        assertFalse(Files.exists(target.resolve("runtime")))
        assertTrue(Files.exists(target.resolve(".multilane-import-complete")))
    }

    @Test
    fun linuxMigrationRetriesMissingFilesWithoutOverwriting() = withLinuxHome { home ->
        val source = home.resolve(".olcbox")
        approvedFiles.forEach { name ->
            Files.createDirectories(source)
            Files.writeString(source.resolve(name), "data-$name")
        }
        val target = DesktopPaths.appDataDir()
        Files.writeString(target.resolve("locations_v4.json"), "custom")
        Files.delete(target.resolve("device_identity"))
        Files.delete(target.resolve(".multilane-import-complete"))

        DesktopPaths.appDataDir()

        assertEquals("custom", Files.readString(target.resolve("locations_v4.json")))
        assertEquals("data-device_identity", Files.readString(target.resolve("device_identity")))
    }

    @Test
    fun linuxMigrationDoesNotRestoreDeletedFilesAfterMarker() = withLinuxHome { home ->
        val source = home.resolve(".olcbox")
        Files.createDirectories(source)
        Files.writeString(source.resolve("device_identity"), "data-device_identity")
        val target = DesktopPaths.appDataDir()
        Files.delete(target.resolve("device_identity"))

        DesktopPaths.appDataDir()

        assertFalse(Files.exists(target.resolve("device_identity")))
    }

    private fun withLinuxHome(block: (Path) -> Unit) {
        val oldOs = System.getProperty("os.name")
        val oldHome = System.getProperty("user.home")
        val oldOverride = System.getProperty("olcbox.appDataDir")
        val home = Files.createTempDirectory("ilyukhaline-linux-home")
        try {
            System.setProperty("os.name", "Linux")
            System.setProperty("user.home", home.toString())
            System.clearProperty("olcbox.appDataDir")
            block(home)
        } finally {
            System.setProperty("os.name", oldOs)
            System.setProperty("user.home", oldHome)
            if (oldOverride == null) {
                System.clearProperty("olcbox.appDataDir")
            } else {
                System.setProperty("olcbox.appDataDir", oldOverride)
            }
            home.toFile().deleteRecursively()
        }
    }

    private companion object {
        val approvedFiles = listOf(
            "locations_v4.json",
            "locations_v3.json",
            "device_identity",
            "desktop_socks_proxy_settings.json",
            "desktop_connection_mode",
        )
    }
}
