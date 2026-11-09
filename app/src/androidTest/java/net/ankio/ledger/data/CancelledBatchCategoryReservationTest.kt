package net.ankio.ledger.data

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Public processor, deletion and retry boundaries; all financial effects use Android SQLite. */
abstract class CancelledBatchCategoryScenario {
    protected val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    protected val state get()=app.getSharedPreferences("synthetic-cancelled-category-restart",0)

    protected suspend fun cancelledBatch():Long {
        app.taskRecovery.await();check(!state.contains("task"))
        val suffix=UUID.randomUUID().toString().take(8)
        val merchant="合成取消独占分类$suffix"
        val wallet=app.store.saveAccount(LedgerAccount(name="合成取消分类钱包",amountCents=10000))
        val old=app.store.walletSettings().defaultAccountUid;app.store.setDefaultWallet(wallet.uid)
        val category=app.store.saveCategory(name="合成取消原用途$suffix",direction=Direction.EXPENSE)
        val replacement=app.store.saveCategory(name="合成取消替代$suffix",direction=Direction.EXPENSE)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成取消分类规则",true,Direction.EXPENSE,category.id,0,
            listOf(ClassificationCondition(value=merchant))))
        val task=app.tasks.create(Source.IMAGE,"image");val key=app.tasks.operationKey(task)!!
        val bill=ParsedBill(600,Direction.EXPENSE,"合成已完成其他$suffix","其他",1793491200000,null,true,"",true)
        val candidates=listOf(TransactionCandidate(CandidateId("cancel_done"),bill),
            TransactionCandidate(CandidateId("cancel_pending"),bill.copy(amountCents=800,merchant=merchant)))
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_ ->
            prepared(image);RecognitionResult("合成取消分类多卡",candidates)
        }))
        val error=runCatching { processor.processPrepared(syntheticBackupImage(139),Source.IMAGE,"image",task) {
            app.store.recognitionBatch(key)?.counts?.added!=1
        } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals("CANCELLED",app.tasks.status(task));assertEquals(9400L,app.store.getAccount(wallet.id)!!.amountCents)
        assertEquals("其他",app.store.all().single { it.bill.merchant==bill.merchant }.bill.category)
        assertFalse(app.store.recognitionBatch(key)!!.complete)
        assertNotNull(app.tasks.image(task))
        assertTrue(state.edit().putLong("task",task).putString("key",key).putString("merchant",merchant)
            .putString("doneMerchant",bill.merchant).putString("category",category.id).putString("replacement",replacement.id)
            .putString("categoryName",category.name).putString("replacementName",replacement.name).putString("wallet",wallet.uid)
            .putString("oldDefault",old).putString("frozen",ClassificationCodec.snapshot(app.tasks.classificationSnapshot(task)!!).toString())
            .putString("rule",rule.id).putInt("pid",Process.myPid()).commit())
        // X is now referenced only by the unfinished task, never by the completed child or current rules.
        app.store.deleteClassificationRule(rule.id)
        return task
    }
    protected suspend fun replaceAndRetry(task:Long) {
        val key=state.getString("key",null)!!
        val category=state.getString("category",null)!!;val replacement=state.getString("replacement",null)!!
        val frozen=ClassificationCodec.readSnapshot(state.getString("frozen",null)!!)
        val preview=app.store.previewCategoryDeletion(category)
        assertEquals(0L,preview.billCount);assertEquals(0L,preview.ruleCount)
        assertEquals(1L,preview.queuedConfigurationCount);assertTrue(preview.requiresReplacement)
        val rejected=runCatching { app.store.confirmCategoryDeletion(category,null,preview.previewToken) }.exceptionOrNull()
        assertTrue(rejected is CategoryException && rejected.code=="CATEGORY_REPLACEMENT_REQUIRED")
        app.store.confirmCategoryDeletion(category,replacement,preview.previewToken)
        val wallet=app.store.getAccountByUid(state.getString("wallet",null)!!)!!
        assertEquals(9400L,wallet.amountCents)
        assertTrue(app.captureQueue.retryBatch(task))
        kotlinx.coroutines.withTimeout(10000) { while(app.tasks.status(task)!="SUCCEEDED")kotlinx.coroutines.delay(25) }
        val result=app.store.recognitionBatch(key)!!
        assertEquals(BatchCounts(2,0,0,0),result.counts);assertTrue(result.complete)
        val pending=app.store.all().single { it.bill.merchant==state.getString("merchant",null) }
        val done=app.store.all().single { it.bill.merchant==state.getString("doneMerchant",null) }
        assertEquals("其他",done.bill.category);assertEquals("cat:expense:other",done.bill.categoryDecision!!.categoryId)
        assertEquals(state.getString("replacementName",null),pending.bill.category)
        assertEquals(replacement,pending.bill.categoryDecision!!.categoryId)
        assertEquals(frozen.customRules.single { it.id==state.getString("rule",null) },pending.bill.categoryDecision!!.rule)
        assertEquals(frozen.version,pending.bill.categoryDecision!!.ruleVersion);assertEquals(frozen,app.tasks.classificationSnapshot(task))
        assertFalse(app.store.categoryDirectory().any { it.id==category || it.name==state.getString("categoryName",null) })
        assertEquals(8600L,app.store.getAccount(wallet.id)!!.amountCents)
        listOf(done,pending).forEach { record -> assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==record.uid }) }
        assertFalse(app.captureQueue.retryBatch(task));assertEquals(0L,app.store.previewCategoryDeletion(replacement).queuedConfigurationCount)
    }
    protected fun cleanup() {
        val key=state.getString("key",null)
        val wallet=state.getString("wallet",null)?.let(app.store::getAccountByUid)
        val merchants=setOf(state.getString("merchant",null),state.getString("doneMerchant",null))
        app.store.all().filter { it.bill.merchant in merchants }.forEach { app.store.delete(it.id) }
        key?.let { app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(it))
            listOf("cancel_done","cancel_pending").forEach { id -> app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(candidateOperationKey(it,CandidateId(id)))) } }
        if(state.contains("task"))app.tasks.delete(state.getLong("task",0))
        app.store.setDefaultWallet(state.getString("oldDefault",null))
        wallet?.let { app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(it.uid));app.store.deleteAccount(it.id) }
        listOf("category","replacement").mapNotNull { state.getString(it,null) }.forEach { id ->
            if(app.store.categoryDirectory().any { it.id==id })app.store.confirmCategoryDeletion(id,"cat:expense:other",app.store.previewCategoryDeletion(id).previewToken)
        }
        assertTrue(state.edit().clear().commit())
    }
}

