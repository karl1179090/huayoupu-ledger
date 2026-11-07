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
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class WalletAccountingTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="wallet-accounting-${UUID.randomUUID()}.db"
    private var store=LedgerStore(context,name)
    private val now=1_790_000_000_000L
    @After fun cleanup() { store.close();context.deleteDatabase(name) }
    private fun wallet(amount:Long=1000,kind:String="cash",bank:String?=null,last4:String?=null):LedgerAccount =
        store.saveAccount(LedgerAccount(name="合成钱包 ${UUID.randomUUID().toString().take(4)}",amountCents=amount,kind=kind,bankCode=bank,cardLast4=last4))
    private fun bill(amount:Long=3000,id:String=UUID.randomUUID().toString())=ParsedBill(amount,Direction.EXPENSE,"合成商户","购物",now,id,true,"",true)
    private fun capture()=Capture(Source.OCR,"com.tencent.mm","合成交易",now)
    private fun ingest(bill:ParsedBill=bill())=(store.ingest(capture(),bill) as IngestResult.Saved).record
    private fun balance(account:LedgerAccount)=store.getAccount(account.id)!!.amountCents
    private fun expect(code:String, action:()->Unit) {
        try { action();fail("expected $code") } catch(error:WalletException) { assertEquals(code,error.code) }
    }

    @Test fun newExpenseCanMakeAssetsNegativeAndReopenDoesNotReplay() {
        val account=wallet();store.setDefaultWallet(account.uid)
        val record=ingest()
        assertEquals(RecordState.POSTED,record.state);assertEquals(-2000L,balance(account))
        assertEquals(-3000L,record.wallet!!.appliedCents)
        store.close();store=LedgerStore(context,name)
        assertEquals(-2000L,balance(account));assertEquals(record,store.get(record.id))
        assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture(),record.bill))
        assertEquals(-2000L,balance(account))
    }
    @Test fun noWalletStaysPendingAndExplicitChoicePostsExactlyOnce() {
        val pending=ingest();assertEquals(RecordState.REVIEW,pending.state)
        assertEquals("WALLET_REQUIRED",pending.wallet!!.reviewCode);assertEquals(0L,pending.wallet.appliedCents)
        val account=wallet()
        val posted=store.saveEdited(pending,walletAccountUid=account.uid,manualWalletSelected=true,submissionToken="confirm")
        assertEquals(RecordState.POSTED,posted.state);assertEquals(-2000L,balance(account))
        assertEquals(posted,store.saveEdited(pending,walletAccountUid=account.uid,manualWalletSelected=true,submissionToken="confirm"))
        expect("STALE_RECORD_REVISION") { store.saveEdited(pending,submissionToken="another") }
    }
    @Test fun editsApplyDifferencesAndSettingsDoNotMoveAnExistingWallet() {
        val account=wallet(20000);store.setDefaultWallet(account.uid)
        val original=ingest(bill(10000));assertEquals(10000L,balance(account))
        val edited=store.saveEdited(original.copy(bill=original.bill.copy(amountCents=12000)))
        assertEquals(8000L,balance(account))
        val income=store.saveEdited(edited.copy(bill=edited.bill.copy(amountCents=10000,direction=Direction.INCOME)))
        assertEquals(30000L,balance(account))
        val other=wallet();store.setDefaultWallet(other.uid)
        store.saveEdited(income.copy(note="合成备注"));assertEquals(30000L,balance(account));assertEquals(1000L,balance(other))
    }
    @Test fun partialRefundEvidenceUpgradeTransfersOnlyRemainingNetAndManualLockSurvives() {
        val app=wallet(20000,"wechat");val card=wallet(20000,"bank","cmb","0007")
        val original=ingest(bill(10000,"SYNTHETIC-REFUND-1"))
        val refund=original.bill.copy(amountCents=7000,refund=RefundInfo("wechat",10000,3000))
        val updated=(store.ingest(capture(),refund) as IngestResult.Saved).record
        assertEquals(13000L,balance(app));assertEquals(7000L,updated.bill.amountCents)
        val evidence=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007")
        val detail=original.bill.copy(paymentMethod=evidence)
        val transferred=(store.ingest(capture(),detail) as IngestResult.Saved).record
        assertEquals(20000L,balance(app));assertEquals(13000L,balance(card));assertEquals(card.uid,transferred.wallet!!.accountUid)
        store.ingest(capture(),detail);assertEquals(13000L,balance(card))
        val manual=store.saveEdited(transferred,walletAccountUid=app.uid,manualWalletSelected=true)
        store.ingest(capture(),detail);assertEquals(app.uid,store.get(manual.id)!!.wallet!!.accountUid)
    }
    @Test fun softDeleteUndoExpiryAndDirectHardDeleteNeverRollbackTwice() {
        val account=wallet(10000);store.setDefaultWallet(account.uid)
        val original=ingest(bill(3000));assertEquals(7000L,balance(account))
        assertTrue(store.softDelete(original.id,now));assertEquals(10000L,balance(account))
        assertFalse(store.softDelete(original.id,now));assertEquals(10000L,balance(account))
        assertTrue(store.undoDelete(original.id,now+1));assertEquals(7000L,balance(account))
        store.softDelete(original.id,now+2);store.purgeExpiredDeletes(now+7000);assertEquals(10000L,balance(account))
        val second=ingest(bill(2000));store.delete(second.id);store.delete(second.id);assertEquals(10000L,balance(account))
    }
    @Test fun metadataPreservesConcurrentBalanceAndCalibrationChecksVersionBeforeWriting() {
        val account=wallet(10000);store.setDefaultWallet(account.uid)
        ingest(bill(3000))
        val renamed=store.saveAccount(account.copy(name="合成改名"))
        assertEquals(7000L,renamed.amountCents)
        expect("STALE_ACCOUNT_REVISION") { store.saveAccount(account.copy(amountCents=12000),calibrateBalance=true,submissionToken="stale") }
        val calibrated=store.saveAccount(renamed.copy(amountCents=12000),calibrateBalance=true,submissionToken="calibrate")
        assertEquals(12000L,balance(account))
        assertEquals(calibrated,store.saveAccount(renamed.copy(amountCents=12000),calibrateBalance=true,submissionToken="calibrate"))
    }
    @Test fun newBoundaryFailureStaysPendingButEditAndUndoFailuresRollbackWholeOperation() {
        val account=wallet(-Money.MAX_CENTS);store.setDefaultWallet(account.uid)
        val pending=ingest(bill(1));assertEquals("BALANCE_LIMIT",pending.wallet!!.reviewCode)
        assertEquals(0L,pending.wallet.appliedCents);assertEquals(-Money.MAX_CENTS,balance(account))
        store.saveAccount(store.getAccount(account.id)!!.copy(amountCents=0),calibrateBalance=true)
        val original=ingest(bill(1));store.softDelete(original.id,now)
        store.saveAccount(store.getAccount(account.id)!!.copy(amountCents=-Money.MAX_CENTS),calibrateBalance=true)
        expect("BALANCE_LIMIT") { store.undoDelete(original.id,now+1) }
        assertNull(store.get(original.id));assertEquals(-Money.MAX_CENTS,balance(account))
    }
    @Test fun archivedWalletKeepsOldAdjustmentsButCannotReceiveNewSelection() {
        val account=wallet(10000);store.setDefaultWallet(account.uid)
        val original=ingest(bill(3000));store.setDefaultWallet(null);store.archiveAccount(account.id)
        store.delete(original.id);assertEquals(10000L,balance(account))
        val pending=ingest(bill(1000));assertEquals(RecordState.REVIEW,pending.state)
        expect("INVALID_WALLET") { store.saveEdited(pending,account.uid,true) }
        expect("ACCOUNT_IN_USE") { store.deleteAccount(account.id) }
    }
    @Test fun associationAndMovementWriteFailuresRollBackRecordsBalancesAndAuditTogether() {
        val account=wallet(10000);store.setDefaultWallet(account.uid)
        for(table in listOf("record_wallet_links","wallet_movements")) {
            val count=store.walletMovements(account.uid).size
            store.writableDatabase.execSQL("CREATE TRIGGER wallet_fail BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
            try { store.ingest(capture(),bill(1000));fail("write should fail") } catch(_:Exception) { }
            store.writableDatabase.execSQL("DROP TRIGGER wallet_fail")
            assertTrue(store.all().isEmpty());assertEquals(10000L,balance(account));assertEquals(count,store.walletMovements(account.uid).size)
        }
    }
    @Test fun concurrentWritesToSameWalletDoNotLoseUpdates() {
        val account=wallet(10000);store.setDefaultWallet(account.uid)
        val executor=Executors.newFixedThreadPool(4)
        try {
            val tasks=(1..20).map { index->executor.submit { ingest(bill(100,"SYNTHETIC-CONCURRENT-$index")) } }
            tasks.forEach { it.get() }
        } finally { executor.shutdownNow() }
        assertEquals(8000L,balance(account));assertEquals(20,store.all().size)
    }
}
