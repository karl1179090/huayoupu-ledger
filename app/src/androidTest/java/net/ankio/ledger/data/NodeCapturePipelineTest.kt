package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.WebUiProbe
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic adapters are injected here only; production registry remains empty. */
@RunWith(AndroidJUnit4::class)
class NodeCapturePipelineTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val time=RecognitionTimeContext(1_799_411_400_000,"Asia/Shanghai")
    private fun snapshot()=NodeSnapshot("com.tencent.mm",7,"com.tencent.mm.TestDetail",3,100,110,1_799_400_000_000,
        NodeEnvironment("synthetic-1",1,"test-api34","synthetic-device"),listOf(
            CaptureNode(0,null,0,NodeBounds(0,0,100,200),"支付成功"),
            CaptureNode(1,0,1,NodeBounds(0,30,100,60),"付款金额：12.34")))
    private fun registry(count:Int=1)=NodeRecognitionRegistry(listOf(NodeLayoutAdapter(NodeLayoutVerification("synthetic-layout","com.tencent.mm","synthetic-1",1,
        "test-api34","synthetic-device",setOf("com.tencent.mm.TestDetail"),false,"synthetic-comparison","manual synthetic detail"),{true}) { _,clock ->
        List(count) { TransactionCandidate(CandidateId.SINGLE,ParsedBill(1234,Direction.EXPENSE,"","其他",clock.recognitionAt,null,true,"",
            receipt=ReceiptEvidence(time=TransactionTimeResolver.fallback(clock)))) }
    }))

    @Test fun savedNodeSnapshotPostsThroughSharedPipelineAndRecoveryDoesNotRecognizeOrChargeAgain()=runBlocking<Unit> {
        app.taskRecovery.await()
        val previous=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成节点钱包",amountCents=10000,kind="cash"));app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm")
        val operation=app.tasks.operationKey(task)!!
        val bitmap=android.graphics.Bitmap.createBitmap(100,200,android.graphics.Bitmap.Config.ARGB_8888)
        val image=try { OcrClient(app).prepareScreenshot(bitmap) } finally { bitmap.recycle() }
        var ocrCalls=0
        val backend=RecognitionBackend { _,_,_,_,_,_->ocrCalls++;error("verified nodes must skip OCR") }
        var saved:Long?=null
        try {
            assertNull(app.tasks.recognitionTime(task))
            assertTrue(app.tasks.queued(task,image,snapshot()))
            RecognitionTasks(app).use { restored ->
                assertEquals(snapshot().fingerprint,restored.nodeSnapshot(task)!!.fingerprint)
                assertNull(restored.recognitionTime(task))
            }
            val processor=CaptureProcessor(app,OcrClient(app,backend),{time},registry())
            val result=processor.processPrepared(image,Source.OCR,"com.tencent.mm",task) { true } as IngestResult.Saved
            saved=result.record.id
            assertEquals(0,ocrCalls);assertEquals(RecordState.POSTED,result.record.state)
            assertEquals(1234L,result.record.bill.amountCents);assertEquals(time.recognitionAt,result.record.bill.occurredAt)
            assertEquals(8766L,app.store.getAccount(wallet.id)!!.amountCents)
            assertArrayEquals(image,app.store.screenshot(saved!!))
            assertEquals("NODE",app.tasks.recognitionEvidence(task)!!.getString("route"))
            assertEquals("verified_layout",app.tasks.recognitionEvidence(task)!!.getString("reason"))
            assertEquals(app.tasks.operationKey(task),app.tasks.portableNodeEvidence().getJSONObject(0).getString("operationKey"))
            assertFalse(RecognitionDiagnostics.encode(app.tasks).contains("付款金额：12.34"))
            assertEquals(IngestResult.DuplicateSkipped,processor.processPrepared(image,Source.OCR,"com.tencent.mm",task) { true })
            assertEquals(0,ocrCalls);assertEquals(8766L,app.store.getAccount(wallet.id)!!.amountCents)
            app.tasks.delete(task)
            val retained=org.json.JSONObject(ReceiptEvidenceCodec.json(app.store.get(saved!!)!!.bill.receipt).toString())
            assertEquals("NODE",retained.getJSONObject("recognition").getString("route"))
            val backup=LedgerBackup.decode(LedgerBackup.encode(app.store,app.channels).byteInputStream())
            assertEquals("NODE",org.json.JSONObject(ReceiptEvidenceCodec.json(backup.records.single { it.uid==result.record.uid }.bill.receipt).toString()).getJSONObject("recognition").getString("route"))
            app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(operation))
            Unit
        } finally {
            saved?.let(app.store::delete)
            app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(operation))
            app.tasks.delete(task);app.store.setDefaultWallet(previous)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }
    @Test fun qualifiedProductionAdapterUsesFrozenClassificationAndOnlyTheActualWechatWallet()=runBlocking<Unit> {
        app.taskRecovery.await()
        val settings=app.store.walletSettings()
        val wallet=app.store.saveAccount(LedgerAccount(name="合成已验证零钱",amountCents=10000,kind="wechat"))
        val cash=app.store.saveAccount(LedgerAccount(name="合成不应扣款现金",amountCents=20000,kind="cash"))
        app.store.setDefaultWallet(cash.uid);app.store.setAppWallet(PaymentApps.WECHAT,wallet.uid)
        val merchant="合成结构商家-${UUID.randomUUID()}"
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成节点固定规则",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(value=merchant))))
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm");val operation=app.tasks.operationKey(task)!!
        val version=app.tasks.classificationSnapshot(task)!!.version
        app.store.saveClassificationRule(rule.copy(enabled=false,categoryId="cat:expense:shopping"))
        val bitmap=android.graphics.Bitmap.createBitmap(100,200,android.graphics.Bitmap.Config.ARGB_8888)
        val image=try { OcrClient(app).prepareScreenshot(bitmap) } finally { bitmap.recycle() }
        var calls=0;var saved:Long?=null
        val backend=RecognitionBackend { _,_,_,_,_,_->calls++;error("qualified production layout must skip OCR") }
        try {
            assertTrue(app.tasks.queued(task,image,QualifiedWechatNodeFixture.page(merchant=merchant)))
            assertNull(app.tasks.recognitionTime(task))
            val processor=CaptureProcessor(app,OcrClient(app,backend),{time})
            val result=processor.processPrepared(image,Source.OCR,PaymentApps.WECHAT,task) { true } as IngestResult.Saved
            saved=result.record.id
            assertEquals(0,calls);assertEquals(RecordState.POSTED,result.record.state)
            assertEquals("餐饮",result.record.bill.category);assertEquals(version,result.record.bill.categoryDecision!!.ruleVersion)
            assertEquals(8766L,app.store.getAccount(wallet.id)!!.amountCents);assertEquals(20000L,app.store.getAccount(cash.id)!!.amountCents)
            assertEquals("合成扣费服务商",result.record.bill.receipt!!.serviceProvider!!.value)
            assertEquals("合成停车费",result.record.bill.receipt!!.transactionDescription!!.value)
            assertEquals(time.recognitionAt,result.record.bill.occurredAt);assertEquals("RECOGNITION_FALLBACK",result.record.bill.receipt!!.time!!.source)
            assertFalse(result.record.capture.text.contains("订单已关闭"));assertFalse(result.record.capture.text.contains("按时支付"))
            assertEquals("f754a6a2-b387-4339-9ece-296b5e122fe2",result.record.bill.receipt!!.recognition!!.adapter!!.comparisonId)
            assertArrayEquals(image,app.store.screenshot(saved!!))
            RecognitionTasks(app).use { reopened -> assertEquals(time,reopened.recognitionTime(task));assertNotNull(reopened.nodeSnapshot(task)) }
            assertEquals(IngestResult.DuplicateSkipped,CaptureProcessor(app,OcrClient(app,backend),{time.copy(recognitionAt=time.recognitionAt+172800000)})
                .processPrepared(image,Source.OCR,PaymentApps.WECHAT,task) { true })
            assertEquals(0,calls);assertEquals(8766L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==result.record.uid })
            app.tasks.delete(task)
            androidx.test.core.app.ActivityScenario.launch(net.ankio.ledger.ui.MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bridge=net.ankio.ledger.ui.LedgerWebBridge(activity)
                    val detail=org.json.JSONObject(bridge.recordDetails(result.record.id)).getJSONObject("record")
                    assertEquals("NODE",detail.getJSONObject("recognitionEvidence").getString("route"))
                    val rows=org.json.JSONObject(bridge.snapshot()).getJSONArray("records")
                    val row=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getLong("id")==result.record.id }
                    assertEquals("NODE",row.getJSONObject("recognitionEvidence").getString("route"))
                }
            }
        } finally {
            saved?.let(app.store::delete);app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(operation))
            app.tasks.delete(task);app.store.deleteClassificationRule(rule.id)
            app.store.setDefaultWallet(settings.defaultAccountUid);app.store.setAppWallet(PaymentApps.WECHAT,settings.appBindings[PaymentApps.WECHAT])
            listOf(wallet,cash).forEach { account ->
                app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(account.uid));app.store.deleteAccount(account.id)
            }
        }
    }

    @Test fun nodeMerchantWithoutItsOwnEvidenceCannotBorrowProviderOrBankAuthorIcons()=runBlocking<Unit> {
        app.taskRecovery.await()
        val name="合成服务商-${UUID.randomUUID()}"
        val bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
        val icon=try { java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray() } finally { bitmap.recycle() }
        val seed=(app.store.ingest(Capture(Source.MANUAL,"manual","合成真实商家",time.recognitionAt),
            ParsedBill(100,Direction.EXPENSE,name,"其他",time.recognitionAt,null,true,"",true),merchantIcon=icon) as IngestResult.Saved).record
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm");var saved:Long?=null
        try {
            val base=registry()
            val verification=base.select(snapshot(),false) { time }.verification!!
            val provider=ReceiptField(name,name,"node")
            val registry=NodeRecognitionRegistry(listOf(NodeLayoutAdapter(verification,{true}) { _,clock ->
                listOf(TransactionCandidate(CandidateId.SINGLE,ParsedBill(1234,Direction.EXPENSE,name,"其他",clock.recognitionAt,null,true,"",
                    receipt=ReceiptEvidence(serviceProvider=provider,receiptIssuer=provider))))
            }))
            val image="synthetic-node-provider".toByteArray();assertTrue(app.tasks.queued(task,image,snapshot()))
            val backend=RecognitionBackend { _,_,_,_,_,_->error("must not invoke OCR for icons") }
            val result=CaptureProcessor(app,OcrClient(app,backend),{time},registry).processPrepared(image,Source.OCR,"com.tencent.mm",task) { true } as IngestResult.Saved
            saved=result.record.id
            assertNotNull(app.store.merchantIcon(seed.id))
            assertNull("A service provider/bank author is not reliable merchant identity",app.store.merchantIcon(saved!!))
        } finally {
            saved?.let(app.store::delete);app.store.delete(seed.id);app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) };app.tasks.delete(task)
        }
    }

    @Test fun qualifiedMissingImageWithoutWechatWalletDoesNotChargeTheDefaultCashWallet()=runBlocking<Unit> {
        app.taskRecovery.await()
        val settings=app.store.walletSettings()
        val cash=app.store.saveAccount(LedgerAccount(name="合成缺微信零钱现金",amountCents=20000,kind="cash"))
        app.store.setDefaultWallet(cash.uid);app.store.setAppWallet(PaymentApps.WECHAT,null)
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm");var saved:Long?=null
        val copies=listOf("t13-pending-json-${UUID.randomUUID()}.db","t13-pending-zip-${UUID.randomUUID()}.db")
        val archive=java.io.File(app.cacheDir,"t13-pending-${UUID.randomUUID()}.zip")
        try {
            val snapshot=QualifiedWechatNodeFixture.page(merchant="合成缺图账户-${UUID.randomUUID()}")
            val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->error("must not invoke OCR") }),{time})
            val route=processor.prepareNodeFallback(task,snapshot,PaymentApps.WECHAT)!!
            assertTrue(app.tasks.queuedWithoutScreenshot(task,snapshot,route,net.ankio.ledger.capture.ScreenshotFailureReason.UNAVAILABLE))
            val result=processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,task) { true } as IngestResult.Saved
            saved=result.record.id
            assertEquals("Explicit actual payment cannot fall back to default cash",RecordState.REVIEW,result.record.state)
            assertEquals(20000L,app.store.getAccount(cash.id)!!.amountCents)
            assertTrue(app.store.walletMovements(cash.uid).none { it.recordUid==result.record.uid })
            assertTrue(result.record.bill.paymentMethod!!.executed)
            assertEquals("ACTUAL_ACCOUNT_REQUIRED",result.record.wallet!!.reviewCode)
            assertNull(result.record.wallet!!.accountUid);assertEquals(0L,result.record.wallet!!.appliedCents)
            fun assertPendingRestored(copy:LedgerStore) {
                val restored=copy.all().single { it.uid==result.record.uid }
                assertEquals(RecordState.REVIEW,restored.state)
                assertEquals(result.record.bill.paymentMethod,restored.bill.paymentMethod)
                assertEquals(result.record.bill.receipt,restored.bill.receipt)
                assertEquals("ACTUAL_ACCOUNT_REQUIRED",restored.wallet!!.reviewCode)
                assertNull(restored.wallet!!.accountUid);assertEquals(0L,restored.wallet!!.appliedCents)
                assertNull(copy.screenshot(restored.id))
                assertEquals(20000L,copy.getAccountByUid(cash.uid)!!.amountCents)
                assertTrue(copy.walletMovements(cash.uid).none { it.recordUid==restored.uid })
            }
            val backup=LedgerBackup.decode(LedgerBackup.encode(app.store,app.channels).byteInputStream())
            LedgerStore(app,copies[0]).use { copy ->
                assertTrue(LedgerBackup.restore(copy,backup)>0);assertPendingRestored(copy)
                val accounts=copy.accounts();val movements=copy.walletMovements(cash.uid)
                assertEquals(0,LedgerBackup.restore(copy,backup));assertEquals(accounts,copy.accounts());assertEquals(movements,copy.walletMovements(cash.uid))
            }
            archive.outputStream().use { LedgerArchive.write(app.store,app.channels,it) }
            LedgerStore(app,copies[1]).use { copy ->
                LedgerArchive.read(archive.inputStream(),app.cacheDir).use { prepared ->
                    assertTrue(LedgerArchive.restore(copy,prepared)>0);assertPendingRestored(copy)
                    val accounts=copy.accounts();val movements=copy.walletMovements(cash.uid)
                    assertEquals(0,LedgerArchive.restore(copy,prepared));assertEquals(accounts,copy.accounts());assertEquals(movements,copy.walletMovements(cash.uid))
                }
            }
        } finally {
            saved?.let(app.store::delete);app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) };app.tasks.delete(task)
            app.store.setDefaultWallet(settings.defaultAccountUid);app.store.setAppWallet(PaymentApps.WECHAT,settings.appBindings[PaymentApps.WECHAT])
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(cash.uid));app.store.deleteAccount(cash.id)
            copies.forEach(app::deleteDatabase);archive.delete()
        }
    }

    @Test fun missingNodeReceiptAndSharedIconSurviveReopenAndBasicArchiveWithoutReplay()=runBlocking<Unit> {
        app.taskRecovery.await()
        val settings=app.store.walletSettings()
        val wallet=app.store.saveAccount(LedgerAccount(name="合成缺图归档零钱",amountCents=20000,kind="wechat"))
        app.store.setAppWallet(PaymentApps.WECHAT,wallet.uid)
        val merchant="合成共享图标-${UUID.randomUUID()}"
        val tasks=mutableListOf<Long>();val records=mutableListOf<Long>();val operations=mutableListOf<String>()
        var calls=0
        val noOcr=RecognitionBackend { _,_,_,_,_,_->calls++;error("node capture must not invoke OCR") }
        val bitmap=android.graphics.Bitmap.createBitmap(12,12,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val image=try { java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray() } finally { bitmap.recycle() }
        val copyName="t13-restored-${UUID.randomUUID()}.db"
        val archive=java.io.File(app.cacheDir,"t13-${UUID.randomUUID()}.zip")
        try {
            val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm").also(tasks::add);operations+=app.tasks.operationKey(task)!!
            val snapshot=QualifiedWechatNodeFixture.page(merchant=merchant)
            val processor=CaptureProcessor(app,OcrClient(app,noOcr),{time})
            assertTrue(app.tasks.queuedWithoutScreenshot(task,snapshot,processor.prepareNodeFallback(task,snapshot,PaymentApps.WECHAT)!!,
                net.ankio.ledger.capture.ScreenshotFailureReason.TIMEOUT))
            RecognitionTasks(app).use { reopened ->
                assertTrue(reopened.hasMissingNodeEvidence(task));assertNull(reopened.image(task))
                assertEquals(snapshot.fingerprint,reopened.nodeSnapshot(task)!!.fingerprint);assertEquals(time,reopened.recognitionTime(task))
            }
            val original=(processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,task) { true } as IngestResult.Saved).record.also { records+=it.id }
            assertEquals(RecordState.POSTED,original.state);assertNull(app.store.merchantIcon(original.id));assertEquals(0,calls)
            assertEquals(IngestResult.DuplicateSkipped,processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,task) { true })
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==original.uid })
            val imported=app.tasks.create(Source.IMAGE,"image").also(tasks::add);operations+=app.tasks.operationKey(imported)!!
            assertTrue(app.tasks.queued(imported,image))
            val ordinaryOcr=RecognitionBackend { bytes,prepared,_,_,_,_->
                calls++;prepared(bytes)
                RecognitionResult("合成可靠商家付款成功",ParsedBill(200,Direction.EXPENSE,merchant,"其他",time.recognitionAt,"SYNTHETIC-ICON-${UUID.randomUUID()}",true,"",true,
                    receipt=ReceiptEvidence(merchant=ReceiptField(merchant,merchant))),bytes,merchantIcon=image)
            }
            val ocr=(CaptureProcessor(app,OcrClient(app,ordinaryOcr),{time}).processPrepared(image,Source.IMAGE,"image",imported) { true } as IngestResult.Saved).record.also { records+=it.id }
            assertEquals(1,calls);assertArrayEquals(image,app.store.merchantIcon(original.id));assertArrayEquals(image,app.store.merchantIcon(ocr.id))
            app.tasks.delete(task)
            archive.outputStream().use { LedgerArchive.write(app.store,app.channels,it) }
            LedgerStore(app,copyName).use { copy ->
                LedgerArchive.read(archive.inputStream(),app.cacheDir).use { prepared ->
                    assertTrue(LedgerArchive.restore(copy,prepared)>0)
                    val restored=copy.all().single { it.uid==original.uid }
                    assertEquals("TIMEOUT",restored.bill.receipt!!.recognition!!.screenshot.state)
                    assertEquals("NODE",restored.bill.receipt!!.recognition!!.route)
                    assertEquals(time.recognitionAt,restored.bill.occurredAt);assertNull(copy.screenshot(restored.id));assertArrayEquals(image,copy.merchantIcon(restored.id))
                    val accounts=copy.accounts();val movements=copy.walletMovements(wallet.uid)
                    assertEquals(0,LedgerArchive.restore(copy,prepared));assertEquals(accounts,copy.accounts());assertEquals(movements,copy.walletMovements(wallet.uid))
                }
                copy.all().forEach { copy.delete(it.id) }
            }
            assertEquals(1,calls)
        } finally {
            records.forEach(app.store::delete);operations.forEach { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) };tasks.forEach(app.tasks::delete)
            app.store.setDefaultWallet(settings.defaultAccountUid);app.store.setAppWallet(PaymentApps.WECHAT,settings.appBindings[PaymentApps.WECHAT])
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
            app.deleteDatabase(copyName);archive.delete()
        }
    }

    @Test fun anotherImageCannotBorrowSavedNodeAuthorizationOrCreateAnOcrFallbackBill()=runBlocking<Unit> {
        app.taskRecovery.await()
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm")
        val records=app.store.all();val accounts=app.store.accounts()
        var calls=0
        val backend=RecognitionBackend { _,_,_,_,_,_->calls++;error("mismatched evidence must stop") }
        try {
            assertTrue(app.tasks.queued(task,"synthetic-original-image".toByteArray(),snapshot()))
            assertNotNull(runCatching { CaptureProcessor(app,OcrClient(app,backend),{time},registry())
                .processPrepared("synthetic-different-image".toByteArray(),Source.OCR,"com.tencent.mm",task) { true } }.exceptionOrNull())
            assertEquals(0,calls);assertEquals("FAILED",app.tasks.status(task))
            assertEquals(records,app.store.all());assertEquals(accounts,app.store.accounts())
        } finally { app.tasks.delete(task) }
    }

    /** Real task/receipt history through the packaged WebView, not a browser bridge fixture. */
    @Test fun missingNodeTaskAndBillViewsKeepSupplementalImagesAndDoNotChangeFunds()=runBlocking<Unit> {
        app.taskRecovery.await()
        val uiTime=RecognitionTimeContext(System.currentTimeMillis(),"Asia/Shanghai")
        val settings=app.store.walletSettings()
        val cash=app.store.saveAccount(LedgerAccount(name="合成缺图界面现金",amountCents=30000,kind="cash"))
        val accounts=mutableListOf(cash);val tasks=mutableListOf<Long>();val records=mutableSetOf<Long>()
        app.store.setDefaultWallet(cash.uid);app.store.setAppWallet(PaymentApps.WECHAT,null)
        val bitmap=android.graphics.Bitmap.createBitmap(100,200,android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(50,140,110))
        }
        val image=try { java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray() } finally { bitmap.recycle() }
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->error("node UI acceptance must not invoke OCR") }),{uiTime})
        fun task()=app.tasks.create(Source.OCR,"shake:${PaymentApps.WECHAT}").also(tasks::add)
        fun readOnlyView(block:()->Unit) {
            val balances=app.store.accounts().associate { it.uid to it.amountCents }
            val movements=app.store.walletSnapshot().movements
            block()
            assertEquals("Viewing evidence must not change balances",balances,app.store.accounts().associate { it.uid to it.amountCents })
            assertEquals("Viewing evidence must not create wallet movements",movements,app.store.walletSnapshot().movements)
        }
        fun screenshot(name:String,web:WebUiProbe) {
            web.await("!document.querySelector('.toast')?.classList.contains('show') && !document.querySelector('#record-panel').classList.contains('fluid-active') && !document.querySelector('#sheet').classList.contains('fluid-active')")
            web.run("window.__t13Painted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>{window.__t13Painted=true}))")
            web.await("window.__t13Painted")
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val drawn=java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<net.ankio.ledger.ui.MainActivity>().single()
                val view=activity.webView
                view.postVisualStateCallback(0,object:android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId:Long) {
                        val listener=object:android.view.ViewTreeObserver.OnDrawListener {
                            override fun onDraw() { view.post { view.viewTreeObserver.removeOnDrawListener(this);drawn.countDown() } }
                        }
                        view.viewTreeObserver.addOnDrawListener(listener);view.postInvalidateOnAnimation()
                    }
                })
            }
            assertTrue("WebView visual state must reach an Android draw",drawn.await(10,java.util.concurrent.TimeUnit.SECONDS))
            val captured=instrumentation.uiAutomation.takeScreenshot()
            try {
                val file=java.io.File(app.cacheDir,"t13-native-ui/$name.png");file.parentFile!!.mkdirs()
                file.outputStream().use { assertTrue(captured.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                java.io.File(file.parentFile,"$name.json").writeText(web.text("JSON.stringify({snapshot:JSON.parse(LedgerNative.snapshot()),record:draft.id?JSON.parse(LedgerNative.recordDetails(draft.id)).record:null,origin:location.origin,csp:document.querySelector('meta[http-equiv=\"Content-Security-Policy\"]')?.content,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio},visibleEvidence:document.querySelector('#receipt-provenance')?.innerText||''})"))
            } finally { captured.recycle() }
        }
        try {
            androidx.test.core.app.ActivityScenario.launch(net.ankio.ledger.ui.MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                assertEquals("https://appassets.androidplatform.net",web.text("location.origin"))
                fun open(script:String) {
                    web.run("if(document.querySelector('#sheet').open)closeSheet(false);if(recordPanelOpen||recordPanelClosing)closeRecordPanel(false);cancelEditor(true);refresh();$script")
                    web.await("document.querySelector('#record-panel').open && !document.querySelector('#record-panel').hidden && !recordPanelClosing && !document.querySelector('#record-panel').classList.contains('fluid-active')")
                }
                fun assertMissing(state:String) {
                    assertEquals(state,web.text("draft.recognitionEvidence.screenshot.state"))
                    assertEquals("true",web.value("document.querySelector('#receipt-provenance').innerText.includes('页面节点') && document.querySelector('#receipt-provenance').innerText.includes('截图')"))
                    assertEquals("0",web.value("document.querySelectorAll('#capture-gallery .history-image:not(:disabled)').length"))
                    web.run("document.querySelector('.scanner').click()")
                    assertEquals("false",web.value("document.querySelector('#sheet').open && document.querySelector('#sheet').classList.contains('capture-viewer')"))
                    web.await("!document.querySelector('.toast')?.classList.contains('show')")
                }
                val missing=mutableListOf<Pair<Long,LedgerRecord>>()
                for(reason in listOf(net.ankio.ledger.capture.ScreenshotFailureReason.UNAVAILABLE,net.ankio.ledger.capture.ScreenshotFailureReason.TIMEOUT)) {
                    if(reason==net.ankio.ledger.capture.ScreenshotFailureReason.TIMEOUT) {
                        val wechat=app.store.saveAccount(LedgerAccount(name="合成缺图界面零钱",amountCents=30000,kind="wechat")).also(accounts::add)
                        app.store.setAppWallet(PaymentApps.WECHAT,wechat.uid)
                    }
                    val id=task();val snapshot=QualifiedWechatNodeFixture.page(amount=if(reason==net.ankio.ledger.capture.ScreenshotFailureReason.UNAVAILABLE)"12.34" else "23.45",
                        merchant="合成缺图界面${reason.name}-${UUID.randomUUID()}")
                    assertTrue(app.tasks.queuedWithoutScreenshot(id,snapshot,processor.prepareNodeFallback(id,snapshot,PaymentApps.WECHAT)!!,reason))
                    // Keep this genuine in-flight task out of the application queue while its UI is inspected.
                    app.tasks.markNodeRecognizing(id);app.changed()
                    readOnlyView {
                        open("openTask($id)");web.await("draft.recognitionTaskId===$id")
                        assertMissing(reason.name);screenshot("task-${reason.name.lowercase()}",web)
                    }
                    val saved=(processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,id) { true } as IngestResult.Saved).record
                    records+=saved.id;missing+=id to saved
                    assertEquals(if(reason==net.ankio.ledger.capture.ScreenshotFailureReason.UNAVAILABLE)RecordState.REVIEW else RecordState.POSTED,saved.state)
                    assertEquals(listOf(false),app.store.captureHistory(saved.id).map { it.hasScreenshot })
                    readOnlyView {
                        open("openTask($id)");web.await("draft.id===${saved.id} && !draft.recognitionTaskId")
                        assertMissing(reason.name)
                        assertEquals("1",web.value("draft.history.length"))
                        assertEquals("false",web.value("draft.hasScreenshot || draft.history[0].hasScreenshot"))
                        assertEquals(if(saved.state==RecordState.REVIEW)"待确认" else "已入账",web.text("document.querySelector('#import .pending').textContent"))
                        screenshot("bill-${reason.name.lowercase()}",web)
                    }
                }

                // The original missing capture remains missing; a later real image belongs to history item 2.
                val (supplementTask,original)=missing.last()
                val supplement=app.store.ingest(original.capture.copy(receivedAt=uiTime.recognitionAt+1000),original.bill,screenshot=image) as IngestResult.Saved
                app.changed()
                assertEquals(original.id,supplement.record.id)
                assertEquals(listOf(false,true),app.store.captureHistory(original.id).map { it.hasScreenshot })
                assertNull(app.store.screenshot(original.id))
                readOnlyView {
                    open("openTask($supplementTask)");web.await("draft.id===${original.id} && draft.history.length===2")
                    assertEquals("TIMEOUT",web.text("draft.recognitionEvidence.screenshot.state"))
                    assertEquals("false",web.value("draft.hasScreenshot"))
                    web.run("document.querySelector('[data-history-sequence=\"1\"]').click()")
                    assertEquals("false",web.value("document.querySelector('#sheet').open && document.querySelector('#sheet').classList.contains('capture-viewer')"))
                    web.await("!document.querySelector('.toast')?.classList.contains('show')")
                    web.await("document.querySelector('[data-history-sequence=\"2\"] img')?.naturalWidth>0")
                    web.run("document.querySelector('[data-history-sequence=\"2\"]').click()")
                    web.await("document.querySelector('#sheet').open && document.querySelector('#sheet-title').textContent.includes('补充截图') && document.querySelector('#sheet .capture-full')?.naturalWidth>0 && document.querySelector('#sheet .capture-full').src.includes('/record-history/${original.id}/2.jpg')")
                    screenshot("supplement-retained-image",web)
                }

                val availableTask=task();val availableSnapshot=QualifiedWechatNodeFixture.page(amount="34.56",merchant="合成有图界面-${UUID.randomUUID()}")
                assertTrue(app.tasks.queued(availableTask,image,availableSnapshot))
                val available=(processor.processPrepared(image,Source.OCR,PaymentApps.WECHAT,availableTask) { true } as IngestResult.Saved).record
                records+=available.id
                val legacy=(app.store.ingest(Capture(Source.IMAGE,"image","合成历史无识别元数据",uiTime.recognitionAt),
                    ParsedBill(99,Direction.EXPENSE,"合成历史界面","其他",uiTime.recognitionAt,"SYNTHETIC-LEGACY-${UUID.randomUUID()}",true,"",true),screenshot=image) as IngestResult.Saved).record
                records+=legacy.id
                for((record,isLegacy) in listOf(available to false,legacy to true))readOnlyView {
                    open("openRecord(${record.id})");web.await("draft.id===${record.id}")
                    assertEquals("true",web.value("draft.hasScreenshot && draft.history[0].hasScreenshot"))
                    assertEquals("0",web.value("document.querySelectorAll('#receipt-provenance .receipt-missing-screenshot-badge').length"))
                    if(isLegacy)assertEquals("true",web.value("draft.recognitionEvidence==null"))
                    else assertEquals("AVAILABLE",web.text("draft.recognitionEvidence.screenshot.state"))
                    web.await("document.querySelector('[data-history-sequence=\"1\"] img')?.naturalWidth>0")
                    web.run("document.querySelector('[data-history-sequence=\"1\"]').click()")
                    web.await("document.querySelector('#sheet').open && document.querySelector('#sheet .capture-full')?.naturalWidth>0")
                    screenshot(if(isLegacy)"legacy-retained-image" else "available-node-retained-image",web)
                }

                // Capacity eviction removes pixels, while each old history item's text and time remain readable.
                app.screenshots.cleanup(maxBytes=0);app.changed()
                for((record,isLegacy) in listOf(available to false,legacy to true)) {
                    val entry=app.store.captureHistory(record.id).single()
                    assertFalse(entry.hasScreenshot);assertNull(app.store.screenshot(record.id))
                    readOnlyView {
                        open("openRecord(${record.id})");web.await("draft.id===${record.id} && draft.history.length===1")
                        assertEquals("false",web.value("draft.hasScreenshot || draft.history[0].hasScreenshot"))
                        if(isLegacy)assertEquals("true",web.value("draft.recognitionEvidence==null"))
                        else assertEquals("AVAILABLE",web.text("draft.recognitionEvidence.screenshot.state"))
                        web.run("document.querySelector('[data-history-sequence=\"1\"]').click()")
                        web.await("document.querySelector('#sheet').open && document.querySelector('#sheet .sheet-copy')?.textContent.includes('未保存或已清理')")
                        assertEquals("0",web.value("document.querySelectorAll('#sheet .capture-full').length"))
                        web.run("document.querySelector('#capture-description summary').click()")
                        assertEquals(entry.rawText,web.text("document.querySelector('#capture-description .raw-text').textContent"))
                        assertEquals("true",web.value("document.querySelector('#capture-description .capture-caption').textContent.includes(localTime(${entry.at}).replace('T',' '))"))
                        screenshot(if(isLegacy)"legacy-evicted-image-text" else "available-node-evicted-image-text",web)
                    }
                }
            }
        } finally {
            records.forEach(app.store::delete)
            tasks.forEach { id ->app.tasks.operationKey(id)?.let { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) };app.tasks.delete(id) }
            app.store.setDefaultWallet(settings.defaultAccountUid);app.store.setAppWallet(PaymentApps.WECHAT,settings.appBindings[PaymentApps.WECHAT])
            accounts.forEach { account ->app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(account.uid));app.store.deleteAccount(account.id) }
            app.changed()
        }
    }

    @Test fun unknownInvalidConflictingAndImportedEvidenceUseExactlyOneOcrAndSharedSubmission()=runBlocking<Unit> {
        app.taskRecovery.await()
        val previous=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成节点回退钱包",amountCents=10000,kind="cash"));app.store.setDefaultWallet(wallet.uid)
        val tasks=mutableListOf<Long>();val records=mutableListOf<Long>();val operations=mutableListOf<String>()
        val bitmap=android.graphics.Bitmap.createBitmap(100,200,android.graphics.Bitmap.Config.ARGB_8888)
        val image=try { OcrClient(app).prepareScreenshot(bitmap) } finally { bitmap.recycle() }
        var calls=0
        val backend=RecognitionBackend { bytes,prepared,_,_,_,_->
            calls++;prepared(bytes)
            RecognitionResult("合成付款成功",ParsedBill(100,Direction.EXPENSE,"合成回退$calls","其他",time.recognitionAt,
                "SYNTHETIC-NODE-FALLBACK-${UUID.randomUUID()}",true,"",true),bytes)
        }
        try {
            val cases=listOf("unknown_layout" to NodeRecognitionRegistry.production,"invalid_candidate" to registry(0),
                "conflicting_candidates" to registry(2),"image_import" to registry())
            cases.forEachIndexed { index,(reason,registry) ->
                val imported=reason=="image_import"
                val task=app.tasks.create(if(imported)Source.IMAGE else Source.OCR,if(imported)"image" else "shake:com.tencent.mm").also(tasks::add)
                operations+=app.tasks.operationKey(task)!!
                assertTrue(app.tasks.queued(task,image,if(imported)null else snapshot()))
                val saved=CaptureProcessor(app,OcrClient(app,backend),{time},registry).processPrepared(image,
                    if(imported)Source.IMAGE else Source.OCR,if(imported)"image" else "com.tencent.mm",task) { true } as IngestResult.Saved
                records+=saved.record.id
                assertEquals(index+1,calls);assertEquals(RecordState.POSTED,saved.record.state)
                assertEquals(reason,app.tasks.recognitionEvidence(task)!!.getString("reason"))
                assertEquals("OCR",app.tasks.recognitionEvidence(task)!!.getString("route"))
                assertEquals(10000L-(index+1)*100,app.store.getAccount(wallet.id)!!.amountCents)
            }
        } finally {
            records.forEach(app.store::delete);operations.forEach { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) }
            tasks.forEach(app.tasks::delete);app.store.setDefaultWallet(previous)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

}
