package net.ankio.ledger

import android.app.UiAutomation
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundSettingsSyncTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private fun exercise(block:(LedgerApp,UiAutomation,ActivityScenario<MainActivity>,WebUiProbe,(Boolean)->Unit)->Unit) {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val services=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val shake=app.channels.enabled(ChannelSettings.Channel.OCR);val automatic=app.channels.automaticEnabled();val packages=app.channels.selectedAutoPackages()
        val prefs=app.getSharedPreferences("background_setup",Context.MODE_PRIVATE);val previous=prefs.all
        val exempt=app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        fun battery(value:Boolean) {
            automation.executeShellCommand("cmd deviceidle whitelist ${if(value)"+" else "-"}${app.packageName}").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            waitFor { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)==value }
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString());Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor { ScreenCaptureService.current!=null && app.channels.permitted(ChannelSettings.Channel.OCR) }
            battery(true)
            prefs.edit().clear().putBoolean("battery",true).putBoolean("autostart",true).putBoolean("task_lock",true).putBoolean("completed",true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                scenario.onActivity { it.permissionSetup.openShake(false) }
                web.await("document.querySelector('#setup-title')?.textContent==='设置已就绪'")
                block(app,automation,scenario,web,::battery)
            }
        } finally {
            battery(exempt)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",services);Settings.Secure.putInt(resolver,"accessibility_enabled",enabled)
            prefs.edit().clear().commit();val edit=prefs.edit();previous.forEach { (k,v)->if(v is Boolean)edit.putBoolean(k,v) };edit.commit()
            app.channels.set(ChannelSettings.Channel.OCR,shake)
            net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in packages) };app.channels.setAutomaticEnabled(automatic)
            automation.dropShellPermissionIdentity();app.changed()
        }
    }
    private fun screenshot(app:LedgerApp,automation:UiAutomation,name:String) {
        SystemClock.sleep(650)
        val bitmap=automation.takeScreenshot()!!
        try { app.openFileOutput(name,0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
    }
    private fun waitFor(check:()->Boolean) { val until=SystemClock.elapsedRealtime()+8000;while(!check() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);assertTrue(check()) }
    @Test fun revokedBatteryGrantCannotRemainConfirmedAndCanOpenSystemSettings()=exercise { app,automation,scenario,web,battery ->
        battery(false)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        web.ready()
        val text=web.text("document.querySelector('#detailContent').textContent")
        assertFalse("Revoked battery permission must not keep an all-confirmed summary: $text",text.contains("3 项你已确认"))
        web.await("document.querySelector('[data-setup-step=battery]')?.textContent.includes('待允许')")
        assertFalse(app.getSharedPreferences("background_setup",0).getBoolean("battery",true))
        screenshot(app,automation,"background-battery-revoked.png")
        web.run("setTimeout(()=>document.querySelector('#setup-primary').click(),0)")
        waitFor { automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings" }
        screenshot(app,automation,"background-system-settings.png");automation.performGlobalAction(1)
        battery(true)
        scenario.onActivity { it.permissionSetup.resume() }
        web.await("document.querySelector('[data-setup-step=battery]')?.textContent.includes('已允许')")
        assertTrue(app.getSharedPreferences("background_setup",0).getBoolean("battery",false))
    }
    @Test fun allPreviouslyConfirmedItemsCanBeReopenedAndManualClaimsReset()=exercise { app,automation,scenario,web,_ ->
        assertTrue("The completed summary needs a way to reopen background settings",web.value("Boolean(document.querySelector('[data-setup-action=\"view:battery\"]'))")=="true")
        web.run("document.querySelector('[data-setup-action=\"view:battery\"]').click()")
        web.await("Boolean(document.querySelector('[data-setup-step=battery]'))")
        web.run("document.querySelector('[data-setup-action=\"view:autostart\"]').click()")
        web.await("document.querySelector('[data-setup-step=autostart]').textContent.includes('上次已确认')")
        web.run("document.querySelector('[data-setup-action=\"reset:autostart\"]').click()")
        waitFor { !app.getSharedPreferences("background_setup",0).getBoolean("autostart",true) }
        web.await("document.querySelector('[data-setup-step=autostart]').textContent.includes('待检查')")
        screenshot(app,automation,"background-autostart-reset.png")
        web.run("setTimeout(()=>document.querySelector('#setup-primary').click(),0)")
        waitFor { automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings" }
        SystemClock.sleep(600);automation.performGlobalAction(1)
        scenario.onActivity { it.permissionSetup.resume() }
        assertFalse("Opening settings must not grant a manual confirmation",app.getSharedPreferences("background_setup",0).getBoolean("autostart",true))
        web.run("document.querySelector('#setup-check').click()")
        waitFor { app.getSharedPreferences("background_setup",0).getBoolean("autostart",false) }
        web.await("Boolean(document.querySelector('.background-summary'))")
        web.run("document.querySelector('.background-summary').click()")
        web.await("Boolean(document.querySelector('[data-setup-step=task_lock]'))")
        web.run("document.querySelector('[data-setup-action=\"view:battery\"]').click()")
        web.run("document.querySelector('[data-setup-action=\"view:task_lock\"]').click()")
        web.await("Boolean(document.querySelector('[data-setup-action=\"reset:task_lock\"]'))")
        web.run("setTimeout(()=>document.querySelector('#setup-primary').click(),0)")
        // Global Recents is asynchronous. Wait until it really leaves the app, then
        // return the existing task without queuing Back against the permission sheet.
        waitFor { automation.rootInActiveWindow?.packageName?.toString()?.let { it!=app.packageName }==true }
        automation.waitForIdle(250,5_000)
        screenshot(app,automation,"background-recents.png")
        app.getSystemService(android.app.ActivityManager::class.java).appTasks.single().moveToFront()
        web.ready()
        web.await("window.LedgerSetup.isOpen() && Boolean(document.querySelector('[data-setup-action=\"reset:task_lock\"]'))")
        web.run("document.querySelector('[data-setup-action=\"reset:task_lock\"]').click()")
        waitFor { !app.getSharedPreferences("background_setup",0).getBoolean("task_lock",true) }
    }
}
