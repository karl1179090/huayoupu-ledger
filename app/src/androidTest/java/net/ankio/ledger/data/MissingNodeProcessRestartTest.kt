package net.ankio.ledger.data

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ScreenshotFailureReason
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Two phases can run around force-stop; immutable nodes replace neither authorization nor pixels. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MissingNodeProcessRestartTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val state get()=app.getSharedPreferences("synthetic-missing-node-restart",0)
    private val time=RecognitionTimeContext(1_799_411_400_000,"Asia/Shanghai")
    private fun snapshot()=NodeSnapshot(PaymentApps.WECHAT,7,"synthetic.NodeResult",3,100,110,1_799_400_000_000,
        NodeEnvironment("synthetic",1,"synthetic-api30","synthetic-device"),listOf(
            CaptureNode(0,null,0,NodeBounds(0,0,100,200),"支付成功"),CaptureNode(1,0,1,NodeBounds(0,30,100,60),"付款金额：12.34")))
    private fun registry()=NodeRecognitionRegistry(listOf(NodeLayoutAdapter(NodeLayoutVerification("synthetic-missing-restart",PaymentApps.WECHAT,"synthetic",1,
        "synthetic-api30","synthetic-device",setOf("synthetic.NodeResult"),true,"synthetic-restart-comparison","synthetic result only"),{true}) { _,clock ->
        listOf(TransactionCandidate(CandidateId.SINGLE,ParsedBill(1234,Direction.EXPENSE,"合成无图跨进程","其他",clock.recognitionAt,null,true,"",
            receipt=ReceiptEvidence(merchant=ReceiptField("合成无图跨进程","合成无图跨进程","node")))))
    }))
    private fun processor()=CaptureProcessor(app,OcrClient(app,RecognitionBackend { _,_,_,_,_,_->error("missing-node recovery must never run OCR") }),
        { time.copy(recognitionAt=time.recognitionAt+172800000) },registry())

    @Test fun aPersistMissingNodesBeforeProcessDeath()=runBlocking {
        app.taskRecovery.await();assertFalse(state.contains("task"))
        val settings=app.store.walletSettings()
        val wallet=app.store.saveAccount(LedgerAccount(name="合成无图跨进程钱包",amountCents=10000,kind="cash"));app.store.setDefaultWallet(wallet.uid)
        val task=app.tasks.create(Source.OCR,"payment_auto:${PaymentApps.WECHAT}")
        app.tasks.beginRecognition(task) { time }
        val snapshot=snapshot()
        assertTrue(app.tasks.queuedWithoutScreenshot(task,snapshot,processor().prepareNodeFallback(task,snapshot,PaymentApps.WECHAT)!!,ScreenshotFailureReason.TIMEOUT))
        app.tasks.markNodeRecognizing(task)
        assertTrue(state.edit().putLong("task",task).putString("wallet",wallet.uid).putString("oldDefault",settings.defaultAccountUid)
            .putString("version",app.tasks.classificationSnapshot(task)!!.version).putInt("pid",Process.myPid()).commit())
    }

    @Test fun bResumeMissingNodesAfterProcessDeathWithoutRecaptureOrFinancialReplay()=runBlocking {
        app.taskRecovery.await();app.tasks.recoverInterrupted();val task=state.getLong("task",0);assertTrue("Run phase a first",task>0)
        if(InstrumentationRegistry.getArguments().getString("expectProcessRestart")=="true")assertNotEquals(state.getInt("pid",0),Process.myPid())
        val wallet=app.store.getAccountByUid(state.getString("wallet",null)!!)!!;val operation=app.tasks.operationKey(task)!!
        try {
            assertEquals("QUEUED",app.tasks.status(task));assertTrue(app.tasks.hasMissingNodeEvidence(task));assertNull(app.tasks.image(task))
            assertEquals(time,app.tasks.recognitionTime(task));assertEquals(snapshot().fingerprint,app.tasks.nodeSnapshot(task)!!.fingerprint)
            val processor=processor()
            val saved=(processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,task) { true } as IngestResult.Saved).record
            assertEquals(RecordState.POSTED,saved.state);assertEquals(time.recognitionAt,saved.bill.occurredAt)
            assertEquals(state.getString("version",null),saved.bill.categoryDecision!!.ruleVersion)
            assertEquals("TIMEOUT",saved.bill.receipt!!.recognition!!.screenshot.state);assertNull(app.store.screenshot(saved.id))
            assertEquals(8766L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(IngestResult.DuplicateSkipped,processor.processWithoutScreenshot(Source.OCR,PaymentApps.WECHAT,task) { true })
            assertEquals(8766L,app.store.getAccount(wallet.id)!!.amountCents);assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==saved.uid })
        } finally {
            app.tasks.recordId(task)?.let(app.store::delete);app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(operation));app.tasks.delete(task)
            app.store.setDefaultWallet(state.getString("oldDefault",null));app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
            assertTrue(state.edit().clear().commit())
        }
    }
}
