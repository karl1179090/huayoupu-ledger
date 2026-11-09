package net.ankio.ledger.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Public processor/queue behavior, real ledger/task databases, synthetic OCR inputs only. */
@RunWith(AndroidJUnit4::class)
class MultiCandidateOutcomeRecoveryTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    // Explicit isolated acceptance only; normal regression runs still clean every fixture.
    private val acceptanceExport get()=InstrumentationRegistry.getArguments().getString("ledgerBatchAcceptanceExport")
        ?.takeIf { it in setOf("snapshot","retain") }


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
            })).processPrepared(if(acceptanceExport!=null)syntheticPng("合成原账单") else "synthetic-original-image-$prefix".toByteArray(),Source.IMAGE,"image",oldTask) { true } as IngestResult.Saved
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
            val result=(processor.processPrepared(if(acceptanceExport!=null)syntheticPng("合成多卡补充") else "synthetic-new-page-$prefix".toByteArray(),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
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
            if(!initialReview && acceptanceExport!=null)exportAcceptance("supplemented",task,tasks,wallet.uid)
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
        val export=kind=="image" && !reviewFirst && acceptanceExport!=null
        var retained=false
        var companionDebit=0L
        val failedId=CandidateId("failed")
        val failedKey=candidateOperationKey(key,failedId)
        val bill=ParsedBill(2345,Direction.EXPENSE,"$prefix-已成功","其他",
            Instant.parse("2026-11-01T02:00:00Z").toEpochMilli(),null,!reviewFirst,if(reviewFirst)"合成草稿待核对" else "",true)
        var calls=0
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { bytes,prepared,_,_,_,_ ->
            calls++;prepared(bytes);RecognitionResult("合成部分成功批次",listOf(
                TransactionCandidate(CandidateId("posted"),bill),TransactionCandidate(failedId,bill.copy(amountCents=4567,merchant="$prefix-未完成"))),screenshot=if(export)bytes else null)
        }))
        try {
            app.store.writableDatabase.execSQL("""CREATE TRIGGER reject_queued_cancel_child BEFORE INSERT ON wallet_submissions
                WHEN NEW.kind='CAPTURE_RESULT' AND NEW.token='$failedKey' BEGIN SELECT RAISE(ABORT,'synthetic child failure'); END""")
            val partial=(processor.processPrepared(if(export)syntheticPng("合成部分成功批次") else "synthetic-queued-cancel-$prefix".toByteArray(),source,origin,task) { true } as IngestResult.Batch).result
            assertEquals(if(reviewFirst)BatchCounts(0,0,1,1) else BatchCounts(1,0,0,1),partial.counts)
            val committed=partial.items.first().recordUID!!
            val movements=app.store.walletMovements(wallet.uid).filter { it.recordUid==committed }
            app.store.writableDatabase.execSQL("DROP TRIGGER reject_queued_cancel_child")
            if(export) {
                // A distinct real task/record exposes a wrong-task bridge call during WebView acceptance.
                val otherTask=app.tasks.create(Source.IMAGE,"image").also(tasks::add)
                val otherBill=bill.copy(amountCents=789,merchant="$prefix-另一任务",transactionId="SYNTHETIC-OTHER-${UUID.randomUUID()}",
                    occurredAt=Instant.parse("2026-11-04T03:00:00Z").toEpochMilli())
                val other=CaptureProcessor(app,OcrClient(app,RecognitionBackend { bytes,prepared,_,_,_,_ ->
                    prepared(bytes);RecognitionResult("合成另一任务完整批次",listOf(
                        TransactionCandidate(CandidateId("other_a"),otherBill),
                        TransactionCandidate(CandidateId("other_b"),otherBill.copy(amountCents=890,merchant="$prefix-另一任务乙",
                            transactionId="SYNTHETIC-OTHER-B-${UUID.randomUUID()}"))),screenshot=bytes)
                })).processPrepared(syntheticPng("合成另一任务"),Source.IMAGE,"image",otherTask) { true } as IngestResult.Batch
                assertTrue(other.result.complete);assertEquals(BatchCounts(2,0,0,0),other.result.counts)
                assertTrue(other.result.items.all { app.store.get(it.recordId!!)!!.state==RecordState.POSTED })
                companionDebit=1679
                exportAcceptance("failed",task,tasks,wallet.uid)
            }
            assertTrue(queue.retryBatch(task));assertEquals("QUEUED",app.tasks.status(task));assertEquals(0,queue.size())
            when(kind) { "shake"->queue.cancelShake(whitelist.packageName);"shortcut"->queue.cancelShortcut(whitelist.packageName);else->queue.cancelAll() }
            assertEquals("CANCELLED",app.tasks.status(task))
            assertTrue(app.tasks.message(task)!!.contains(if(reviewFirst)"已保存的账单已保留" else "新增 1"))
            assertFalse(app.tasks.message(task)!!.contains("未新增账单"))
            assertEquals(partial,app.store.recognitionBatch(key))
            assertEquals(movements,app.store.walletMovements(wallet.uid).filter { it.recordUid==committed })
            assertEquals((if(reviewFirst)100000L else 97655L)-companionDebit,app.store.getAccount(wallet.id)!!.amountCents)
            assertNotNull(app.tasks.image(task));assertEquals(1,calls)
            if(kind!="image") { permission.set(false);assertFalse(queue.retryBatch(task)) }
            if(export) {
                exportAcceptance("cancelled",task,tasks,wallet.uid)
                retained=acceptanceExport=="retain"
            }
        } finally {
            permission.set(false);queue.cancelAll();startup.cancel()
            app.store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_queued_cancel_child")
            if(!retained)cleanup(prefix,tasks,wallet,oldDefault)
            app.channels.set(ChannelSettings.Channel.OCR,oldShake)
        }
    }


    private fun syntheticPng(title:String):ByteArray {
        val bitmap=Bitmap.createBitmap(720,560,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=36f }
        listOf("仅合成验收数据",title,"交易金额：23.45元","另一卡金额：45.67元").forEachIndexed { i,text ->
            canvas.drawText(text,40f,90f+i*95f,paint)
        }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray() }
    }

    /** Snapshot actual app DB/media, never fabricate bridge JSON or recognition queue rows. */
    private fun exportAcceptance(stage:String,parent:Long,tasks:List<Long>,walletUid:String) {
        val directory=File(app.cacheDir,"t10-batch-acceptance/$stage").apply {
            check(!exists() || deleteRecursively());check(mkdirs())
        }
        val snapshot=app.tasks.snapshot()
        val selected=JSONArray().apply { for(i in 0 until snapshot.length())snapshot.getJSONObject(i).takeIf {
            it.getLong("id") in tasks
        }?.let(::put) }
        val row=(0 until selected.length()).map { selected.getJSONObject(it) }.single { it.getLong("id")==parent }
        val operation=app.tasks.operationKey(parent)!!
        if(stage in setOf("failed","cancelled")) {
            assertEquals(stage.uppercase(),row.getString("status"))
            assertTrue(row.getJSONObject("batch").getBoolean("canRetry"))
            assertFalse(row.getJSONObject("batch").getBoolean("complete"))
            assertEquals(operation.removePrefix("recognition:"),row.getString("operationUID"))
            assertEquals(2,selected.length())
        }
        val images=JSONArray()
        tasks.forEach { task ->
            val bytes=app.tasks.image(task)!!
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!;decoded.recycle()
            val name="task-$task.png";File(directory,name).writeBytes(bytes)
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            images.put(JSONObject().put("taskId",task).put("file",name).put("sha256",hash))
        }
        // No worker is active: the startup gate holds the image retry until direct cancellation.
        synchronized(app.store) { synchronized(app.screenshots) {
            for((name,db) in listOf("ledger.db" to app.store.readableDatabase,"recognition.db" to app.tasks.readableDatabase,
                "screenshots.db" to app.screenshots.readableDatabase)) {
                db.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use { if(it.moveToFirst())check(it.getInt(0)==0) }
                val target=File(directory,"databases/$name");check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                app.getDatabasePath(name).copyTo(target,overwrite=true)
            }
            File(app.filesDir,"screenshots").copyRecursively(File(directory,"files/screenshots"),overwrite=true)
        } }
        File(app.applicationInfo.dataDir,"shared_prefs").takeIf { it.isDirectory }
            ?.copyRecursively(File(directory,"shared_prefs"),overwrite=true)
        File(directory,"manifest.json").writeText(JSONObject().put("stage",stage).put("taskId",parent)
            .put("operationKey",operation).put("operationUID",operation.removePrefix("recognition:"))
            .put("completeTaskId",if(stage in setOf("failed","cancelled"))tasks.single { it!=parent } else parent)
            .put("walletAccountUID",walletUid).put("walletAmountCents",app.store.getAccountByUid(walletUid)!!.amountCents)
            .put("tasks",selected).put("images",images).put("batch",app.store.recognitionBatch(operation)!!.json())
            .put("syntheticOnly",true).put("retainedInIsolatedSession",acceptanceExport=="retain" && stage=="cancelled").toString(2))
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
