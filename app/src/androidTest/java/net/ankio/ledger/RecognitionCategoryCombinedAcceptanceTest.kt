package net.ankio.ledger

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ocr.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit, three-stage isolated acceptance. The driver archives source data before starting a fresh target process. */
class RecognitionCategoryCombinedAcceptanceTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val directory get()=File(app.filesDir,"t15-combined").apply { mkdirs() }
    private val metadata get()=File(directory,"metadata.json")
    private val clock=RecognitionTimeContext(1730611800000L,"America/New_York")
    private fun enabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ledgerCombinedAcceptance")=="yes")
        check(android.os.Build.VERSION.SDK_INT==34 && android.os.Build.MODEL.contains("sdk_gphone"))
        runBlocking { app.taskRecovery.await() }
    }
    private fun save(meta:JSONObject)=metadata.writeText(meta.toString(2))
    private fun taskByOperation(key:String):Long=app.tasks.snapshot(500).let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("operationUID")==key.removePrefix("recognition:") }.getLong("id")
    }
    private fun image():ByteArray {
        val bitmap=Bitmap.createBitmap(1080,1500,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=48f;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
        listOf("支付成功","商户名称：合成组合平台","付款金额：23.45元","商品说明：午餐套餐","付款方式：微信零钱").forEachIndexed { i,text -> canvas.drawText(text,60f,160f+i*170,paint) }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle() }.toByteArray()
    }
    private fun set(web:WebUiProbe,id:String,value:String)=web.run("document.getElementById(${JSONObject.quote(id)}).value=${JSONObject.quote(value)};document.getElementById(${JSONObject.quote(id)}).dispatchEvent(new Event('change',{bubbles:true}))")
    private fun painted(name:String) {
        val drawn=java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
            val view=activity.webView
            view.postVisualStateCallback(0,object:android.webkit.WebView.VisualStateCallback() {
                override fun onComplete(requestId:Long) {
                    view.viewTreeObserver.registerFrameCommitCallback {
                        android.view.Choreographer.getInstance().postFrameCallback {
                            android.view.Choreographer.getInstance().postFrameCallback { drawn.countDown() }
                        }
                    };view.postInvalidateOnAnimation()
                }
            })
        }
        assertTrue(drawn.await(10,java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
    }

    @Test fun aConfigureCompoundRuleAndSubmitQualifiedNodes()=runBlocking<Unit> {
        enabled();assertFalse(metadata.exists());assertTrue(app.store.all().isEmpty())
        val run=UUID.randomUUID().toString().take(8)
        val wallet=app.store.saveAccount(LedgerAccount(name="合成组合零钱",amountCents=100000,kind="wechat"))
        app.store.setAppWallet(PaymentApps.WECHAT,wallet.uid);app.store.setDefaultWallet(wallet.uid)
        val categoryName="合成组合用途$run";val renamed="合成组合午餐$run";val ruleName="合成组合ALL$run"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("openCategoryManager('expense')")
            set(web,"new-category-name",categoryName);web.run("document.querySelector('#save-category').click()")
            web.await("Array.from(document.querySelectorAll('[data-category-row]')).some(e=>e.dataset.categoryName===${JSONObject.quote(categoryName)})")
            val category=app.store.categoryDirectory().single { it.name==categoryName }
            web.run("document.querySelector('[data-edit-category=\"${category.id}\"]').click()")
            set(web,"new-category-name",renamed);web.run("document.querySelector('[data-icon-key=\"utensils\"]')?.click();document.querySelector('#save-category').click()")
            web.await("Array.from(document.querySelectorAll('[data-category-row]')).some(e=>e.dataset.categoryName===${JSONObject.quote(renamed)})")
            web.run("openClassificationRules('EXPENSE')");set(web,"rule-name",ruleName);set(web,"rule-category",category.id)
            set(web,"rule-keyword","合成组合平台");web.run("document.querySelector('#add-rule-condition').click()")
            set(web,"rule-field-1","ORDER_CONTENT");set(web,"rule-keyword-1","午餐套餐")
            web.run("document.querySelector('#save-rule').click()")
            web.await("Array.from(document.querySelectorAll('[data-rule-row]')).some(e=>e.dataset.ruleName===${JSONObject.quote(ruleName)})")
            val rule=app.store.classificationRules().customRules.single { it.name==ruleName }
            assertEquals(ClassificationMatchMode.ALL,rule.matchMode);assertEquals(2,rule.conditions.size)
            val before=app.store.all();val funds=app.store.walletSnapshot();val config=app.store.classificationRules()
            set(web,"test-merchant","合成组合平台");web.run("document.querySelector('#add-test-order-content').click()")
            set(web,"test-order-0","午餐套餐");web.run("document.querySelector('#run-rule-test-btn').click()")
            web.await("document.querySelector('#rule-test-result')?.textContent.includes(${JSONObject.quote(renamed)})")
            assertEquals(before,app.store.all());assertEquals(funds,app.store.walletSnapshot());assertEquals(config,app.store.classificationRules())
            web.run("closeSheet()")
            val bytes=image();File(directory,"c1-image.png").writeBytes(bytes)
            val task=app.tasks.create(Source.OCR,"shake:${PaymentApps.WECHAT}")
            val page=QualifiedWechatNodeFixture.page(merchant="合成组合平台",content="午餐套餐")
            assertTrue(app.tasks.queued(task,bytes,page))
            val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->error("qualified fixture cannot invoke OCR") }),{clock})
            val record=(processor.processPrepared(bytes,Source.OCR,"shake:${PaymentApps.WECHAT}",task) { true } as IngestResult.Saved).record
            assertEquals(RecordState.POSTED,record.state);assertEquals(category.id,record.bill.categoryDecision!!.categoryId)
            assertEquals(CategorySource.CUSTOM_RULE,record.bill.categoryDecision!!.source);assertEquals(clock.recognitionAt,record.bill.occurredAt)
            assertEquals(wallet.uid,record.wallet!!.accountUid);assertEquals(98766L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals("NODE",record.bill.receipt!!.recognition!!.route)
            save(JSONObject().put("run",run).put("sourcePid",Process.myPid()).put("wallet",wallet.uid).put("category",category.id)
                .put("rule",rule.id).put("nodeRecordUid",record.uid).put("nodeSourceId",record.id).put("nodeOperation",app.tasks.operationKey(task)))
        }
    }

    @Test fun bFreezePartialAllSkippedAndMissingNodeStateForCompleteRestore()=runBlocking<Unit> {
        enabled();val meta=JSONObject(metadata.readText()).put("sourcePid",Process.myPid());val prefix="合成组合${meta.getString("run")}";val bytes=image()
        val c1=app.store.all().single { it.bill.amountCents==2345L && it.bill.merchant=="合成组合平台" }
        assertEquals("OCR",c1.bill.receipt!!.recognition!!.route);assertEquals(meta.getString("category"),c1.bill.categoryDecision!!.categoryId)
        assertEquals("RECOGNITION_FALLBACK",c1.bill.receipt!!.time!!.source)
        val bank=app.store.accounts().singleOrNull { it.name=="合成组合招商卡" }
            ?: app.store.saveAccount(LedgerAccount(name="合成组合招商卡",amountCents=100000,kind="bank",bankCode="cmb",cardLast4="0007"))
        assertEquals(100000L,bank.amountCents)
        meta.put("bank",bank.uid)
        val payment=PaymentMethodEvidence("BANK_CARD","DEBIT",bankCode="cmb",cardLast4="0007",executed=true)
        val receipt=ReceiptEvidence(merchant=ReceiptField("合成组合平台","合成组合平台"),transactionDescription=ReceiptField("午餐套餐","午餐套餐"),
            bank=BankReceiptEvidence("cmb","0007",ReceiptField("网上快捷支付","交易类型：网上快捷支付"),Direction.EXPENSE),time=TransactionTimeResolver.fallback(clock))
        val identity="SYNTHETIC-T15-${meta.getString("run")}";meta.put("postedIdentity",identity)
        val first=ParsedBill(500,Direction.EXPENSE,"合成组合平台","其他",clock.recognitionAt,identity,true,"",paymentMethod=payment,receipt=receipt)
        val second=first.copy(amountCents=700,transactionId=null)
        val unknown=first.copy(amountCents=900,transactionId=null,paymentMethod=payment.copy(cardLast4="9999"),receipt=receipt.copy(bank=receipt.bank!!.copy(cardLast4="9999")))
        val task=app.tasks.create(Source.IMAGE,"image");app.tasks.queued(task,bytes);app.tasks.beginRecognition(task) { clock }
        val operation=app.tasks.operationKey(task)!!;val frozen=app.tasks.classificationSnapshot(task)!!
        val failed=CandidateId("unfinished");val failedKey=candidateOperationKey(operation,failed)
        app.store.writableDatabase.execSQL("CREATE TRIGGER reject_t15_child BEFORE INSERT ON wallet_submissions WHEN NEW.kind='CAPTURE_RESULT' AND NEW.token='$failedKey' BEGIN SELECT RAISE(ABORT,'synthetic combined receipt failure'); END")
        try {
            val backend=RecognitionBackend { image,prepared,_,_,_,_->prepared(image);RecognitionResult("合成组合三张独立银行卡",listOf(
                TransactionCandidate(CandidateId("posted"),first,"微信交易号：$identity"),
                TransactionCandidate(failed,second,"合成独立银行卡扣款7.00元，尾号0007"),
                TransactionCandidate(CandidateId("review"),unknown,"合成独立银行卡扣款9.00元，尾号9999")),screenshot=image) }
            val result=(CaptureProcessor(app,OcrClient(app,backend),{clock}).processPrepared(bytes,Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            assertEquals(BatchCounts(1,0,1,1),result.counts);assertEquals(99500L,app.store.getAccount(bank.id)!!.amountCents)
            assertNull(app.store.committedCandidate(operation,failed));assertEquals("FAILED",app.tasks.status(task))
            val posted=app.store.get(result.items.single { it.candidateId==CandidateId("posted") }.recordId!!)!!
            val edited=app.store.saveEdited(posted.copy(note="合成人工保留",bill=posted.bill.copy(category="购物",categoryDecision=null)),
                walletAccountUid=bank.uid,manualWalletSelected=true,manualCategorySelected=true)
            assertTrue(edited.wallet!!.manualLocked);assertTrue(edited.bill.categoryDecision!!.manual)
            meta.put("postedUid",edited.uid).put("postedSourceId",edited.id).put("postedCategory",edited.bill.categoryDecision!!.categoryId)
            meta.put("partial",operation).put("partialSourceId",task).put("frozen",ClassificationCodec.snapshot(frozen))
        } finally { app.store.writableDatabase.execSQL("DROP TRIGGER reject_t15_child") }
        val rule=app.store.classificationRules().customRules.single { it.id==meta.getString("rule") }
        app.store.saveClassificationRule(rule.copy(enabled=false))
        val replacement=app.store.saveCategory(name="$prefix 替代",direction=Direction.EXPENSE)
        val original=meta.getString("category")
        app.store.confirmCategoryDeletion(original,replacement.id,app.store.previewCategoryDeletion(original).previewToken)
        meta.put("replacement",replacement.id).put("replacementName",replacement.name)
        val historyBill=first.copy(amountCents=1100,merchant="$prefix 历史",transactionId="T15-HISTORY-${meta.getString("run")}")
        val history=(app.store.ingest(Capture(Source.MANUAL,"manual","合成历史",clock.recognitionAt),historyBill) as IngestResult.Saved).record
        val skippedTask=app.tasks.create(Source.IMAGE,"image");app.tasks.queued(skippedTask,bytes)
        val skipped=(CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_->prepared(image);RecognitionResult("合成全部跳过",
            listOf("skip_a","skip_b").map { TransactionCandidate(CandidateId(it),historyBill.copy(merchant="$prefix 待跳过",transactionId=null)) },screenshot=image) }),{clock})
            .processPrepared(bytes,Source.IMAGE,"image",skippedTask) { true } as IngestResult.Batch).result
        assertEquals(BatchCounts(0,2,0,0),skipped.counts)
        meta.put("allSkipped",app.tasks.operationKey(skippedTask)).put("historyUid",history.uid)
        val missing=JSONArray()
        val merchant="$prefix 无图共享"
        for((index,reason) in listOf(ScreenshotFailureReason.UNAVAILABLE,ScreenshotFailureReason.TIMEOUT).withIndex()) {
            val node=app.tasks.create(Source.OCR,"shake:${PaymentApps.WECHAT}",ShakeAuthorization(PaymentApps.WECHAT,7,9))
            val page=QualifiedWechatNodeFixture.page(amount=if(index==0)"3.21" else "4.32",merchant=if(index==0)merchant else "$prefix 无图默认")
            val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->error("missing nodes cannot invoke OCR") }),{clock})
            assertTrue(app.tasks.queuedWithoutScreenshot(node,page,processor.prepareNodeFallback(node,page,PaymentApps.WECHAT)!!,reason))
            val saved=(processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,node) { true } as IngestResult.Saved).record
            assertNull(app.store.screenshot(saved.id));assertEquals(RecordState.POSTED,saved.state)
            missing.put(JSONObject().put("operation",app.tasks.operationKey(node)).put("uid",saved.uid).put("reason",reason.name).put("fingerprint",page.fingerprint))
        }
        val iconTask=app.tasks.create(Source.IMAGE,"image")
        val iconBill=first.copy(amountCents=100,merchant=merchant,paymentMethod=PaymentMethodEvidence("WECHAT_BALANCE","DEBIT",executed=true),
            receipt=ReceiptEvidence(merchant=ReceiptField(merchant,merchant)),transactionId="T15-ICON-${meta.getString("run")}")
        CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_->prepared(image);RecognitionResult("合成共享图标凭证",iconBill,screenshot=image,merchantIcon=image) }),{clock})
            .processPrepared(bytes,Source.IMAGE,"image",iconTask) { true }
        assertArrayEquals(bytes,app.store.merchantIcon(app.store.all().single { it.uid==missing.getJSONObject(0).getString("uid") }.id))
        meta.put("missing",missing).put("sourceSnapshot",app.tasks.snapshot(500)).put("sourceAccounts",JSONArray(app.store.accounts().map { JSONObject().put("uid",it.uid).put("cents",it.amountCents) }))
        meta.put("sourceRecordUids",JSONArray(app.store.all().map { it.uid })).put("clockAt",clock.recognitionAt).put("clockZone",clock.zoneId)
        File(directory,"complete.zip").outputStream().use { LedgerArchive.write(app.store,app.channels,it) }
        File(directory,"complete.json").writeText(LedgerBackup.encode(app.store,app.channels))
        save(meta)
    }

    @Test fun cRestoreIntoAnotherProcessAndLedgerThenRetryOnlyTheUnfinishedChild()=runBlocking<Unit> {
        enabled();val meta=JSONObject(metadata.readText());assertNotEquals(meta.getInt("sourcePid"),Process.myPid())
        assertTrue(app.store.all().isEmpty())
        repeat(5) { index -> app.store.ingest(Capture(Source.MANUAL,"manual","合成目标占位$index",clock.recognitionAt),
            ParsedBill(10L+index,Direction.INCOME,"合成目标占位$index","其他",clock.recognitionAt,"T15-PLACEHOLDER-$index",true,"",true)) }
        repeat(12) { app.tasks.create(Source.IMAGE,"image").also { id->app.tasks.update(id,"NO_BILL","合成目标占位") } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }
                .invoke(activity,MainActivity.RESTORE_REQUEST,Activity.RESULT_OK,Intent().setData(Uri.fromFile(File(directory,"complete.zip")))) }
            val deadline=android.os.SystemClock.elapsedRealtime()+10_000
            var clicked=false
            while(!clicked && android.os.SystemClock.elapsedRealtime()<deadline) {
                fun visit(node:android.view.accessibility.AccessibilityNodeInfo) {
                    if(node.text?.toString()=="恢复" && node.isClickable)clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    else for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
                if(!clicked)android.os.SystemClock.sleep(25)
            }
            assertTrue("Actual native restore confirmation",clicked)
            web.await("document.querySelector('.toast')?.textContent.includes('新增') && document.querySelector('.toast')?.textContent.includes('采集保持暂停')")
            val partial=meta.getString("partial");val task=taskByOperation(partial)
            assertNotEquals(meta.getLong("partialSourceId"),task)
            assertEquals(clock,app.tasks.recognitionTime(task));assertEquals(ClassificationCodec.readSnapshot(meta.getJSONObject("frozen")),app.tasks.classificationSnapshot(task))
            val posted=app.store.all().single { it.uid==meta.getString("postedUid") }
            assertNotEquals(meta.getLong("postedSourceId"),posted.id);assertEquals(meta.getString("postedCategory"),posted.bill.categoryDecision!!.categoryId)
            assertTrue(posted.bill.categoryDecision!!.manual);assertTrue(posted.wallet!!.manualLocked);assertEquals("合成人工保留",posted.note)
            assertEquals(BatchCounts(1,0,1,1),app.store.recognitionBatch(partial)!!.counts)
            val missing=meta.getJSONArray("missing")
            for(i in 0 until missing.length()) {
                val item=missing.getJSONObject(i);val node=taskByOperation(item.getString("operation"));val record=app.store.all().single { it.uid==item.getString("uid") }
                assertNull(app.tasks.shakeAuthorization(node));assertTrue(app.tasks.hasMissingNodeEvidence(node));assertNull(app.tasks.image(node));assertNull(app.store.screenshot(record.id))
                assertEquals(item.getString("fingerprint"),app.tasks.nodeSnapshot(node)!!.fingerprint)
                assertEquals(item.getString("reason"),record.bill.receipt!!.recognition!!.screenshot.state)
                if(i==0)assertNotNull(app.store.merchantIcon(record.id)) else assertNull(app.store.merchantIcon(record.id))
            }
            assertFalse(app.store.categoryDirectory().any { it.id==meta.getString("category") })
            val funds=app.store.accounts();val movements=funds.associate { it.uid to app.store.walletMovements(it.uid) }
            LedgerArchive.read(File(directory,"complete.zip").inputStream(),app.cacheDir).use { LedgerArchive.restoreDetailed(app.store,it) }
            assertEquals(funds,app.store.accounts());assertEquals(movements,funds.associate { it.uid to app.store.walletMovements(it.uid) })
            val history=app.store.all().single { it.uid==meta.getString("historyUid") };app.store.delete(history.id)
            val skippedKey=meta.getString("allSkipped")
            assertEquals(BatchCounts(0,2,0,0),app.store.recognitionBatch(skippedKey)!!.counts)
            val newHistory=ParsedBill(700,Direction.INCOME,"合成恢复后新历史","其他",clock.recognitionAt,"T15-LATER-${meta.getString("run")}",true,"",true)
            app.store.ingest(Capture(Source.MANUAL,"manual","合成后来历史",clock.recognitionAt),newHistory)
            val beforeBank=app.store.getAccountByUid(meta.getString("bank"))!!.amountCents
            web.run("LedgerUI.event('refresh');openTask($task)")
            web.await("document.querySelector('#batch-retry-button')&&!document.querySelector('#batch-retry-button').disabled && !document.querySelector('#record-panel').classList.contains('fluid-active')")
            painted("c2-restored-partial")
            web.run("document.querySelector('#batch-retry-button').click()")
            web.await("JSON.parse(LedgerNative.snapshot()).recognitionTasks.find(t=>t.id===$task)?.status==='REVIEW'")
            assertEquals(BatchCounts(2,0,1,0),app.store.recognitionBatch(partial)!!.counts)
            painted("c2-retried-result")
            assertEquals(beforeBank-700,app.store.getAccountByUid(meta.getString("bank"))!!.amountCents)
            val result=app.store.recognitionBatch(partial)!!;val child=result.items.single { it.candidateId==CandidateId("unfinished") }
            val new=app.store.get(child.recordId!!)!!
            assertEquals(meta.getString("replacement"),new.bill.categoryDecision!!.categoryId);assertEquals(clock.recognitionAt,new.bill.occurredAt)
            assertEquals(clock.zoneId,new.bill.receipt!!.time!!.zoneId);assertEquals("RECOGNITION_FALLBACK",new.bill.receipt!!.time!!.source)
            assertEquals(1,app.store.walletMovements(meta.getString("bank")).count { it.recordUid==new.uid && it.kind=="POST" })
            assertEquals(posted,app.store.get(posted.id));assertEquals(posted.id,result.items.single { it.candidateId==CandidateId("posted") }.recordId)
            val review=app.store.get(result.items.single { it.candidateId==CandidateId("review") }.recordId!!)!!
            assertEquals(RecordState.REVIEW,review.state);assertNull(review.wallet!!.accountUid);assertTrue(review.walletEffects.isEmpty())
            val after=app.store.accounts();val afterMovements=app.store.walletMovements(meta.getString("bank"))
            LedgerArchive.read(File(directory,"complete.zip").inputStream(),app.cacheDir).use { LedgerArchive.restoreDetailed(app.store,it) }
            assertEquals(after,app.store.accounts());assertEquals(afterMovements,app.store.walletMovements(meta.getString("bank")))
            assertEquals(BatchCounts(0,2,0,0),app.store.recognitionBatch(skippedKey)!!.counts)
            assertEquals(BatchCounts(2,0,1,0),app.store.recognitionBatch(partial)!!.counts)
            val supplementTask=app.tasks.create(Source.IMAGE,"image")
            val supplementImage=File(directory,"c1-image.png").readBytes()
            val laterClock=clock.copy(recognitionAt=clock.recognitionAt+172800000,zoneId="Asia/Shanghai")
            val supplement=posted.bill.copy(occurredAt=laterClock.recognitionAt,category="其他",categoryDecision=null,
                receipt=posted.bill.receipt!!.copy(time=TransactionTimeResolver.fallback(laterClock)))
            val supplemented=(CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_->prepared(image)
                RecognitionResult("微信交易号：${meta.getString("postedIdentity")}",listOf(TransactionCandidate(CandidateId("same_order"),supplement)),screenshot=image) }),
                {laterClock})
                .processPrepared(supplementImage,Source.IMAGE,"image",supplementTask) { true } as IngestResult.Batch).result
            assertEquals(BatchCounts(0,1,0,0),supplemented.counts);assertEquals(posted.uid,supplemented.items.single().recordUID)
            assertEquals(posted.id,supplemented.items.single().recordId)
            val protected=app.store.get(posted.id)!!
            assertEquals(posted.bill.categoryDecision,protected.bill.categoryDecision);assertEquals(posted.bill.occurredAt,protected.bill.occurredAt)
            assertTrue(protected.wallet!!.manualLocked);assertEquals(posted.wallet!!.accountUid,protected.wallet!!.accountUid)
            assertEquals(posted.wallet!!.appliedCents,protected.wallet!!.appliedCents);assertEquals(posted.note,protected.note)
            assertEquals(after,app.store.accounts());assertEquals(afterMovements,app.store.walletMovements(meta.getString("bank")))
            web.run("LedgerUI.event('refresh');openTask($task)")
            web.await("!!document.querySelector('[data-nav-record=\"${posted.id}\"]')")
            web.run("document.querySelector('[data-nav-record=\"${posted.id}\"]').click()")
            web.await("draft?.id===${posted.id} && !recordPanelClosing && !document.querySelector('#record-panel').classList.contains('fluid-active')")
            assertEquals(posted.uid,app.store.get(posted.id)!!.uid)
            web.run("document.querySelector('#note').value='合成未保存草稿';document.querySelector('#note').dispatchEvent(new Event('input',{bubbles:true}));LedgerUI.event('refresh')")
            assertEquals("合成未保存草稿",web.text("document.querySelector('#note').value"));assertEquals("合成人工保留",app.store.get(posted.id)!!.note)
            painted("c2-original-uid-navigation-draft")
            val missingRecord=app.store.all().single { it.uid==missing.getJSONObject(0).getString("uid") }
            web.run("if(recordPanelOpen||recordPanelClosing)closeRecordPanel(false);cancelEditor(true);LedgerUI.event('openRecord',${missingRecord.id})")
            web.await("draft?.id===${missingRecord.id} && !recordPanelClosing && !document.querySelector('#record-panel').classList.contains('fluid-active')")
            assertEquals("true",web.value("document.querySelector('#receipt-provenance').innerText.includes('页面节点') && document.querySelector('.scanner').classList.contains('missing-screenshot')"))
            assertEquals("0",web.value("document.querySelectorAll('#capture-gallery .history-image:not(:disabled)').length"))
            painted("c3-restored-node-missing-image")
            File(directory,"target-result.json").writeText(JSONObject().put("sourcePid",meta.getInt("sourcePid")).put("targetPid",Process.myPid())
                .put("partial",app.tasks.snapshot(500)).put("postedUid",posted.uid).put("postedTargetId",posted.id).put("unfinishedUid",new.uid)
                .put("bankBeforeRetry",beforeBank).put("bankAfterRetry",app.store.getAccountByUid(meta.getString("bank"))!!.amountCents)
                .put("bankPostMovements",JSONArray(afterMovements.map { it.toString() })).put("allSkippedPreserved",true)
                .put("missingImagesAndIconsPreservedWithoutAuthorization",true).toString(2))
        }
    }

    @Test fun dReadOnlyRestoredResultNavigationAndMissingImageAreVisiblySettled() {
        enabled();val meta=JSONObject(metadata.readText());val result=JSONObject(File(directory,"target-result.json").readText())
        val before=app.store.all();val accounts=app.store.accounts();val config=app.store.classificationBackup()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            fun settled()=web.await("document.querySelector('#record-panel').open && !recordPanelClosing && !document.querySelector('#record-panel').classList.contains('fluid-active') && !document.querySelector('#record-panel')._morph && !document.querySelector('.toast').classList.contains('show')")
            fun open(script:String) {
                web.run("if(document.querySelector('#sheet').open)closeSheet(false);if(recordPanelOpen||recordPanelClosing)closeRecordPanel(false);cancelEditor(true);LedgerUI.event('refresh');$script")
                settled()
            }
            val task=taskByOperation(meta.getString("partial"));open("openTask($task)")
            web.await("activeBatch?.counts.added===2 && activeBatch?.counts.review===1 && activeBatch?.counts.failed===0")
            assertEquals("0",web.value("document.querySelectorAll('#batch-retry-button').length"))
            painted("c2-final-steady-result")
            val posted=app.store.all().single { it.uid==result.getString("postedUid") }
            web.run("document.querySelector('[data-nav-record=\"${posted.id}\"]').click()")
            settled();web.await("draft?.id===${posted.id}")
            assertEquals("合成人工保留",web.text("document.querySelector('#note').value"))
            painted("c2-final-steady-original-uid-child")
            val missing=meta.getJSONArray("missing")
            for(i in 0 until missing.length()) {
                val item=missing.getJSONObject(i);val record=app.store.all().single { it.uid==item.getString("uid") }
                open("LedgerUI.event('openRecord',${record.id})")
                web.await("draft?.id===${record.id} && draft.recognitionEvidence.screenshot.state===${JSONObject.quote(item.getString("reason"))}")
                assertEquals("true",web.value("document.querySelector('.scanner').classList.contains('missing-screenshot') && document.querySelector('#receipt-provenance').innerText.includes('页面节点')"))
                assertEquals("0",web.value("document.querySelectorAll('#capture-gallery .history-image:not(:disabled)').length"))
                painted("c3-final-steady-${item.getString("reason").lowercase()}")
            }
            File(directory,"ui-steady-result.json").writeText(JSONObject().put("actualVisualStateCallback",true).put("hardwareFrameCommitted",true)
                .put("twoLaterFrameCallbacks",true).put("morphAndToastSettled",true).put("readOnly",true).put("postedTargetId",posted.id)
                .put("missingStates",JSONArray(listOf("UNAVAILABLE","TIMEOUT"))).toString(2))
        }
        assertEquals(before,app.store.all());assertEquals(accounts,app.store.accounts());assertEquals(config,app.store.classificationBackup())
    }
}
