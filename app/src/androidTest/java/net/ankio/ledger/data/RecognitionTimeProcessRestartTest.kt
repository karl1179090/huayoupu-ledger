package net.ankio.ledger.data

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.time.Instant
import java.util.UUID

/** Run a/b separately around force-stop to verify a new process reads the first recognition context. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecognitionTimeProcessRestartTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val state get()=app.getSharedPreferences("synthetic-time-restart",0)
    private val frozen=RecognitionTimeContext(Instant.parse("2026-03-08T06:59:23.456Z").toEpochMilli(),"America/New_York")
    @Test fun aPersistFirstRecognitionBeforeProcessDeath()=runBlocking {
        app.taskRecovery.await();assertFalse(state.contains("task"))
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成时间跨进程钱包",amountCents=6000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.IMAGE,"image")
        app.tasks.prepared(task,"synthetic-time-restart".toByteArray())
        assertEquals(frozen,app.tasks.beginRecognition(task) { frozen })
        assertTrue(state.edit().putLong("task",task).putString("wallet",wallet.uid).putString("oldDefault",oldDefault)
            .putString("version",app.tasks.classificationSnapshot(task)!!.version).putInt("pid",Process.myPid()).commit())
    }
    @Test fun bResumeAfterProcessDeathWithoutMovingDateOrChargingTwice()=runBlocking {
        app.taskRecovery.await()
        val task=state.getLong("task",0);assertTrue("Run phase a first",task>0)
        if(InstrumentationRegistry.getArguments().getString("expectProcessRestart")=="true")assertNotEquals(state.getInt("pid",0),Process.myPid())
        val wallet=app.store.getAccountByUid(state.getString("wallet",null)!!)!!
        val operation=app.tasks.operationKey(task)!!
        val id="SYNTHETIC-TIME-RESTART-$task"
        try {
            assertEquals("QUEUED",app.tasks.status(task))
            assertEquals(frozen,app.tasks.recognitionTime(task))
            val raw="支付成功\n付款金额：7.00元\n交易单号：$id"
            val backend=object:RecognitionBackend {
                override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                    livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult=error("context required")
                override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                    livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->RecognitionTimeContext):RecognitionResult {
                    onPrepared(image);val time=recognitionTime()
                    return RecognitionResult(raw,LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,time.recognitionAt,
                        policy=policy,origin=origin,timeContext=time),screenshot=image)
                }
            }
            val processor=CaptureProcessor(app,OcrClient(app,backend),recognitionClock={RecognitionTimeContext(
                Instant.parse("2026-03-10T16:00:00Z").toEpochMilli(),"Asia/Shanghai")})
            val record=(processor.processPrepared(app.tasks.image(task)!!,Source.IMAGE,"image",task) { true } as IngestResult.Saved).record
            assertEquals(frozen.recognitionAt,record.bill.occurredAt)
            assertEquals("2026-03-08",record.bill.receipt!!.time!!.localDate)
            assertEquals("America/New_York",record.bill.receipt!!.time!!.zoneId)
            assertEquals(state.getString("version",null),record.bill.categoryDecision!!.ruleVersion)
            assertEquals(5300L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(IngestResult.DuplicateSkipped,processor.processPrepared("synthetic-retry".toByteArray(),Source.IMAGE,"image",task) { true })
            assertEquals(5300L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==record.uid })
        } finally {
            app.store.all().filter { it.bill.transactionId==id }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(operation));app.tasks.delete(task)
            app.store.setDefaultWallet(state.getString("oldDefault",null))
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
            assertTrue(state.edit().clear().commit())
        }
    }
}
