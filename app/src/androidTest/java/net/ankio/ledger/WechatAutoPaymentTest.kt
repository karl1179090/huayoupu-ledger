package net.ankio.ledger

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WechatAutoPaymentTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    @get:org.junit.Rule val wallet=CaptureWalletFixture()
    @Test fun realAccessibilityEventCapturesCompletedPaymentWithoutShakeOrNotification()=captureCompletedPayment(null)
    @Test fun liteSdkDescriptionOnlySuccessCapturesWithoutShake()=captureCompletedPayment("lite_readable",true)
    @Test fun coldFirstTreeReplyCapturesWithoutReenteringOrOpeningLedger()=captureCompletedPayment("lite_cold_ready",true)
    @Test fun coldPasswordTreeNeverCapturesAfterItsLateReply()=captureCompletedPayment("lite_cold_password",blocked=true)
    @Test fun returningToRecreatedResultAndRebindingNeverPostsAgainButNewPaymentCan()=captureCompletedPayment("lite_readable",returnToPage=true)
    @Test fun alipayReturningToRecreatedResultNeverPostsAgainButNewPaymentCan()=captureCompletedPayment("lite_readable",returnToPage=true,packageName=PaymentApps.ALIPAY)
    @Test fun liteSdkSuccessWithCouponAmountsCapturesWithoutMerchantSpecificRule()=captureCompletedPayment("lite_coupons",true)
    @Test fun liteSdkCouponOnlySuccessNeverCaptures()=captureCompletedPayment("lite_coupon_only",blocked=true,shakeOnBlocked=false)
    @Test fun liteSdkMultipleGenuineAmountsNeverCaptures()=captureCompletedPayment("lite_multiple_amounts",blocked=true,shakeOnBlocked=false)
    @Test fun liteSdkPasswordFieldNeverCaptures()=captureCompletedPayment("lite_password",blocked=true)
    @Test fun unreadableLiteSdkNeverCaptures()=captureCompletedPayment("lite_unreadable",blocked=true)
    @Test fun emptyTreeOnDedicatedResultDoesNotRequestAnyScreenshot()=captureCompletedPayment("unreadable",blocked=true)
    @Test fun acquiredFrameSurvivesLeavingPageWithShakeDisabled()=captureCompletedPayment(null,true)
    @Test fun unreadableSharedContainerDoesNotRequestAnyScreenshot()=captureCompletedPayment("framework",blocked=true)
    @Test fun readableSharedSuccessCapturesAfterPreflight()=captureCompletedPayment("framework_readable",true)
    @Test fun groupedTransferSuccessExposesOmittedChildrenAndCaptures()=captureCompletedPayment("framework_grouped",true)
    @Test fun deepGroupedSuccessWithinNodeBudgetCaptures()=captureCompletedPayment("framework_deep_grouped",true)
    @Test fun deepPasswordFieldStillBlocksCapture()=captureCompletedPayment("framework_deep_password",blocked=true)
    @Test fun excessiveTreeDepthStillFailsClosed()=captureCompletedPayment("framework_too_deep",blocked=true)
    @Test fun passwordFieldOverridesStaleSuccessTextAndAccidentalShake()=captureCompletedPayment("framework_password",blocked=true)
    @Test fun unreadableSecurePaymentContainerNeverAttemptsScreenshot()=captureCompletedPayment("framework_secure",blocked=true)
    @Test fun verificationPageNeverAttemptsScreenshot()=captureCompletedPayment("framework_verify",blocked=true)
    private fun captureCompletedPayment(mode:String?,leaveDuringModel:Boolean=false,blocked:Boolean=false,shakeOnBlocked:Boolean=true,returnToPage:Boolean=false,packageName:String=PaymentApps.WECHAT) {
        // Never replace a genuine WeChat installation or run synthetic account tests on a phone.
        assumeTrue(android.os.Build.FINGERPRINT.startsWith("google/sdk_gphone") || android.os.Build.MODEL.contains("sdk_gphone"))
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val lite=mode?.startsWith("lite_")==true
        val framework=mode?.startsWith("framework")==true || lite
        val component=ComponentName(packageName,if(packageName==PaymentApps.ALIPAY)"com.alipay.fixture.PaymentResultActivity" else if(lite)"com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI" else if(framework)PaymentPageDetector.WECHAT_FRAMEWORK_RESULT else "com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI")
        assumeTrue("External synthetic payment fixture is not installed: $component",runCatching { app.packageManager.getActivityInfo(component,0) }.isSuccess)
        app.channels.setShakePackage(packageName,true)
        assertNull(PaymentPageDetector.detect(PaymentPage(PaymentApps.WECHAT,1,component.className,emptyList())))
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val previousServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val previousEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val oldSwitch=app.channels.enabled(ChannelSettings.Channel.OCR)
        val oldAuto=app.channels.automaticEnabled()
        val oldApps=app.channels.selectedAutoPackages()
        val homeActivity=if(packageName==PaymentApps.ALIPAY)"com.alipay.mobile.quinox.LauncherActivity" else "com.tencent.mm.ui.LauncherUI"
        fun setAuto(enabled:Boolean) { app.channels.setAutoPackage(packageName,enabled);app.channels.setAutomaticEnabled(enabled) }
        val before=app.store.all().map { it.id }.toSet()
        val receiptIsolation=CaptureReceiptIsolation(app)
        val taskIds=receiptIsolation.entryTaskIds
        val calls=AtomicInteger()
        val releaseModel=java.util.concurrent.CountDownLatch(if(leaveDuringModel)1 else 0)
        fun waitFor(timeout:Long=10000,check:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+timeout
            while(!check() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
            assertTrue("Timed out; monitor=${ScreenCaptureService.current?.paymentDiagnostics()}",check())
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            // Settings changes unbind asynchronously. Do not accept a previous case's
            // service instance while its onDestroy is still pending.
            Settings.Secure.putString(resolver,"enabled_accessibility_services","")
            Settings.Secure.putInt(resolver,"accessibility_enabled",0)
            waitFor { ScreenCaptureService.current==null }

            Settings.Secure.putString(resolver,"enabled_accessibility_services",net.ankio.ledger.capture.captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            waitFor { ScreenCaptureService.current!=null && app.channels.permitted(ChannelSettings.Channel.OCR) }
            app.channels.set(ChannelSettings.Channel.OCR,true)
            CaptureRuntime.refreshHealth()
            ScreenCaptureService.current!!.processorFactory={ CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,guard ->
                assertTrue(guard());calls.incrementAndGet()
                assertTrue("Synthetic model did not get released",releaseModel.await(15,java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(guard())
                val bill=JSONObject().put("is_bill",true).put("amount",12.34).put("direction","expense")
                    .put("merchant","合成测试商店").put("category","其他").put("occurred_at","2026-10-17T02:32:51+08:00")
                    .put("transaction_id","10001073012026101701183202026521").put("reliable",true)
                    .put("raw_text","微信支付成功 合成测试商店 支付金额12.34元 转账时间2026年10月17日 02:32:51 转账单号10001073012026101701183202026521")
                if(framework)bill.put("occurred_at",JSONObject.NULL).put("transaction_id",JSONObject.NULL)
                    .put("raw_text",(if(packageName==PaymentApps.ALIPAY)"支付宝 " else "微信 ")+"支付成功 待合成测试商店确认收款 ¥12.34")
                JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
            })) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                // An app upgrade can kill the service while keeping the user's switch enabled.
                // Opening the app must restore that already-authorized listener.
                waitFor { CaptureRuntime.running }
                val initialRequests=ScreenCaptureService.current!!.screenshotRequests
                val initialStatus=app.channels.lastStatus()
                // Upgrade keeps this additional automatic upload consent off.
                setAuto(false)
                if(!framework) {
                    app.startActivity(Intent().setComponent(component).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    SystemClock.sleep(3000);assertEquals(0,calls.get())
                    scenario.onActivity { it.startActivity(Intent(it,MainActivity::class.java)) }
                }
                setAuto(true)
                if(leaveDuringModel)app.channels.set(ChannelSettings.Channel.OCR,false)
                // A singleTop fixture already at the front produces no new window-state
                // event. Return through Home so each case exercises an actual app transition.
                assertTrue(automation.performGlobalAction(2))
                SystemClock.sleep(400)
                app.startActivity(Intent().setComponent(component).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                if(blocked) {
                    waitFor { ScreenCaptureService.current!!.paymentDiagnostics().optString("lastActivity")==component.className }
                    SystemClock.sleep(1800)
                    // Payment gestures can resemble a shake; it must use the same privacy gate.
                    // Financial ambiguity blocks automatic acquisition. Explicit shake
                    // remains a separate supplementation path; only privacy cases
                    // must reject that intentional request as well.
                    if(shakeOnBlocked)ScreenCaptureService.current!!.captureOnShake()
                    SystemClock.sleep(2200)
                    if(mode=="framework_password") {
                        // A success match queued before a password overlay appeared must be
                        // re-read, not trusted at the actual acquisition boundary.
                        val windows=automation.windows
                        val window=try { windows.first { it.isActive && it.type==1 }.id } finally { windows.forEach { it.recycle() } }
                        val stale=PaymentPageDetector.detect(PaymentPage("com.tencent.mm",window,component.className,listOf("支付成功","¥12.34","完成")))!!
                        ScreenCaptureService.current!!.captureAutomatic(stale)
                        SystemClock.sleep(1200)
                    }
                    assertEquals("Sensitive/unknown pages must never call the screenshot API",initialRequests,ScreenCaptureService.current!!.screenshotRequests)
                    assertEquals(0,calls.get());assertEquals(before,app.store.all().map { it.id }.toSet())
                    assertEquals(taskIds,receiptIsolation.taskIds())
                    // An explicit shake may explain the privacy rejection; automatic checks stay silent.
                    if(!shakeOnBlocked)assertEquals("No automatic error/progress banner on a blocked page",initialStatus,app.channels.lastStatus())
                    return@use
                }
                if(leaveDuringModel) {
                    waitFor { calls.get()==1 }
                    assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
                    assertTrue(app.tasks.pendingQueued().isEmpty())
                    scenario.onActivity { it.startActivity(Intent(it,MainActivity::class.java)) }
                    releaseModel.countDown()
                }
                waitFor(16000) { app.store.all().any { it.id !in before } }
                val record=app.store.all().first { it.id !in before }
                assertEquals(1,calls.get());assertEquals(1234L,record.bill.amountCents)
                assertEquals(record.reason,RecordState.POSTED,record.state);assertNotNull(app.store.screenshot(record.id))
                if(framework)assertFalse(record.bill.timeExplicit)
                SystemClock.sleep(2500);assertEquals(1,calls.get())
                if(returnToPage) {
                    fun returnToResult() {
                        assertTrue(automation.performGlobalAction(2));SystemClock.sleep(400)
                        app.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        SystemClock.sleep(2000)
                    }
                    repeat(3) { returnToResult() }
                    assertEquals("Returning to the existing result must not upload it again",1,calls.get())
                    assertEquals(1,app.store.all().count { it.id !in before })
                    // A resumed payment can be accompanied by home/activity events from
                    // another window. Those events do not prove that the result was left.
                    repeat(2) {
                        val event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                        event.packageName=packageName;event.className=homeActivity
                        try { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            ScreenCaptureService.current!!.onAccessibilityEvent(event)
                        } } finally { event.recycle() }
                        SystemClock.sleep(2000)
                        assertEquals("An unrelated home event must not create another payment",1,calls.get())
                        assertEquals(1,app.store.all().count { it.id !in before })
                    }
                    val viewEvent=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    viewEvent.packageName=packageName;viewEvent.className="$packageName.fixture.CustomActivity"
                    try { InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val activityBeforeViewEvent=ScreenCaptureService.current!!.paymentDiagnostics().getString("lastActivity")
                        ScreenCaptureService.current!!.onAccessibilityEvent(viewEvent)
                        assertEquals("A custom View class cannot replace the last declared activity",activityBeforeViewEvent,
                            ScreenCaptureService.current!!.paymentDiagnostics().getString("lastActivity"))
                    } } finally { viewEvent.recycle() }
                    val factory=ScreenCaptureService.current!!.processorFactory
                    Settings.Secure.putString(resolver,"enabled_accessibility_services","")
                    Settings.Secure.putInt(resolver,"accessibility_enabled",0)
                    waitFor { ScreenCaptureService.current==null }
                    Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
                    Settings.Secure.putInt(resolver,"accessibility_enabled",1)
                    waitFor { ScreenCaptureService.current!=null }
                    // Revoking accessibility now pauses capture preferences; restore explicit test intent.
                    app.channels.set(ChannelSettings.Channel.OCR,true);setAuto(true)
                    ScreenCaptureService.current!!.processorFactory=factory
                    returnToResult()
                    assertEquals("Rebinding must keep the durable event identity and skip AI",1,calls.get())
                    assertEquals(1,app.store.all().count { it.id !in before })
                    // Simulate a vendor recreating the retained result window on resume.
                    repeat(2) {
                        assertTrue(automation.performGlobalAction(2));SystemClock.sleep(400)
                        app.startActivity(Intent().setComponent(component).putExtra("mode",mode)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        SystemClock.sleep(2500)
                        assertEquals("Recreated result windows are still the same payment",1,calls.get())
                        assertEquals(1,app.store.all().count { it.id !in before })
                    }
                    // A real second payment navigates within the payment app before its new result.
                    app.startActivity(Intent().setComponent(ComponentName(packageName,homeActivity))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    waitFor { ScreenCaptureService.current!!.paymentDiagnostics().optString("lastActivity")==homeActivity }
                    SystemClock.sleep(400)
                    app.startActivity(Intent().setComponent(component).putExtra("mode",mode)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    waitFor(16000) { calls.get()==2 && app.store.all().count { it.id !in before }==2 }
                    assertEquals("A new payment window with the same amount is a separate payment",2,calls.get())
                }
                setAuto(false)
                assertFalse(app.channels.autoAllowed(packageName))
            }
        } finally {
            releaseModel.countDown()
            setAuto(false)
            ScreenCaptureService.current?.cancelRecognition()
            ScreenCaptureService.current?.processorFactory={ CaptureProcessor(app) }
            CaptureRuntime.stopSafely()
            app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
            val createdTaskIds=receiptIsolation.createdTaskIds()
            receiptIsolation.clean(createdTaskIds)
            createdTaskIds.forEach(app.tasks::delete)
            app.recognitionSession.invalidate()
            Settings.Secure.putString(resolver,"enabled_accessibility_services",previousServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",previousEnabled)
            automation.dropShellPermissionIdentity()
            app.channels.set(ChannelSettings.Channel.OCR,oldSwitch)
            PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in oldApps) }
            app.channels.setAutomaticEnabled(oldAuto);app.changed()
        }
    }
}
