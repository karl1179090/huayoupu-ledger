package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
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
