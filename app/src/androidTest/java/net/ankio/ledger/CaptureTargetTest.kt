package net.ankio.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import net.ankio.ledger.capture.CaptureProbe
import net.ankio.ledger.capture.resolveCaptureTarget
import net.ankio.ledger.capture.CaptureWindow
import net.ankio.ledger.capture.captureForegroundPackage
import net.ankio.ledger.capture.lookupCaptureOwner
import net.ankio.ledger.capture.CaptureRoot
import android.view.accessibility.AccessibilityWindowInfo as Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureTargetTest {
    @Test fun unreadablePageHasABoundedRetryAndDoesNotAuthorizeAnyCapture()=runBlocking {
        var reads=0
        val result=net.ankio.ledger.capture.readManualCapturePage {
            reads++;net.ankio.ledger.capture.CaptureProbeResult<net.ankio.ledger.core.PaymentPage>(null,"query_empty")
        }
        assertNull(result.value);assertEquals(8,reads)
    }
    @Test fun temporarilyBlankSharedContainerIsReadAgainWithinTheSameShake()=runBlocking {
        var reads=0
        val page=net.ankio.ledger.core.PaymentPage("com.tencent.mm",7,"com.tencent.mm.SharedUI",emptyList(),false)
        val result=net.ankio.ledger.capture.readManualCapturePage {
            reads++;net.ankio.ledger.capture.CaptureProbeResult(if(reads==1)page else page.copy(texts=listOf("已全额退款","20.30")),"query_ok")
        }
        assertEquals(2,reads);assertEquals(listOf("已全额退款","20.30"),result.value?.texts)
    }
    @Test fun firstShakeRequeriesAnUnavailablePageButNeverReusesALatePage()=runBlocking {
        var reads=0
        val page=net.ankio.ledger.core.PaymentPage("com.tencent.mm",7,"com.tencent.mm.BillActivity",listOf("账单详情","15.58"),false)
        val result=net.ankio.ledger.capture.readManualCapturePage {
            reads++;if(reads==1)net.ankio.ledger.capture.CaptureProbeResult(null,"query_timeout")
            else if(reads==2)net.ankio.ledger.capture.CaptureProbeResult(null,"query_busy")
            else net.ankio.ledger.capture.CaptureProbeResult(page,"query_ok")
        }
        assertEquals(page,result.value);assertEquals(3,reads)
    }
    @Test fun explicitSensitivePageNeverRetriesIntoASafePage()=runBlocking {
        var reads=0
        val password=net.ankio.ledger.core.PaymentPage("com.tencent.mm",7,"com.tencent.mm.PasswordUI",listOf("请输入支付密码"),true)
        val result=net.ankio.ledger.capture.readManualCapturePage {
            reads++;net.ankio.ledger.capture.CaptureProbeResult(password,"query_ok")
        }
        assertFalse(net.ankio.ledger.core.CapturePrivacy.manualAllowed(result.value!!));assertEquals(1,reads)
    }
    private val alipay="com.eg.android.AlipayGphone"
    @Test fun currentWindowOwnershipMustNotWaitForBlockedNodeTree()=runBlocking {
        val result=withTimeoutOrNull(250) {
            lookupCaptureOwner(listOf(CaptureWindow(7,Window.TYPE_APPLICATION,true,true,2,null)),mapOf(7 to alipay)) {
                delay(5_000);null
            }
        }
        assertEquals(alipay,result)
    }
    @Test fun backgroundOwnershipAndDifferentRootWindowCannotAuthorizeCapture()=runBlocking {
        val windows=listOf(CaptureWindow(7,Window.TYPE_APPLICATION,false,false,1,null),
            CaptureWindow(8,Window.TYPE_APPLICATION,true,true,2,null))
        assertNull(lookupCaptureOwner(windows,mapOf(7 to alipay)) { CaptureRoot(7,alipay) })
        assertEquals("com.example.other",lookupCaptureOwner(windows,mapOf(7 to alipay)) { CaptureRoot(8,"com.example.other") })
    }
    @Test fun missingFocusAndSystemWindowsNeverTriggerRootLookup()=runBlocking {
        assertNull(lookupCaptureOwner(listOf(CaptureWindow(7,Window.TYPE_APPLICATION,false,false,1,null)),mapOf(7 to alipay)) { error("background root queried") })
        assertEquals("com.android.systemui",lookupCaptureOwner(listOf(CaptureWindow(3,Window.TYPE_SYSTEM,true,true,4,null)),emptyMap()) { error("system root queried") })
    }
    @Test fun anInFlightRootDoesNotBlockCurrentWindowMetadataQueue()=runBlocking {
        val rootProbe=CaptureProbe();val metadataProbe=CaptureProbe()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        try {
            val root=async { rootProbe.readResult(100) { entered.countDown();release.await();CaptureRoot(7,alipay) } }
            withTimeout(1_000){while(entered.count>0)delay(5)}
            assertEquals("query_timeout",root.await().outcome)
            val snapshot=metadataProbe.read { listOf(CaptureWindow(7,Window.TYPE_APPLICATION,true,true,1,null)) }!!
            assertEquals(alipay,lookupCaptureOwner(snapshot,mapOf(7 to alipay)) { rootProbe.read { CaptureRoot(7,alipay) } })
        } finally { release.countDown();rootProbe.close();metadataProbe.close() }
    }
    @Test fun focusedPaymentWindowWinsOverActiveFloatingApplication() {
        assertEquals(alipay,captureForegroundPackage(listOf(
            CaptureWindow(3,Window.TYPE_APPLICATION,true,false,9,"com.example.floating"),
            CaptureWindow(7,Window.TYPE_APPLICATION,false,true,2,alipay)),"com.example.floating"))
    }
    @Test fun accessibilityFeedbackDoesNotHideFocusedPaymentWindow() {
        assertEquals(alipay,captureForegroundPackage(listOf(
            CaptureWindow(9,Window.TYPE_ACCESSIBILITY_OVERLAY,true,false,10,"net.ankio.ledger"),
            CaptureWindow(7,Window.TYPE_APPLICATION,false,true,2,alipay)),"net.ankio.ledger"))
    }
    @Test fun matchingWindowEventCanIdentifyUnavailableRoot() {
        assertEquals(alipay,captureForegroundPackage(listOf(CaptureWindow(7,Window.TYPE_APPLICATION,true,true,2,null)),null,mapOf(7 to alipay)))
    }
    @Test fun oldPaymentWindowCannotAuthorizeAnotherFocusedApp() {
        assertEquals("com.example.other",captureForegroundPackage(listOf(
            CaptureWindow(7,Window.TYPE_APPLICATION,true,false,1,alipay),
            CaptureWindow(8,Window.TYPE_APPLICATION,false,true,3,"com.example.other")),alipay,mapOf(7 to alipay)))
    }
    @Test fun visibleBackgroundPaymentAndStaleRootAreInsufficient() {
        assertNull(captureForegroundPackage(listOf(CaptureWindow(7,Window.TYPE_APPLICATION,false,false,2,alipay)),alipay,mapOf(7 to alipay)))
        assertNull(captureForegroundPackage(listOf(CaptureWindow(8,Window.TYPE_APPLICATION,true,true,3,null)),alipay,mapOf(7 to alipay)))
    }
    @Test fun notificationShadeBlocksPaymentCapture() {
        assertEquals("com.android.systemui",captureForegroundPackage(listOf(
            CaptureWindow(11,Window.TYPE_SYSTEM,true,true,10,"com.android.systemui"),
            CaptureWindow(7,Window.TYPE_APPLICATION,false,false,2,alipay)),alipay))
    }
    @Test fun firstEntryWaitsForWindowRootWithoutAnotherShake() = runBlocking {
        var reads=0
        assertEquals("com.tencent.mm",resolveCaptureTarget { if(++reads<3) null else "com.tencent.mm" })
        assertEquals(3,reads)
    }
    @Test fun explicitShakeAcceptsAnotherApplicationButNeverOurOwnAppOrSystemUi() = runBlocking {
        assertEquals("com.example.other",resolveCaptureTarget { "com.example.other" })
        assertNull(resolveCaptureTarget { "com.android.systemui" })
    }
    @Test fun aKnownPreviousApplicationCannotAuthorizeALaterPaymentWindow() = runBlocking {
        var reads=0
        assertNull(resolveCaptureTarget { if(++reads<3) "net.ankio.ledger" else alipay })
        assertEquals(1,reads)
    }
    @Test fun slowBinderReadDoesNotBlockUiOrQueueMoreRootQueries() = runBlocking {
        val probe=CaptureProbe()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        try {
            withContext(Dispatchers.Main) {
                val read=async { probe.read(150) { entered.countDown();release.await();alipay } }
                withTimeout(1_000) { while(entered.count>0)delay(5) }
                // A Main dispatcher continuation must run while the Binder query is blocked.
                assertTrue(async(Dispatchers.Main) { true }.await())
                assertNull(read.await())
                assertNull(probe.read { error("A second query must not be queued behind a blocked one") })
            }
            release.countDown()
            assertEquals(alipay,withTimeout(2_000) {
                var result:String?=null
                while(result==null){delay(10);result=probe.read { alipay }}
                result
            })
        } finally { release.countDown();probe.close() }
    }
    @Test fun cancellationNeverAcceptsALateRoot() = runBlocking {
        val probe=CaptureProbe()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        try {
            val pending=async { probe.read { entered.countDown();release.await();alipay } }
            withTimeout(1_000){while(entered.count>0)delay(5)}
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        } finally { release.countDown();probe.close() }
    }
}
