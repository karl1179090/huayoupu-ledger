package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** I10 integration: a final wallet snapshot followed by excluded history must not charge it again. */
@RunWith(AndroidJUnit4::class)
class HistoricalMigrationFlowTest {
    @Test fun walletSnapshotThenClosedHistorySurvivesRestartEditsDeleteUndoAndEnabledReimport() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="historical-flow-${UUID.randomUUID()}.db"
        var store=LedgerStore(context,name)
        val at=Instant.parse("2020-01-02T04:00:00Z").toEpochMilli()
        val accounts=listOf(LedgerAccount(name="合成最终余额A",amountCents=50000),LedgerAccount(name="合成最终余额B",amountCents=20000))
        fun row(direction:Direction,amount:Long)=LedgerRecord(
            capture=Capture(Source.MANUAL,"synthetic-history","合成历史迁移",at),
            bill=ParsedBill(amount,direction,"合成历史商户","购物",at,null,true,"",true),state=RecordState.POSTED)
        fun assertBalances() {
            accounts.forEach { assertEquals(it.amountCents,store.getAccountByUid(it.uid)!!.amountCents) }
            assertTrue(store.all().all { it.state==RecordState.POSTED && it.wallet!!.eligibility==WalletEligibility.IMPORT_EXCLUDED && it.walletEffects.isEmpty() })
        }
        try {
            val walletBytes=ByteArrayOutputStream().also { WalletCsvExport.write(accounts,it) }.toByteArray()
            val wallets=WalletCsvImport.read(walletBytes.inputStream())
            val walletPreview=WalletCsvImport.prepare(store,wallets)
            assertEquals(0,walletPreview.unresolved)
            assertEquals(2,WalletCsvImport.apply(store,walletPreview).imported)
            assertTrue(store.all().isEmpty())
            val expense=row(Direction.EXPENSE,10000).let { it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,
                accounts[0].uid,accountReference=WalletFileReference.of(accounts[0]).json())) }
            val income=row(Direction.INCOME,2500).let { it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,
                accounts[1].uid,accountReference=WalletFileReference.of(accounts[1]).json())) }
            val transfer=row(Direction.TRANSFER,10000).let { it.copy(transfer=RecordTransferDetails(it.uid,
                accounts[0].uid,accounts[1].uid,100,FeeMode.EXTRA,
                fromReference=WalletFileReference.of(accounts[0]).json(),toReference=WalletFileReference.of(accounts[1]).json())) }
            val bytes=ByteArrayOutputStream().also { LedgerCsvExport.write(listOf(expense,income,transfer),LedgerCsvExport.Format.GENERAL,it,accounts) }.toByteArray()
            val document=LedgerCsvImport.read(bytes.inputStream())
            val preview=LedgerCsvImport.prepare(store,document)
            assertFalse(preview.options.linkWalletBalances)
            assertTrue(preview.walletImpacts.isEmpty())
            assertEquals(3,LedgerCsvImport.applyPreview(store,document,preview).posted)
            assertBalances()
            var totals=LedgerStatistics(store).month("2020-01",ZoneId.of("Asia/Shanghai")).getJSONObject("totals")
            assertEquals("10100",totals.getString("expense"));assertEquals("2500",totals.getString("income"))
            store.close();store=LedgerStore(context,name)
            for(record in store.all()) {
                val edited=when(record.bill.direction) {
                    Direction.EXPENSE->record.copy(bill=record.bill.copy(amountCents=12000))
                    Direction.TRANSFER->record.copy(transfer=record.transfer!!.copy(feeCents=200))
                    else->record.copy(note="合成历史核对")
                }
                store.saveEdited(edited);store.softDelete(record.id,at);store.undoDelete(record.id,at+1)
            }
            val reopened=LedgerCsvImport.prepare(store,document,BillImportOptions(linkWalletBalances=true,useDefaultWallet=true))
            assertEquals(3,reopened.duplicates);assertEquals(0,reopened.ready)
            assertEquals(0,LedgerCsvImport.applyPreview(store,document,reopened).imported)
            assertBalances()
            totals=LedgerStatistics(store).month("2020-01",ZoneId.of("Asia/Shanghai")).getJSONObject("totals")
            assertEquals("12200",totals.getString("expense"));assertEquals("2500",totals.getString("income"))
        } finally { store.close();context.deleteDatabase(name) }
    }
}
