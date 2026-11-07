package net.ankio.ledger

import android.app.UiAutomation
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
class CaptureReadinessTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun disconnectedServiceScopeRefreshDoesNotRecreateOrDereferenceItsConfiguration() {
        val disconnected=object:ScreenCaptureService() { init { attachBaseContext(app) } }
        disconnected.refreshScope()
        assertNull(disconnected.serviceInfo)
    }
    private fun waitFor(condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+6500
        while(!condition()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        assertTrue(condition())
    }
    private fun withoutPermission(block:()->Unit) {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val services=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        app.channels.pauseForMissingPermission();CaptureRuntime.stopSafely()
        Settings.Secure.putString(resolver,"enabled_accessibility_services","")
        Settings.Secure.putInt(resolver,"accessibility_enabled",0)
        waitFor { !app.channels.permitted(ChannelSettings.Channel.OCR) && ScreenCaptureService.current==null && !CaptureRuntime.running }

        try { block() } finally {
            app.channels.set(ChannelSettings.Channel.OCR,false)
            app.channels.pauseForMissingPermission();CaptureRuntime.stopSafely();app.recognitionSession.invalidate()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",services)
            Settings.Secure.putInt(resolver,"accessibility_enabled",enabled)
            automation.dropShellPermissionIdentity();app.changed()
        }
    }
    @Test fun missingPermissionClosesSwitchesWithoutExtraPageBannerAndRetainsRepairGuide()=withoutPermission {
        app.channels.set(ChannelSettings.Channel.OCR,true)
        app.channels.enablePaymentApplication("com.tencent.mm")
        app.channels.enablePaymentApplication("com.eg.android.AlipayGphone")
        val before=app.tasks.snapshot().length()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            waitFor { !app.channels.captureEnabled() && !CaptureRuntime.running }
            assertTrue(app.channels.selectedAutoPackages().isEmpty())
            assertEquals("PERMISSION",app.captureHealth.snapshot().getString("lastBlockedReason"))
            assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty())
            assertEquals(before,app.tasks.snapshot().length())
            web.run("go('settings');refresh()")
            web.await("!document.querySelector('[data-capture-repair]') && [...document.querySelectorAll('[data-payment-app],[data-channel=ocr]')].every(e=>e.getAttribute('aria-checked')==='false')")
            scenario.onActivity { it.repairCapture() }
            web.await("document.querySelector('#detailDialog').open && document.querySelector('#setup-primary').textContent==='去开启页面识别'")
            web.run("LedgerNative.setupAction('close')")
            assertFalse(app.channels.permitted(ChannelSettings.Channel.OCR))
        }
    }
    @Test fun permissionLossInBackgroundClosesAllAffectedSwitchesAndDoesNotReenableThem()=withoutPermission {
        val resolver=app.contentResolver
        Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
        Settings.Secure.putInt(resolver,"accessibility_enabled",1)
        waitFor { ScreenCaptureService.current!=null && app.channels.permitted(ChannelSettings.Channel.OCR) }
        app.channels.set(ChannelSettings.Channel.OCR,true);app.channels.enablePaymentApplication("com.tencent.mm")
        CaptureRuntime.refreshHealth()
        waitFor { CaptureRuntime.running && app.captureHealth.snapshot().getBoolean("ready") }
        val before=app.tasks.snapshot().length()
        Settings.Secure.putString(resolver,"enabled_accessibility_services","")
        Settings.Secure.putInt(resolver,"accessibility_enabled",0)
        waitFor { !app.channels.captureEnabled() && !CaptureRuntime.running }
        assertTrue(app.channels.selectedAutoPackages().isEmpty());assertEquals(before,app.tasks.snapshot().length())
        Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
        Settings.Secure.putInt(resolver,"accessibility_enabled",1)
        waitFor { ScreenCaptureService.current!=null && !CaptureRuntime.running }
        assertFalse(app.channels.captureEnabled())
    }
    @Test fun individualApplicationEnableCannotRestoreStaleOtherApplicationSelection() {
        val selected=app.channels.selectedAutoPackages();val enabled=app.channels.automaticEnabled()
        try {
            app.channels.setAutoPackage("com.tencent.mm",true);app.channels.setAutoPackage("com.eg.android.AlipayGphone",true)
            app.channels.setAutomaticEnabled(false)
            app.channels.enablePaymentApplication("com.tencent.mm")
            assertTrue(app.channels.autoAllowed("com.tencent.mm"));assertFalse(app.channels.autoAllowed("com.eg.android.AlipayGphone"))
            app.channels.enablePaymentApplication("com.eg.android.AlipayGphone")
            assertTrue(app.channels.autoAllowed("com.tencent.mm"));assertTrue(app.channels.autoAllowed("com.eg.android.AlipayGphone"))
            app.channels.setAutoPackage("com.tencent.mm",false)
            assertFalse(app.channels.autoAllowed("com.tencent.mm"));assertTrue(app.channels.autoAllowed("com.eg.android.AlipayGphone"))
        } finally {
            net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in selected) }
            app.channels.setAutomaticEnabled(enabled)
        }
    }
    @Test fun missingPermissionCannotKeepASensorRuntimeOrNotificationsAlive()=withoutPermission {
        app.channels.set(ChannelSettings.Channel.OCR,true)
        app.captureHealth.suspendForMissingPermission()
        CaptureRuntime.refreshHealth()
        assertFalse(app.channels.captureEnabled())
        assertFalse(CaptureRuntime.running);assertNull(CaptureRuntime.instance)
        assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
