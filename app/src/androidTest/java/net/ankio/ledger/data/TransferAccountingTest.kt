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
class TransferAccountingTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="transfer-accounting-${UUID.randomUUID()}.db"
    private var store=LedgerStore(context,name)
    private val now=1_790_000_000_000L
    @After fun cleanup() { store.close();context.deleteDatabase(name) }
    private fun wallet(balance:Long)=store.saveAccount(LedgerAccount(name="合成钱包",amountCents=balance))
    private fun balance(account:LedgerAccount)=store.getAccountByUid(account.uid)!!.amountCents
    private fun draft(from:LedgerAccount?,to:LedgerAccount?,amount:Long=10000,fee:Long=0,mode:FeeMode=FeeMode.EXTRA):LedgerRecord {
        val uid=UUID.randomUUID().toString()
        return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"manual","合成转账",now),
            bill=ParsedBill(amount,Direction.TRANSFER,"合成互转","转账",now,null,true,""),state=RecordState.POSTED,
            transfer=RecordTransferDetails(uid,from?.uid,to?.uid,fee,mode,"MANUAL","MANUAL",true,true))
    }
    private fun expect(code:String,action:()->Unit) {
        try { action();fail("expected $code") } catch(error:WalletException) { assertEquals(code,error.code) }
    }
    @Test fun T01_T02_T03BothFeeModesHaveOneBillAndExactEffects() {
        data class Case(val mode:FeeMode,val fee:Long,val debit:Long,val credit:Long)
        for((mode,fee,debit,credit) in listOf(Case(FeeMode.EXTRA,0L,10000L,10000L),Case(FeeMode.EXTRA,100L,10100L,10000L),Case(FeeMode.DEDUCTED,100L,10000L,9900L))) {
            val a=wallet(100000);val b=wallet(20000)
            val record=store.saveEdited(draft(a,b,fee=fee,mode=mode))
            assertEquals(100000L-debit,balance(a));assertEquals(20000L+credit,balance(b))
            assertEquals(mapOf(a.uid to -debit,b.uid to credit),record.walletEffects)
            assertEquals(record,store.get(record.id));assertNull(record.wallet!!.accountUid)
        }
        assertEquals(3,store.all().size)
    }
    @Test fun T04_T05_T06EditsApplyOnlyDifferencesAcrossFourAccounts() {
        val a=wallet(100000);val b=wallet(20000);val c=wallet(5000);val d=wallet(0)
        val original=store.saveEdited(draft(a,b,fee=100))
        val edited=store.saveEdited(original.copy(bill=original.bill.copy(amountCents=12000),transfer=original.transfer!!.copy(feeCents=200)))
        assertEquals(87800L,balance(a));assertEquals(32000L,balance(b))
        val oneChanged=store.saveEdited(edited.copy(transfer=edited.transfer!!.copy(toAccountUid=c.uid)))
        assertEquals(20000L,balance(b));assertEquals(17000L,balance(c))
        store.saveEdited(oneChanged.copy(transfer=oneChanged.transfer!!.copy(fromAccountUid=c.uid,toAccountUid=d.uid)))
        assertEquals(100000L,balance(a));assertEquals(-7200L,balance(c));assertEquals(12000L,balance(d))
    }
    @Test fun T07_T08_T09DeleteUndoTokensReopenAndRevisionsAreIdempotent() {
        val a=wallet(100000);val b=wallet(20000);val input=draft(a,b,fee=100)
        val saved=store.saveEdited(input,submissionToken="stable-save")
        assertEquals(saved,store.saveEdited(input,submissionToken="stable-save"))
        store.close();store=LedgerStore(context,name)
        assertEquals(saved,store.saveEdited(input,submissionToken="stable-save"));assertEquals(89900L,balance(a))
        val updated=store.saveEdited(saved.copy(note="合成更新"),submissionToken="fresh")
        expect("STALE_RECORD_REVISION") { store.saveEdited(saved.copy(note="旧表单"),submissionToken="stale") }
        assertTrue(store.softDelete(saved.id,now,updated.wallet!!.revision,"delete"));assertFalse(store.softDelete(saved.id,now))
        assertEquals(100000L,balance(a));assertEquals(20000L,balance(b))
        assertTrue(store.undoDelete(saved.id,now+1,submissionToken="undo"));assertFalse(store.undoDelete(saved.id,now+1))
        assertEquals(89900L,balance(a));assertEquals(30000L,balance(b));assertEquals(1,store.all().size)
    }
    @Test fun T10_T11MissingSameLiabilityAndArchivedNewWalletsRejectButNegativeAssetsWork() {
        val a=wallet(1000);val b=wallet(20000)
        expect("TRANSFER_WALLET_REQUIRED") { store.saveEdited(draft(a,null)) }
        expect("TRANSFER_SAME_WALLET") { store.saveEdited(draft(a,a)) }
        val debt=store.saveAccount(LedgerAccount(name="合成负债",amountCents=0,type=AccountType.LIABILITY))
        expect("TRANSFER_ACCOUNT_UNSUPPORTED") { store.saveEdited(draft(a,debt)) }
        store.archiveAccount(b.id);expect("INVALID_WALLET") { store.saveEdited(draft(a,b)) }
        val c=wallet(0);store.saveEdited(draft(a,c));assertEquals(-9000L,balance(a));assertEquals(10000L,balance(c))
    }
    @Test fun T12_T13AnyLegFailureRollsBackRecordEffectsBalancesAndAudit() {
        val a=wallet(100000);val b=wallet(Money.MAX_CENTS)
        val counts=store.walletSnapshot().movements.size
        expect("BALANCE_LIMIT") { store.saveEdited(draft(a,b,amount=1)) }
        expect("TRANSFER_FEE_AMBIGUOUS") { store.saveEdited(draft(a,b,fee=-1)) }
        expect("TRANSFER_AMOUNT_CONFLICT") { store.saveEdited(draft(a,b,amount=100,fee=100,mode=FeeMode.DEDUCTED)) }
        assertTrue(store.all().isEmpty());assertEquals(counts,store.walletSnapshot().movements.size)
        store.saveAccount(store.getAccount(b.id)!!.copy(amountCents=0),calibrateBalance=true)
        store.writableDatabase.execSQL("CREATE TRIGGER transfer_fail BEFORE INSERT ON record_wallet_effects BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { store.saveEdited(draft(a,b));fail("write should fail") } catch(_:Exception) { }
        store.writableDatabase.execSQL("DROP TRIGGER transfer_fail")
        assertTrue(store.all().isEmpty());assertEquals(100000L,balance(a));assertEquals(0L,balance(b))
        assertTrue(store.walletSnapshot().effects.values.all { it.isEmpty() })
    }
    @Test fun T14_T15ConversionsPreserveEligibilityAndApplyNetDifferences() {
        val a=wallet(100000);val b=wallet(20000);val transfer=draft(a,b)
        val expense=store.saveEdited(transfer.copy(transfer=null,bill=transfer.bill.copy(direction=Direction.EXPENSE)),a.uid,true)
        store.saveEdited(expense.copy(transfer=transfer.transfer,bill=expense.bill.copy(direction=Direction.TRANSFER)))
        assertEquals(90000L,balance(a));assertEquals(30000L,balance(b))
        val excluded=draft(a,b,fee=100)
        val imported=store.importTransactions(listOf(excluded)).single()
        assertEquals(WalletEligibility.IMPORT_EXCLUDED,imported.wallet!!.eligibility);assertTrue(imported.walletEffects.isEmpty())
        store.saveEdited(imported.copy(bill=imported.bill.copy(amountCents=12000)));store.softDelete(imported.id,now);store.undoDelete(imported.id,now+1)
        assertEquals(90000L,balance(a));assertEquals(30000L,balance(b))
    }
    @Test fun T16_T17ArchivedOriginalReferencesCanAdjustAndCannotBeDeletedOrMadeLiabilities() {
        val a=wallet(100000);val b=wallet(20000);val original=store.saveEdited(draft(a,b))
        store.archiveAccount(a.id);store.archiveAccount(b.id)
        val updated=store.saveEdited(original.copy(bill=original.bill.copy(amountCents=12000)))
        assertEquals(88000L,balance(a));assertEquals(32000L,balance(b))
        expect("ACCOUNT_IN_USE") { store.deleteAccount(b.id) }
        expect("ACCOUNT_IN_USE") { store.saveAccount(store.getAccount(a.id)!!.copy(type=AccountType.LIABILITY,amountCents=0)) }
        store.softDelete(updated.id,now);store.undoDelete(updated.id,now+1);assertEquals(88000L,balance(a))
    }
    @Test fun I11_I12_I13BatchNetEffectsAreOrderIndependentAndTokensRecover() {
        val a=wallet(Money.MAX_CENTS)
        fun bill(direction:Direction)=LedgerRecord(capture=Capture(Source.MANUAL,"csv","合成导入",now),
            bill=ParsedBill(100, direction,"合成","其他",now,null,true,""),state=RecordState.POSTED,
            wallet=RecordWalletLink("placeholder",WalletEligibility.ACTIVE,accountUid=a.uid,selectionSource="IMPORT"))
        val rows=listOf(bill(Direction.INCOME),bill(Direction.EXPENSE));val token=store.walletConfigurationToken()
        val result=store.importTransactions(rows,true,token,"batch-token")
        assertEquals(Money.MAX_CENTS,balance(a));assertEquals(2,result.size)
        assertEquals(result,store.importTransactions(rows,true,"obsolete","batch-token"))
        expect("IMPORT_PREVIEW_STALE") { store.importTransactions(listOf(bill(Direction.EXPENSE)),true,token,"fresh-batch") }
        assertEquals(2,store.all().size)
        val b=wallet(0);val invalid=draft(a,b)
        store.writableDatabase.execSQL("CREATE TRIGGER batch_fail BEFORE INSERT ON import_batches BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { store.importTransactions(listOf(invalid),true,submissionToken="batch-fail");fail("batch should fail") } catch(_:Exception) { }
        store.writableDatabase.execSQL("DROP TRIGGER batch_fail")
        assertEquals(2,store.all().size);assertEquals(Money.MAX_CENTS,balance(a));assertEquals(0L,balance(b))
    }
    @Test fun O07_O08StrongIdentityIncludesReviewAndConflictsKeepCommittedEffects() {
        val a=wallet(100000);val b=wallet(20000)
        val capture=Capture(Source.OCR,"com.tencent.mm","合成识别",now)
        val bill=ParsedBill(10000,Direction.EXPENSE,"合成商户","购物",now,"SYNTHETIC-STRONG-ID",true,"")
        val pending=(store.ingest(capture,bill) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state)
        store.ingest(capture,bill.copy(direction=Direction.TRANSFER))
        assertEquals(1,store.all().size);assertEquals(100000L,balance(a));assertEquals(20000L,balance(b))
        val current=store.get(pending.id)!!
        val posted=store.saveEdited(current.copy(bill=current.bill.copy(direction=Direction.TRANSFER),
            transfer=RecordTransferDetails(current.uid,a.uid,b.uid,100,FeeMode.EXTRA)))
        store.ingest(capture,bill.copy(direction=Direction.TRANSFER,amountCents=12000))
        assertEquals(89900L,balance(a));assertEquals(30000L,balance(b));assertEquals(posted.walletEffects,store.get(posted.id)!!.walletEffects)
        assertEquals(1,store.all().size);assertEquals("TRANSFER_IDENTITY_CONFLICT",store.get(posted.id)!!.wallet!!.reviewCode)
    }

}
