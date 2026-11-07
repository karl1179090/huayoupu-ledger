package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException

/** Independent adversarial checks of frozen S5 contracts; every database and row is synthetic. */
@RunWith(AndroidJUnit4::class)
class PortableImportAcceptanceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="portable-independent-${UUID.randomUUID()}.db"
    private var store=LedgerStore(context,name)
    private val now=1_793_937_600_000L
    @After fun cleanup(){store.close();context.deleteDatabase(name)}
    private fun wallet(name:String="合成本地卡",balance:Long=50000)=store.saveAccount(LedgerAccount(name=name,amountCents=balance,kind="bank",bankCode="cmb",cardLast4="0000"))
    private fun walletFile(rows:List<LedgerAccount>)=WalletCsvImport.read(ByteArrayOutputStream().also {WalletCsvExport.write(rows,it)}.toByteArray().inputStream())
    private fun bill(direction:Direction=Direction.EXPENSE,amount:Long?=10000)=LedgerRecord(capture=Capture(Source.MANUAL,"synthetic","合成独立文件",now),
        bill=ParsedBill(amount,direction,"合成独立商户","购物",now,null,true,"",true),state=RecordState.POSTED)
    private fun bills(rows:List<LedgerRecord>)=LedgerCsvImport.read(ByteArrayOutputStream().also {LedgerCsvExport.write(rows,LedgerCsvExport.Format.GENERAL,it)}.toByteArray().inputStream())
    private fun expect(code:String,action:()->Unit){try{action();fail("Expected $code")}catch(error:WalletException){assertEquals(code,error.code)}}

    @Test fun conflictingBankTailBankCodeAndArchiveMetadataCannotBeCollapsedToTheFirstSource() {
        val local=wallet()
        val source=local.copy(id=0,uid=UUID.randomUUID().toString(),name="合成来源卡",amountCents=30000,cardLast4="0007",revision=0)
        for(other in listOf(source.copy(uid=UUID.randomUUID().toString(),cardLast4="0008"),
            source.copy(uid=UUID.randomUUID().toString(),bankCode="icbc"),source.copy(uid=UUID.randomUUID().toString(),archived=true))) {
            val doc=walletFile(listOf(source,other))
            val choices=listOf(source,other).associate {it.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,local.uid,updateMetadata=true,overwriteBalance=true)}
            val preview=WalletCsvImport.prepare(store,doc,choices)
            assertTrue(preview.entries.isEmpty());assertEquals(2,preview.plan.errors.size)
            assertEquals(50000L,store.getAccount(local.id)!!.amountCents);assertTrue(store.walletImportAliases().isEmpty())
        }
    }

    @Test fun knownAppBindingArchiveConflictIsVisibleBeforeAnyCommit() {
        val local=wallet();store.setAppWallet(PaymentApps.WECHAT,local.uid)
        val doc=walletFile(listOf(local.copy(id=0,archived=true,revision=0)))
        val preview=WalletCsvImport.prepare(store,doc,mapOf(local.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,local.uid,updateMetadata=true)))
        assertTrue(preview.entries.isEmpty());assertEquals(1,preview.plan.errors.size)
        assertTrue(preview.plan.errors.single().message.contains("绑定"))
        assertFalse(store.getAccount(local.id)!!.archived)
    }

    @Test fun appBindingAddedAfterArchivePreviewInvalidatesThePlanWithTheStableStaleCode() {
        val local=wallet()
        val doc=walletFile(listOf(local.copy(id=0,archived=true,revision=0)))
        val preview=WalletCsvImport.prepare(store,doc,mapOf(local.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,local.uid,updateMetadata=true)))
        assertEquals(1,preview.entries.size)
        store.setAppWallet(PaymentApps.WECHAT,local.uid)
        expect("IMPORT_PREVIEW_STALE"){WalletCsvImport.apply(store,preview)}
        assertFalse(store.getAccount(local.id)!!.archived);assertEquals(50000L,store.getAccount(local.id)!!.amountCents)
        assertNull(store.importReceipt(preview.plan.submissionToken))
    }

    @Test fun closedImportIgnoresUnrelatedDefaultAndBalanceChangesAndStaysExcludedAfterEditing() {
        val a=wallet("合成A");val b=wallet("合成B");store.setDefaultWallet(a.uid)
        val input=bill().let {it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,accountReference=WalletFileReference.of(a).json()))}
        val doc=bills(listOf(input));val preview=LedgerCsvImport.prepare(store,doc)
        store.setDefaultWallet(b.uid);store.saveAccount(store.getAccount(b.id)!!.copy(amountCents=80000),calibrateBalance=true)
        val result=LedgerCsvImport.applyPreview(store,doc,preview)
        assertEquals(1,result.posted)
        val saved=store.all().single();assertEquals(WalletEligibility.IMPORT_EXCLUDED,saved.wallet!!.eligibility)
        store.saveEdited(saved.copy(bill=saved.bill.copy(amountCents=12000)));store.softDelete(saved.id,now);store.undoDelete(saved.id,now+1)
        assertEquals(50000L,store.getAccount(a.id)!!.amountCents);assertEquals(80000L,store.getAccount(b.id)!!.amountCents)
        assertTrue(store.get(saved.id)!!.walletEffects.isEmpty())
    }

    @Test fun sameScopedIdentityWithAnotherUidCannotReactivateAClosedImport() {
        val a=wallet();val first=bill().let {it.copy(capture=Capture(Source.IMAGE,PaymentApps.WECHAT,"",now),
            bill=it.bill.copy(transactionId="SYNTHETIC-INDEPENDENT-ORDER"),identities=listOf(TransactionIdentity("wechat","SYNTHETIC-INDEPENDENT-ORDER")),
            wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,accountReference=WalletFileReference.of(a).json()))}
        val doc=bills(listOf(first));val closed=LedgerCsvImport.prepare(store,doc);LedgerCsvImport.applyPreview(store,doc,closed)
        val second=bills(listOf(first.copy(uid=UUID.randomUUID().toString())))
        val linked=LedgerCsvImport.prepare(store,second,BillImportOptions(true))
        assertEquals(1,linked.duplicates);assertEquals(0,linked.ready)
        LedgerCsvImport.applyPreview(store,second,linked)
        assertEquals(1,store.all().size);assertEquals(WalletEligibility.IMPORT_EXCLUDED,store.all().single().wallet!!.eligibility)
        assertEquals(50000L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun cancellationAfterWalletWritesRollsBackRowsEffectsMovementsAndReceipt() {
        val a=wallet()
        val input=bill().let {it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,accountReference=WalletFileReference.of(a).json()))}
        val doc=bills(listOf(input));val preview=LedgerCsvImport.prepare(store,doc,BillImportOptions(true))
        val before=store.walletSnapshot();var observedWrite=false
        try {
            LedgerCsvImport.applyPreview(store,doc,preview) {
                if(store.getAccount(a.id)!!.amountCents!=50000L){observedWrite=true;throw CancellationException("synthetic after balance write")}
            }
            fail("Expected cancellation")
        } catch(_:CancellationException){}
        assertTrue(observedWrite);assertTrue(store.all().isEmpty());assertEquals(before,store.walletSnapshot())
        assertNull(store.importReceipt(preview.plan!!.submissionToken))
    }

    @Test fun committedSummarySurvivesRestartDeletionAndANewPreviewUsingTheSameToken() {
        val a=wallet();val input=bill().let {it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,accountReference=WalletFileReference.of(a).json()))}
        val doc=bills(listOf(input));val token="SYNTHETIC-RESTART-${UUID.randomUUID()}"
        val preview=LedgerCsvImport.prepare(store,doc,submissionToken=token)
        val result=LedgerCsvImport.applyPreview(store,doc,preview);store.delete(store.all().single().id)
        store.close();store=LedgerStore(context,name)
        val changed=LedgerCsvImport.prepare(store,doc,BillImportOptions(true),submissionToken=token)
        assertEquals(result,LedgerCsvImport.applyPreview(store,doc,changed))
        assertEquals(result.json(),store.importReceipt(token));assertTrue(store.all().isEmpty())
        assertEquals(50000L,store.getAccount(a.id)!!.amountCents)
    }

    @Test fun excludedStorageImportStillRejectsInvalidPostedFinancialModels() {
        val examples=listOf(bill(amount=-1),bill(amount=Money.MAX_CENTS+1),bill(amount=null),bill(amount=0),
            bill().let {it.copy(bill=it.bill.copy(direction=null))},
            bill().let {it.copy(bill=it.bill.copy(refund=RefundInfo("wechat",10000,5000)))},
            bill().let {it.copy(transfer=RecordTransferDetails(it.uid,feeCents=0,feeMode=FeeMode.EXTRA))},
            bill(Direction.TRANSFER,100).let {it.copy(transfer=RecordTransferDetails(it.uid,feeCents=100,feeMode=FeeMode.DEDUCTED))},
            bill(Direction.TRANSFER).let {it.copy(transfer=RecordTransferDetails(it.uid))})
        for(example in examples) {
            val before=store.walletSnapshot()
            // A preceding valid row must roll back with the illegal row, even when funding is disabled.
            assertNotNull(runCatching {store.importTransactions(listOf(bill(),example))}.exceptionOrNull())
            assertTrue(store.all().isEmpty());assertEquals(before,store.walletSnapshot())
        }
    }

    @Test fun linkedArchivedTransferDraftCannotFirstPostWithoutReplacingItsArchivedEnd() {
        val a=wallet("合成可用出");val b=wallet("合成归档入");store.archiveAccount(b.id)
        val row=bill(Direction.TRANSFER).let {it.copy(transfer=RecordTransferDetails(it.uid,feeCents=0,feeMode=FeeMode.EXTRA,
            fromReference=WalletFileReference.of(a).json(),toReference=WalletFileReference.of(b).json()))}
        val doc=bills(listOf(row));val preview=LedgerCsvImport.prepare(store,doc,BillImportOptions(true))
        assertEquals(1,preview.pendingWallets);LedgerCsvImport.applyPreview(store,doc,preview)
        val pending=store.all().single();assertEquals(RecordState.REVIEW,pending.state);assertTrue(pending.walletEffects.isEmpty())
        expect("INVALID_WALLET"){store.saveEdited(pending)}
        assertEquals(RecordState.REVIEW,store.get(pending.id)!!.state);assertEquals(50000L,store.getAccount(a.id)!!.amountCents)
        assertEquals(50000L,store.getAccount(b.id)!!.amountCents)
        val c=wallet("合成可用新入")
        val confirmed=store.saveEdited(pending.copy(transfer=pending.transfer!!.copy(toAccountUid=c.uid)))
        assertEquals(RecordState.POSTED,confirmed.state);assertEquals(40000L,store.getAccount(a.id)!!.amountCents)
        assertEquals(60000L,store.getAccount(c.id)!!.amountCents);assertEquals(50000L,store.getAccount(b.id)!!.amountCents)
    }
}
