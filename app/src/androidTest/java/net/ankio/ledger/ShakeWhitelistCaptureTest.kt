package net.ankio.ledger

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.SyntheticRecognitionBackend
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual accessibility screenshot boundary on our own non-launcher synthetic APK. */
@RunWith(AndroidJUnit4::class)
class ShakeWhitelistCaptureTest {
    @get:org.junit.Rule val wallet=CaptureWalletFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val fixturePackage get()=InstrumentationRegistry.getInstrumentation().context.packageName
    private lateinit var automation:UiAutomation
    private lateinit var service:ScreenCaptureService
    private var previousServices:String?=null
    private var previousAccessibility=0
    private var previousShake=false
    private var previousAutomatic=false
    private var previousPackages=emptySet<String>()
    private var previousAutoPackages=emptySet<String>()
    private var previousTasks=emptySet<Long>()
    private var initialized=false
    private var fixtureObservation:ShakeWindowObservation?=null
    private val calls=AtomicInteger()
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    private val merchant="白名单截图合成${UUID.randomUUID()}"

    @Before fun setup() {
        assumeTrue("Dedicated emulator; never capture another user's applications",Build.MODEL.contains("sdk_gphone"))
        runBlocking { withTimeout(10_000) { app.taskRecovery.await() } }
        assumeTrue(app.captureQueue.size()==0 && app.tasks.pendingQueued().isEmpty())
        automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        previousServices=Settings.Secure.getString(app.contentResolver,"enabled_accessibility_services")
        previousAccessibility=Settings.Secure.getInt(app.contentResolver,"accessibility_enabled",0)
        previousShake=app.channels.enabled(ChannelSettings.Channel.OCR);previousAutomatic=app.channels.automaticEnabled()
        previousPackages=app.channels.selectedShakePackages();previousAutoPackages=app.channels.selectedAutoPackages()
        previousTasks=taskIds();initialized=true
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        automation.executeShellCommand("wm dismiss-keyguard").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
        app.channels.setAutomaticEnabled(false);app.channels.set(ChannelSettings.Channel.OCR,false)
        Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services","")
        Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",0)
        await { ScreenCaptureService.current==null }
        app.channels.selectedShakePackages().forEach { app.channels.setShakePackage(it,false) }
        app.channels.setShakePackage(fixturePackage,true)
        Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
        Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",1)
        await { ScreenCaptureService.current!=null && app.channels.permitted(ChannelSettings.Channel.OCR) }
        app.channels.set(ChannelSettings.Channel.OCR,true);CaptureRuntime.refreshHealth()
        await { CaptureRuntime.running }
        service=ScreenCaptureService.current!!
        service.processorFactory=factory()
    }

