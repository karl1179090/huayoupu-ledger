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

@RunWith(AndroidJUnit4::class)
class WalletStandaloneRefundTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="wallet-standalone-${UUID.randomUUID()}.db"
    private var store=LedgerStore(context,name)
    private val now=1_790_000_000_000L
    private val capture=Capture(Source.OCR,"com.tencent.mm","合成成功退款回执",now)
    private val originalBill=ParsedBill(10000,Direction.EXPENSE,"合成商户","购物",now,"SYNTHETIC-ORIGINAL-1",true,"",true)
    @After fun cleanup() { store.close();context.deleteDatabase(name) }
    private fun wallet():LedgerAccount=store.saveAccount(LedgerAccount(name="合成微信零钱",amountCents=20000,kind="wechat"))
    private fun standalone(id:String?="SYNTHETIC-ORIGINAL-1",refundId:String?="SYNTHETIC-REFUND-1",amount:Long=3000)=
        ParsedBill(null,Direction.EXPENSE,"合成商户","购物",now,id,false,"需原账单核对",true,
            standaloneRefund=StandaloneRefundEvidence("wechat",amount,refundId,id))
    @Test fun successfulStandaloneReceiptUpdatesOnlyUniqueProvenOriginalAndRetriesDoNotAddAgain() {
        val wallet=wallet();val original=(store.ingest(capture.copy(text="合成原付款"),originalBill) as IngestResult.Saved).record
        val refunded=(store.ingest(capture,standalone()) as IngestResult.Saved).record
        assertEquals(original.id,refunded.id);assertEquals(7000L,refunded.bill.amountCents)
        assertEquals(13000L,store.getAccount(wallet.id)!!.amountCents)
        store.ingest(capture,standalone());assertEquals(13000L,store.getAccount(wallet.id)!!.amountCents)
        store.close();store=LedgerStore(context,name);store.ingest(capture,standalone())
        assertEquals(13000L,store.getAccount(wallet.id)!!.amountCents);assertEquals(1,store.all().size)
    }
    @Test fun unlinkedSuccessfulReceiptPersistsAndCanBeExplicitlyConfirmedWithoutGuessingIncome() {
        val wallet=wallet();val original=(store.ingest(capture.copy(text="合成原付款"),originalBill) as IngestResult.Saved).record
        val pending=(store.ingest(capture,standalone(id=null)) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state);assertNull(pending.bill.amountCents);assertEquals(0L,pending.wallet!!.appliedCents)
        assertTrue(store.discardStandaloneRefundReceipts().isEmpty());assertTrue(store.discardRejectedDrafts().isEmpty())
        store.close();store=LedgerStore(context,name)
        assertEquals(pending.bill.standaloneRefund,store.get(pending.id)!!.bill.standaloneRefund)
        assertEquals(listOf(original.id),store.refundCandidates(pending.id).map { it.id })
        store.confirmRefund(pending.id,original.id)
        assertNull(store.get(pending.id));assertEquals(13000L,store.getAccount(wallet.id)!!.amountCents)
    }
    @Test fun incompleteRefundIdentityNeverAppliesAndLegacyOriginalNeverReceivesMoney() {
        val wallet=wallet()
        val legacy=LedgerRecord(capture=capture.copy(text="合成历史原付款"),bill=originalBill,state=RecordState.POSTED)
        store.merge(listOf(legacy));val original=store.all().single()
        store.ingest(capture,standalone());assertEquals(20000L,store.getAccount(wallet.id)!!.amountCents)
        assertEquals(WalletEligibility.RESTORE_EXCLUDED,store.get(original.id)!!.wallet!!.eligibility)
        val pending=(store.ingest(capture,standalone(id=null,refundId=null)) as IngestResult.Saved).record
        assertTrue(store.refundCandidates(pending.id).isEmpty());assertEquals(0L,pending.wallet!!.appliedCents)
    }
    @Test fun completedFullRefundReturnsExactNetAndRemainsLinkedAtZero() {
        val wallet=wallet();val original=(store.ingest(capture.copy(text="合成原付款"),originalBill) as IngestResult.Saved).record
        val refunded=(store.ingest(capture,originalBill.copy(amountCents=0,refund=RefundInfo("wechat",10000,10000))) as IngestResult.Saved).record
        assertEquals(original.id,refunded.id);assertEquals(0L,refunded.wallet!!.appliedCents)
        assertEquals(wallet.uid,refunded.wallet.accountUid);assertEquals(20000L,store.getAccount(wallet.id)!!.amountCents)
        store.delete(original.id);assertEquals(20000L,store.getAccount(wallet.id)!!.amountCents)
    }
}
