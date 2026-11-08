package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.CaptureWhitelistFixture
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.PaymentRecognitionQueue
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.RecognitionBackend
import net.ankio.ledger.ocr.RecognitionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Public processor/queue behavior, real ledger/task databases, synthetic OCR inputs only. */
@RunWith(AndroidJUnit4::class)
class MultiCandidateOutcomeRecoveryTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun postedStrongIdentitySupplementAcrossDaysIsSkippedWithOriginalNavigationAndManualChoices()=runBlocking {
        existingIdentityBatch(initialReview=false,promote=false)
    }
    @Test fun supplementedDraftStillRequiresReviewRatherThanBeingSkipped()=runBlocking {
        existingIdentityBatch(initialReview=true,promote=false)
    }
    @Test fun firstDraftPromotionCountsAsPostedAndPreservesItsOriginalIdentityAndCategory()=runBlocking {
        existingIdentityBatch(initialReview=true,promote=true)
    }

    private suspend fun existingIdentityBatch(initialReview:Boolean,promote:Boolean) {
        app.taskRecovery.await()
        val prefix="合成批结果${UUID.randomUUID()}"
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成批结果钱包",amountCents=100000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val tasks=mutableListOf<Long>()
        val dining=app.store.category("cat:expense:dining",Direction.EXPENSE)
        val identity="SYNTHETIC-BATCH-RESULT-${UUID.randomUUID()}"
        val originalBill=ParsedBill(1234,Direction.EXPENSE,"$prefix-原商家",dining.name,
            Instant.parse("2026-11-01T02:00:00Z").toEpochMilli(),identity,!initialReview,
            if(initialReview)"合成信息待核对" else "",true,
            categoryDecision=CategoryDecision(dining.id,CategorySource.MANUAL,true,explanation="合成人工分类"))
        try {
            val oldTask=app.tasks.create(Source.IMAGE,"image").also(tasks::add)
            val first=CaptureProcessor(app,OcrClient(app,RecognitionBackend { bytes,prepared,_,_,_,_ ->
                prepared(bytes);RecognitionResult("微信交易号：$identity",originalBill,screenshot=bytes)
            })).processPrepared("synthetic-original-image-$prefix".toByteArray(),Source.IMAGE,"image",oldTask) { true } as IngestResult.Saved
            val original=if(initialReview)first.record else app.store.saveEdited(first.record,
                walletAccountUid=wallet.uid,manualWalletSelected=true,manualCategorySelected=true)
            assertEquals(if(initialReview)RecordState.REVIEW else RecordState.POSTED,original.state)
            val originalHistory=app.store.captureHistory(original.id).size
            val oldMovements=app.store.walletMovements(wallet.uid).count { it.recordUid==original.uid }
            val incoming=originalBill.copy(occurredAt=if(initialReview)originalBill.occurredAt else Instant.parse("2026-11-02T03:00:00Z").toEpochMilli(),
                reliable= !initialReview || promote,category="其他",categoryDecision=null)
            val other=incoming.copy(amountCents=5678,merchant="$prefix-另一笔",transactionId=null,reliable=true,
                occurredAt=Instant.parse("2026-11-03T04:00:00Z").toEpochMilli(),reason="")
            val task=app.tasks.create(Source.IMAGE,"image").also(tasks::add)
            var calls=0
            val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { bytes,prepared,_,_,_,_ ->
                calls++;prepared(bytes);RecognitionResult("合成多卡补充",listOf(
                    TransactionCandidate(CandidateId("original"),incoming,"微信交易号：$identity"),
                    TransactionCandidate(CandidateId("new"),other,"合成另一笔")),screenshot=bytes)
            }))
            val result=(processor.processPrepared("synthetic-new-page-$prefix".toByteArray(),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            val updated=app.store.get(original.id)!!
            assertEquals(2,app.store.all().count { it.bill.merchant.startsWith(prefix) })
            assertEquals(original.uid,updated.uid)
            assertEquals(original.id,result.items.first().recordId);assertEquals(original.uid,result.items.first().recordUID)
            assertEquals(original.bill.category,updated.bill.category);assertEquals(original.bill.categoryDecision,updated.bill.categoryDecision)
            assertEquals(original.bill.occurredAt,updated.bill.occurredAt)
            assertEquals(originalHistory+1,app.store.captureHistory(original.id).size)
            assertTrue(app.store.captureHistory(original.id).last().hasScreenshot)
            when {
                !initialReview -> {
                    assertEquals(BatchCounts(1,1,0,0),result.counts)
                    assertEquals("SKIPPED",result.items.first().status)
                    assertTrue(result.items.first().reason.contains("补充"))
                    assertEquals(93088L,app.store.getAccount(wallet.id)!!.amountCents)
                    assertEquals(oldMovements,app.store.walletMovements(wallet.uid).count { it.recordUid==original.uid })
                    assertTrue(updated.wallet!!.manualLocked)
                }
                promote -> {
                    assertEquals(BatchCounts(2,0,0,0),result.counts)
                    assertEquals(RecordState.POSTED,updated.state)
                    assertEquals(93088L,app.store.getAccount(wallet.id)!!.amountCents)
                    assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==original.uid })
                }
                else -> {
                    assertEquals(BatchCounts(1,0,1,0),result.counts)
                    assertEquals("REVIEW",result.items.first().status);assertEquals(RecordState.REVIEW,updated.state)
                    assertEquals(94322L,app.store.getAccount(wallet.id)!!.amountCents)
                    assertEquals(0,app.store.walletMovements(wallet.uid).count { it.recordUid==original.uid })
                }
            }
            RecognitionTasks(app).use { reopenedTasks -> LedgerStore(app).use { reopenedStore ->
                assertTrue(RecognitionCommitRecovery.recover(reopenedTasks,reopenedStore,task))
                assertEquals(result,reopenedStore.recognitionBatch(app.tasks.operationKey(task)!!))
                assertEquals(result.message,reopenedTasks.message(task))
            } }
            val replay=(processor.processPrepared(byteArrayOf(1),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            assertEquals(result,replay);assertEquals(1,calls)
        } finally { cleanup(prefix,tasks,wallet,oldDefault) }
    }

    @Test fun cancellingQueuedImageRetryPreservesTheCommittedBatchSummary()=runBlocking { cancelledQueuedRetry("image") }
    @Test fun cancellingQueuedShakeRetryPreservesTheCommittedBatchSummary()=runBlocking { cancelledQueuedRetry("shake") }
    @Test fun cancellingQueuedShortcutRetryPreservesTheCommittedBatchSummary()=runBlocking { cancelledQueuedRetry("shortcut") }

    @Test fun cancellingQueuedRetryAlsoPreservesAnAlreadySavedReviewDraft()=runBlocking { cancelledQueuedRetry("image",reviewFirst=true) }

    private suspend fun cancelledQueuedRetry(kind:String,reviewFirst:Boolean=false) {
        app.taskRecovery.await()
        val prefix="合成排队取消${UUID.randomUUID()}"
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val oldShake=app.channels.enabled(ChannelSettings.Channel.OCR)
        app.channels.set(ChannelSettings.Channel.OCR,true)
        val wallet=app.store.saveAccount(LedgerAccount(name="合成排队取消钱包",amountCents=100000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val permission=AtomicBoolean(true)
        // The public startup gate leaves the retry durably QUEUED before a worker/catch can run.
        val startup=CompletableDeferred<Unit>()
        val queue=PaymentRecognitionQueue(app,startup) { permission.get() }
        val source=if(kind=="image")Source.IMAGE else Source.OCR
        val authorization=when(kind) {
            "shake"->app.channels.shakeAuthorization(whitelist.packageName)!!
            "shortcut"->app.channels.shortcutAuthorization(whitelist.packageName)!!
            else->null
        }
        val origin=authorization?.origin ?: "image"
        val task=app.tasks.create(source,origin,authorization)
        val key=app.tasks.operationKey(task)!!
        val tasks=mutableListOf(task)
        val failedId=CandidateId("failed")
        val failedKey=candidateOperationKey(key,failedId)
        val bill=ParsedBill(2345,Direction.EXPENSE,"$prefix-已成功","其他",
            Instant.parse("2026-11-01T02:00:00Z").toEpochMilli(),null,!reviewFirst,if(reviewFirst)"合成草稿待核对" else "",true)
        var calls=0
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { bytes,prepared,_,_,_,_ ->
            calls++;prepared(bytes);RecognitionResult("合成部分成功批次",listOf(
                TransactionCandidate(CandidateId("posted"),bill),TransactionCandidate(failedId,bill.copy(amountCents=4567,merchant="$prefix-未完成"))))
        }))
        try {
            app.store.writableDatabase.execSQL("""CREATE TRIGGER reject_queued_cancel_child BEFORE INSERT ON wallet_submissions
                WHEN NEW.kind='CAPTURE_RESULT' AND NEW.token='$failedKey' BEGIN SELECT RAISE(ABORT,'synthetic child failure'); END""")
            val partial=(processor.processPrepared("synthetic-queued-cancel-$prefix".toByteArray(),source,origin,task) { true } as IngestResult.Batch).result
            assertEquals(if(reviewFirst)BatchCounts(0,0,1,1) else BatchCounts(1,0,0,1),partial.counts)
            val committed=partial.items.first().recordUID!!
            val movements=app.store.walletMovements(wallet.uid).filter { it.recordUid==committed }
            app.store.writableDatabase.execSQL("DROP TRIGGER reject_queued_cancel_child")
            assertTrue(queue.retryBatch(task));assertEquals("QUEUED",app.tasks.status(task));assertEquals(0,queue.size())
            when(kind) { "shake"->queue.cancelShake(whitelist.packageName);"shortcut"->queue.cancelShortcut(whitelist.packageName);else->queue.cancelAll() }
            assertEquals("CANCELLED",app.tasks.status(task))
            assertTrue(app.tasks.message(task)!!.contains(if(reviewFirst)"已保存的账单已保留" else "新增 1"))
            assertFalse(app.tasks.message(task)!!.contains("未新增账单"))
            assertEquals(partial,app.store.recognitionBatch(key))
            assertEquals(movements,app.store.walletMovements(wallet.uid).filter { it.recordUid==committed })
            assertEquals(if(reviewFirst)100000L else 97655L,app.store.getAccount(wallet.id)!!.amountCents)
            assertNotNull(app.tasks.image(task));assertEquals(1,calls)
            if(kind!="image") { permission.set(false);assertFalse(queue.retryBatch(task)) }
        } finally {
            permission.set(false);queue.cancelAll();startup.cancel()
            app.store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_queued_cancel_child")
            cleanup(prefix,tasks,wallet,oldDefault)
            app.channels.set(ChannelSettings.Channel.OCR,oldShake)
        }
    }

    private fun cleanup(prefix:String,tasks:List<Long>,wallet:LedgerAccount,oldDefault:String?) {
        val records=app.store.all().filter { it.bill.merchant.startsWith(prefix) }
        records.forEach { app.store.writableDatabase.delete("wallet_submissions","result_uid=?",arrayOf(it.uid));app.store.delete(it.id) }
        tasks.forEach { task ->
            app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(it)) }
            app.tasks.delete(task)
        }
        app.store.setDefaultWallet(oldDefault)
        app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
    }
}
