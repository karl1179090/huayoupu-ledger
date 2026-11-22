package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.UUID

class CompleteBatchRestoreTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun allSkippedTaskRetainsItsFrozenConclusionAfterHistoryDeletionAndRepeatedMerge() {
        val prefix="skipped-${UUID.randomUUID()}";val media=ScreenshotStore(app,prefix)
        val source=LedgerStore(app,"$prefix-source.db",media);val tasks=RecognitionTasks(app,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(app,"$prefix-target.db",media);val restored=RecognitionTasks(app,"$prefix-target-tasks.db",media,target)
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成跳过钱包",amountCents=5000));source.setDefaultWallet(wallet.uid)
            val bill=ParsedBill(500,Direction.EXPENSE,"合成历史","其他",1730611800000L,null,true,"",true)
            source.ingest(Capture(Source.MANUAL,"manual","合成历史",1730611800000L),bill)
            val id=tasks.create(Source.IMAGE,"image");tasks.queued(id,syntheticBackupImage(148));val key=tasks.operationKey(id)!!
            source.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成全部跳过",1730611800000L),listOf("a","b").map { TransactionCandidate(CandidateId(it),bill) },key,"America/New_York",tasks.classificationSnapshot(id)!!,RecognitionPolicy.USER_INITIATED)
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            LedgerBackup.restore(target,backup);target.all().forEach { target.delete(it.id) }
            val restoredTask=restored.pendingQueued().single().first
            assertTrue(RecognitionCommitRecovery.recover(restored,target,restoredTask));assertEquals("SKIPPED",restored.status(restoredTask))
            assertEquals(BatchCounts(0,2,0,0),target.recognitionBatch(key)!!.counts)
            repeat(2) { LedgerBackup.restore(target,backup) }
            assertEquals("SKIPPED",restored.status(restoredTask));assertEquals(BatchCounts(0,2,0,0),target.recognitionBatch(key)!!.counts)
            assertEquals(5000L,target.accounts().single().amountCents)
        } finally { tasks.close();restored.close();source.close();target.close();media.close() }
    }
    @Test fun batchDistinguishesActualRefundFromItsRepeatAfterCompleteBackupRestore() {
        val prefix = "batch-refund-${UUID.randomUUID()}"
        val media = ScreenshotStore(app, prefix)
        val source = LedgerStore(app, "$prefix-source.db", media)
        val tasks = RecognitionTasks(app, "$prefix-source-tasks.db", media, source)
        val target = LedgerStore(app, "$prefix-target.db", media)
        val restored = RecognitionTasks(app, "$prefix-target-tasks.db", media, target)
        try {
            val wallet = source.saveAccount(LedgerAccount(name = "合成批次退款钱包", amountCents = 20_000))
            source.setDefaultWallet(wallet.uid)
            val at = 1730611800000L
            val capture = Capture(Source.IMAGE, PaymentApps.WECHAT, "合成付款", at)
            val bill = ParsedBill(10_000, Direction.EXPENSE, "合成批次退款商家", "其他", at, prefix, true, "", true)
            val original = (source.ingest(capture, bill) as IngestResult.Saved).record
            val task = tasks.create(Source.IMAGE, "image")
            val image = syntheticBackupImage(204)
            tasks.queued(task, image)
            val key = tasks.operationKey(task)!!
            val refund = bill.copy(amountCents = 7000, refund = RefundInfo("wechat", 10_000, 3000))
            val raw = "微信支付 合成退款"
            val candidates = listOf("actual", "repeat").map { TransactionCandidate(CandidateId(it), refund, raw) }
            source.freezeRecognitionBatch(capture.copy(text = raw), candidates, key, "Asia/Shanghai",
                tasks.classificationSnapshot(task)!!, RecognitionPolicy.USER_INITIATED)
            val first = source.submitBatchCandidate(key, candidates.first().id, image)
            assertEquals("UPDATED", first.items.first().status)
            LedgerBackup.restore(target, LedgerBackup.decode(LedgerBackup.encode(source, app.channels).byteInputStream()))
            val result = target.submitBatchCandidate(key, candidates.last().id, image)
            assertEquals(listOf("UPDATED", "SKIPPED"), result.items.map { it.status })
            assertEquals(BatchCounts(0, 1, 0, 0, updated = 1), result.counts)
            assertEquals(1, result.json().getJSONObject("counts").getInt("updated"))
            assertEquals(listOf(original.uid, original.uid), result.items.map { it.recordUID })
            val restoredTask = restored.pendingQueued().single().first
            assertTrue(RecognitionCommitRecovery.recover(restored, target, restoredTask))
            assertEquals("SUCCEEDED", restored.status(restoredTask))
            assertEquals("退款已更新", RecognitionCommitRecovery.immediate(restored, target, restoredTask, IngestResult.Batch(result))?.message)
            assertEquals(13000L, target.accounts().single().amountCents)
            assertEquals(1, target.walletMovements(wallet.uid).count { it.kind == "REFUND" && it.recordUid == original.uid })
        } finally {
            tasks.close(); restored.close(); source.close(); target.close(); media.close()
        }
    }

    @Test fun partialBatchKeepsFrozenDecisionReplacementAndClockThroughProcessorWithoutAnotherOcrOrDebit()=runBlocking {
        app.taskRecovery.await()
        val prefix="partial-${UUID.randomUUID()}";val media=ScreenshotStore(app,prefix)
        val source=LedgerStore(app,"$prefix-source.db",media);val tasks=RecognitionTasks(app,"$prefix-tasks.db",media,source)
        val old=app.store.walletSettings().defaultAccountUid
        var operation:String?=null;var walletUid:String?=null;var importedTask:Long?=null
        var replacementId:String?=null
        try {
            val tail="0512"
            val wallet=source.saveAccount(LedgerAccount(name="合成冻结恢复钱包",amountCents=10000,kind="bank",bankCode="cmb",cardLast4=tail))
            walletUid=wallet.uid;source.setDefaultWallet(wallet.uid)
            val first=source.saveCategory(name="合成待删除${prefix.takeLast(8)}",direction=Direction.EXPENSE)
            val replacement=source.saveCategory(name="合成替代${prefix.takeLast(8)}",direction=Direction.EXPENSE)
            replacementId=replacement.id
            val rule=source.saveClassificationRule(ClassificationRule("","合成冻结规则$prefix",true,Direction.EXPENSE,first.id,0,listOf(ClassificationCondition(value=prefix))))
            val image=syntheticBackupImage(145)
            val id=tasks.create(Source.IMAGE,"image");tasks.queued(id,image)
            val key=tasks.operationKey(id)!!;operation=key
            val time=RecognitionTimeContext(1730611800000L,"America/New_York");tasks.beginRecognition(id) { time }
            val frozen=tasks.classificationSnapshot(id)!!
            fun card(amount:String,offset:Int)=listOf("招商银行","交易成功提醒","交易类型：网上快捷支付",
                "交易金额：${amount}元","交易卡号：尾号$tail","商户名称：$prefix")
                .mapIndexed { index,text -> LocalOcrLine(text,30,offset+index*40,700,offset+index*40+30) }
            val lines=card("5.00",100)+card("7.00",600)
            val candidates=TransactionPageParser.parse(lines,1080,1600,time,origin="image")
            assertEquals(listOf(500L,700L),candidates.map { it.bill.amountCents })
            val (posted,pending)=candidates
            assertNotEquals(posted.id,pending.id)
            assertTrue(candidates.all { it.bill.merchant==prefix && it.bill.paymentMethod?.cardLast4==tail && it.bill.receipt?.time?.source=="RECOGNITION_FALLBACK" })
            val capture=Capture(Source.IMAGE,"image",lines.joinToString("\n") { it.text },time.recognitionAt)
            source.freezeRecognitionBatch(capture,candidates,key,time.zoneId,frozen,RecognitionPolicy.USER_INITIATED)
            source.submitBatchCandidate(key,posted.id,image);source.failBatchCandidate(key,pending.id)
            tasks.update(id,"FAILED","该项保存失败，请重试")
            assertEquals(BatchCounts(1,0,0,1),source.recognitionBatch(key)!!.counts)
            assertEquals(9500L,source.getAccountByUid(wallet.uid)!!.amountCents)
            val postedUid=source.all().single().uid
            fun assertPostedReceipt(store:LedgerStore,candidate:CandidateId,uid:String) {
                val receipt=requireNotNull(store.committedCandidate(key,candidate))
                assertEquals(RecognitionCommitEffect.RECORDED,receipt.effect)
                assertEquals(uid,receipt.recordUid);assertEquals(uid,receipt.record?.uid)
            }
            assertPostedReceipt(source,posted.id,postedUid)
            assertNull(source.committedCandidate(key,pending.id))
            source.deleteClassificationRule(rule.id)
            val before=source.previewCategoryDeletion(first.id)
            assertEquals(1L,before.billCount);assertEquals(0L,before.ruleCount);assertEquals(1L,before.queuedConfigurationCount)
            source.confirmCategoryDeletion(first.id,replacement.id,before.previewToken)
            assertEquals(1L,source.previewCategoryDeletion(replacement.id).queuedConfigurationCount)
            val backup=ByteArrayOutputStream().also { LedgerArchive.write(source,app.channels,it) }.toByteArray()
            fun restore()=LedgerArchive.read(backup.inputStream(),app.cacheDir).use { LedgerArchive.restoreDetailed(app.store,it) }
            restore()
            fun taskRow()=app.tasks.snapshot(500).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }
                .single { it.getString("operationUID")==key.removePrefix("recognition:") } }
            val restoredId=taskRow().getLong("id");importedTask=restoredId
            assertEquals(time,app.tasks.recognitionTime(restoredId));assertEquals(frozen,app.tasks.classificationSnapshot(restoredId))
            assertArrayEquals(image,app.tasks.image(restoredId))
            assertEquals("FAILED",taskRow().getString("status"))
            assertTrue(taskRow().getJSONObject("batch").getBoolean("canRetry"))
            assertEquals(9500L,app.store.getAccountByUid(wallet.uid)!!.amountCents)
            assertEquals(1L,app.store.previewCategoryDeletion(replacement.id).queuedConfigurationCount)
            assertPostedReceipt(app.store,posted.id,postedUid)
            assertNull(app.store.committedCandidate(key,pending.id))
            assertFalse(app.store.categoryDirectory().any { it.id==first.id })
            var ocr=0
            val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->ocr++;error("frozen batch must not run OCR") }),{time.copy(recognitionAt=time.recognitionAt+172800000,zoneId="Asia/Shanghai")})
            val result=processor.processPrepared(app.tasks.image(restoredId)!!,Source.IMAGE,"image",restoredId) { true } as IngestResult.Batch
            assertEquals(BatchCounts(2,0,0,0),result.result.counts);assertEquals(0,ocr)
            assertEquals(8800L,app.store.getAccountByUid(wallet.uid)!!.amountCents)
            val matched=app.store.all().filter { it.bill.merchant==prefix }
            assertEquals(2,matched.size)
            val records=matched.associateBy { it.bill.amountCents }
            assertEquals(setOf(500L,700L),records.keys)
            for(candidate in candidates) {
                val record=records.getValue(candidate.bill.amountCents)
                assertEquals(candidate.rawText,record.capture.text)
                assertEquals(candidate.bill.receipt?.blockId,record.bill.receipt?.blockId)
                assertEquals(time.recognitionAt,record.bill.occurredAt)
                assertEquals(replacement.id,record.bill.categoryDecision?.categoryId)
                assertEquals(rule,record.bill.categoryDecision?.rule)
                assertEquals(wallet.uid,record.wallet?.accountUid)
                assertPostedReceipt(app.store,candidate.id,record.uid)
            }
            assertEquals(postedUid,records.getValue(500L).uid)
            val movements=app.store.walletMovements(wallet.uid)
            assertEquals(mapOf(records.getValue(500L).uid to -500L,records.getValue(700L).uid to -700L),
                movements.filter { it.kind=="POST" }.associate { it.recordUid to it.deltaCents })
            assertEquals(2,movements.count { it.kind=="POST" })
            assertEquals(0L,app.store.previewCategoryDeletion(replacement.id).queuedConfigurationCount)
            val message="新增 2 · 跳过 0 · 待确认 0 · 失败 0"
            assertEquals(message,result.result.message);assertEquals(message,app.tasks.message(restoredId));assertEquals("SUCCEEDED",app.tasks.status(restoredId))
            val feedback=app.captureQueue.completedFeedback(restoredId,result)
            assertEquals(message,feedback?.message);assertNull(feedback?.recordId)
            assertFalse(taskRow().getJSONObject("batch").getBoolean("canRetry"))
            restore()
            val repeated=processor.processPrepared(app.tasks.image(restoredId)!!,Source.IMAGE,"image",restoredId) { true } as IngestResult.Batch
            assertEquals(result.result,repeated.result)
            assertEquals(8800L,app.store.getAccountByUid(wallet.uid)!!.amountCents);assertEquals(0,ocr)
            assertEquals(movements,app.store.walletMovements(wallet.uid))
            assertEquals(message,app.tasks.message(restoredId))
            candidates.forEach { assertPostedReceipt(app.store,it.id,records.getValue(it.bill.amountCents).uid) }
        } finally {
            importedTask?.let(app.tasks::delete)
            app.store.all().filter { it.bill.merchant==prefix }.forEach { app.store.delete(it.id) }
            app.store.setDefaultWallet(old)
            operation?.let { key -> app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(key)) }
            walletUid?.let { uid -> app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(uid));app.store.getAccountByUid(uid)?.let { app.store.deleteAccount(it.id) } }
            replacementId?.takeIf { id -> app.store.categoryDirectory().any { it.id==id } }?.let { id ->
                app.store.confirmCategoryDeletion(id,"cat:expense:other",app.store.previewCategoryDeletion(id).previewToken)
            }
            source.close();tasks.close();media.close()
        }
    }
    @Test fun strongIdentityMapsReceiptsBatchAndTaskToLocallyEditedBill() {
        val prefix="identity-${UUID.randomUUID()}";val media=ScreenshotStore(app,prefix)
        val source=LedgerStore(app,"$prefix-source.db",media);val tasks=RecognitionTasks(app,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(app,"$prefix-target.db",media);val restored=RecognitionTasks(app,"$prefix-target-tasks.db",media,target)
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成身份钱包",amountCents=10000));source.setDefaultWallet(wallet.uid)
            val id=tasks.create(Source.IMAGE,"image");tasks.queued(id,syntheticBackupImage(146));val key=tasks.operationKey(id)!!
            val bill=ParsedBill(500,Direction.EXPENSE,"合成身份商家","其他",1730611800000L,"SYNTHETIC-$prefix",true,"",true)
            val capture=Capture(Source.OCR,"com.tencent.mm","合成身份凭证",1730611800000L)
            val candidate=TransactionCandidate(CandidateId("single_card"),bill)
            source.freezeRecognitionBatch(capture,listOf(candidate),key,"Asia/Shanghai",tasks.classificationSnapshot(id)!!,RecognitionPolicy.USER_INITIATED)
            val posted=source.submitBatchCandidate(key,candidate.id).items.single();tasks.update(id,"SUCCEEDED","已记账",posted.recordId)
            target.saveAccount(wallet.copy(id=0,amountCents=21000));target.setDefaultWallet(wallet.uid)
            target.ingest(Capture(Source.MANUAL,"manual","合成既有账单",1730525400000L),bill.copy(amountCents=1000,merchant="合成既有",transactionId="OTHER-$prefix",occurredAt=1730525400000L))
            restored.create(Source.IMAGE,"image")
            val local=(target.ingest(capture,bill) as IngestResult.Saved).record
            val edited=target.saveEdited(local.copy(bill=local.bill.copy(category="本地人工用途"),note="本地保留"))
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            assertNotEquals(posted.recordId,local.id)
            repeat(2) { LedgerBackup.restore(target,backup) }
            assertEquals(edited,target.get(local.id));assertEquals(19500L,target.getAccountByUid(wallet.uid)!!.amountCents)
            assertEquals(local.uid,target.committedCandidate(key,candidate.id)!!.recordUid)
            assertEquals(local.id,target.recognitionBatch(key)!!.items.single().recordId)
            val row=restored.snapshot().getJSONObject(0);assertEquals(local.id,row.getLong("recordId"))
        } finally { tasks.close();restored.close();source.close();target.close();media.close() }
    }
}
