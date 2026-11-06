package net.ankio.ledger

import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.ScreenCaptureService
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupStepsTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun localDefaultOpensSharedPermissionFlowWithoutModelConfiguration() {
        val previous=app.channels.enabled(ChannelSettings.Channel.OCR)
        app.recognitionSession.invalidate();app.channels.set(ChannelSettings.Channel.OCR,false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                scenario.onActivity { it.showBackgroundSetup(true) }
                web.await("document.querySelector('#detailDialog').open")
                assertFalse(web.text("document.querySelector('.flow-progress').textContent").contains("配置模型"))
                assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
                scenario.onActivity { assertNull(it.modelPage) }
                web.run("document.querySelector('[data-setup-action=close]').click()")
                web.await("!document.querySelector('#detailDialog').open")
            }
        } finally { app.channels.set(ChannelSettings.Channel.OCR,previous);app.recognitionSession.invalidate();app.changed() }
    }

    @Test fun batteryCannotBeConfirmedWithoutGrantButCanBeSkippedAndSurvivesRecreation() {
        val prefs=app.getSharedPreferences("background_setup",android.content.Context.MODE_PRIVATE)
        val previous=prefs.all.toMap()
        val enabled=app.channels.enabled(ChannelSettings.Channel.OCR)
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val oldServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val oldAccessibility=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        app.channels.set(ChannelSettings.Channel.OCR,false)
        val wasExempt=app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        automation.executeShellCommand("cmd deviceidle whitelist -${app.packageName}").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }

        prefs.edit().clear().commit()
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            val component=net.ankio.ledger.capture.captureServiceComponent(app).flattenToString()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",listOfNotNull(oldServices?.takeIf { it.isNotBlank() },component).joinToString(":"))
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            while(!app.channels.permitted(ChannelSettings.Channel.OCR) && android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50)
            assertTrue(app.channels.permitted(ChannelSettings.Channel.OCR))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                app.getSharedPreferences("background_setup",0).edit().putBoolean("overlay_skipped",true).commit()
                val web=WebUiProbe(scenario);web.ready()
                scenario.onActivity { it.showBackgroundSetup(false) }
                web.await("document.querySelector('#setup-primary')?.textContent==='去允许后台运行'")
                assertEquals("重新检查权限",web.text("document.querySelector('#setup-check').textContent"))
                assertFalse(prefs.getBoolean("battery",false))
                scenario.recreate();web.ready()
                web.await("document.querySelector('#detailDialog').open && document.querySelector('#setup-primary').textContent==='去允许后台运行'")
                assertFalse("Recreation must not imply confirmation",prefs.getBoolean("battery",false))
                web.run("document.querySelector('#setup-check').click()")
                web.await("document.querySelector('#setupMessage').textContent.includes('仍未允许')")
                assertFalse("Checking cannot manufacture a system battery grant",prefs.getBoolean("battery",false))
                web.run("document.querySelector('[data-setup-action=skip]').click()")
                web.await("document.querySelector('#setup-primary').textContent==='打开应用信息'")
                assertTrue(prefs.getBoolean("battery_skipped",false))
                assertFalse(prefs.getBoolean("autostart",false))
                assertFalse(prefs.getBoolean("completed",false))
                web.run("document.querySelector('#setup-check').click()")
                web.await("document.querySelector('#setup-primary').textContent==='打开最近任务'")
                assertTrue(prefs.getBoolean("autostart",false))
                assertFalse(prefs.getBoolean("task_lock",false))
                assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
                web.run("document.querySelector('[data-setup-action=skip]').click()")
                web.await("document.querySelector('#setup-title').textContent==='设置已就绪'")
                assertTrue(prefs.getBoolean("task_lock_skipped",false))
                assertFalse(prefs.getBoolean("task_lock",false))
                web.run("document.querySelector('#setup-check').click()")
                web.await("document.querySelector('#setup-title').textContent==='运行设置已检查'")
                assertFalse("Inspection does not turn shake on",app.channels.enabled(ChannelSettings.Channel.OCR))
                assertFalse("Skipped setting is not a confirmed system grant",prefs.getBoolean("completed",false))
                web.run("document.querySelector('#setup-check').click()")
                // A completed inspection is shared by the real enable path, including skipped OEM items.
                web.run("go('settings');document.querySelector('[data-channel=ocr]').click()")
                web.await("document.querySelector('#setup-title').textContent==='设置已就绪'")
                web.run("document.querySelector('#setup-check').click()")
                web.await("document.querySelector('#setup-title').textContent==='摇一摇识别已开启'")
                assertTrue("Explicit enable must not get stuck rechecking skipped items",app.channels.enabled(ChannelSettings.Channel.OCR))
                web.run("document.querySelector('#setup-check').click()")
            }
        } finally {
            automation.executeShellCommand("cmd deviceidle whitelist ${if(wasExempt)"+" else "-"}${app.packageName}").close()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",oldServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",oldAccessibility)
            automation.dropShellPermissionIdentity()
            prefs.edit().clear().commit();val edit=prefs.edit();previous.forEach { (k,v)->if(v is Boolean)edit.putBoolean(k,v) };edit.commit()
            app.recognitionSession.invalidate();app.channels.set(ChannelSettings.Channel.OCR,enabled);app.changed()
        }
    }
}