    @After fun cleanup() {
        if(!initialized)return
        if(::service.isInitialized) { service.beforeScreenshot={};service.beforeShakeOwner={};service.cancelRecognition() }
        releases.forEach { it.complete(Unit) }
        await { app.captureQueue.size()==0 }
        if(::service.isInitialized)service.processorFactory={CaptureProcessor(app)}
        app.channels.set(ChannelSettings.Channel.OCR,false);app.channels.setAutomaticEnabled(false)
        app.store.all().filter { it.bill.merchant.startsWith(merchant) }.forEach { app.store.delete(it.id) }
        (taskIds()-previousTasks).forEach(app.tasks::delete)
        app.channels.selectedShakePackages().forEach { app.channels.setShakePackage(it,false) }
        previousPackages.forEach { app.channels.setShakePackage(it,true) }
        Settings.Secure.putString(app.contentResolver,"enabled_accessibility_services",previousServices)
        Settings.Secure.putInt(app.contentResolver,"accessibility_enabled",previousAccessibility)
        app.channels.set(ChannelSettings.Channel.OCR,previousShake)
        PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in previousAutoPackages) }
        app.channels.setAutomaticEnabled(previousAutomatic)
        automation.performGlobalAction(AccessibilityServiceActions.HOME)
        automation.dropShellPermissionIdentity();app.changed()
    }

    @Test fun selectedSafeFramePostsAndMayFinishAfterLeavingItsApplication() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        service.processorFactory=factory(entered,release)
        startFixture("safe")
        val screenshots=service.screenshotRequests
        service.captureOnShake()
        await { entered.isCompleted }
        assertEquals(screenshots+1,service.screenshotRequests)
        assertEquals(1,(taskIds()-previousTasks).size)
        if(Build.VERSION.SDK_INT>=34)assertNotNull(service.lastScreenshotWindowId) else assertNull(service.lastScreenshotWindowId)
        run {
            startLedger()
            release.complete(Unit)
            await { app.captureQueue.size()==0 && app.store.all().any { it.bill.merchant.startsWith(merchant) } }
            val record=app.store.all().single { it.bill.merchant.startsWith(merchant) }
            assertEquals(record.reason,RecordState.POSTED,record.state);assertEquals(-1234L,record.wallet!!.appliedCents)
            assertNotNull(app.store.screenshot(record.id));assertEquals(1,calls.get())
            val captured=app.tasks.nodeSnapshot((taskIds()-previousTasks).single())
            assertNotNull("production traversal must retain the exact validated node evidence",captured)
            assertEquals(fixturePackage,captured!!.packageName)
            assertTrue(captured.nodes.any { it.parentId!=null && it.bounds.bottom>it.bounds.top })
            assertTrue(captured.environment.system.contains("/api"))
        }
        // The previous result banner is an accessibility overlay. Removing it for a
        // second frame must not be mistaken for a switch away from the selected app.
        startFixture("safe");service.captureOnShake()
        await { calls.get()==2 && app.captureQueue.size()==0 }
        assertEquals(screenshots+2,service.screenshotRequests)
        assertEquals(2,(taskIds()-previousTasks).size)
    }

    @Test fun publicUnknownLayoutMeasuresRealAcquisitionAndOneBundledOcrAttempt() = observePublicAttempt(false)

    @Test fun publicSyntheticQualifiedLayoutMeasuresRealAcquisitionWithoutOcr() = observePublicAttempt(true)

    /** Two constructed emulator routes, not a population sample or real payment benchmark. */
    private fun observePublicAttempt(syntheticNode:Boolean) {
        val beforeRecords=app.store.all().map { it.id }.toSet()
        val completed=AtomicInteger()
        if(syntheticNode)service.processorFactory=nodeFactory()
        else {
            val local=net.ankio.ledger.ocr.LocalRecognitionClient(app)
            val backend=object:net.ankio.ledger.ocr.RecognitionBackend {
                override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                    livePaymentAt:Long?,policy:net.ankio.ledger.core.RecognitionPolicy,origin:String):net.ankio.ledger.ocr.RecognitionResult = error("frozen context required")
                override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                    livePaymentAt:Long?,policy:net.ankio.ledger.core.RecognitionPolicy,origin:String,
                    recognitionTime:()->net.ankio.ledger.core.RecognitionTimeContext):net.ankio.ledger.ocr.RecognitionResult {
                    calls.incrementAndGet()
                    return local.recognizePrepared(image,onPrepared=onPrepared,stillAllowed=stillAllowed,
                        livePaymentAt=livePaymentAt,policy=policy,origin=origin,recognitionTime=recognitionTime).also {
                        if(it.phaseTimingsMs.containsKey("ocr"))completed.incrementAndGet()
                    }
                }
            }
            service.processorFactory={CaptureProcessor(app,OcrClient(app,backend))}
        }
        startFixture("safe")
        val requests=service.screenshotRequests
        val started=SystemClock.elapsedRealtime()
        var task:Long?=null
        try {
            service.captureOnShake()
            val deadline=started+30_000
            while(SystemClock.elapsedRealtime()<deadline) {
                task=(taskIds()-previousTasks).singleOrNull()
                if(task?.let { app.tasks.status(it) !in setOf("CAPTURING","QUEUED","RECOGNIZING") }==true && app.captureQueue.size()==0)break
                SystemClock.sleep(30)
            }
            val terminal=SystemClock.elapsedRealtime()
            val id=requireNotNull(task) { "Public trigger did not create a task: ${app.channels.lastStatus()}" }
            val evidence=app.tasks.recognitionEvidence(id)
            val timings=evidence?.optJSONObject("phaseTimingsMs") ?: JSONObject()
            val phases=JSONObject()
            for(phase in listOf("nodeRead","screenshot","route","ocr","parse","save")) {
                phases.put(phase,JSONObject().put("state",when {
                    timings.has(phase)->"measured"
                    phase=="ocr" && syntheticNode->"not_run"
                    else->"missing"
                }).put("millis",if(timings.has(phase))timings.getLong(phase) else JSONObject.NULL))
            }
            val snapshot=app.tasks.nodeSnapshot(id)
            val output=java.io.File(app.filesDir,"t15-acceptance").apply { mkdirs() }
            val row=JSONObject().put("attemptId",UUID.randomUUID().toString()).put("trigger","ScreenCaptureService.captureOnShake")
                .put("serial",InstrumentationRegistry.getArguments().getString("ledgerAcceptanceSerial") ?: JSONObject.NULL)
                .put("api",Build.VERSION.SDK_INT).put("model",Build.MODEL).put("systemFingerprint",Build.FINGERPRINT)
                .put("fixturePackage",fixturePackage).put("fixtureMode","safe")
                .put("registry",if(syntheticNode)"test_only_synthetic" else "production_unknown_layout")
                .put("parseScope",if(syntheticNode)"synthetic_adapter" else "production_transaction_parser")
                .put("operationKey",app.tasks.operationKey(id)).put("taskId",id).put("status",app.tasks.status(id))
                .put("backendEntered",calls.get()).put("ocrExecutedConfirmed",completed.get())
                .put("triggerElapsedMs",started).put("persistedTerminalObservedElapsedMs",terminal)
                .put("intervalMeaning","public trigger to persisted terminal observed; readiness excluded; model warmth uncontrolled")
                .put("screenshotRequestDelta",service.screenshotRequests-requests).put("phases",phases)
                .put("taskEvidence",evidence).put("actualNodeReadStartedMs",snapshot?.readStartedMs)
                .put("actualNodeReadCompletedMs",snapshot?.readCompletedMs)
                .put("recordCount",app.store.all().count { it.id !in beforeRecords })
            java.io.File(output,"attempts.jsonl").appendText(row.toString()+"\n")
            assertEquals(row.toString(),if(syntheticNode)"NODE" else "OCR",evidence?.getString("route"))
            assertEquals(1,service.screenshotRequests-requests)
            assertNotNull(snapshot);assertEquals(fixturePackage,snapshot!!.packageName)
            assertTrue(snapshot.readCompletedMs>=snapshot.readStartedMs)
            for(phase in listOf("nodeRead","screenshot","route","parse","save"))assertTrue("$phase absent: $row",timings.has(phase))
            assertEquals(if(syntheticNode)0 else 1,calls.get())
            assertEquals(if(syntheticNode)0 else 1,completed.get())
            if(syntheticNode)assertFalse(timings.has("ocr")) else assertTrue(timings.has("ocr"))
            assertTrue(row.toString(),app.tasks.status(id) in setOf("SUCCEEDED","REVIEW","NO_BILL"))
        } finally {
            app.store.all().filter { it.id !in beforeRecords }.forEach { app.store.delete(it.id) }
        }
    }

    @Test fun ordinaryScreenshotFailurePostsReliableNodesWithoutOcrAndKeepsMissingImageEvidence() {
        startFixture("safe")
        service.processorFactory=nodeFactory()
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,_->throw ScreenshotAcquisitionException(ScreenshotFailureReason.UNAVAILABLE,"synthetic unavailable") }
        try {
            service.captureOnShake()
            await { (taskIds()-previousTasks).singleOrNull()?.let { app.tasks.status(it) !in setOf("CAPTURING","QUEUED","RECOGNIZING") }==true && app.captureQueue.size()==0 }
            val task=(taskIds()-previousTasks).single()
            val record=app.tasks.recordId(task)?.let(app.store::get)
            assertNotNull("Reliable qualified nodes must survive ordinary screenshot failure",record)
            assertEquals(RecordState.POSTED,record!!.state)
            assertEquals(-1234L,record.wallet!!.appliedCents);assertEquals(0,calls.get())
            assertNull(app.store.screenshot(record.id));assertNull(app.tasks.image(task))
            assertEquals("NODE",record.bill.receipt!!.recognition!!.route)
            assertEquals("UNAVAILABLE",record.bill.receipt!!.recognition!!.screenshot.state)
            assertEquals("UNAVAILABLE",app.tasks.recognitionEvidence(task)!!.getJSONObject("screenshot").getString("state"))
        } finally { service.screenshotProvider=provider }
    }

    @Test fun unknownLayoutDelayedSuccessfulPixelsKeepTheOriginalTriggerDeadline() {
        startFixture("safe")
        val provider=service.screenshotProvider
        val deliveryAllowed=CompletableDeferred<Boolean>()
        val accounts=app.store.accounts()
        service.screenshotProvider={ _,allowed ->
            delay(1_800)
            val permitted=allowed();deliveryAllowed.complete(permitted)
            if(!permitted)throw ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"synthetic expired pixel delivery")
            android.graphics.Bitmap.createBitmap(100,200,android.graphics.Bitmap.Config.ARGB_8888)
        }
        try {
            service.captureOnShake()
            await { deliveryAllowed.isCompleted && (taskIds()-previousTasks).singleOrNull()?.let {
                app.tasks.status(it) !in setOf("CAPTURING","QUEUED","RECOGNIZING") }==true && app.captureQueue.size()==0 }
            assertFalse("Unknown/OCR layouts must reject pixels delivered after the original trigger deadline",runBlocking { deliveryAllowed.await() })
            assertEquals(0,calls.get())
            assertNull(app.tasks.recordId((taskIds()-previousTasks).single()))
            assertEquals(accounts,app.store.accounts())
        } finally { service.screenshotProvider=provider }
    }

    @Test fun ordinaryScreenshotTimeoutStillPostsTheOriginalReliableNodesWithoutOcr() {
        startFixture("safe");service.processorFactory=nodeFactory()
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,_->delay(5_000);error("bounded timeout must cancel pixel acquisition") }
        try {
            service.captureOnShake()
            await { (taskIds()-previousTasks).singleOrNull()?.let { app.tasks.status(it) !in setOf("CAPTURING","QUEUED","RECOGNIZING") }==true && app.captureQueue.size()==0 }
            val task=(taskIds()-previousTasks).single()
            val record=app.tasks.recordId(task)?.let(app.store::get)
            assertNotNull("A bounded ordinary timeout must preserve still-current reliable nodes",record)
            assertEquals("TIMEOUT",record!!.bill.receipt!!.recognition!!.screenshot.state)
            assertEquals(RecordState.POSTED,record.state);assertEquals(0,calls.get());assertNull(app.store.screenshot(record.id))
        } finally { service.screenshotProvider=provider }
    }

    private fun nodeFactory():()->CaptureProcessor {
        val info=app.packageManager.getPackageInfo(fixturePackage,0)
        val verification=net.ankio.ledger.core.NodeLayoutVerification("synthetic-failure",fixturePackage,info.versionName.orEmpty(),info.longVersionCode,
            "${Build.VERSION.RELEASE}/api${Build.VERSION.SDK_INT}/${Build.FINGERPRINT}".take(256),"${Build.MANUFACTURER}/${Build.MODEL}".take(256),
            setOf("net.ankio.ledger.ShakeCaptureFixtureActivity"),false,"synthetic-failure-comparison","synthetic capture only")
        val registry=net.ankio.ledger.core.NodeRecognitionRegistry(listOf(net.ankio.ledger.core.NodeLayoutAdapter(verification,{true}) { _,clock ->
            val field=net.ankio.ledger.core.ReceiptField(merchant,merchant,"node")
            listOf(net.ankio.ledger.core.TransactionCandidate(net.ankio.ledger.core.CandidateId.SINGLE,
                net.ankio.ledger.core.ParsedBill(1234,net.ankio.ledger.core.Direction.EXPENSE,merchant,"其他",clock.recognitionAt,null,true,"",
                    receipt=net.ankio.ledger.core.ReceiptEvidence(merchant=field))))
        }))
        return { CaptureProcessor(app,OcrClient(app,net.ankio.ledger.ocr.RecognitionBackend { _,_,_,_,_,_->calls.incrementAndGet();error("nodes must not invoke OCR") }),nodeRegistry=registry) }
    }

    @Test fun protectedPixelFailureRetainsTheExistingPrivacyRefusalWithoutATaskOrBill() {
        startFixture("safe");service.processorFactory=nodeFactory()
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,_->throw ScreenshotAcquisitionException(ScreenshotFailureReason.PROTECTED,"合成受保护页面，未截图") }
        try {
            service.captureOnShake()
            await { app.channels.lastStatus().contains("合成受保护页面") }
            assertEquals(previousTasks,taskIds())
            assertEquals(0,calls.get());assertTrue(app.store.all().none { it.bill.merchant.startsWith(merchant) })
        } finally { service.screenshotProvider=provider }
    }

    @Test fun ordinaryFailureAfterWindowContentChangeCannotSaveOldNodes() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        startFixture("safe");service.processorFactory=nodeFactory()
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,_->entered.complete(Unit);release.await();throw ScreenshotAcquisitionException(ScreenshotFailureReason.UNAVAILABLE,"synthetic unavailable") }
        try {
            service.captureOnShake();await { entered.isCompleted }
            val event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            event.packageName=fixturePackage
            try { InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onAccessibilityEvent(event) } }
            finally { event.recycle() }
            release.complete(Unit)
            await { (taskIds()-previousTasks).singleOrNull()?.let { app.tasks.status(it) !in setOf("CAPTURING","QUEUED","RECOGNIZING") }==true && app.captureQueue.size()==0 }
            val task=(taskIds()-previousTasks).single()
            assertNull(app.tasks.recordId(task));assertEquals(0,calls.get());assertFalse(app.tasks.hasMissingNodeEvidence(task))
        } finally { service.screenshotProvider=provider }
    }

    @Test fun ordinaryFailureAfterConsentRevocationCannotSaveOldNodes() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        startFixture("safe");service.processorFactory=nodeFactory()
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,_->entered.complete(Unit);release.await();throw ScreenshotAcquisitionException(ScreenshotFailureReason.TIMEOUT,"synthetic timeout") }
        try {
            service.captureOnShake();await { entered.isCompleted }
            app.channels.setShakePackage(fixturePackage,false);release.complete(Unit)
            await { (taskIds()-previousTasks).singleOrNull()?.let { app.tasks.status(it) !in setOf("CAPTURING","QUEUED","RECOGNIZING") }==true && app.captureQueue.size()==0 }
            val task=(taskIds()-previousTasks).single()
            assertEquals("CANCELLED",app.tasks.status(task));assertNull(app.tasks.recordId(task));assertEquals(0,calls.get());assertFalse(app.tasks.hasMissingNodeEvidence(task))
        } finally { service.screenshotProvider=provider }
    }

    @Test fun typedAuthorizationAndWindowFailuresNeverUseReliableNodeFallback() {
        val provider=service.screenshotProvider
        service.processorFactory=nodeFactory()
        try {
            listOf(ScreenshotFailureReason.AUTHORIZATION_CHANGED,ScreenshotFailureReason.WINDOW_CHANGED).forEach { reason ->
                startFixture("safe");val before=taskIds()
                service.screenshotProvider={ _,_->throw ScreenshotAcquisitionException(reason,"synthetic refusal") }
                service.captureOnShake()
                await { (taskIds()-before).singleOrNull()?.let { app.tasks.status(it)=="FAILED" }==true }
                val task=(taskIds()-before).single()
                assertNull(app.tasks.recordId(task));assertFalse(app.tasks.hasMissingNodeEvidence(task));assertEquals(0,calls.get())
            }
        } finally { service.screenshotProvider=provider }
    }

    @Test fun unknownNodesWithOrdinaryScreenshotFailureNeverStartOcrOrPost() {
        startFixture("safe")
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,_->throw ScreenshotAcquisitionException(ScreenshotFailureReason.UNAVAILABLE,"synthetic unavailable") }
        try {
            service.captureOnShake()
            await { (taskIds()-previousTasks).singleOrNull()?.let { app.tasks.status(it)=="FAILED" }==true }
            val task=(taskIds()-previousTasks).single()
            assertNull(app.tasks.recordId(task));assertEquals(0,calls.get());assertFalse(app.tasks.hasMissingNodeEvidence(task))
        } finally { service.screenshotProvider=provider }
    }

    @Test fun contentChangeWhilePixelsArePendingCannotFallBackIntoOcrOrCreateABill() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        startFixture("safe")
        val provider=service.screenshotProvider
        service.screenshotProvider={ _,allowed ->
            entered.complete(Unit);release.await()
            if(!allowed())throw ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"合成采集期间交易改变")
            android.graphics.Bitmap.createBitmap(100,200,android.graphics.Bitmap.Config.ARGB_8888)
        }
        try {
            service.captureOnShake();await { entered.isCompleted }
            val event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            event.packageName=fixturePackage
            // Some platform content events omit a window ID. They cannot certify that
            // an in-flight frame still belongs to the previously checked transaction.
            try { InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onAccessibilityEvent(event) } }
            finally { event.recycle() }
            release.complete(Unit)
            await { (taskIds()-previousTasks).singleOrNull()?.let { app.tasks.status(it)=="FAILED" }==true }
            assertEquals(0,calls.get());assertTrue(app.store.all().none { it.bill.merchant.startsWith(merchant) })
            assertTrue((taskIds()-previousTasks).all { app.tasks.image(it)==null })
        } finally { release.complete(Unit);service.screenshotProvider=provider }
    }

    @Test fun sensitiveEditableAndUnreadablePagesNeverRequestPixelsOrCreateTasks() {
        service.processorFactory=nodeFactory()
        val count=service.screenshotRequests;val before=taskIds()
        for(mode in listOf("password","editable","verification","blank")) {
            startFixture(mode);service.captureOnShake();SystemClock.sleep(1_900)
            assertEquals("Screenshot requested for $mode",count,service.screenshotRequests)
            assertEquals(before,taskIds());assertEquals(0,calls.get())
        }
    }

    @Test fun aWindowSwitchBeforeScreenshotCancelsAndAnOutsideShakeStaysSilentWhileBusy() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        service.beforeScreenshot={ entered.complete(Unit);release.await() }
        startFixture("safe")
        val count=service.screenshotRequests;val before=taskIds()
        service.captureOnShake();await { entered.isCompleted }
        run {
            startLedger()
            await { foregroundPackage()==app.packageName }
            val status=app.channels.lastStatus()
            service.captureOnShake();SystemClock.sleep(250)
            assertEquals(status,app.channels.lastStatus());assertEquals(before,taskIds())
            release.complete(Unit);SystemClock.sleep(500)
            assertEquals(count,service.screenshotRequests);assertEquals(0,calls.get())
        }
    }

    @Test fun anOutsideTriggerCannotBorrowALaterSelectedWindowAfterWorkerDispatchIsDelayed() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        run {
            startLedger()
            await { foregroundPackage()==app.packageName }
            service.beforeShakeOwner={ entered.complete(Unit);release.await() }
            val count=service.screenshotRequests;val before=taskIds()
            service.captureOnShake()
            // A known excluded event may reject synchronously; otherwise hold the async probe.
            startFixture("safe");release.complete(Unit);SystemClock.sleep(900)
            assertEquals(count,service.screenshotRequests);assertEquals(before,taskIds());assertEquals(0,calls.get())
        }
    }

    @Test fun emptySelectionStopsSensorsWhileAutomaticRuntimeRemainsAvailable() {
        app.channels.setAutoPackage(PaymentApps.WECHAT,true);app.channels.setAutomaticEnabled(true)
        app.channels.setShakePackage(fixturePackage,false);CaptureRuntime.refreshHealth()
        await { !app.shakeRuntime.snapshot().getBoolean("sensorRunning") }
        assertTrue(CaptureRuntime.running);assertTrue(app.channels.autoAllowed(PaymentApps.WECHAT))
        val before=taskIds();val count=service.screenshotRequests
        startFixture("safe");service.captureOnShake();SystemClock.sleep(300)
        assertEquals(before,taskIds());assertEquals(count,service.screenshotRequests)
    }
    @Test fun selectionRevokedAndReaddedDuringPreflightCannotAuthorizeTheEarlierTrigger() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        startFixture("safe")
        service.beforeShakeOwner={ entered.complete(Unit);release.await() }
        val count=service.screenshotRequests;val before=taskIds()
        service.captureOnShake();await { entered.isCompleted }
        app.channels.setShakePackage(fixturePackage,false);app.channels.setShakePackage(fixturePackage,true)
        release.complete(Unit);SystemClock.sleep(500)
        assertEquals(count,service.screenshotRequests);assertEquals(before,taskIds());assertEquals(0,calls.get())
    }

    @Test fun shortcutIsPublishedAndReturnsToTheSelectedBillBeforeCapturing() {
        val shortcut=app.getSystemService(android.content.pm.ShortcutManager::class.java)
            .dynamicShortcuts.single { it.id==CaptureShortcuts.ID }
        assertEquals("识别当前账单",shortcut.shortLabel.toString())
        assertEquals(CaptureShortcutActivity.CAPTURE_CURRENT_BILL,shortcut.intent!!.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,shortcut.intent!!.flags)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,CaptureShortcuts.info(app).intent.flags)
        val legacy=Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(app.packageName)
        assertTrue(app.packageManager.queryIntentActivities(legacy,0).any {
            it.activityInfo.name==CaptureShortcutActivity::class.java.name
        })
        startFixture("safe")
        val count=service.screenshotRequests
        startShortcut()
        await { calls.get()==1 && app.captureQueue.size()==0 }
        assertEquals(count+1,service.screenshotRequests)
        assertEquals(fixturePackage,foregroundPackage())
        assertTrue(app.store.all().any { it.bill.merchant.startsWith(merchant) })
    }

    @Test fun shortcutRetainsPrivacyChecksAndRejectsAnUnselectedApplication() {
        val count=service.screenshotRequests;val before=taskIds()
        for(mode in listOf("password","editable","verification","blank")) {
            startFixture(mode);startShortcut();SystemClock.sleep(1_900)
            assertEquals("Shortcut requested pixels for $mode",count,service.screenshotRequests)
            assertEquals(before,taskIds())
        }
        startLedger();startShortcut();SystemClock.sleep(1_900)
        assertEquals(count,service.screenshotRequests);assertEquals(before,taskIds())
        startFixture("safe")
        app.channels.setShakePackage(fixturePackage,false)
        startShortcut();SystemClock.sleep(1_900)
        assertEquals(count,service.screenshotRequests);assertEquals(before,taskIds());assertEquals(0,calls.get())
    }

    @Test fun shortcutWorksWithMotionAndAutomaticRecognitionDisabled() {
        startFixture("safe")
        val count=service.screenshotRequests
        app.channels.set(ChannelSettings.Channel.OCR,false)
        CaptureRuntime.refreshHealth()
        await { !CaptureRuntime.running && !CaptureRuntime.sensorRunning }
        startShortcut();await { calls.get()==1 && app.captureQueue.size()==0 }
        assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
        assertFalse(app.channels.automaticEnabled());assertFalse(CaptureRuntime.sensorRunning)
        assertEquals(count+1,service.screenshotRequests)
        val task=(taskIds()-previousTasks).single()
        assertTrue(app.tasks.shakeAuthorization(task)!!.shortcut)
        assertTrue(app.store.all().any { it.bill.merchant.startsWith(merchant) })
    }

    @Test fun turningOffMotionDoesNotCancelAnAcquiredShortcutOrder() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        service.processorFactory=factory(entered,release)
        startFixture("safe");startShortcut();await { entered.isCompleted }
        app.channels.set(ChannelSettings.Channel.OCR,false)
        service.cancelChannel(ChannelSettings.Channel.OCR);CaptureRuntime.refreshHealth()
        await { !CaptureRuntime.sensorRunning }
        release.complete(Unit)
        await { app.captureQueue.size()==0 && app.store.all().any { it.bill.merchant.startsWith(merchant) } }
        assertEquals(1,calls.get())
    }

    @Test fun shortcutCancelsIfTheBillWindowChangesBeforeTheScreenshot() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>().also { releases+=it }
        service.beforeScreenshot={ entered.complete(Unit);release.await() }
        startFixture("safe")
        val count=service.screenshotRequests;val before=taskIds()
        startShortcut();await { entered.isCompleted }
        startLedger();release.complete(Unit);SystemClock.sleep(500)
        assertEquals(count,service.screenshotRequests);assertEquals(before,taskIds());assertEquals(0,calls.get())
    }

    private fun startShortcut() {
        // Exercise the same system API as launchers/gesture tools, including its intent flags.
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS","android.permission.ACCESS_SHORTCUTS")
        app.getSystemService(android.content.pm.LauncherApps::class.java)
            .startShortcut(app.packageName,CaptureShortcuts.ID,null,null,android.os.Process.myUserHandle())
    }

    private fun startFixture(mode:String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        automation.waitForIdle(250,3_000)
        val component=ComponentName(fixturePackage,ShakeCaptureFixtureActivity::class.java.name)
        automation.executeShellCommand("am start -W -n ${component.flattenToString()} --es mode $mode -f 0x10008000").use {
            val output=java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)
            assertFalse("Synthetic fixture activity failed: $output",output.contains("Error:"))
        }
        await { foregroundPackage()==fixturePackage }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        automation.waitForIdle(100,3_000)
        // UiAutomation's root and this service's interactive-window publication can
        // arrive separately. Establish actual service metadata before the one trigger.
        var previous:ShakeWindowObservation?=null
        var stableSince=SystemClock.elapsedRealtime()
        await {
            // The metadata Binder worker can observe the new window before the service's
            // main looper receives its events. Drain that queue before each readiness sample.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            val observed=runBlocking { service.observeShakeWindow() }
            fixtureObservation=observed
            val valid=observed.windowId!=null && observed.packageName==fixturePackage
            val now=SystemClock.elapsedRealtime()
            // Two adjacent Binder reads can precede delayed window events on a busy CI emulator.
            // Keep the same real owner/window/generation stable before issuing the single trigger.
            if(!valid || observed!=previous)stableSince=now
            val ready=valid && observed==previous && now-stableSince>=500
            previous=observed
            ready
        }
    }
    private fun startLedger() {
        val component=ComponentName(app,MainActivity::class.java)
        automation.executeShellCommand("am start -W -n ${component.flattenToString()} -f 0x10000000").use {
            val output=java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)
            assertFalse("Ledger activity failed: $output",output.contains("Error:"))
        }
        await { foregroundPackage()==app.packageName }
    }
    // UiAutomation's cached active root can still belong to a system bar after an activity switch.
    // Read the same selected-window ownership that the real screenshot boundary will validate.
    private fun foregroundPackage():String?=runBlocking { service.observeShakeWindow() }.packageName
    private fun taskIds():Set<Long> = app.tasks.snapshot(500).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") }.toSet() }
    private fun await(check:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<deadline) {
            if(check())return
            SystemClock.sleep(30)
        }
        fail("Capture boundary timeout: status=${app.channels.lastStatus()}; fixture=$fixtureObservation; ${ScreenCaptureService.current?.paymentDiagnostics()}")
    }
    private fun factory(entered:CompletableDeferred<Unit>?=null,release:CompletableDeferred<Unit>?=null):()->CaptureProcessor = {
        CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ ->
            val index=calls.incrementAndGet();entered?.complete(Unit);release?.await()
            val time=LocalDateTime.now().withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
            val name="$merchant-$index"
            val order=UUID.randomUUID().toString()
            val bill=JSONObject().put("is_bill",true).put("amount","12.34").put("direction","expense")
                .put("merchant",name).put("category","其他").put("occurred_at",time)
                .put("transaction_id",order).put("reliable",true)
                .put("raw_text","合成账单 $name 支出12.34元 支付时间$time 交易单号$order").put("merchant_icon",JSONObject.NULL)
            JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
        }))
    }
    private object AccessibilityServiceActions { const val HOME=2 }
}
