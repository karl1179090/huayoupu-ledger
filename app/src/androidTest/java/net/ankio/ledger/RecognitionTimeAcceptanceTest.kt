package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import net.ankio.ledger.capture.PaymentRecognitionQueue
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Public recognition entry, real task/ledger SQLite and only synthetic receipt text. */
@RunWith(AndroidJUnit4::class)
class RecognitionTimeAcceptanceTest {
    private val app = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun firstActualRecognitionTimeSurvivesFailedAttemptReopenCrossDayAndZoneChange() = runBlocking {
        app.taskRecovery.await()
        val oldDefault = app.store.walletSettings().defaultAccountUid
        val wallet = app.store.saveAccount(LedgerAccount(name="合成时间测试钱包", amountCents=10000, kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task = app.tasks.create(Source.IMAGE, "image")
        val suffix = UUID.randomUUID().toString()
        val raw = "支付成功\n付款金额：12.00元\n交易单号：SYNTHETIC-TIME-$suffix"
        var clock = RecognitionTimeContext(Instant.parse("2026-12-31T15:59:00Z").toEpochMilli(), "Asia/Shanghai")
        var fail = true
        val backend = object : RecognitionBackend {
            override suspend fun recognize(image: ByteArray, onPrepared: (ByteArray) -> Unit, stillAllowed: () -> Boolean,
                livePaymentAt: Long?, policy: RecognitionPolicy, origin: String): RecognitionResult = error("context required")
            override suspend fun recognize(image: ByteArray, onPrepared: (ByteArray) -> Unit, stillAllowed: () -> Boolean,
                livePaymentAt: Long?, policy: RecognitionPolicy, origin: String,
                recognitionTime: () -> RecognitionTimeContext): RecognitionResult {
                // Queue/preparation delay crosses midnight before actual recognition begins.
                if (fail) clock = RecognitionTimeContext(Instant.parse("2026-12-31T16:01:23.456Z").toEpochMilli(), "Asia/Shanghai")
                onPrepared(image)
                val time = recognitionTime()
                if (fail) { fail = false; error("synthetic interruption after recognition started") }
                return RecognitionResult(raw, LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine), 0, 0,
                    time.recognitionAt, policy=policy, origin=origin, timeContext=time), screenshot=image)
            }
        }
        val processor = CaptureProcessor(app, OcrClient(app, backend), recognitionClock={ clock })
        val bytes = "synthetic-time-image-$suffix".toByteArray()
        try {
            assertNull(app.tasks.recognitionTime(task))
            assertTrue(runCatching { processor.processPrepared(bytes, Source.IMAGE, "image", task) { true } }.isFailure)
            val expected = RecognitionTimeContext(Instant.parse("2026-12-31T16:01:23.456Z").toEpochMilli(), "Asia/Shanghai")
            RecognitionTasks(app).use { reopened -> assertEquals(expected, reopened.recognitionTime(task)) }
            clock = RecognitionTimeContext(Instant.parse("2027-01-03T12:00:00Z").toEpochMilli(), "America/New_York")
            val result = processor.processPrepared(bytes, Source.IMAGE, "image", task) { true } as IngestResult.Saved
            assertEquals(expected.recognitionAt, result.record.bill.occurredAt)
            assertEquals(expected.recognitionAt, result.record.capture.receivedAt)
            assertEquals("2027-01-01", result.record.bill.receipt!!.time!!.localDate)
            assertEquals("Asia/Shanghai", result.record.bill.receipt!!.time!!.zoneId)
            assertEquals("RECOGNITION_FALLBACK", result.record.bill.receipt!!.time!!.source)
            assertEquals(RecordState.POSTED, result.record.state)
            LedgerStore(app).use { reopened -> assertEquals(result.record.bill.receipt, reopened.get(result.record.id)!!.bill.receipt) }
        } finally {
            app.store.all().filter { it.bill.transactionId == "SYNTHETIC-TIME-$suffix" }.forEach { app.store.delete(it.id) }
            app.tasks.delete(task)
            app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
    }
    @Test fun savingANoteKeepsExactTimeAndActualTimeEditKeepsTruthfulProvenance() {
        val name = "synthetic-time-edit-${UUID.randomUUID()}.db"
        try { LedgerStore(app, name).prepareSyntheticDefaultWallet().use { store ->
            val time = RecognitionTimeContext(Instant.parse("2026-12-31T16:01:23.456Z").toEpochMilli(), "Asia/Shanghai")
            val raw = "支付成功\n付款金额：12.00元"
            val bill = LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine), 0, 0, time.recognitionAt,
                origin="image", timeContext=time)!!
            val original = (store.ingest(Capture(Source.IMAGE,"image",raw,time.recognitionAt),bill) as IngestResult.Saved).record
            val noteOnly = store.saveEdited(original.copy(note="合成备注"))
            assertEquals(Instant.parse("2026-12-31T16:01:23.456Z").toEpochMilli(), noteOnly.bill.occurredAt)
            assertEquals(original.bill.receipt, noteOnly.bill.receipt)
            val editedAt = Instant.parse("2026-12-30T04:25:00Z").toEpochMilli()
            val edited = store.saveEdited(noteOnly.copy(bill=noteOnly.bill.copy(occurredAt=editedAt)))
            val evidence = edited.bill.receipt!!.time!!
            assertEquals(editedAt, evidence.accountingAt)
            assertEquals("2026-12-30", evidence.localDate)
            assertEquals("MANUAL", evidence.source)
            assertEquals("USER", evidence.reference)
            assertEquals(time.recognitionAt, evidence.recognitionAt)
            assertTrue(edited.bill.timeExplicit)
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun jsonAndZipBackupPreserveTimeBasisAndRejectContradictoryFinalTime() {
        val sourceName = "synthetic-time-source-${UUID.randomUUID()}.db"
        val targetName = "synthetic-time-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).prepareSyntheticDefaultWallet().use { source ->
            val time = RecognitionTimeContext(Instant.parse("2026-02-28T12:00:00Z").toEpochMilli(), "Asia/Shanghai")
            val raw = "支付成功\n付款金额：12.00元\n交易日期：02月29日"
            val bill = LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,time.recognitionAt,
                origin="image",timeContext=time)!!
            val original = (source.ingest(Capture(Source.IMAGE,"image",raw,time.recognitionAt),bill) as IngestResult.Saved).record
            val json=LedgerBackup.encode(source,ChannelSettings(app))
            val zipped=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(app),it) }.toByteArray()
            for(bytes in listOf(json.toByteArray(),zipped)) {
                LedgerStore(app,targetName).use { target ->
                    LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                        assertEquals(1,LedgerArchive.restore(target,prepared))
                        val restored=target.all().single()
                        assertEquals(original.bill.receipt,restored.bill.receipt)
                        assertEquals("2024-02-29",restored.bill.receipt!!.time!!.localDate)
                        assertEquals("YEAR_INFERRED",restored.bill.receipt!!.time!!.source)
                        val financial=target.walletSnapshot()
                        assertEquals(0,LedgerArchive.restore(target,prepared))
                        assertEquals(financial,target.walletSnapshot())
                    }
                }
                app.deleteDatabase(targetName)
            }
            val inconsistent=JSONObject(json)
            inconsistent.getJSONArray("records").getJSONObject(0).getJSONObject("receipt").getJSONObject("time")
                .put("accountingAt",original.bill.occurredAt+1)
            assertTrue(runCatching { LedgerBackup.decode(inconsistent.toString().byteInputStream()) }.isFailure)
        } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun interruptedImageImportResumesWithFrozenTimeAndImageSourceWithoutCapturePermission() = runBlocking {
        app.taskRecovery.await()
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成图片恢复钱包",amountCents=10000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.IMAGE,"image")
        val suffix=UUID.randomUUID().toString()
        val raw="支付成功\n付款金额：12.00元\n交易单号：SYNTHETIC-IMAGE-TIME-$suffix"
        val frozen=RecognitionTimeContext(Instant.parse("2026-12-31T16:01:23.456Z").toEpochMilli(),"Asia/Shanghai")
        val bytes="synthetic-resumable-image-$suffix".toByteArray()
        val backend=object:RecognitionBackend {
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult=error("context required")
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->RecognitionTimeContext):RecognitionResult {
                onPrepared(image)
                val context=recognitionTime()
                return RecognitionResult(raw,LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,context.recognitionAt,
                    policy=policy,origin=origin,timeContext=context),screenshot=image)
            }
        }
        val queue=PaymentRecognitionQueue(app,hasCapturePermission={false})
        try {
            app.tasks.prepared(task,bytes)
            app.tasks.beginRecognition(task) { frozen }
            RecognitionTasks(app).use { it.recoverInterrupted() }
            assertEquals("QUEUED",app.tasks.status(task))
            queue.resume(factory={CaptureProcessor(app,OcrClient(app,backend),recognitionClock={error("must reuse frozen time")})},
                feedback={_,_,_->})
            withTimeout(10000) { while(app.tasks.status(task)=="QUEUED" || app.tasks.status(task)=="RECOGNIZING" || queue.size()>0)delay(25) }
            assertEquals("SUCCEEDED",app.tasks.status(task))
            val record=app.store.get(app.tasks.recordId(task)!!)!!
            assertEquals(Source.IMAGE,record.capture.source)
            assertEquals("image",record.capture.origin)
            assertEquals(frozen.recognitionAt,record.bill.occurredAt)
            assertEquals("2027-01-01",record.bill.receipt!!.time!!.localDate)
            assertEquals(8800L,app.store.getAccount(wallet.id)!!.amountCents)
        } finally {
            queue.cancelAll()
            app.store.all().filter { it.bill.transactionId=="SYNTHETIC-IMAGE-TIME-$suffix" }.forEach { app.store.delete(it.id) }
            app.tasks.delete(task);app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun taskV6MigrationKeepsRuleSnapshotAndLeavesRecognitionClockUnsetUntilProcessing() {
        val ledgerName="synthetic-time-migration-ledger-${UUID.randomUUID()}.db"
        val taskName="synthetic-time-migration-task-${UUID.randomUUID()}.db"
        try { LedgerStore(app,ledgerName).use { ledger ->
            val snapshot=ledger.classificationRules()
            app.openOrCreateDatabase(taskName,0,null).use { old ->
                old.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,origin TEXT NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,record_id INTEGER,image BLOB,diagnostic TEXT NOT NULL DEFAULT '{}',shake_authorization TEXT,operation_uid TEXT NOT NULL,classification_snapshot TEXT NOT NULL)")
                old.execSQL("CREATE UNIQUE INDEX tasks_operation_uid ON tasks(operation_uid)")
                old.execSQL("INSERT INTO tasks(source,origin,created_at,status,message,operation_uid,classification_snapshot) VALUES('IMAGE','image',1798732800000,'QUEUED','合成旧任务',?,?)",
                    arrayOf(UUID.randomUUID().toString(),ClassificationCodec.snapshot(snapshot).toString()))
                old.version=6
            }
            val expected=RecognitionTimeContext(Instant.parse("2027-01-01T04:00:00Z").toEpochMilli(),"America/New_York","2024-06-01")
            RecognitionTasks(app,taskName,classificationStore=ledger).use { migrated ->
                assertEquals(8,migrated.readableDatabase.version)
                assertNull(migrated.recognitionTime(1))
                assertEquals(snapshot,migrated.classificationSnapshot(1))
                assertEquals(expected,migrated.beginRecognition(1) { expected })
            }
            RecognitionTasks(app,taskName,classificationStore=ledger).use { reopened ->
                assertEquals(expected,reopened.beginRecognition(1) { error("clock must not be read again") })
                assertEquals(snapshot,reopened.classificationSnapshot(1))
            }
        } } finally { app.deleteDatabase(ledgerName);app.deleteDatabase(taskName) }
    }

    @Test fun trustedNativeCaptureDayGuidesYearInferenceWithoutFreezingRecognitionTime() {
        val name="synthetic-original-capture-${UUID.randomUUID()}.db"
        try { RecognitionTasks(app,name).use { tasks ->
            val task=tasks.create(Source.OCR,"payment_auto:com.tencent.mm")
            tasks.recordOriginalCaptureDay(task,Instant.parse("2026-12-31T15:59:00Z").toEpochMilli(),"Asia/Shanghai")
            assertNull(tasks.recognitionTime(task))
            val time=tasks.beginRecognition(task) { RecognitionTimeContext(Instant.parse("2026-12-31T16:01:00Z").toEpochMilli(),"Asia/Shanghai") }
            assertEquals("2026-12-31",time.trustedOriginalCaptureDate)
            assertEquals("2027-01-01",time.recognitionDate.toString())
            val bill=LocalOcrBillParser.parse(listOf("支付成功","付款金额：12.00元","交易日期：01月01日").map(::LocalOcrLine),
                0,0,time.recognitionAt,origin="com.tencent.mm",timeContext=time)!!
            assertEquals("2026-01-01",bill.receipt!!.time!!.localDate)
            assertEquals("ORIGINAL_CAPTURE",bill.receipt!!.time!!.reference)
            assertEquals("YEAR_INFERRED",bill.receipt!!.time!!.source)
            val imported=tasks.create(Source.IMAGE,"image")
            assertTrue(runCatching { tasks.recordOriginalCaptureDay(imported,time.recognitionAt,"Asia/Shanghai") }.isFailure)
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun delayedRecognitionFallbackStillLetsExplicitDetailCompleteOneNativePaymentWithoutSecondDebit() = runBlocking {
        app.taskRecovery.await()
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成排队延迟钱包",amountCents=10000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.OCR,"payment_auto:com.tencent.mm")
        val eventAt=System.currentTimeMillis()
        app.tasks.diagnose(task,"capture_context","payment_success_result:$eventAt")
        val first=RecognitionTimeContext(eventAt+300000,"Asia/Shanghai")
        val merchant="合成排队延迟${UUID.randomUUID().toString().replace(Regex("[0-9-]"),"字")}"
        val raw="支付成功\n付款金额：12.00元\n商户名称：$merchant"
        val backend=RecognitionBackend { image,prepared,_,liveAt,policy,origin ->
            prepared(image)
            RecognitionResult(raw,LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,first.recognitionAt,liveAt,
                policy,origin,first),screenshot=image)
        }
        try {
            val initial=(CaptureProcessor(app,OcrClient(app,backend),recognitionClock={first})
                .processPrepared("synthetic-delay".toByteArray(),Source.OCR,PaymentApps.WECHAT,task) { true } as IngestResult.Saved).record
            assertEquals(merchant,initial.bill.merchant)
            assertEquals(first.recognitionAt,initial.bill.occurredAt)
            assertEquals(eventAt,initial.capture.receivedAt)
            assertEquals("RECOGNITION_FALLBACK",initial.bill.receipt!!.time!!.source)
            assertEquals(8800L,app.store.getAccount(wallet.id)!!.amountCents)
            val printed=java.time.Instant.ofEpochMilli(eventAt).atZone(first.zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val detail="$raw\n付款时间：$printed\n交易单号：SYNTHETIC-DELAY-$task"
            val explicit=LocalOcrBillParser.parse(detail.lines().map(::LocalOcrLine),0,0,first.recognitionAt,
                origin=PaymentApps.WECHAT,timeContext=first)!!
            val completed=app.coordinator.acceptRecognition(Capture(Source.IMAGE,PaymentApps.WECHAT,detail,eventAt+1000),explicit) { true }
            assertTrue(completed is IngestResult.Saved || completed==IngestResult.DuplicateSkipped)
            val records=app.store.all().filter { it.bill.merchant==merchant }
            assertEquals(1,records.size)
            assertEquals(initial.uid,records.single().uid)
            assertEquals("SYNTHETIC-DELAY-$task",records.single().bill.transactionId)
            assertEquals(8800L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==initial.uid })
        } finally {
            app.store.all().filter { it.bill.merchant==merchant || it.wallet?.accountUid==wallet.uid }.forEach { app.store.delete(it.id) }
            app.tasks.delete(task);app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

}
