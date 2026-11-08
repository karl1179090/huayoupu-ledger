package net.ankio.ledger

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.*
import net.ankio.ledger.data.NodeSnapshotCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run explicitly against the isolated diagnostic debug variant, on a synthetic emulator only. */
@RunWith(AndroidJUnit4::class)
class NodeDiagnosticAcquisitionTest {
    @Test fun productionCollectorExportsAConsistentPrivatePairWithoutAnyFinancialTask() {
        assumeTrue(BuildConfig.NODE_DIAGNOSTIC && BuildConfig.DEBUG)
        assumeTrue(Build.MODEL.contains("sdk_gphone"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val fixture=instrumentation.context.packageName
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val services=Settings.Secure.getString(app.contentResolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(app.contentResolver,"accessibility_enabled",0)
        val shake=app.channels.enabled(ChannelSettings.Channel.OCR)
        val selected=app.channels.selectedShakePackages()
        var comparison:String?=null
        fun await(check:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+15_000
            while(SystemClock.elapsedRealtime()<deadline) { if(check())return;SystemClock.sleep(30) }
            fail("Synthetic diagnostic acquisition did not reach its required state")
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            runBlocking { app.taskRecovery.await() }
            Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services","")
            Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",0)
            await { ScreenCaptureService.current==null }
            app.channels.selectedShakePackages().forEach { app.channels.setShakePackage(it,false) }
            app.channels.setShakePackage(fixture,true)
            Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",1)
            await { ScreenCaptureService.current!=null && app.channels.permitted(ChannelSettings.Channel.OCR) }
            app.channels.set(ChannelSettings.Channel.OCR,true);CaptureRuntime.refreshHealth()
            await { CaptureRuntime.running }
            val component=ComponentName(fixture,"net.ankio.ledger.ShakeCaptureFixtureActivity")
            automation.executeShellCommand("am start -W -n ${component.flattenToString()} --es mode safe -f 0x10000000").use {
                val output=java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)
                assertFalse(output,output.contains("Error:"))
            }
            val service=ScreenCaptureService.current!!
            var previous:ShakeWindowObservation?=null;var stableAt=SystemClock.elapsedRealtime()
            await {
                instrumentation.runOnMainSync {}
                val current=runBlocking { service.observeShakeWindow() }
                val now=SystemClock.elapsedRealtime()
                if(current!=previous || current.packageName!=fixture)stableAt=now
                previous=current
                current.packageName==fixture && current.windowId!=null && now-stableAt>=500
            }
            val tasks=app.tasks.snapshot().toString();val records=app.store.all();val accounts=app.store.accounts()
            val result=runBlocking { service.collectNodeComparison(fixture) }
            assertTrue(result.toString(),result.getBoolean("ok"));comparison=result.getString("comparisonId")
            val directory=java.io.File(app.filesDir,"node-comparisons/$comparison")
            val snapshot=NodeSnapshotCodec.decode(java.io.File(directory,"snapshot.json").readText())
            val manifest=JSONObject(java.io.File(directory,"manifest.json").readText())
            val screenshot=android.graphics.BitmapFactory.decodeFile(java.io.File(directory,"screenshot.png").absolutePath)!!
            try {
                assertEquals(fixture,snapshot.packageName)
                assertEquals(snapshot.fingerprint,manifest.getString("secondSnapshotFingerprint"))
                assertEquals(screenshot.width,manifest.getInt("width"));assertEquals(screenshot.height,manifest.getInt("height"))
                assertEquals("WINDOW",manifest.getString("screenshotTarget"));assertTrue(manifest.getBoolean("sameTransaction"))
                assertEquals(448,android.system.Os.stat(directory.absolutePath).st_mode and 511)
                assertEquals(384,android.system.Os.stat(java.io.File(directory,"snapshot.json").absolutePath).st_mode and 511)
                assertEquals(tasks,app.tasks.snapshot().toString());assertEquals(records,app.store.all());assertEquals(accounts,app.store.accounts())
            } finally { screenshot.recycle() }
        } finally {
            comparison?.let { java.io.File(app.filesDir,"node-comparisons/$it").deleteRecursively() }
            app.channels.set(ChannelSettings.Channel.OCR,false)
            app.channels.selectedShakePackages().forEach { app.channels.setShakePackage(it,false) }
            selected.forEach { app.channels.setShakePackage(it,true) }
            Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services",services)
            Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",enabled)
            app.channels.set(ChannelSettings.Channel.OCR,shake)
            automation.performGlobalAction(2);automation.dropShellPermissionIdentity()
        }
    }
}
