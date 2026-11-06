package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.TimeZone
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class PortableImportTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val names=mutableListOf<String>();private val stores=mutableListOf<LedgerStore>();private val now=1_793_937_600_000L
    @After fun clean() { stores.forEach { it.close() };names.forEach(context::deleteDatabase) }
    private fun store():LedgerStore { val name="portable-import-${UUID.randomUUID()}.db";names.add(name);return LedgerStore(context,name).also(stores::add) }
    private fun wallet(store:LedgerStore,balance:Long=100000,name:String="合成钱包",uid:String=UUID.randomUUID().toString())=store.saveAccount(LedgerAccount(uid=uid,name=name,amountCents=balance))
    private fun bill(direction:Direction=Direction.EXPENSE,amount:Long=10000):LedgerRecord=LedgerRecord(capture=Capture(Source.MANUAL,"manual","",now),
        bill=ParsedBill(amount,direction,"合成商户","购物",now,null,true,"",true),state=RecordState.POSTED)
    private fun transfer(a:LedgerAccount,b:LedgerAccount,mode:FeeMode=FeeMode.EXTRA):LedgerRecord { val r=bill(Direction.TRANSFER)
        return r.copy(transfer=RecordTransferDetails(r.uid,a.uid,b.uid,100,mode,fromReference=WalletFileReference.of(a).json(),toReference=WalletFileReference.of(b).json())) }
    private fun csv(rows:List<LedgerRecord>,format:LedgerCsvExport.Format=LedgerCsvExport.Format.GENERAL,accounts:List<LedgerAccount> = emptyList())=ByteArrayOutputStream().also { LedgerCsvExport.write(rows,format,it,accounts) }.toByteArray()
    private fun document(rows:List<LedgerRecord>)=LedgerCsvImport.read(csv(rows).inputStream())
    private fun walletDocument(rows:List<LedgerAccount>)=WalletCsvImport.read(ByteArrayOutputStream().also { WalletCsvExport.write(rows,it) }.toByteArray().inputStream())
    private fun balance(s:LedgerStore,a:LedgerAccount)=s.getAccountByUid(a.uid)!!.amountCents
    private fun reject(code:String,block:()->Unit) { try { block();fail("Expected $code") } catch(e:WalletException) { assertEquals(code,e.code) } }

    @Test fun I01_I02_I03_DefaultExclusionIsPermanentForAllThreeDirectionsAndNewFilesResetOptions() {
        val s=store();val a=wallet(s);val b=wallet(s,20000)
        val expense=bill().let { it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,a.uid,accountReference=WalletFileReference.of(a).json())) }
        val income=bill(Direction.INCOME,3000);val doc=document(listOf(expense,income,transfer(a,b)))
        val preview=LedgerCsvImport.prepare(s,doc);assertFalse(preview.options.linkWalletBalances);assertTrue(preview.walletImpacts.isEmpty());assertEquals(3,preview.posted)
        val result=LedgerCsvImport.applyPreview(s,doc,preview);assertEquals(3,result.imported)
        assertTrue(s.all().all { it.wallet!!.eligibility==WalletEligibility.IMPORT_EXCLUDED && it.walletEffects.isEmpty() })
        for(r in s.all()) { val edited=s.saveEdited(r.copy(note="合成编辑"));s.softDelete(edited.id,now);s.undoDelete(edited.id,now+1) }
        assertEquals(100000L,balance(s,a));assertEquals(20000L,balance(s,b))
        val again=LedgerCsvImport.prepare(s,doc,BillImportOptions(true,true));assertEquals(3,again.duplicates);assertEquals(0,again.ready)
        assertFalse(LedgerCsvImport.prepare(s,document(listOf(bill()))).options.linkWalletBalances)
    }
    @Test fun I04_I05_MissingEndpointIsPostedWhenExcludedAndReviewWithNoSingleLegWhenLinked() {
        val s=store();val a=wallet(s);val external=LedgerAccount(name="合成外部端",amountCents=0)
        val doc=document(listOf(transfer(a,external)))
        val closed=LedgerCsvImport.prepare(s,doc);assertEquals(1,closed.posted);assertEquals(0,closed.pendingWallets)
        val saved=LedgerCsvImport.applyPreview(s,doc,closed);assertEquals(1,saved.posted)
        assertTrue(s.all().single().transfer!!.toReference!!.contains(external.name));assertNull(s.all().single().transfer!!.toAccountUid)
        val linkedDoc=document(listOf(transfer(a,external)))
        val linked=LedgerCsvImport.prepare(s,linkedDoc,BillImportOptions(true));assertEquals(1,linked.pendingWallets);assertTrue(linked.walletImpacts.isEmpty())
        LedgerCsvImport.applyPreview(s,linkedDoc,linked);assertEquals(100000L,balance(s,a))
    }
    @Test fun I05_ExplicitlyUnmappedOrdinaryImportCannotSilentlyFallBackToDefault() {
        val s=store();val a=wallet(s);s.setDefaultWallet(a.uid)
        val doc=document(listOf(bill()));val preview=LedgerCsvImport.prepare(s,doc,BillImportOptions(true,useDefaultWallet=false))
        assertEquals(1,preview.pendingWallets);assertTrue(preview.walletImpacts.isEmpty())
        val result=LedgerCsvImport.applyPreview(s,doc,preview);assertEquals(1,result.pendingWallets);assertEquals(0,result.posted)
        assertNull(s.all().single().wallet!!.accountUid);assertTrue(s.all().single().walletEffects.isEmpty());assertEquals(100000L,balance(s,a))
    }
    @Test fun I04_TwoFeeModesPreviewEqualsActualAndArchivedHistoryOnlyPostsWhenExcluded() {
        for(mode in FeeMode.entries) { val s=store();val a=wallet(s);val b=wallet(s,20000)
            val doc=document(listOf(transfer(a,b,mode)));val preview=LedgerCsvImport.prepare(s,doc,BillImportOptions(true));assertEquals(2,preview.walletImpacts.size)
            LedgerCsvImport.applyPreview(s,doc,preview);preview.walletImpacts.forEach { impact->assertEquals(impact.afterCents,s.getAccountByUid(impact.accountUid!!)!!.amountCents) }
            assertEquals(if(mode==FeeMode.EXTRA)89900L else 90000L,balance(s,a));assertEquals(if(mode==FeeMode.EXTRA)30000L else 29900L,balance(s,b))
            s.archiveAccount(b.id);val archiveDoc=document(listOf(transfer(a,b)))
            assertEquals(1,LedgerCsvImport.prepare(s,archiveDoc).posted);assertEquals(1,LedgerCsvImport.prepare(s,archiveDoc,BillImportOptions(true)).pendingWallets)
        }
    }
    @Test fun I06_I12_BatchSummaryAndOriginalUidsRecoverAfterHardDeleteAndOptionsChange() {
        val s=store();val doc=document(listOf(bill()));val preview=LedgerCsvImport.prepare(s,doc,submissionToken="stable-csv-result")
        val first=LedgerCsvImport.applyPreview(s,doc,preview);s.delete(s.all().single().id)
        val retry=LedgerCsvImport.applyPreview(s,doc,preview);assertEquals(first,retry);assertEquals(1,retry.imported);assertEquals(first.recordUids,retry.recordUids)
        assertTrue(s.all().isEmpty());assertNotNull(s.importReceipt(preview.plan!!.submissionToken))
    }
    @Test fun I11_ClosedPreviewIgnoresUnrelatedCalibrationButRejectsIdentityAndMappingChanges() {
        val s=store();val a=wallet(s);val doc=document(listOf(bill()));val preview=LedgerCsvImport.prepare(s,doc)
        s.saveAccount(s.getAccount(a.id)!!.copy(amountCents=50000),calibrateBalance=true)
        assertEquals(1,LedgerCsvImport.applyPreview(s,doc,preview).imported)
        val second=document(listOf(bill()));val before=LedgerCsvImport.prepare(s,second)
        s.saveWalletImportAlias(UUID.randomUUID().toString(),a.uid)
        reject("IMPORT_PREVIEW_STALE") { LedgerCsvImport.applyPreview(s,second,before) }
    }
    @Test fun I12_I13_NetImportAtLimitIsOrderIndependentAndCancellationRollsBackWholeBatch() {
        val s=store();val a=wallet(s,Money.MAX_CENTS);val ref=WalletFileReference.of(a).json()
        fun row(d:Direction)=bill(d,100).let { it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,a.uid,accountReference=ref)) }
        for(rows in listOf(listOf(row(Direction.INCOME),row(Direction.EXPENSE)),listOf(row(Direction.EXPENSE),row(Direction.INCOME)))) {
            val doc=document(rows);val preview=LedgerCsvImport.prepare(s,doc,BillImportOptions(true));assertEquals(0L,preview.walletImpacts.single().deltaCents)
            LedgerCsvImport.applyPreview(s,doc,preview);assertEquals(Money.MAX_CENTS,balance(s,a))
        }
        val doc=document(listOf(row(Direction.EXPENSE),row(Direction.EXPENSE)));val preview=LedgerCsvImport.prepare(s,doc,BillImportOptions(true));val count=s.all().size;var checks=0
        try { LedgerCsvImport.applyPreview(s,doc,preview) { if(++checks==2)throw CancellationException("synthetic cancel") };fail("cancel should throw") } catch(_:CancellationException) { }
        assertEquals(count,s.all().size);assertEquals(Money.MAX_CENTS,balance(s,a));assertNull(s.importReceipt(preview.plan!!.submissionToken))
    }
    @Test fun I14_I15_I16_SchemasTimezoneAndQianjiFeeRoundTripPreserveActualLegs() {
        val s=store();val a=wallet(s);val b=wallet(s,20000)
        val oldZone=TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));val original=transfer(a,b).copy(note="备注,\"精确\"\n第二行")
            val bytes=csv(listOf(original));TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val parsed=LedgerCsvImport.read(bytes.inputStream()).records.single();assertEquals(original.bill.occurredAt,parsed.bill.occurredAt);assertEquals(original.note,parsed.note)
            val qianji=LedgerCsvImport.read(csv(listOf(original),LedgerCsvExport.Format.QIANJI,listOf(a,b)).inputStream()).records.single()
            assertEquals(10100L,qianji.bill.amountCents);assertEquals(100L,qianji.transfer!!.feeCents);assertEquals(FeeMode.DEDUCTED,qianji.transfer.feeMode)
            val amounts=TransferCalculator.calculate(qianji.bill.amountCents!!,qianji.transfer.feeCents!!,qianji.transfer.feeMode!!)
            assertEquals(10100L,amounts.debitCents);assertEquals(10000L,amounts.creditCents)
            val fixture=InstrumentationRegistry.getInstrumentation().context.assets
            assertEquals("ledger-v1",fixture.open("transfers/general-v1.csv").use(LedgerCsvImport::read).schema)
            assertTrue(fixture.open("transfers/general-v2.csv").use(LedgerCsvImport::read).errors.isEmpty())
        } finally { TimeZone.setDefault(oldZone) }
    }
    @Test fun I07_WalletRoundTripKeepsSignedAssetsNonnegativeLiabilityArchiveAndFourDigitTail() {
        val accounts=listOf(LedgerAccount(name="合成负资产",amountCents=-5000,kind="bank",bankCode="cmb",cardLast4="0007",archived=true),LedgerAccount(name="合成负债",amountCents=1234,type=AccountType.LIABILITY))
        val bytes=ByteArrayOutputStream().also { WalletCsvExport.write(accounts,it) }.toByteArray();assertTrue(bytes.toString(Charsets.UTF_8).contains("\"'0007\""))
        val parsed=WalletCsvImport.read(bytes.inputStream());assertTrue(parsed.errors.isEmpty());assertEquals(accounts,parsed.rows.map { it.account })
        val s=store();val preview=WalletCsvImport.prepare(s,parsed);val result=WalletCsvImport.apply(s,preview);assertEquals(2,result.imported)
        assertEquals(-5000L,s.getAccountByUid(accounts[0].uid)!!.amountCents);assertEquals("0007",s.getAccountByUid(accounts[0].uid)!!.cardLast4);assertTrue(s.all().isEmpty())
        val invalid=bytes.toString(Charsets.UTF_8).replace("\"12.34\"","\"'-12.34\"")
        assertEquals(1,WalletCsvImport.read(invalid.byteInputStream()).errors.size)
    }
    @Test fun I08_WalletOverwriteIsExplicitCalibrationAndKeepsExistingEffects() {
        val s=store();val a=wallet(s,50000);val expense=s.saveEdited(bill(),a.uid,true)
        val doc=walletDocument(listOf(s.getAccount(a.id)!!.copy(id=0,amountCents=30000,revision=0)))
        val keep=WalletCsvImport.prepare(s,doc);assertEquals(40000L,keep.accounts.single().finalBalance);WalletCsvImport.apply(s,keep);assertEquals(40000L,balance(s,a))
        val overwrite=WalletCsvImport.prepare(s,doc,mapOf(a.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,a.uid,overwriteBalance=true)))
        val result=WalletCsvImport.apply(s,overwrite);assertEquals(30000L,balance(s,a));assertEquals(result,WalletCsvImport.apply(s,overwrite))
        assertEquals(expense.walletEffects,s.get(expense.id)!!.walletEffects)
        assertTrue(s.walletMovements(a.uid).any { it.kind=="WALLET_IMPORT_SNAPSHOT" && it.deltaCents==-10000L && it.operationUid==result.batchUid })
        s.saveEdited(s.get(expense.id)!!.copy(bill=expense.bill.copy(amountCents=12000)));assertEquals(28000L,balance(s,a))
    }
    @Test fun I09_NameOnlySuggestsAndConfirmedAliasMapsLaterBills() {
        val s=store();val a=wallet(s,name="合成同名");val source=a.copy(id=0,uid=UUID.randomUUID().toString(),amountCents=5000,revision=0)
        val doc=walletDocument(listOf(source));val suggested=WalletCsvImport.prepare(s,doc);assertEquals(1,suggested.unresolved);assertTrue(s.walletImportAliases().isEmpty())
        val merged=WalletCsvImport.prepare(s,doc,mapOf(source.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,a.uid)))
        WalletCsvImport.apply(s,merged);assertEquals(a.uid,s.walletImportAliases()[source.uid]);assertEquals(100000L,balance(s,a))
        val row=bill().let { it.copy(wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,accountReference=WalletFileReference.of(source).json())) }
        val bills=document(listOf(row));val preview=LedgerCsvImport.prepare(s,bills,BillImportOptions(true));assertEquals(a.uid,preview.plan!!.records.single().wallet!!.accountUid)
        LedgerCsvImport.applyPreview(s,bills,preview);assertEquals(90000L,balance(s,a))
    }
    @Test fun I18_ConflictingWalletUidsAndManySourceSnapshotsNeverUseLastRowWins() {
        val a=LedgerAccount(name="合成",amountCents=100);val conflict=walletDocument(listOf(a,a.copy(amountCents=200)))
        assertTrue(conflict.rows.isEmpty());assertEquals(2,conflict.errors.size)
        val s=store();val local=wallet(s);val b=a.copy(uid=UUID.randomUUID().toString(),amountCents=200)
        val doc=walletDocument(listOf(a,b));val options=listOf(a,b).associate { it.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,local.uid,overwriteBalance=true) }
        val preview=WalletCsvImport.prepare(s,doc,options);assertTrue(preview.entries.isEmpty());assertEquals(2,preview.plan.errors.size);assertEquals(100000L,balance(s,local))
    }
    @Test fun I12_WalletBatchFailureRollsBackBalancesAccountsAliasesAndReceipt() {
        val s=store();val a=wallet(s);val source=a.copy(id=0,uid=UUID.randomUUID().toString(),amountCents=20000,revision=0)
        val doc=walletDocument(listOf(source));val preview=WalletCsvImport.prepare(s,doc,mapOf(source.uid to WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,a.uid,overwriteBalance=true)),"wallet-atomic")
        s.writableDatabase.execSQL("CREATE TRIGGER portable_wallet_abort BEFORE INSERT ON import_batches BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { WalletCsvImport.apply(s,preview);fail("must fail") } catch(_:Exception) { }
        assertEquals(100000L,balance(s,a));assertTrue(s.walletImportAliases().isEmpty());assertNull(s.importReceipt("wallet-atomic"))
        s.writableDatabase.execSQL("DROP TRIGGER portable_wallet_abort")
        val success=WalletCsvImport.apply(s,preview);assertEquals(success,WalletCsvImport.apply(s,preview));assertEquals(20000L,balance(s,a))
    }
    @Test fun I17_I19_MalformedSchemaRowsAndWrongFileEntryAreExplicit() {
        val bytes=csv(listOf(bill())).toString(Charsets.UTF_8)
        val unknown=bytes.replace("\"2\"","\"99\"");assertNotNull(runCatching { LedgerCsvImport.read(unknown.byteInputStream()) }.exceptionOrNull())
        val unknownType=bytes.replace("\"支出\"","\"信用卡还款\"");assertEquals(1,LedgerCsvImport.read(unknownType.byteInputStream()).errors.size)
        val invalidMoney=bytes.replace("\"100.00\"","\"100.001\"");assertEquals(1,LedgerCsvImport.read(invalidMoney.byteInputStream()).errors.size)
        val walletBytes=ByteArrayOutputStream().also { WalletCsvExport.write(listOf(LedgerAccount(name="合成",amountCents=0)),it) }.toByteArray()
        assertTrue(runCatching { LedgerCsvImport.read(walletBytes.inputStream()) }.exceptionOrNull()!!.message!!.contains("钱包"))
        assertTrue(runCatching { WalletCsvImport.read(bytes.byteInputStream()) }.exceptionOrNull()!!.message!!.contains("账单"))
    }
    @Test fun I17_MixedOrUnknownSchemaVersionsRejectWholeFilesWhileFieldErrorsAllowVisibleSubset() {
        val bytes=csv(listOf(bill(),bill(Direction.INCOME,20000))).toString(Charsets.UTF_8)
        for(version in listOf("1","3")) {
            val mixed=bytes.replaceFirst(",\"2\",",",\"$version\",")
            assertNotEquals(bytes,mixed)
            assertTrue(runCatching { LedgerCsvImport.read(mixed.byteInputStream()) }.exceptionOrNull()!!.message!!.contains("版本"))
        }
        val wallets=ByteArrayOutputStream().also { WalletCsvExport.write(listOf(LedgerAccount(name="合成一",amountCents=100),LedgerAccount(name="合成二",amountCents=200)),it) }.toString(Charsets.UTF_8.name())
        for(version in listOf("2","3")) {
            val mixed=wallets.replaceFirst("\"1\"","\"$version\"")
            assertTrue(runCatching { WalletCsvImport.read(mixed.byteInputStream()) }.exceptionOrNull()!!.message!!.contains("版本"))
        }
        val subset=LedgerCsvImport.read(bytes.replaceFirst("\"100.00\"","\"100.001\"").byteInputStream())
        assertEquals(1,subset.records.size);assertEquals(1,subset.errors.size)
        val s=store();val preview=LedgerCsvImport.prepare(s,subset);assertEquals(1,preview.ready);assertEquals(1,preview.errors.size)
        assertEquals(1,LedgerCsvImport.applyPreview(s,subset,preview).imported)
    }
    @Test fun I20_ExportUsesOneRecordAndAccountSnapshotEvenIfWriteTriggersConcurrentEdits() {
        val s=store();val a=wallet(s,name="合成旧名称");val b=wallet(s,0,name="合成目标");val r=s.saveEdited(transfer(a,b));var changed=false
        val output=object:ByteArrayOutputStream() { override fun write(bytes:ByteArray,off:Int,len:Int) {
            if(!changed) { changed=true;s.saveAccount(s.getAccount(a.id)!!.copy(name="合成新名称"));s.saveEdited(s.get(r.id)!!.copy(bill=r.bill.copy(amountCents=12000))) }
            super.write(bytes,off,len)
        } }
        LedgerCsvExport.write(s,LedgerCsvExport.Format.GENERAL,output)
        val parsed=LedgerCsvImport.read(output.toByteArray().inputStream()).records.single()
        assertEquals(10000L,parsed.bill.amountCents);assertEquals("合成旧名称",WalletFileReference.from(parsed.transfer!!.fromReference)!!.name)
        assertEquals(12000L,s.get(r.id)!!.bill.amountCents)
    }
}
