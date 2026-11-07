package net.ankio.ledger

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.RecordState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** The window manager can publish a usable window after the activity's initial event. */
@RunWith(AndroidJUnit4::class)
class PaymentWindowReadyTest {
    @get:org.junit.Rule val wallet=CaptureWalletFixture()
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    @Test fun explicitShakeCapturesReadOnlyActivityWithoutAPaymentAdapter()=windowReady(false,activityOverride="com.tencent.mm.BillActivity",manualShake=true)
    @Test fun explicitShakeStillBlocksEditableActivityWithoutAPaymentAdapter()=windowReady(true,activityOverride="com.tencent.mm.BillActivity",manualShake=true)
    @Test fun packageLessWindowReadyEventCapturesInBackgroundWithoutOpeningLedger()=windowReady(false)
    @Test fun packageLessWindowReadyEventStillRejectsPasswordPage()=windowReady(true)
    @Test fun explicitShakeOnRejectedPageExplainsRefusalWithoutScreenshotOrBill()=windowReady(true,manualShake=true)
    @Test fun windowReadyEventNeverReadsAnUnselectedApplicationTree()=windowReady(false,false)
    @Test fun stoppedRuntimeRecoversOnPaymentEventWithoutOpeningLedger()=windowReady(false,restartRuntime=true)
    @Test fun stoppedRuntimeRecoversButNeverScreenshotsPasswordPage()=windowReady(true,restartRuntime=true)
    @Test fun alipayStoppedRuntimeRecoversOnPaymentEventWithoutOpeningLedger()=windowReady(false,restartRuntime=true,pkg="com.eg.android.AlipayGphone")
    @Test fun alipayStoppedRuntimeRecoversButNeverScreenshotsPasswordPage()=windowReady(true,restartRuntime=true,pkg="com.eg.android.AlipayGphone")
    @Test fun userPauseIsNeverUndoneByAnotherPaymentEvent()=windowReady(false,restartRuntime=true,pausedByUser=true)
    @Test fun unselectedApplicationCannotRestartAutomaticOnlyRuntime()=windowReady(false,selected=false,restartRuntime=true)
    @Test fun nonTransparentLiteResultRestoresRuntimeWithoutOpeningLedger()=windowReady(false,restartRuntime=true,activityOverride="com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI")
    @Test fun nonTransparentLitePasswordRestoresRuntimeButNeverScreenshots()=windowReady(true,restartRuntime=true,activityOverride="com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI")

