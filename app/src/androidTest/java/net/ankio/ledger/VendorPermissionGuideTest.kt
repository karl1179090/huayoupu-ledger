package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.*
import net.ankio.ledger.ui.PermissionSetupController
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class VendorPermissionGuideTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val brands=listOf("Xiaomi","OPPO","OnePlus","realme","vivo","HUAWEI","HONOR","samsung","Meizu","google","unknown")
    private fun guide(brand:String,sdk:Int=36)=PermissionGuidance.forDevice(PermissionDevice(brand,brand,"vendor-fixture","16",sdk))
    private fun waitFor(check:()->Boolean) { val end=SystemClock.elapsedRealtime()+10000;while(!check()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);assertTrue(check()) }
    private fun notifications(app:LedgerApp,automation:UiAutomation,enabled:Boolean) {
        val manager=app.getSystemService(android.app.NotificationManager::class.java)
        if(manager.areNotificationsEnabled()==enabled)return
        app.startActivity(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,app.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun toggle(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isCheckable && node.className?.toString()?.contains("Switch")==true)return node
            for(i in 0 until node.childCount)toggle(node.getChild(i))?.let { return it }
            return null
        }
        var control:android.view.accessibility.AccessibilityNodeInfo?=null
        waitFor { control=toggle(automation.rootInActiveWindow);control!=null }
        while(control!=null && !control!!.isClickable)control=control!!.parent
        assertNotNull("Notification master switch must have a clickable container",control)
        assertTrue(control!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitFor { manager.areNotificationsEnabled()==enabled }
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK),true)
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK),true)
    }
    private fun emulator(block:(LedgerApp,UiAutomation)->Unit) {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        val old=Settings.Secure.getString(app.contentResolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(app.contentResolver,"accessibility_enabled",0)
        val hadNotifications=app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        val prefs=app.getSharedPreferences("background_setup",Context.MODE_PRIVATE);val previous=prefs.all.toMap()
        fun op(name:String,value:String){automation.executeShellCommand("appops set ${app.packageName} $name $value").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }}
        try {
            app.channels.set(ChannelSettings.Channel.OCR,false);app.channels.setAutomaticEnabled(false);CaptureRuntime.stopSafely()
            prefs.edit().clear().apply()
            Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services","");Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",0)
            waitFor { ScreenCaptureService.current==null }
            block(app,automation)
        } finally {
            app.channels.set(ChannelSettings.Channel.OCR,false);CaptureRuntime.stopSafely()
            op("SYSTEM_ALERT_WINDOW","default");op("POST_NOTIFICATION","allow")
            notifications(app,automation,hadNotifications)
            prefs.edit().clear().apply();val edit=prefs.edit();previous.forEach { (key,value)->if(value is Boolean)edit.putBoolean(key,value) };edit.apply()
            Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services",old);Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",enabled)
            automation.dropShellPermissionIdentity()
        }
    }
    @Test fun permissionGuideShowsDetectedDeviceAndAnActionableSystemHint() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        app.channels.set(ChannelSettings.Channel.OCR,false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("go('settings');document.querySelector('[data-channel=ocr]').click()")
            web.await("document.querySelector('#detailDialog').open")
            assertTrue("Authorization guide must identify this device",web.value("Boolean(document.querySelector('#setup-device'))")=="true")
            assertTrue(web.text("document.querySelector('#setup-device').textContent").contains(android.os.Build.MODEL))
            assertTrue("System path/search hint must be visible",web.value("Boolean(document.querySelector('[data-permission-hint=accessibility]'))")=="true")
            assertFalse(app.channels.captureEnabled())
            web.run("document.querySelector('[data-setup-action=close]').click()")
        }
    }
    @Test fun vendorMatrixUsesNativeProfilesForDeniedPermissionAndRestrictedSettings()=emulator { app,_ ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            brands.forEach { brand ->
                val profile=guide(brand);lateinit var controller:PermissionSetupController
                scenario.onActivity { controller=PermissionSetupController(it,profile);controller.start(ChannelSettings.Channel.OCR) }
                web.await("document.querySelector('#setup-device')?.dataset.deviceFamily==='${profile.family}'")
                assertTrue(web.text("document.querySelector('[data-permission-hint=accessibility]').textContent").contains(profile.accessibilitySearch))
                scenario.onActivity { controller.action("check") }
                web.await("document.querySelector('#setupMessage').textContent==='${profile.permissionMessage}'")
                assertEquals("nowrap",web.text("getComputedStyle(document.querySelector('#setupMessage')).whiteSpace"))
                scenario.onActivity { controller.action("restricted") }
                web.await("Boolean(document.querySelector('[data-permission-hint=restricted]'))")
                assertTrue(web.text("document.querySelector('[data-permission-hint=restricted]').textContent").contains("允许受限设置"))
                scenario.onActivity { controller.cancel() }
                assertFalse(app.channels.captureEnabled())
            }
            scenario.onActivity { PermissionSetupController(it,guide("Xiaomi",30)).apply { start(ChannelSettings.Channel.OCR);action("restricted") } }
            web.await("document.querySelector('[data-permission-hint=restricted]')?.textContent.includes('按系统提示检查权限限制')")
            assertFalse(web.text("document.querySelector('#detailContent').textContent").contains("允许受限设置"))
        }
    }
    @Test fun actualGrantDenialAndOptionalBackgroundStatesRemainDistinctForEveryVendor()=emulator { app,automation ->
        fun op(name:String,value:String){automation.executeShellCommand("appops set ${app.packageName} $name $value").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }}
        Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
        Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",1)
        waitFor { ScreenCaptureService.current!=null }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            brands.forEach { brand ->
                app.getSharedPreferences("background_setup",Context.MODE_PRIVATE).edit().clear().apply()
                op("SYSTEM_ALERT_WINDOW","deny")
                val profile=guide(brand);lateinit var controller:PermissionSetupController
                scenario.onActivity { controller=PermissionSetupController(it,profile);controller.openShake(false) }
                web.await("Boolean(document.querySelector('[data-permission-hint=battery]'))")
                listOf("battery","autostart","task_lock").forEach { key ->
                    scenario.onActivity { controller.action("view:$key") }
                    web.await("Boolean(document.querySelector('[data-permission-hint=$key]'))")
                    val expected=when(key){"battery"->profile.batteryHint;"autostart"->profile.autostartHint;else->profile.taskLockInstruction}
                    assertTrue(web.text("document.querySelector('[data-permission-hint=$key]').textContent").contains(expected))
                }
                scenario.onActivity { controller.cancel() }
                assertFalse("Inspecting guides must not enable recognition",app.channels.captureEnabled())
            }
        }
    }
}
