package net.ankio.ledger

import android.app.UiAutomation
import android.content.ComponentName
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureRestoreTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    @Test fun accessibilityRebindRestoresAutomaticListenerWithoutOpeningLedger() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val oldServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val oldEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val oldShake=app.channels.enabled(ChannelSettings.Channel.OCR)
        val oldAuto=app.channels.automaticEnabled();val oldApps=app.channels.selectedAutoPackages()
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        fun waitFor(message:String,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+6000
            while(!condition() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100)
            assertTrue(message,condition())
        }
        try {

            app.channels.set(ChannelSettings.Channel.OCR,false)
            Settings.Secure.putString(resolver,"enabled_accessibility_services","")
            Settings.Secure.putInt(resolver,"accessibility_enabled",0)
            CaptureRuntime.stopSafely()
            waitFor("Previous service must disconnect") { ScreenCaptureService.current==null && !CaptureRuntime.running }
            app.channels.setAutoPackage("com.tencent.mm",true);app.channels.setAutomaticEnabled(true)
            // No MainActivity is opened: the system reconnects accessibility in the background.
            Settings.Secure.putString(resolver,"enabled_accessibility_services",net.ankio.ledger.capture.captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor("Accessibility should bind") { ScreenCaptureService.current!=null }
            waitFor("Enabled automatic capture must restore on accessibility binding, without opening the ledger") { CaptureRuntime.running }
            assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                app.getSharedPreferences("background_setup",0).edit().putBoolean("overlay_skipped",true).commit()
                val web=WebUiProbe(scenario);web.ready()
                scenario.onActivity { activity ->
                    activity.getSharedPreferences("background_setup",0).edit().putBoolean("battery_skipped",true)
                        .putBoolean("task_lock",true).putBoolean("autostart",true).commit()
                    activity.permissionSetup.startAutomatic()
                }
                web.await("document.querySelector('#detailDialog').open && document.querySelector('#setup-title').textContent==='设置已就绪' && document.querySelector('#setup-check').textContent==='开启支付自动识别'")
                web.run("LedgerNative.setupAction('close')")
            }
        } finally {
            app.channels.setAutomaticEnabled(false);CaptureRuntime.stopSafely();app.recognitionSession.invalidate()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",oldServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",oldEnabled)
            app.channels.set(ChannelSettings.Channel.OCR,oldShake)
            net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in oldApps) }
            app.channels.setAutomaticEnabled(oldAuto);automation.dropShellPermissionIdentity()
        }
    }
    @Test fun enabledListenerRestoresWhenSystemPermissionListArrivesAfterResume() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val oldServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val oldEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val oldSwitch=app.channels.captureEnabled()
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {

            app.channels.set(ChannelSettings.Channel.OCR,true)
            Settings.Secure.putString(resolver,"enabled_accessibility_services","")
            Settings.Secure.putInt(resolver,"accessibility_enabled",0)
            CaptureRuntime.stopSafely();SystemClock.sleep(400)
            ActivityScenario.launch(MainActivity::class.java).use {
                SystemClock.sleep(500);assertFalse("Confirmed missing permission disables the user switch",app.channels.enabled(ChannelSettings.Channel.OCR))
                Settings.Secure.putString(resolver,"enabled_accessibility_services",net.ankio.ledger.capture.captureServiceComponent(app).flattenToString())
                Settings.Secure.putInt(resolver,"accessibility_enabled",1)
                val until=SystemClock.elapsedRealtime()+5000
                while(ScreenCaptureService.current==null && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100)
                assertFalse("Restoring permission does not silently reactivate a disabled feature",app.channels.enabled(ChannelSettings.Channel.OCR))
            }
        } finally {
            CaptureRuntime.stopSafely();app.recognitionSession.invalidate()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",oldServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",oldEnabled)
            app.channels.set(ChannelSettings.Channel.OCR,oldSwitch)
            automation.dropShellPermissionIdentity()
        }
    }
}
