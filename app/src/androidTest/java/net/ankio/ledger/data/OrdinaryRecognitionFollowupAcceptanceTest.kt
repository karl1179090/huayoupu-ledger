package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Independent checks of the ordinary-review regression found during full backup regression. */
@RunWith(AndroidJUnit4::class)
class OrdinaryRecognitionFollowupAcceptanceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="ordinary-followup-${UUID.randomUUID()}.db";private val store=LedgerStore(context,name);private val now=1_793_937_600_000L
    @After fun close() { store.close();context.deleteDatabase(name) }
    private fun wallet(label:String,kind:String="cash")=store.saveAccount(LedgerAccount(name=label,amountCents=100000,kind=kind,
        bankCode=if(kind=="bank")"cmb" else null,cardLast4=if(kind=="bank")"0007" else null))
    private fun bill(id:String=UUID.randomUUID().toString(),reliable:Boolean=true)=ParsedBill(10000,Direction.EXPENSE,"合成补充商户","购物",now,id,reliable,if(reliable)"" else "合成信息不完整",true)
    private fun capture(source:Source=Source.OCR)=Capture(source,PaymentApps.WECHAT,"合成识别补充",now)
    @Test fun missingAmountAndDirectionCompleteTheSameStrongIdentityAndApplyExactlyOnce() {
        val a=wallet("合成A");store.setDefaultWallet(a.uid);val complete=bill()
        val pending=(store.ingest(capture(),complete.copy(amountCents=null,direction=null,reliable=false)) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state);assertTrue(pending.walletEffects.isEmpty())
        store.writableDatabase.execSQL("UPDATE records SET note='合成保留备注' WHERE uid=?",arrayOf(pending.uid))
        store.ingest(capture(Source.IMAGE),complete)
        val posted=store.get(pending.id)!!;assertEquals(RecordState.POSTED,posted.state);assertEquals("合成保留备注",posted.note)
        repeat(3) { store.ingest(capture(),complete) }
        assertEquals(1,store.all().size);assertEquals(90000L,store.getAccount(a.id)!!.amountCents)
        assertEquals(mapOf(a.uid to -10000L),store.get(pending.id)!!.walletEffects)
        assertEquals(1,store.walletMovements(a.uid).count { it.recordUid==pending.uid })
    }
    @Test fun requiredUnreliableManualAndConflictingFollowupsLeaveTheDraftUnfunded() {
        val a=wallet("合成A");store.setDefaultWallet(a.uid);val complete=bill()
        val pending=(store.ingest(capture(),complete.copy(reliable=false)) as IngestResult.Saved).record
        store.ingest(capture(),complete,requireReview=true);assertEquals(RecordState.REVIEW,store.get(pending.id)!!.state)
        store.ingest(capture(),complete.copy(reliable=false));assertEquals(RecordState.REVIEW,store.get(pending.id)!!.state)
        store.ingest(capture(Source.MANUAL),complete);assertEquals(RecordState.REVIEW,store.get(pending.id)!!.state)
        store.ingest(capture(),complete.copy(amountCents=12000));assertEquals(RecordState.REVIEW,store.get(pending.id)!!.state)
        assertTrue(store.get(pending.id)!!.walletEffects.isEmpty());assertEquals(100000L,store.getAccount(a.id)!!.amountCents);assertEquals(1,store.all().size)
    }
    @Test fun excludedQualificationAndManualWalletSurviveReliableCompletionAndPostedRepeats() {
        val a=wallet("合成人工A");val b=wallet("合成证据B","bank");store.setDefaultWallet(b.uid)
        val candidate=bill(reliable=false);val uid=UUID.randomUUID().toString()
        val pending=store.importTransactions(listOf(LedgerRecord(uid=uid,capture=capture(),bill=candidate,state=RecordState.REVIEW,
            wallet=RecordWalletLink(uid,WalletEligibility.ACTIVE,a.uid,"MANUAL",manualLocked=true))),linkWalletBalances=false).single()
        val incoming=candidate.copy(reliable=true,reason="",paymentMethod=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007"))
        store.ingest(capture(Source.IMAGE),incoming);val posted=store.get(pending.id)!!
        assertEquals(RecordState.POSTED,posted.state);assertEquals(WalletEligibility.IMPORT_EXCLUDED,posted.wallet!!.eligibility)
        assertEquals(a.uid,posted.wallet.accountUid);assertTrue(posted.wallet.manualLocked);assertTrue(posted.walletEffects.isEmpty())
        repeat(3) { store.ingest(capture(),incoming) }
        assertEquals(100000L,store.getAccount(a.id)!!.amountCents);assertEquals(100000L,store.getAccount(b.id)!!.amountCents)
        assertEquals(1,store.all().size)
    }
}
