package org.olcbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBrandingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedAppUsesMultiLaneLabelNotificationAndLauncherIcon() {
        val packageManager = context.packageManager
        val applicationInfo = context.applicationInfo
        assertEquals("MultiLane", applicationInfo.loadLabel(packageManager).toString())

        val titleId = context.resources.getIdentifier("notification_title_proxy", "string", context.packageName)
        assertNotEquals(0, titleId)
        val title = context.resources.getString(titleId)
        assertTrue(title.startsWith("MultiLane"))
        assertFalse(title.contains("Ghostlane"))

        assertNotEquals(0, applicationInfo.icon)
        val icon = requireNotNull(packageManager.getDrawable(context.packageName, applicationInfo.icon, applicationInfo))
        assertTrue(icon.intrinsicWidth > 0)
        assertTrue(icon.intrinsicHeight > 0)
    }
}
