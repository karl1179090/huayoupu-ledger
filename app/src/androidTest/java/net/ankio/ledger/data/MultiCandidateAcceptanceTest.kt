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

@RunWith(AndroidJUnit4::class)
class MultiCandidateAcceptanceTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun twoIndependentEqualCardsBothPostAndRetryWithoutRecognizingOrDebitingAgain()=runBlocking {
        app.taskRecovery.await()
        val old=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成批次钱包",amountCents=100000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.IMAGE,"image")
        val merchant="合成多笔${UUID.randomUUID()}"
        val bill=ParsedBill(1234,Direction.EXPENSE,merchant,"其他",1793491200000,null,true,"",true)
        val candidates=listOf(TransactionCandidate(CandidateId("card_a"),bill,"合成相同卡片"),
            TransactionCandidate(CandidateId("card_b"),bill,"合成相同卡片"))
        var calls=0
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_ ->
            calls++;prepared(image);RecognitionResult("合成相同卡片\n合成相同卡片",candidates,screenshot=image)
        }))
        try {
            val result=processor.processPrepared(byteArrayOf(1),Source.IMAGE,"image",task) { true } as IngestResult.Batch
            assertEquals(2,result.result.counts.added);assertTrue(result.result.complete)
            assertEquals(2,app.store.all().count { it.bill.merchant==merchant })
            assertEquals(97532L,app.store.getAccount(wallet.id)!!.amountCents)
            val restored=processor.processPrepared(byteArrayOf(1),Source.IMAGE,"image",task) { true } as IngestResult.Batch
            assertEquals(result.result,restored.result);assertEquals(1,calls)
            assertEquals(97532L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(2,app.store.walletMovements(wallet.uid).count { it.kind=="POST" })
        } finally {
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(app.tasks.operationKey(task)!!))
            app.tasks.delete(task);app.store.setDefaultWallet(old)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }
    @Test fun skippedBatchBackupKeepsItsDecisionsWhenHistoryIsLaterRemoved() {
        val sourceName="synthetic-batch-source-${UUID.randomUUID()}.db"
        val targetName="synthetic-batch-target-${UUID.randomUUID()}.db"
        val operation="recognition:${UUID.randomUUID()}"
        try {
            LedgerStore(app,sourceName).use { source ->
                val wallet=source.saveAccount(LedgerAccount(name="合成备份钱包",amountCents=100000,kind="cash"))
                source.setDefaultWallet(wallet.uid)
                val bill=ParsedBill(3456,Direction.EXPENSE,"合成备份历史","其他",1793491200000,null,true,"",true)
                val history=(source.ingest(Capture(Source.MANUAL,"manual","合成历史"),bill) as IngestResult.Saved).record
                val candidates=listOf("bank_a","bank_b").map { TransactionCandidate(CandidateId(it),bill.copy(merchant="合成待跳过卡")) }
                val before=source.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成多卡"),candidates,operation,"Asia/Shanghai",
                    source.classificationRules(),RecognitionPolicy.USER_INITIATED)
                assertEquals(2,before.counts.skipped)
                val json=LedgerBackup.encode(source,net.ankio.ledger.capture.ChannelSettings(app))
                assertEquals(1,org.json.JSONObject(json).getJSONArray("recognitionBatches").length())
                LedgerStore(app,targetName).use { target ->
                    val validated=LedgerBackup.decode(json.byteInputStream())
                    LedgerBackup.restore(target,validated)
                    val restored=target.recognitionBatch(operation)!!
                    assertEquals(2,restored.counts.skipped);assertTrue(restored.complete)
                    target.all().forEach { target.delete(it.id) }
                    assertEquals(2,target.recognitionBatch(operation)!!.counts.skipped)
                    assertEquals(0,target.recognitionBatch(operation)!!.counts.added)
                    assertEquals(before.items.map { it.operationKey },restored.items.map { it.operationKey })
                    assertTrue(restored.items.all { it.recordUID==history.uid })
                }
            }
        } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun partialReceiptFailureRollsBackOneChildAndRetriesFrozenInputAfterHistoryChanges()=runBlocking {
        app.taskRecovery.await()
        val old=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成部分失败钱包",amountCents=100000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.IMAGE,"image");val operation=app.tasks.operationKey(task)!!
        val prefix="合成故障${UUID.randomUUID()}"
        val first=ParsedBill(2345,Direction.EXPENSE,prefix+"甲","其他",1793491200000,null,true,"",true)
        val second=first.copy(amountCents=4567,merchant=prefix+"乙")
        val failedId=CandidateId("b".repeat(128))
        val candidates=listOf(TransactionCandidate(CandidateId("a".repeat(128)),first),TransactionCandidate(failedId,second))
        val failedKey=candidateOperationKey(operation,failedId)
        var calls=0
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_ ->
            calls++;prepared(image);RecognitionResult("合成多卡故障",candidates)
        }))
        try {
            app.store.writableDatabase.execSQL("""CREATE TRIGGER reject_batch_child BEFORE INSERT ON wallet_submissions
                WHEN NEW.kind='CAPTURE_RESULT' AND NEW.token='$failedKey' BEGIN SELECT RAISE(ABORT,'synthetic child receipt failure'); END""")
            val partial=(processor.processPrepared(byteArrayOf(2),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            assertEquals(BatchCounts(1,0,0,1),partial.counts);assertFalse(partial.complete)
            assertEquals(97655L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.all().count { it.bill.merchant.startsWith(prefix) })
            assertNull(app.store.committedCandidate(operation,failedId))
            assertEquals("FAILED",app.tasks.status(task))
            assertTrue(partial.items.all { it.operationKey.length<=160 })
            app.store.writableDatabase.execSQL("DROP TRIGGER reject_batch_child")
            // The new POSTED history matches the failed child's date+amount but must not revise its saved decision.
            app.store.ingest(Capture(Source.MANUAL,"manual","合成后来历史"),second.copy(merchant=prefix+"后来历史",direction=Direction.INCOME))
            assertTrue(app.captureQueue.retryBatch(task))
            kotlinx.coroutines.withTimeout(10000) { while(app.tasks.status(task)!="SUCCEEDED")kotlinx.coroutines.delay(25) }
            val retried=(processor.processPrepared(byteArrayOf(9),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            assertEquals(BatchCounts(2,0,0,0),retried.counts);assertTrue(retried.complete)
            assertEquals(1,calls);assertEquals(97655L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(partial.items.first().recordUID,retried.items.first().recordUID)
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==retried.items[1].recordUID })
            val snapshot=app.tasks.snapshot().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.single { it.getLong("id")==task } }
            assertEquals(2,snapshot.getJSONObject("batch").getJSONObject("counts").getInt("added"))
        } finally {
            app.store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_batch_child")
            app.store.all().filter { it.bill.merchant.startsWith(prefix) }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(operation))
            candidates.forEach { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(candidateOperationKey(operation,it.id))) }
            app.tasks.delete(task);app.store.setDefaultWallet(old)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun postedHistoryMatchesOnlyTheCompleteLocalDayAndCentsIgnoringMerchantWalletDirectionAndClock() {
        val name="synthetic-day-matrix-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val wallet=store.saveAccount(LedgerAccount(name="合成历史钱包",amountCents=100000,kind="cash"));store.setDefaultWallet(wallet.uid)
            fun bill(amount:Long,date:String,merchant:String="合成历史商家",direction:Direction=Direction.EXPENSE):ParsedBill {
                val at=java.time.ZonedDateTime.parse(date).toInstant().toEpochMilli()
                return ParsedBill(amount,direction,merchant,"其他",at,null,true,"",true)
            }
            store.ingest(Capture(Source.MANUAL,"manual","合成正式历史"),bill(7777,"2026-11-01T23:50:00+08:00",direction=Direction.INCOME))
            store.ingest(Capture(Source.IMAGE,"image","合成待确认历史"),bill(2222,"2026-11-02T10:00:00+08:00"),requireReview=true)
            val deleted=(store.ingest(Capture(Source.MANUAL,"manual","合成删除历史"),bill(3333,"2026-11-03T10:00:00+08:00")) as IngestResult.Saved).record
            store.softDelete(deleted.id)
            val candidates=listOf(
                bill(7777,"2026-11-01T00:10:00+08:00","合成不同商家甲").copy(paymentMethod=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007")),
                bill(7777,"2026-11-01T12:30:00+08:00","合成不同商家乙").copy(paymentMethod=PaymentMethodEvidence("BANK_CARD","DEBIT","icbc","0008")),
                bill(7777,"2025-11-01T10:00:00+08:00","合成旧年未记"),
                bill(2222,"2026-11-02T10:00:00+08:00","合成草稿不阻挡"),
                bill(3333,"2026-11-03T10:00:00+08:00","合成删除不阻挡"))
                .mapIndexed { i,b -> TransactionCandidate(CandidateId("bank_$i"),b) }
            val operation="recognition:${UUID.randomUUID()}"
            val frozen=store.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成日金额矩阵"),candidates,operation,"Asia/Shanghai",
                store.classificationRules(),RecognitionPolicy.USER_INITIATED)
            assertEquals(listOf("SKIPPED","SKIPPED","PENDING","PENDING","PENDING"),frozen.items.map { it.status })
            candidates.forEach { store.submitBatchCandidate(operation,it.id) }
            val complete=store.recognitionBatch(operation)!!
            assertEquals(BatchCounts(3,2,0,0),complete.counts)
            assertEquals(5,store.all().size) // original POSTED + REVIEW + three newly posted; deleted excluded
            assertEquals(1,store.all().count { it.bill.merchant=="合成旧年未记" })
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun ledgerCommitAfterTaskWriteFailureRestoresBatchWithoutOcrOrAnotherDebit()=runBlocking {
        app.taskRecovery.await()
        val old=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成批次写回钱包",amountCents=100000,kind="cash"));app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.IMAGE,"image");val operation=app.tasks.operationKey(task)!!
        val merchant="合成批写回${UUID.randomUUID()}"
        val bill=ParsedBill(1234,Direction.EXPENSE,merchant,"其他",1793491200000,null,true,"",true)
        var calls=0
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_ ->
            calls++;prepared(image);RecognitionResult("合成批写回",listOf(TransactionCandidate(CandidateId("bank_0"),bill),
                TransactionCandidate(CandidateId("bank_1"),bill)))
        }))
        try {
            app.tasks.writableDatabase.execSQL("""CREATE TRIGGER reject_batch_feedback BEFORE UPDATE ON tasks
                WHEN NEW.id=$task AND NEW.status='SUCCEEDED' BEGIN SELECT RAISE(ABORT,'synthetic task write interruption'); END""")
            assertNotNull(runCatching { processor.processPrepared(byteArrayOf(3),Source.IMAGE,"image",task) { true } }.exceptionOrNull())
            assertEquals(BatchCounts(2,0,0,0),app.store.recognitionBatch(operation)!!.counts)
            assertEquals(97532L,app.store.getAccount(wallet.id)!!.amountCents)
            app.tasks.writableDatabase.execSQL("DROP TRIGGER reject_batch_feedback")
            RecognitionTasks(app).use { tasks -> LedgerStore(app).use { ledger ->
                assertTrue(RecognitionCommitRecovery.recover(tasks,ledger,task));assertEquals("SUCCEEDED",tasks.status(task))
                assertNull(tasks.recordId(task))
            } }
            val recovered=(processor.processPrepared(byteArrayOf(4),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            assertEquals(BatchCounts(2,0,0,0),recovered.counts);assertEquals(1,calls)
            assertEquals(97532L,app.store.getAccount(wallet.id)!!.amountCents)
        } finally {
            app.tasks.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_batch_feedback")
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(operation))
            app.tasks.delete(task);app.store.setDefaultWallet(old)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun parsedBankCardsKeepTheirActualWalletsWhileAnUnmatchedCardRemainsReview()=runBlocking {
        app.taskRecovery.await()
        val old=app.store.walletSettings().defaultAccountUid
        val a=app.store.saveAccount(LedgerAccount(name="合成批次招商卡",amountCents=100000,kind="bank",bankCode="cmb",cardLast4="0007"))
        val b=app.store.saveAccount(LedgerAccount(name="合成批次工行卡",amountCents=100000,kind="bank",bankCode="icbc",cardLast4="0008"))
        app.store.setDefaultWallet(a.uid)
        val task=app.tasks.create(Source.IMAGE,"image");val operation=app.tasks.operationKey(task)!!
        fun card(bank:String,amount:String,tail:String,date:String,offset:Int)=listOf(bank,"交易成功提醒","交易类型：网上快捷支付",
            "交易金额：${amount}元","交易卡号：尾号$tail","交易日期：$date","账户余额：88888.88元")
            .mapIndexed { i,text -> LocalOcrLine(text,30,offset+i*40,700,offset+i*40+30) }
        val lines=card("招商银行","12.34","0007","2026-11-01",20)+
            card("招商银行","56.78","9999","2026-11-02",400)+card("工商银行","78.90","0008","2026-11-03",800)
        val backend=object:RecognitionBackend {
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult=error("context required")
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->RecognitionTimeContext):RecognitionResult {
                onPrepared(image);return RecognitionResult(lines.joinToString("\n") { it.text },
                    TransactionPageParser.parse(lines,1080,1600,recognitionTime(),livePaymentAt,policy,origin))
            }
        }
        var ids=listOf<Long>()
        try {
            val result=(CaptureProcessor(app,OcrClient(app,backend)).processPrepared(byteArrayOf(6),Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            ids=result.items.mapNotNull { it.recordId }
            assertEquals(BatchCounts(2,0,1,0),result.counts);assertTrue(result.complete)
            assertEquals(98766L,app.store.getAccount(a.id)!!.amountCents);assertEquals(92110L,app.store.getAccount(b.id)!!.amountCents)
            val records=ids.map { app.store.get(it)!! }.associateBy { it.bill.amountCents }
            assertEquals(a.uid,records[1234L]!!.wallet!!.accountUid);assertEquals(b.uid,records[7890L]!!.wallet!!.accountUid)
            assertEquals(RecordState.REVIEW,records[5678L]!!.state);assertNull(records[5678L]!!.wallet!!.accountUid)
            assertTrue(records[5678L]!!.walletEffects.isEmpty())
            assertTrue(records.values.all { it.bill.merchant.isEmpty() && it.bill.receipt!!.time!!.recognitionAt==app.tasks.recognitionTime(task)!!.recognitionAt })
            val row=app.tasks.snapshot().let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getLong("id")==task } }
            assertFalse(row.getJSONObject("batch").getBoolean("canRetry"))
        } finally {
            ids.forEach { app.store.delete(it) };app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(operation))
            app.tasks.delete(task);app.store.setDefaultWallet(old)
            for(wallet in listOf(a,b)) { app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id) }
        }
    }

    @Test fun strongIdentityStillDeduplicatesIndependentCardsAndBoundedChildKeysAcceptNonstandardParents() {
        val name="synthetic-strong-batch-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val wallet=store.saveAccount(LedgerAccount(name="合成强身份钱包",amountCents=100000,kind="cash"));store.setDefaultWallet(wallet.uid)
            val identity="SYNTHETIC-STRONG-${UUID.randomUUID()}"
            val bill=ParsedBill(8765,Direction.EXPENSE,"合成强身份商家","其他",1793491200000,identity,true,"",true)
            val capture=Capture(Source.IMAGE,"image","微信交易号：$identity")
            val candidates=listOf("bank_0","bank_1").map { TransactionCandidate(CandidateId(it),bill,capture.text) }
            val operation="recognition:${UUID.randomUUID()}"
            store.freezeRecognitionBatch(capture,candidates,operation,"Asia/Shanghai",store.classificationRules(),RecognitionPolicy.USER_INITIATED)
            candidates.forEach { store.submitBatchCandidate(operation,it.id) }
            assertEquals(1,store.all().size);assertEquals(91235L,store.getAccount(wallet.id)!!.amountCents)
            assertEquals(BatchCounts(1,1,0,0),store.recognitionBatch(operation)!!.counts)
            val longParent="synthetic-parent:"+"x".repeat(300)
            val candidate=TransactionCandidate(CandidateId("z".repeat(128)),bill.copy(transactionId=null,amountCents=1234,merchant="合成长键商家"))
            val result=store.ingestCandidate(Capture(Source.IMAGE,"image","合成长键凭证"),candidate,longParent).outcome as IngestResult.Saved
            assertEquals(result.record.uid,store.committedCandidate(longParent,candidate.id)!!.recordUid)
            assertTrue(candidateOperationKey(longParent,candidate.id).length<=160)
            assertEquals("legacy-parent",candidateOperationKey("legacy-parent",CandidateId.SINGLE))
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun pendingChildKeepsFrozenRuleButFollowsAnExplicitCategoryReplacement() {
        val name="synthetic-batch-category-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val original=store.saveCategory(name="合成冻结用途",direction=Direction.EXPENSE)
            val replacement=store.saveCategory(name="合成替代用途",direction=Direction.EXPENSE)
            val bill=ParsedBill(1111,Direction.EXPENSE,"合成冻结分类商家","其他",1793491200000,null,true,"",true)
            val rule=ClassificationRule("synthetic-frozen","合成旧规则",true,Direction.EXPENSE,original.id,0,
                listOf(ClassificationCondition(ClassificationField.MERCHANT,ClassificationOperator.CONTAINS,"合成冻结")))
            val snapshot=ClassificationSnapshot("synthetic-frozen-snapshot",listOf(rule),emptyList())
            val operation="recognition:${UUID.randomUUID()}"
            val candidates=listOf("bank_0","bank_1").map { TransactionCandidate(CandidateId(it),bill) }
            store.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成冻结分类"),candidates,operation,"Asia/Shanghai",snapshot,RecognitionPolicy.USER_INITIATED)
            store.submitBatchCandidate(operation,candidates[0].id)
            store.confirmCategoryDeletion(original.id,replacement.id,store.previewCategoryDeletion(original.id).previewToken)
            store.submitBatchCandidate(operation,candidates[1].id)
            val records=store.all()
            assertEquals(2,records.size);assertTrue(records.all { it.bill.category==replacement.name })
            assertTrue(records.all { it.bill.categoryDecision!!.categoryId==replacement.id && it.bill.categoryDecision!!.ruleVersion==snapshot.version })
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun batchReceiptFailureRollsBackTheBillBalanceAndSingleReceiptTogether() {
        val name="synthetic-batch-atomic-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val wallet=store.saveAccount(LedgerAccount(name="合成批次回执钱包",amountCents=100000,kind="cash"));store.setDefaultWallet(wallet.uid)
            val bill=ParsedBill(1234,Direction.EXPENSE,"合成批次回执","其他",1793491200000,null,true,"",true)
            val candidates=listOf("bank_0","bank_1").map { TransactionCandidate(CandidateId(it),bill) }
            val key="recognition:${UUID.randomUUID()}"
            store.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成批次回执"),candidates,key,"Asia/Shanghai",store.classificationRules(),RecognitionPolicy.USER_INITIATED)
            store.writableDatabase.execSQL("""CREATE TRIGGER reject_batch_receipt BEFORE INSERT ON recognition_batches
                WHEN NEW.batch_json LIKE '%"status":"ADDED"%' BEGIN SELECT RAISE(ABORT,'synthetic batch receipt failure'); END""")
            assertNotNull(runCatching { store.submitBatchCandidate(key,candidates[0].id) }.exceptionOrNull())
            assertTrue(store.all().isEmpty());assertEquals(100000L,store.getAccount(wallet.id)!!.amountCents)
            assertNull(store.committedCandidate(key,candidates[0].id))
            assertEquals("PENDING",store.recognitionBatch(key)!!.items[0].status)
            assertTrue(store.walletMovements(wallet.uid).none { it.recordUid!=null })
            store.writableDatabase.execSQL("DROP TRIGGER reject_batch_receipt")
            assertEquals(1,store.submitBatchCandidate(key,candidates[0].id).counts.added)
            assertEquals(98766L,store.getAccount(wallet.id)!!.amountCents)
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun schema14MigrationPreservesFinancialFactsAndCreatesAnEmptyBatchDirectory() {
        val name="synthetic-schema14-batch-${UUID.randomUUID()}.db"
        try {
            lateinit var before:LedgerRecord
            lateinit var snapshot:WalletSnapshot
            LedgerStore(app,name).use { store ->
                val wallet=store.saveAccount(LedgerAccount(name="合成升级钱包",amountCents=10000,kind="cash"));store.setDefaultWallet(wallet.uid)
                before=(store.ingest(Capture(Source.MANUAL,"manual","合成升级账单"),
                    ParsedBill(1234,Direction.EXPENSE,"合成升级商家","其他",1793491200000,null,true,"",true)) as IngestResult.Saved).record
                snapshot=store.walletSnapshot()
                store.writableDatabase.execSQL("DROP TABLE recognition_batches");store.writableDatabase.version=14
            }
            LedgerStore(app,name).use { store ->
                assertEquals(16,store.readableDatabase.version);assertEquals(before,store.get(before.id))
                assertEquals(snapshot,store.walletSnapshot());assertEquals(0,store.recognitionBatchBackup().length())
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun bundledPixelOcrSeparatesTwoSyntheticBankCards()=runBlocking {
        app.taskRecovery.await()
        val wallet=app.store.saveAccount(LedgerAccount(name="合成像素多卡",amountCents=50000,kind="bank",bankCode="cmb",cardLast4="0007"))
        val task=app.tasks.create(Source.IMAGE,"image");val key=app.tasks.operationKey(task)!!
        val bitmap=android.graphics.Bitmap.createBitmap(1080,1600,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK;textSize=42f }
        fun card(amount:String,date:String,y:Int) {
            listOf("招商银行","交易成功提醒","交易类型：网上快捷支付","交易金额：${amount}元","交易卡号：尾号0007",
                "交易日期：$date","账户余额：9999.99元").forEachIndexed { i,line -> canvas.drawText(line,60f,(y+i*65).toFloat(),paint) }
        }
        card("12.34","2026-11-01",100);card("56.78","2026-11-02",850)
        val image=java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);it.toByteArray() };bitmap.recycle()
        var records=listOf<Long>()
        try {
            val result=CaptureProcessor(app).processPrepared(image,Source.IMAGE,"image",task) { true } as IngestResult.Batch
            records=result.result.items.mapNotNull { it.recordId }
            assertEquals(BatchCounts(2,0,0,0),result.result.counts)
            assertEquals(listOf(1234L,5678L),result.result.items.map { it.amountCents })
            assertEquals(43088L,app.store.getAccount(wallet.id)!!.amountCents)
            assertTrue(records.all { app.store.get(it)!!.wallet!!.accountUid==wallet.uid })
        } finally {
            records.forEach { app.store.delete(it) };app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(key));app.tasks.delete(task)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun zipBackupRestoresBatchMappingsAndWalletSnapshotWithoutReplay() {
        val sourceName="synthetic-batch-zip-source-${UUID.randomUUID()}.db"
        val targetName="synthetic-batch-zip-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source ->
            val wallet=source.saveAccount(LedgerAccount(name="合成批次ZIP钱包",amountCents=100000,kind="cash"));source.setDefaultWallet(wallet.uid)
            val bill=ParsedBill(4321,Direction.EXPENSE,"合成ZIP卡片","其他",1793491200000,null,true,"",true)
            val candidates=listOf("bank_0","bank_1").map { TransactionCandidate(CandidateId(it),bill) }
            val key="recognition:${UUID.randomUUID()}"
            source.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成ZIP多卡"),candidates,key,"Asia/Shanghai",source.classificationRules(),RecognitionPolicy.USER_INITIATED)
            candidates.forEach { source.submitBatchCandidate(key,it.id) }
            val bytes=java.io.ByteArrayOutputStream().use { LedgerArchive.write(source,net.ankio.ledger.capture.ChannelSettings(app),it);it.toByteArray() }
            LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared -> LedgerStore(app,targetName).use { target ->
                LedgerArchive.restore(target,prepared)
                val restored=target.recognitionBatch(key)!!
                assertEquals(BatchCounts(2,0,0,0),restored.counts);assertTrue(restored.complete)
                assertEquals(91358L,target.getAccountByUid(wallet.uid)!!.amountCents)
                assertTrue(restored.items.all { item -> item.recordId!=null && target.get(item.recordId)!!.uid==item.recordUID })
                candidates.forEach { target.submitBatchCandidate(key,it.id) }
                assertEquals(91358L,target.getAccountByUid(wallet.uid)!!.amountCents)
                assertEquals(2,target.all().size)
            } }
        } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun cancellationAfterOneCommitRetainsItsReceiptAndResumesOnlyTheOtherChild()=runBlocking {
        app.taskRecovery.await()
        val old=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成取消续传钱包",amountCents=100000,kind="cash"));app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.IMAGE,"image");val key=app.tasks.operationKey(task)!!
        val merchant="合成取消续传${UUID.randomUUID()}"
        val bill=ParsedBill(5432,Direction.EXPENSE,merchant,"其他",1793491200000,null,true,"",true)
        var calls=0
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_ ->
            calls++;prepared(image);RecognitionResult("合成取消续传",listOf(TransactionCandidate(CandidateId("bank_0"),bill),TransactionCandidate(CandidateId("bank_1"),bill)))
        }))
        try {
            val cancelled=runCatching { processor.processPrepared(byteArrayOf(7),Source.IMAGE,"image",task) {
                app.store.recognitionBatch(key)?.counts?.added!=1
            } }.exceptionOrNull()
            assertTrue(cancelled is kotlinx.coroutines.CancellationException)
            assertEquals("CANCELLED",app.tasks.status(task));assertEquals(94568L,app.store.getAccount(wallet.id)!!.amountCents)
            assertTrue(app.tasks.message(task)!!.contains("新增 1"));assertFalse(app.tasks.message(task)!!.contains("未新增账单"))
            assertTrue(app.captureQueue.retryBatch(task))
            kotlinx.coroutines.withTimeout(10000) { while(app.tasks.status(task)!="SUCCEEDED")kotlinx.coroutines.delay(25) }
            assertEquals(1,calls);assertEquals(89136L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(BatchCounts(2,0,0,0),app.store.recognitionBatch(key)!!.counts)
        } finally {
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(key));app.tasks.delete(task);app.store.setDefaultWallet(old)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun malformedPortableCandidateAmountIsRejectedBeforeRestore() {
        val name="synthetic-batch-invalid-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val bill=ParsedBill(1234,Direction.EXPENSE,"合成校验卡","其他",1793491200000,null,true,"",true)
            val candidates=listOf("bank_0","bank_1").map { TransactionCandidate(CandidateId(it),bill) }
            store.freezeRecognitionBatch(Capture(Source.IMAGE,"image","合成校验"),candidates,"recognition:${UUID.randomUUID()}","Asia/Shanghai",store.classificationRules(),RecognitionPolicy.USER_INITIATED)
            val root=org.json.JSONObject(LedgerBackup.encode(store,net.ankio.ledger.capture.ChannelSettings(app)))
            root.getJSONArray("recognitionBatches").getJSONObject(0).getJSONArray("items").getJSONObject(0)
                .getJSONObject("bill").put("amount","1234")
            assertNotNull(runCatching { LedgerBackup.decode(root.toString().byteInputStream()) }.exceptionOrNull())
            assertTrue(store.all().isEmpty())
        } } finally { app.deleteDatabase(name) }
    }

}
