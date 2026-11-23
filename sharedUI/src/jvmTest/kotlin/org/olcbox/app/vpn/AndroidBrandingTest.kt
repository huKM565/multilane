package org.olcbox.app.vpn

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidBrandingTest {
    @Test
    fun androidForkHasItsOwnNameAndPackage() {
        val build = File("../androidApp/build.gradle.kts").readText()
        val manifest = File("../androidApp/src/main/AndroidManifest.xml").readText()
        val strings = File("../androidApp/src/main/res/values/strings.xml").readText()
        val common = File("../sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/kit/Pk.kt").readText()

        assertTrue("const val name = \"MultiLane\"" in common)
        assertTrue("applicationId = \"ru.hukm.ilyukhaline\"" in build)
        assertTrue("android:label=\"MultiLane\"" in manifest)
        assertTrue("<string name=\"qs_tile_label\">MultiLane</string>" in strings)
    }

    @Test
    fun androidForkUsesMultiLaneUpdates() {
        val store = File("../androidApp/src/main/res/values/store.xml").readText()
        val updateService = File("src/commonMain/kotlin/org/olcbox/app/update/AppUpdateService.kt").readText()
        assertTrue("<bool name=\"store_self_update\">true</bool>" in store)
        assertTrue("https://github.com/huKM565/multilane" in updateService)
    }
}
