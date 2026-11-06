package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletBackupTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val stores=mutableListOf<Pair<String,LedgerStore>>()
    private val now=1_790_000_000_000L
    private fun store():LedgerStore { val name="wallet-backup-${UUID.randomUUID()}.db";return LedgerStore(context,name).also { stores.add(name to it) } }
    @After fun cleanup() { stores.forEach { (name,store)->store.close();context.deleteDatabase(name) } }
    private fun account(store:LedgerStore,amount:Long=1000,kind:String="cash")=store.saveAccount(LedgerAccount(name="合成钱包",amountCents=amount,kind=kind))
    private fun record(amount:Long=3000,uid:String=UUID.randomUUID().toString())=LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"manual","",now),
        bill=ParsedBill(amount,Direction.EXPENSE,"合成商户","购物",now,null,true,"",true),state=RecordState.POSTED)
    private fun backup(store:LedgerStore)=LedgerBackup.decode(LedgerBackup.encode(store,ChannelSettings(context)).byteInputStream())
    private fun expectFailure(action:()->Unit) { assertNotNull(runCatching(action).exceptionOrNull()) }

    @Test fun fullSnapshotRestoresNegativeBalanceAndAppliedStateWithoutReplay() {
        val source=store();val wallet=account(source);source.setDefaultWallet(wallet.uid)
        val saved=source.saveEdited(record())
        val decoded=backup(source);assertEquals(4,decoded.metadataVersion)
        val target=store();val summary=LedgerBackup.restoreDetailed(target,decoded)
        assertEquals(RestoreSummary(1,0,0,1),summary)
        assertEquals(-2000L,target.getAccountByUid(wallet.uid)!!.amountCents)
        val restored=target.all().single();assertEquals(saved.wallet,restored.wallet)
        val movementCount=target.walletSnapshot().movements.size
        assertEquals(0,LedgerBackup.restore(target,decoded));assertEquals(movementCount,target.walletSnapshot().movements.size)
        target.delete(restored.id);assertEquals(1000L,target.getAccountByUid(wallet.uid)!!.amountCents)
    }
    @Test fun mergeKeepsExistingAccountAndExcludesItsNewTransactions() {
        val source=store();val wallet=account(source,10000);source.setDefaultWallet(wallet.uid);source.saveEdited(record())
        val target=store();target.saveAccount(wallet.copy(id=0,amountCents=50000));val local=account(target,77)
        target.setDefaultWallet(local.uid)
        val summary=LedgerBackup.restoreDetailed(target,backup(source))
        assertEquals(1,summary.imported);assertEquals(1,summary.excludedRecords);assertEquals(1,summary.retainedAccounts)
        assertEquals(50000L,target.getAccountByUid(wallet.uid)!!.amountCents)
        assertEquals(local.uid,target.walletSettings().defaultAccountUid)
        val restored=target.all().single();assertEquals(WalletEligibility.RESTORE_EXCLUDED,restored.wallet!!.eligibility)
        target.saveEdited(restored.copy(note="合成编辑"));target.delete(restored.id)
        assertEquals(50000L,target.getAccountByUid(wallet.uid)!!.amountCents)
    }
    @Test fun mergeRestoresNewAccountCompleteGroupButExcludesWholeConflictedNewAccountGroup() {
        val source=store();val complete=account(source,10000);source.setDefaultWallet(complete.uid)
        val completeRecord=source.saveEdited(record(1000))
        val conflicted=account(source,20000);source.setDefaultWallet(conflicted.uid)
        val collision=source.saveEdited(record(2000));val sibling=source.saveEdited(record(3000))
        val target=store();val local=account(target,10000);target.setDefaultWallet(local.uid)
        val retained=target.saveEdited(record(500,collision.uid))
        val summary=LedgerBackup.restoreDetailed(target,backup(source))
        assertEquals(2,summary.imported);assertEquals(1,summary.excludedRecords);assertEquals(2,summary.restoredAccounts)
        assertEquals(retained,target.get(retained.id))
        val restored=target.all().associateBy { it.uid }
        assertEquals(WalletEligibility.ACTIVE,restored.getValue(completeRecord.uid).wallet!!.eligibility)
        assertEquals(WalletEligibility.RESTORE_EXCLUDED,restored.getValue(sibling.uid).wallet!!.eligibility)
        assertEquals(9000L,target.getAccountByUid(complete.uid)!!.amountCents)
        assertEquals(15000L,target.getAccountByUid(conflicted.uid)!!.amountCents)
        target.delete(restored.getValue(completeRecord.uid).id);assertEquals(10000L,target.getAccountByUid(complete.uid)!!.amountCents)
        target.delete(restored.getValue(sibling.uid).id);assertEquals(15000L,target.getAccountByUid(conflicted.uid)!!.amountCents)
    }
    @Test fun oldVersionSnapshotsRemainLegacyExcluded() {
        val json=JSONObject(LedgerBackup.encode(listOf(record()),ChannelSettings(context),listOf(LedgerAccount(name="合成旧卡",amountCents=10000))))
            .put("version",3).apply { remove("wallets") }
        val decoded=LedgerBackup.decode(json.toString().byteInputStream());val target=store()
        LedgerBackup.restore(target,decoded)
        val restored=target.all().single();assertEquals(WalletEligibility.LEGACY_EXCLUDED,restored.wallet!!.eligibility)
        val before=target.accounts().single().amountCents;target.delete(restored.id);assertEquals(before,target.accounts().single().amountCents)
    }
    @Test fun malformedFinancialMetadataIsRejectedAtomically() {
        val source=store();val wallet=account(source,10000);source.setDefaultWallet(wallet.uid);source.saveEdited(record())
        val original=LedgerBackup.encode(source,ChannelSettings(context));val target=store();val local=account(target,500)
        val variants=listOf<(JSONObject)->Unit>(
            { it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0).put("appliedCents",-2999) },
            { it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0).put("accountUid",UUID.randomUUID().toString()) },
            { it.getJSONObject("wallets").getJSONArray("links").put(it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0)) },
            { it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0).put("eligibility","UNKNOWN") },
            { it.getJSONArray("accounts").getJSONObject(0).put("amountCents",1.5) },
            { it.getJSONObject("wallets").getJSONArray("movements").getJSONObject(0).put("balanceAfter",9999) },
        )
        variants.forEach { mutation->expectFailure {
            val json=JSONObject(original);mutation(json);LedgerBackup.restore(target,LedgerBackup.decode(json.toString().byteInputStream()))
        } }
        assertTrue(target.all().isEmpty());assertEquals(listOf(local),target.accounts())
    }
    @Test fun restoreFailureRollsBackFinancialRowsConfigurationAndMovements() {
        val source=store();val wallet=account(source,10000);source.setDefaultWallet(wallet.uid);source.saveEdited(record())
        val target=store()
        expectFailure { LedgerBackup.restore(target,backup(source)) { throw IllegalStateException("synthetic media failure") } }
        assertTrue(target.all().isEmpty());assertTrue(target.accounts().isEmpty());assertTrue(target.walletSnapshot().links.isEmpty())
        assertTrue(target.walletSnapshot().movements.isEmpty());assertNull(target.walletSettings().defaultAccountUid)
    }
    @Test fun exportingDuringUndoOmitsDeletedRecordAndItsAppliedLinkButRetainsAudit() {
        val source=store();val wallet=account(source,10000);source.setDefaultWallet(wallet.uid)
        val saved=source.saveEdited(record());source.softDelete(saved.id,now)
        val decoded=backup(source);assertTrue(decoded.records.isEmpty());assertTrue(decoded.wallets!!.links.isEmpty())
        assertTrue(decoded.wallets.movements.any { it.recordUid==saved.uid })
        val target=store();LedgerBackup.restore(target,decoded)
        assertTrue(target.all().isEmpty());assertEquals(10000L,target.getAccountByUid(wallet.uid)!!.amountCents)
    }
    @Test fun csvUsesDefaultIgnoresUntrustedSourceAndInvalidatesChangedPreview() {
        val target=store();val app=account(target,10000,"wechat");val card=account(target,20000,"bank")
        target.setDefaultWallet(card.uid)
        val incoming=record().copy(capture=Capture(Source.OCR,"com.tencent.mm","",now))
        val document=LedgerCsvImport.Document("合成 CSV",listOf(incoming),emptyList(),1)
        val preview=LedgerCsvImport.preview(target,document)
        assertEquals(card.uid,preview.walletImpacts.single().accountUid)
        target.setDefaultWallet(app.uid)
        try { LedgerCsvImport.apply(target,document,preview.previewToken);fail("stale preview") }
        catch(error:WalletException) { assertEquals("CSV_PREVIEW_STALE",error.code) }
        assertTrue(target.all().isEmpty())
        target.setDefaultWallet(card.uid)
        val fresh=LedgerCsvImport.preview(target,document)
        val result=LedgerCsvImport.apply(target,document,fresh.previewToken)
        assertEquals(1,result.posted);assertEquals(0,result.pendingWallets)
        assertEquals(10000L,target.getAccount(app.id)!!.amountCents);assertEquals(17000L,target.getAccount(card.id)!!.amountCents)
        assertEquals(0,LedgerCsvImport.apply(target,document).imported)
    }
    @Test fun csvWithNoDefaultIsPendingAndNeverRestoresClaimedAppliedState() {
        val target=store();val app=account(target,10000,"wechat")
        val incoming=record().copy(capture=Capture(Source.OCR,"com.tencent.mm","",now))
        val document=LedgerCsvImport.Document("合成 CSV",listOf(incoming),emptyList(),1)
        assertEquals(1,LedgerCsvImport.preview(target,document).pendingWallets)
        val result=LedgerCsvImport.apply(target,document)
        assertEquals(1,result.pendingWallets);assertEquals(0,result.posted);assertEquals(10000L,target.getAccount(app.id)!!.amountCents)
        assertEquals("WALLET_REQUIRED",target.all().single().wallet!!.reviewCode)
    }
}