class CancelledBatchCategoryReservationTest:CancelledBatchCategoryScenario() {
    @Test fun cancelledUnfinishedBatchStillRequiresReplacementAndExplicitRetryUsesIt()=runBlocking {
        val task=cancelledBatch()
        try { replaceAndRetry(task) } finally { cleanup() }
    }
    @Test fun restoredCancelledBatchReservesItsCategoryBeforeAnyTaskRecovery()=runBlocking {
        cancelledBatch()
        val prefix="synthetic-cancelled-restore-${UUID.randomUUID()}";val media=ScreenshotStore(app,prefix)
        val target=LedgerStore(app,"$prefix-ledger.db",media);val tasks=RecognitionTasks(app,"$prefix-tasks.db",media,target)
        try {
            val backup=LedgerBackup.decode(LedgerBackup.encode(app.store,app.channels).byteInputStream())
            LedgerBackup.restore(target,backup)
            val preview=target.previewCategoryDeletion(state.getString("category",null)!!)
            assertEquals(0L,preview.billCount);assertEquals(0L,preview.ruleCount);assertEquals(1L,preview.queuedConfigurationCount)
            assertTrue(preview.requiresReplacement)
            assertEquals(9400L,target.getAccountByUid(state.getString("wallet",null)!!)!!.amountCents)
        } finally { tasks.close();target.close();media.close();cleanup() }
    }
    @Test fun quiescentRestoreReturnsWithCancelledBatchReservedAndStillNeedsExplicitRetry()=runBlocking {
        val task=cancelledBatch()
        try {
            val backup=LedgerBackup.decode(LedgerBackup.encode(app.store,app.channels).byteInputStream())
            app.tasks.delete(task)
            app.restoreQuiescent { LedgerBackup.restore(app.store,backup) }
            val key=state.getString("key",null)!!
            val rows=app.tasks.snapshot()
            val restored=(0 until rows.length()).map { rows.getJSONObject(it) }.single { "recognition:${it.getString("operationUID")}"==key }.getLong("id")
            assertTrue(state.edit().putLong("task",restored).commit())
            assertEquals("CANCELLED",app.tasks.status(restored))
            replaceAndRetry(restored)
        } finally { cleanup() }
    }
    @Test fun cancelledSingleCompletedAndMissingImageTasksReleaseTheirCategoryReservations() {
        val prefix="synthetic-cancelled-terminal-${UUID.randomUUID()}";val media=ScreenshotStore(app,prefix)
        val store=LedgerStore(app,"$prefix-ledger.db",media);val tasks=RecognitionTasks(app,"$prefix-tasks.db",media,store)
        try {
            val category=store.saveCategory(name="合成终止分类",direction=Direction.EXPENSE)
            val rule=store.saveClassificationRule(ClassificationRule("","合成终止规则",true,Direction.EXPENSE,category.id,0,
                listOf(ClassificationCondition(value="合成终止商家"))))
            val single=tasks.create(Source.IMAGE,"image");tasks.queued(single,syntheticBackupImage(140));tasks.update(single,"CANCELLED","合成取消")
            val missing=tasks.create(Source.IMAGE,"image");val missingKey=tasks.operationKey(missing)!!
            val bill=ParsedBill(300,Direction.EXPENSE,"合成终止商家","其他",1793491200000,null,true,"",true)
            val candidate=TransactionCandidate(CandidateId("terminal"),bill)
            store.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成终止"),listOf(candidate),missingKey,"Asia/Shanghai",tasks.classificationSnapshot(missing)!!,RecognitionPolicy.USER_INITIATED)
            tasks.update(missing,"CANCELLED","合成取消")
            val completed=tasks.create(Source.IMAGE,"image");tasks.queued(completed,syntheticBackupImage(141));val completedKey=tasks.operationKey(completed)!!
            store.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成完成"),listOf(candidate),completedKey,"Asia/Shanghai",tasks.classificationSnapshot(completed)!!,RecognitionPolicy.USER_INITIATED)
            assertTrue(store.submitBatchCandidate(completedKey,candidate.id).complete)
            tasks.update(completed,"CANCELLED","合成取消")
            store.deleteClassificationRule(rule.id)
            assertEquals(0L,store.previewCategoryDeletion(category.id).queuedConfigurationCount)
            tasks.recoverInterrupted()
            assertEquals(0L,store.previewCategoryDeletion(category.id).queuedConfigurationCount)
        } finally { tasks.close();store.close();media.close() }
    }
}

/** Host-only phases: invoke separately with force-stop; excluded from ordinary class registry. */
class CancelledBatchCategoryProcessRestartTest:CancelledBatchCategoryScenario() {
    @Test fun aCreateCancelledBatchBeforeProcessDeath()=runBlocking { cancelledBatch();Unit }
    @Test fun bRetryCancelledBatchAfterProcessDeath()=runBlocking {
        app.taskRecovery.await()
        val task=state.getLong("task",0);assertTrue("Run phase a first",task>0)
        try {
            assertEquals("true",InstrumentationRegistry.getArguments().getString("expectProcessRestart"))
            println("SYNTHETIC_CANCELLED_RESTART oldPid=${state.getInt("pid",0)} newPid=${Process.myPid()}")
            assertNotEquals(state.getInt("pid",0),Process.myPid())
            assertEquals("CANCELLED",app.tasks.status(task))
            assertEquals(9400L,app.store.getAccountByUid(state.getString("wallet",null)!!)!!.amountCents)
            replaceAndRetry(task)
        } finally { cleanup() }
    }
}