    private fun windowReady(password:Boolean,selected:Boolean=true,restartRuntime:Boolean=false,pkg:String="com.tencent.mm",pausedByUser:Boolean=false,activityOverride:String?=null,manualShake:Boolean=false) {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val activity=activityOverride?:if(pkg=="com.tencent.mm")"com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI" else "com.alipay.fixture.PaymentResultActivity"
        assumeTrue("External synthetic payment fixture is not installed: $pkg/$activity",runCatching { app.packageManager.getActivityInfo(ComponentName(pkg,activity),0) }.isSuccess)
        if(manualShake)app.channels.setShakePackage(pkg,true)
        val resolver=app.contentResolver
        val services=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val enabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val shake=app.channels.enabled(ChannelSettings.Channel.OCR)
        val automatic=app.channels.automaticEnabled()
        val apps=app.channels.selectedAutoPackages()
        val before=app.store.all().map { it.id }.toSet()
        val receiptIsolation=CaptureReceiptIsolation(app)
        val calls=AtomicInteger()
        fun waitFor(condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+8000
            while(!condition() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(80)
            assertTrue("Window-ready event should resume capture: ${ScreenCaptureService.current?.paymentDiagnostics()}",condition())
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            app.channels.setAutomaticEnabled(false)
            Settings.Secure.putString(resolver,"enabled_accessibility_services","")
            Settings.Secure.putInt(resolver,"accessibility_enabled",0)
            CaptureRuntime.stopSafely()
            waitFor { ScreenCaptureService.current==null && !CaptureRuntime.running }

            app.channels.set(ChannelSettings.Channel.OCR,true)
            app.channels.setAutoPackage(pkg,selected)
            if(!selected)app.channels.setAutoPackage("com.eg.android.AlipayGphone",true)
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor { ScreenCaptureService.current!=null && CaptureRuntime.running }
            val service=ScreenCaptureService.current!!
            service.processorFactory={ CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,guard ->
                assertTrue(guard());calls.incrementAndGet()
                val bill=JSONObject().put("is_bill",true).put("amount",12.34).put("direction","expense")
                    .put("merchant","合成测试商店").put("category","其他").put("reliable",true)
                    .put("occurred_at",JSONObject.NULL).put("transaction_id",JSONObject.NULL)
                    .put("raw_text","支付成功 合成测试商店 ¥12.34 返回商家")
                JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
            })) }
            if(restartRuntime) {
                // Simulate an independently stopped runtime while accessibility
                // remains bound. A prior app visit must not rescue the listener.
                app.channels.set(ChannelSettings.Channel.OCR,false)
                app.channels.setAutomaticEnabled(true)
                if(pausedByUser) {
                    app.channels.setAutomaticEnabled(false);app.channels.set(ChannelSettings.Channel.OCR,false)
                }
                CaptureRuntime.stopSafely()
                waitFor { !CaptureRuntime.running }
                assertSame(service,ScreenCaptureService.current)
            }
            automation.performGlobalAction(2);SystemClock.sleep(300)
            val initialRequests=service.screenshotRequests
            app.startActivity(Intent().setComponent(ComponentName(pkg,activity))
                .putExtra("mode",if(password && activity=="com.tencent.mm.BillActivity")"chat" else if(password)"lite_password" else "lite_readable")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            if(pausedByUser || restartRuntime && !selected) {
                SystemClock.sleep(2200)
                if(pausedByUser)assertFalse(app.channels.captureEnabled())
                assertFalse(CaptureRuntime.running)
                assertEquals(initialRequests,service.screenshotRequests);assertEquals(0,calls.get())
                return
            }
            waitFor { service.paymentDiagnostics().optString("lastActivity")==activity }
            if(restartRuntime)waitFor { CaptureRuntime.running }
            if(restartRuntime) {
                assertFalse("Restoring automatic capture must not turn on shake",app.channels.enabled(ChannelSettings.Channel.OCR))
                assertTrue(service.paymentDiagnostics().getJSONObject("runtimeRestore").getLong("attempts")>=2)
            }
            // Keep activity evidence while automatic acquisition is not ready. Then
            // make it ready and deliver only a package-less window-manager event;
            // no new activity event, MainActivity visit or shake may rescue the test.
            SystemClock.sleep(1800)
            if(!restartRuntime || password)assertEquals(0,calls.get())
            assertTrue("The real window manager must deliver package-less window changes: ${service.paymentDiagnostics()}",
                service.paymentDiagnostics().optLong("windowEvents")>0)
            val requests=service.screenshotRequests
            app.channels.setAutomaticEnabled(true)
            val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            assertNull(event.packageName)
            instrumentation.runOnMainSync { service.onAccessibilityEvent(event) }
            event.recycle()
            if(manualShake && !password) {
                SystemClock.sleep(500)
                assertEquals("No automatic screenshot is allowed for an unadapted activity",requests,service.screenshotRequests)
                service.captureOnShake()
                waitFor { app.store.all().any { it.id !in before } }
                assertEquals(RecordState.POSTED,app.store.all().first { it.id !in before }.state)
                assertEquals(1,calls.get())
            } else if(password || !selected) {
                SystemClock.sleep(2200)
                assertEquals(requests,service.screenshotRequests);assertEquals(0,calls.get())
                assertEquals(before,app.store.all().map { it.id }.toSet())
                if(manualShake) {
                    val taskCount=app.tasks.snapshot().length()
                    service.captureOnShake()
                    waitFor { service.paymentDiagnostics().getJSONObject("feedback").optBoolean("attached") }
                    assertEquals("capsule",service.paymentDiagnostics().getJSONObject("feedback").getString("delivery"))
                    assertEquals(requests,service.screenshotRequests);assertEquals(0,calls.get())
                    assertEquals(taskCount,app.tasks.snapshot().length())
                }
                if(!selected) {
                    val probe=service.paymentDiagnostics().getJSONObject("probe")
                    assertEquals("application_not_selected",probe.optString("stage"))
                    assertFalse("Unselected applications must be rejected before tree traversal",probe.has("nodes"))
                }
            } else {
                waitFor { app.store.all().any { it.id !in before } }
                assertEquals(RecordState.POSTED,app.store.all().first { it.id !in before }.state)
                assertEquals(1,calls.get())
                // Repeated window events on the same visit must not create another record.
                repeat(3) {
                    val next=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                    instrumentation.runOnMainSync { service.onAccessibilityEvent(next) };next.recycle()
                }
                SystemClock.sleep(1200);assertEquals(1,calls.get())
            }
        } finally {
            app.channels.setAutomaticEnabled(false)
            ScreenCaptureService.current?.cancelRecognition();CaptureRuntime.stopSafely()
            app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
            val createdTaskIds=receiptIsolation.createdTaskIds()
            receiptIsolation.clean(createdTaskIds)
            createdTaskIds.forEach(app.tasks::delete)
            app.recognitionSession.invalidate()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",services)
            Settings.Secure.putInt(resolver,"accessibility_enabled",enabled)
            app.channels.set(ChannelSettings.Channel.OCR,shake)
            net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in apps) }
            app.channels.setAutomaticEnabled(automatic);automation.dropShellPermissionIdentity()
        }
    }
}
