package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.RecognitionBackend
import net.ankio.ledger.ocr.RecognitionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real CaptureProcessor and two SQLite databases; the recognizer returns synthetic local values. */
@RunWith(AndroidJUnit4::class)
class RecognitionCommitRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun singleCandidateKeepsTheRecognizedBillScreenshotWalletAndFeedback() = runBlocking {
        app.taskRecovery.await()
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name = "合成单候选钱包", amountCents = 10_000, kind = "cash"))
        app.store.setDefaultWallet(wallet.uid)
        val suffix = UUID.randomUUID().toString()
        val task = app.tasks.create(Source.IMAGE, "image")
        val bill = ParsedBill(1250, Direction.EXPENSE, "合成候选商户$suffix", "餐饮", 1_798_776_000_000,
            "SYNTHETIC-CANDIDATE-$suffix", true, "", true)
        val image = "synthetic-candidate-$suffix".toByteArray()
        val backend = RecognitionBackend { bytes, prepared, _, _, _, _ ->
            prepared(bytes)
            RecognitionResult("合成付款成功 12.50", bill, screenshot = bytes)
        }
        try {
            val result = CaptureProcessor(app, OcrClient(app, backend))
                .processPrepared(image, Source.IMAGE, "image", task) { true } as IngestResult.Saved
            val record = result.record
            assertEquals(bill.copy(category="其他"), record.bill.copy(categoryDecision=null))
            assertEquals(CategorySource.FALLBACK,record.bill.categoryDecision!!.source)
            assertEquals(app.tasks.classificationSnapshot(task)!!.version,record.bill.categoryDecision!!.ruleVersion)
            assertEquals(Source.IMAGE, record.capture.source); assertEquals("image", record.capture.origin)
            assertEquals("合成付款成功 12.50", record.capture.text)
            assertEquals(RecordState.POSTED, record.state)
            assertEquals(8750L, app.store.getAccount(wallet.id)!!.amountCents)
            assertArrayEquals(image, app.store.screenshot(record.id))
            assertEquals("SUCCEEDED", app.tasks.status(task)); assertEquals("已记账 ¥12.50", app.tasks.message(task))
            val receipt = app.store.committedCandidate(app.tasks.operationKey(task)!!, CandidateId.SINGLE)!!
            assertEquals(CandidateId.SINGLE, receipt.candidateId); assertEquals(record.uid, receipt.recordUid)
            assertEquals(record.id, receipt.record!!.id)
        } finally {
            app.store.all().filter { it.bill.merchant == bill.merchant }.forEach { app.store.delete(it.id) }
            app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
            app.tasks.delete(task); app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun committedBillSurvivesTaskWriteFailureAndRestoresFeedbackWithoutRecognizingOrChargingAgain() = runBlocking {
        app.taskRecovery.await()
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name = "合成提交恢复钱包", amountCents = 1000, kind = "cash"))
        app.store.setDefaultWallet(wallet.uid)
        val origin = "payment_auto:com.tencent.mm"
        val task = app.tasks.create(Source.OCR, origin)
        val event = "payment_page:${UUID.randomUUID()}:${"a".repeat(64)}"
        app.tasks.diagnose(task, "payment_identity", event)
        val operation = app.tasks.operationKey(task)!!
        val merchant = "合成提交恢复${UUID.randomUUID().toString().take(8)}"
        val calls = AtomicInteger()
        val backend = RecognitionBackend { image, prepared, _, _, _, _ ->
            calls.incrementAndGet(); prepared(image)
            RecognitionResult("合成付款成功", ParsedBill(3000, Direction.EXPENSE, merchant, "购物", System.currentTimeMillis(),
                "SYNTHETIC-COMMIT-${UUID.randomUUID()}", true, "", true), screenshot = image)
        }
        val client = OcrClient(app, backend)
        val image = UUID.randomUUID().toString().toByteArray()
        val relatedTasks = mutableListOf(task)
        try {
            app.tasks.writableDatabase.execSQL("""CREATE TRIGGER reject_synthetic_task_success BEFORE UPDATE ON tasks
                WHEN NEW.id=$task AND NEW.status='SUCCEEDED' BEGIN SELECT RAISE(ABORT,'synthetic task write failure'); END""")
            val failed = runCatching { CaptureProcessor(app, client).processPrepared(image, Source.OCR, "com.tencent.mm", task) { true } }
            assertNotNull(failed.exceptionOrNull())
            val saved = app.store.all().single { it.bill.merchant == merchant }
            assertEquals(RecordState.POSTED, saved.state); assertEquals(-2000L, app.store.getAccount(wallet.id)!!.amountCents)
            assertNull(app.tasks.recordId(task)); assertEquals("FAILED", app.tasks.status(task))
            assertEquals(saved.uid, app.store.committedCapture(operation)!!.recordUid)
            assertEquals(saved.uid, app.store.committedPaymentEvent(event)!!.recordUid)
            app.tasks.writableDatabase.execSQL("DROP TRIGGER reject_synthetic_task_success")

            // A second task for the same native event can recover before the failed task is repaired.
            val repeat = app.tasks.create(Source.OCR, origin); relatedTasks += repeat
            app.tasks.diagnose(repeat, "payment_identity", event)
            assertEquals(IngestResult.DuplicateSkipped, CaptureProcessor(app, client).processPrepared(image, Source.OCR, "com.tencent.mm", repeat) { true })
            assertEquals(saved.id, app.tasks.recordId(repeat)); assertEquals(1, calls.get())

            RecognitionTasks(app).use { reopenedTasks -> LedgerStore(app).use { reopenedLedger ->
                assertEquals(operation, reopenedTasks.operationKey(task))
                RecognitionCommitRecovery.recoverAll(reopenedTasks, reopenedLedger)
                reopenedTasks.recoverInterrupted()
                assertEquals("SUCCEEDED", reopenedTasks.status(task)); assertEquals(saved.id, reopenedTasks.recordId(task))
                assertEquals("已记账 ¥30.00", reopenedTasks.message(task))
                assertEquals(-2000L, reopenedLedger.getAccount(wallet.id)!!.amountCents)
            } }
            assertEquals(IngestResult.DuplicateSkipped, CaptureProcessor(app, client).processPrepared(image, Source.OCR, "com.tencent.mm", task) { true })
            assertEquals(1, calls.get()); assertEquals(-2000L, app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1, app.store.walletMovements(wallet.uid).count { it.recordUid == saved.uid })
        } finally {
            app.tasks.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_synthetic_task_success")
            app.store.all().filter { it.bill.merchant == merchant }.forEach { app.store.delete(it.id) }
            relatedTasks.forEach { id ->
                app.tasks.operationKey(id)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
                app.tasks.delete(id)
            }
            app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf("capture-event:$event"))
            app.store.writableDatabase.delete("capture_events", "event_key=?", arrayOf(event))
            app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun processorReceiptFailureRollsBackTheCandidateAndRetryChargesOnlyOnce() = runBlocking {
        app.taskRecovery.await()
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name = "合成候选回滚钱包", amountCents = 10_000, kind = "cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task = app.tasks.create(Source.IMAGE, "image")
        val operation = app.tasks.operationKey(task)!!
        val suffix = UUID.randomUUID().toString()
        val merchant = "合成候选回滚$suffix"
        val calls = AtomicInteger()
        val backend = RecognitionBackend { image, prepared, _, _, _, _ ->
            calls.incrementAndGet(); prepared(image)
            RecognitionResult("合成付款成功 25.00", ParsedBill(2500, Direction.EXPENSE, merchant, "购物",
                System.currentTimeMillis(), "SYNTHETIC-ROLLBACK-$suffix", true, "", true), screenshot = image)
        }
        val processor = CaptureProcessor(app, OcrClient(app, backend))
        val image = "synthetic-rollback-$suffix".toByteArray()
        val movementsBefore = app.store.walletMovements(wallet.uid)
        try {
            app.store.writableDatabase.execSQL("""CREATE TRIGGER reject_synthetic_candidate_receipt BEFORE INSERT ON wallet_submissions
                WHEN NEW.kind='CAPTURE_RESULT' AND NEW.token='$operation' BEGIN SELECT RAISE(ABORT,'synthetic receipt failure'); END""")
            assertNotNull(runCatching { processor.processPrepared(image, Source.IMAGE, "image", task) { true } }.exceptionOrNull())
            assertTrue(app.store.all().none { it.bill.merchant == merchant })
            assertEquals(10_000L, app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(movementsBefore, app.store.walletMovements(wallet.uid))
            assertNull(app.store.committedCandidate(operation, CandidateId.SINGLE))
            assertEquals("FAILED", app.tasks.status(task)); assertNull(app.tasks.recordId(task))
            app.store.writableDatabase.execSQL("DROP TRIGGER reject_synthetic_candidate_receipt")

            val saved = processor.processPrepared(image, Source.IMAGE, "image", task) { true } as IngestResult.Saved
            assertEquals(IngestResult.DuplicateSkipped, processor.processPrepared(image, Source.IMAGE, "image", task) { true })
            assertEquals(2, calls.get()); assertEquals(7500L, app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1, app.store.walletMovements(wallet.uid).count { it.recordUid == saved.record.uid })
            assertEquals("SUCCEEDED", app.tasks.status(task)); assertEquals("已记账 ¥25.00", app.tasks.message(task))
        } finally {
            app.store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_synthetic_candidate_receipt")
            app.store.all().filter { it.bill.merchant == merchant }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(operation))
            app.tasks.delete(task); app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun receiptWriteFailureRollsBackTheBillBalanceAndMovementTogether() {
        val name = "recognition-receipt-${UUID.randomUUID()}.db"
        LedgerStore(app, name).use { store ->
            try {
                val wallet = store.saveAccount(LedgerAccount(name = "合成回执事务钱包", amountCents = 1000))
                store.setDefaultWallet(wallet.uid)
                val before = store.walletSnapshot()
                store.writableDatabase.execSQL("""CREATE TRIGGER reject_synthetic_receipt BEFORE INSERT ON wallet_submissions
                    WHEN NEW.kind='CAPTURE_RESULT' BEGIN SELECT RAISE(ABORT,'synthetic receipt write failure'); END""")
                val operation = "recognition:${UUID.randomUUID()}"
                assertNotNull(runCatching {
                    store.ingestRecognition(Capture(Source.OCR, "com.tencent.mm", "合成付款成功"),
                        ParsedBill(3000, Direction.EXPENSE, "合成商户", "购物", System.currentTimeMillis(), null, true, "", true), operation)
                }.exceptionOrNull())
                assertTrue(store.all().isEmpty()); assertEquals(1000L, store.getAccount(wallet.id)!!.amountCents)
                assertEquals(before, store.walletSnapshot()); assertNull(store.committedCapture(operation))
            } finally { store.close(); app.deleteDatabase(name) }
        }
    }

    @Test fun cancellationAfterRecognitionDoesNotCommitTheSingleCandidate() = runBlocking {
        app.taskRecovery.await()
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name = "合成候选取消钱包", amountCents = 10_000, kind = "cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task = app.tasks.create(Source.IMAGE, "image")
        val operation = app.tasks.operationKey(task)!!
        val suffix = UUID.randomUUID().toString()
        val merchant = "合成取消商户$suffix"
        var allowed = true
        val backend = RecognitionBackend { image, prepared, _, _, _, _ ->
            prepared(image); allowed = false
            RecognitionResult("合成付款成功 25.00", ParsedBill(2500, Direction.EXPENSE, merchant, "购物",
                System.currentTimeMillis(), "SYNTHETIC-CANCEL-$suffix", true, "", true), screenshot = image)
        }
        val movementsBefore = app.store.walletMovements(wallet.uid)
        try {
            val error = runCatching {
                CaptureProcessor(app, OcrClient(app, backend)).processPrepared(suffix.toByteArray(), Source.IMAGE, "image", task) { allowed }
            }.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertEquals("CANCELLED", app.tasks.status(task)); assertEquals("识别已停止，未新增账单", app.tasks.message(task))
            assertNull(app.tasks.recordId(task)); assertNull(app.store.committedCandidate(operation, CandidateId.SINGLE))
            assertTrue(app.store.all().none { it.bill.merchant == merchant })
            assertEquals(10_000L, app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(movementsBefore, app.store.walletMovements(wallet.uid))
        } finally {
            app.store.all().filter { it.bill.merchant == merchant }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(operation))
            app.tasks.delete(task); app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun versionFourTasksReceiveStableUniqueOperationIdsAndReopeningDoesNotChangeThem() {
        val name = "recognition-v4-${UUID.randomUUID()}.db"
        try {
            app.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("""CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,origin TEXT NOT NULL,
                    created_at INTEGER NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,record_id INTEGER,image BLOB,
                    diagnostic TEXT NOT NULL DEFAULT '{}',shake_authorization TEXT)""")
                db.execSQL("INSERT INTO tasks(source,origin,created_at,status,message) VALUES('IMAGE','image',1,'FAILED','synthetic')")
                db.version = 4
            }
            val operation = RecognitionTasks(app, name).use { tasks ->
                assertEquals(7, tasks.readableDatabase.version)
                val existing = tasks.operationKey(1)!!
                val newer = tasks.create(Source.IMAGE, "image")
                assertNotEquals(existing, tasks.operationKey(newer)); existing
            }
            RecognitionTasks(app, name).use { assertEquals(operation, it.operationKey(1)) }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun independentSingleTasksWithTheSameDayAndAmountBothPost() = runBlocking {
        app.taskRecovery.await()
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name = "合成单笔日金额钱包", amountCents = 10_000, kind = "cash"))
        app.store.setDefaultWallet(wallet.uid)
        val suffix = UUID.randomUUID().toString()
        val merchant = "合成独立单笔$suffix"
        val noon = java.time.ZonedDateTime.of(2026, 11, 8, 12, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tasks = mutableListOf<Long>()
        val records = mutableListOf<LedgerRecord>()
        try {
            for (index in 0..1) {
                val task = app.tasks.create(Source.IMAGE, "image").also(tasks::add)
                val bill = ParsedBill(2500, Direction.EXPENSE, merchant, "购物", noon + index * 3_600_000,
                    "SYNTHETIC-INDEPENDENT-$suffix-$index", true, "", true)
                val backend = RecognitionBackend { image, prepared, _, _, _, _ ->
                    prepared(image)
                    RecognitionResult("合成付款成功 25.00 交易$index", bill, screenshot = image)
                }
                val saved = CaptureProcessor(app, OcrClient(app, backend))
                    .processPrepared("synthetic-$suffix-$index".toByteArray(), Source.IMAGE, "image", task) { true } as IngestResult.Saved
                records += saved.record
                assertEquals(RecordState.POSTED, saved.record.state)
                assertEquals("SUCCEEDED", app.tasks.status(task))
            }
            assertEquals(2, records.map { it.uid }.distinct().size)
            assertEquals(2, app.store.all().count { it.bill.merchant == merchant })
            assertEquals(5000L, app.store.getAccount(wallet.id)!!.amountCents)
        } finally {
            app.store.all().filter { it.bill.merchant == merchant }.forEach { app.store.delete(it.id) }
            tasks.forEach { task ->
                app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
                app.tasks.delete(task)
            }
            app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun recoveryAfterShakeRevocationRetainsOnlyTheAlreadyCommittedBillAndDoesNotReauthorize() = runBlocking {
        app.taskRecovery.await()
        val pkg = InstrumentationRegistry.getInstrumentation().context.packageName
        val wasSelected = pkg in app.channels.selectedShakePackages()
        val wasEnabled = app.channels.enabled(ChannelSettings.Channel.OCR)
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name = "合成撤销后恢复钱包", amountCents = 1000))
        app.store.setDefaultWallet(wallet.uid)
        val suffix = UUID.randomUUID().toString()
        val merchant = "合成授权内商户$suffix"
        val taskIds = mutableListOf<Long>()
        try {
            app.channels.set(ChannelSettings.Channel.OCR, true); app.channels.setShakePackage(pkg, true)
            val authorization = app.channels.shakeAuthorization(pkg)!!
            val task = app.tasks.create(Source.OCR, "shake:$pkg", authorization).also(taskIds::add)
            val uncommitted = app.tasks.create(Source.OCR, "shake:$pkg", authorization).also(taskIds::add)
            app.tasks.queued(task, suffix.toByteArray()); app.tasks.queued(uncommitted, "$suffix-pending".toByteArray())
            val saved = app.store.ingestRecognition(Capture(Source.OCR, pkg, "合成授权内已提交"),
                ParsedBill(3000, Direction.EXPENSE, merchant, "购物", System.currentTimeMillis(),
                    "SYNTHETIC-AUTH-COMMIT-$suffix", true, "", true), app.tasks.operationKey(task)!!) as IngestResult.Saved
            // Exercise the actual app cancellation path, including its persisted task statuses.
            app.channels.setShakePackage(pkg, false)
            assertFalse(app.channels.shakeAuthorized(authorization))
            assertEquals("CANCELLED", app.tasks.status(task)); assertEquals("CANCELLED", app.tasks.status(uncommitted))
            RecognitionTasks(app).use { reopenedTasks -> LedgerStore(app).use { reopenedLedger ->
                RecognitionCommitRecovery.recoverAll(reopenedTasks, reopenedLedger); reopenedTasks.recoverInterrupted()
                assertEquals("SUCCEEDED", reopenedTasks.status(task)); assertEquals(saved.record.id, reopenedTasks.recordId(task))
                assertEquals("CANCELLED", reopenedTasks.status(uncommitted)); assertNull(reopenedTasks.recordId(uncommitted))
                assertTrue(reopenedTasks.pendingQueued().none { it.first in taskIds })
                assertEquals(-2000L, reopenedLedger.getAccount(wallet.id)!!.amountCents)
                assertEquals(1, reopenedLedger.walletMovements(wallet.uid).count { it.recordUid == saved.record.uid })
            } }
            assertFalse(pkg in app.channels.selectedShakePackages())
        } finally {
            app.store.all().filter { it.bill.merchant == merchant }.forEach { app.store.delete(it.id) }
            taskIds.forEach { id ->
                app.tasks.operationKey(id)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
                app.tasks.delete(id)
            }
            app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
            if (wasSelected) app.channels.setShakePackage(pkg, true) else if (pkg in app.channels.selectedShakePackages()) app.channels.setShakePackage(pkg, false)
            app.channels.set(ChannelSettings.Channel.OCR, wasEnabled)
        }
    }

    @Test fun confirmedRefundRedirectsBothTaskAndEventReceiptsToTheRetainedOriginal() {
        val suffix = UUID.randomUUID().toString()
        val ledgerName = "refund-receipt-$suffix.db"; val taskName = "refund-receipt-task-$suffix.db"
        try {
            LedgerStore(app, ledgerName).use { store -> RecognitionTasks(app, taskName).use { tasks ->
                val wallet = store.saveAccount(LedgerAccount(name = "合成退款回执钱包", amountCents = 10_000))
                store.setDefaultWallet(wallet.uid)
                val capture = Capture(Source.OCR, "com.tencent.mm", "合成原付款")
                val original = (store.ingest(capture, ParsedBill(10_000, Direction.EXPENSE, "合成退款商户", "购物", capture.receivedAt,
                    "SYNTHETIC-ORIGINAL-$suffix", true, "", true)) as IngestResult.Saved).record
                val task = tasks.create(Source.OCR, "payment_auto:com.tencent.mm")
                val operation = tasks.operationKey(task)!!
                val event = "payment_page:${UUID.randomUUID()}:${"b".repeat(64)}"
                tasks.diagnose(task, "payment_identity", event)
                try {
                    val pending = store.ingestRecognition(capture.copy(text = "合成退款成功，请核对原账单"),
                        ParsedBill(null, Direction.EXPENSE, "合成退款商户", "购物", capture.receivedAt, null, false, "需核对", true,
                            standaloneRefund = StandaloneRefundEvidence("wechat", 3000, "SYNTHETIC-REFUND-$suffix", null)),
                        operation, eventKey = event) as IngestResult.Saved
                    assertEquals(RecordState.REVIEW, pending.record.state)
                    assertEquals(pending.record.uid, store.committedCapture(operation)!!.recordUid)
                    store.confirmRefund(pending.record.id, original.id)
                    assertNull(store.get(pending.record.id))
                    assertEquals(original.uid, store.committedCapture(operation)!!.recordUid)
                    assertEquals(original.uid, store.committedPaymentEvent(event)!!.recordUid)
                    RecognitionCommitRecovery.recoverAll(tasks, store)
                    assertEquals(original.id, tasks.recordId(task)); assertEquals("退款已更新", tasks.message(task))
                    assertEquals(3000L, store.getAccount(wallet.id)!!.amountCents)
                } finally { tasks.delete(task) }
            } }
        } finally { app.deleteDatabase(ledgerName); app.deleteDatabase(taskName) }
    }
}
