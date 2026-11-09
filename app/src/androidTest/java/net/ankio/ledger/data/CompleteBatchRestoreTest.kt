package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Test
import org.junit.Assert.*
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
    @Test fun partialBatchKeepsFrozenDecisionReplacementAndClockThroughProcessorWithoutAnotherOcrOrDebit()=runBlocking {
        app.taskRecovery.await()
        val prefix="partial-${UUID.randomUUID()}";val media=ScreenshotStore(app,prefix)
        val source=LedgerStore(app,"$prefix-source.db",media);val tasks=RecognitionTasks(app,"$prefix-tasks.db",media,source)
        val old=app.store.walletSettings().defaultAccountUid
        var operation:String?=null;var walletUid:String?=null;var importedTask:Long?=null
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成冻结恢复钱包",amountCents=10000));walletUid=wallet.uid;source.setDefaultWallet(wallet.uid)
            val first=source.saveCategory(name="合成待删除${prefix.takeLast(8)}",direction=Direction.EXPENSE)
            val replacement=source.saveCategory(name="合成替代${prefix.takeLast(8)}",direction=Direction.EXPENSE)
            val rule=source.saveClassificationRule(ClassificationRule("","合成冻结规则$prefix",true,Direction.EXPENSE,first.id,0,listOf(ClassificationCondition(value=prefix))))
            val id=tasks.create(Source.IMAGE,"image");tasks.queued(id,syntheticBackupImage(145));operation=tasks.operationKey(id)!!
            val time=RecognitionTimeContext(1730611800000L,"America/New_York");tasks.beginRecognition(id) { time }
            val frozen=tasks.classificationSnapshot(id)!!
            val bill=ParsedBill(500,Direction.EXPENSE,prefix,"其他",time.recognitionAt,null,true,"",receipt=ReceiptEvidence(time=TransactionTimeResolver.fallback(time)))
            val candidates=listOf(TransactionCandidate(CandidateId("first"),bill),TransactionCandidate(CandidateId("second"),bill.copy(amountCents=700)))
            source.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成冻结凭证",time.recognitionAt),candidates,operation!!,time.zoneId,frozen,RecognitionPolicy.USER_INITIATED)
            source.submitBatchCandidate(operation!!,CandidateId("first"));source.failBatchCandidate(operation!!,CandidateId("second"))
            tasks.update(id,"FAILED","该项保存失败，请重试")
            source.saveClassificationRule(rule.copy(enabled=false))
            source.confirmCategoryDeletion(first.id,replacement.id,source.previewCategoryDeletion(first.id).previewToken)
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            LedgerBackup.restore(app.store,backup)
            importedTask=app.tasks.snapshot(500).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("operationUID")==operation!!.removePrefix("recognition:") }.getLong("id") }
            assertEquals(time,app.tasks.recognitionTime(importedTask!!));assertEquals(frozen,app.tasks.classificationSnapshot(importedTask!!))
            assertFalse(app.store.categoryDirectory().any { it.id==first.id })
            var ocr=0
            val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->ocr++;error("frozen batch must not run OCR") }),{time.copy(recognitionAt=time.recognitionAt+172800000,zoneId="Asia/Shanghai")})
            val result=processor.processPrepared(app.tasks.image(importedTask!!)!!,Source.IMAGE,"image",importedTask) { true } as IngestResult.Batch
            assertEquals(BatchCounts(2,0,0,0),result.result.counts);assertEquals(0,ocr)
            assertEquals(8800L,app.store.getAccountByUid(wallet.uid)!!.amountCents)
            assertTrue(app.store.all().filter { it.bill.merchant==prefix }.all { it.bill.category==replacement.name && it.bill.occurredAt==time.recognitionAt })
            LedgerBackup.restore(app.store,backup)
            processor.processPrepared(app.tasks.image(importedTask!!)!!,Source.IMAGE,"image",importedTask) { true }
            assertEquals(8800L,app.store.getAccountByUid(wallet.uid)!!.amountCents);assertEquals(0,ocr)
            assertEquals(2,app.store.walletMovements(wallet.uid).count { it.kind=="POST" })
        } finally {
            importedTask?.let(app.tasks::delete)
            app.store.all().filter { it.bill.merchant==prefix }.forEach { app.store.delete(it.id) }
            app.store.setDefaultWallet(old)
            operation?.let { key -> app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(key)) }
            walletUid?.let { uid -> app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(uid));app.store.getAccountByUid(uid)?.let { app.store.deleteAccount(it.id) } }
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
