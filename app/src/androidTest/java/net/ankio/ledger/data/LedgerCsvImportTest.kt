package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerCsvImportTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val stores=mutableListOf<Pair<String,LedgerStore>>()
    private val at=1_790_000_000_000L
    private fun store():LedgerStore {
        val name="csv-import-${UUID.randomUUID()}.db"
        return LedgerStore(context,name).prepareSyntheticDefaultWallet().also { stores.add(name to it) }
    }
    private fun record(amount:Long=12345,direction:Direction=Direction.EXPENSE)=LedgerRecord(
        capture=Capture(Source.OCR,"com.taobao.taobao","private OCR",at+1000),
        bill=ParsedBill(amount,direction,"导入验证商户","日用购物",at,"512781660045037738",true,"",true),
        state=RecordState.POSTED,note="备注,\"尺码\"\r\n第二行🙂")
    private fun csv(records:List<LedgerRecord>,format:LedgerCsvExport.Format=LedgerCsvExport.Format.GENERAL)=ByteArrayOutputStream()
        .also { LedgerCsvExport.write(records,format,it) }.toByteArray()
    private fun read(bytes:ByteArray)=LedgerCsvImport.read(bytes.inputStream())
    private fun rejected(bytes:ByteArray) {
        try { read(bytes);fail("malformed CSV accepted") } catch(_:Exception) { }
    }
    @After fun close() { stores.forEach { (name,store) -> store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name) } }

    @Test fun generalRoundTripPreservesNetRefundLongIdsIncomeAndQuotedMultiline() {
        val source=record().copy(bill=record().bill.copy(merchant="小王,\"精选\"🙂",amountCents=3654,
            refund=RefundInfo("alipay",10000,6346)))
        val full=record(0).copy(bill=record(0).bill.copy(transactionId="512781660045037739",refund=RefundInfo("wechat",10000,10000)))
        val income=record(25000,Direction.INCOME).copy(bill=record(25000,Direction.INCOME).bill.copy(occurredAt=at-90L*86400000,transactionId=null))
        val document=read(csv(listOf(source,full,income)))
        assertTrue(document.errors.isEmpty());assertEquals(3,document.records.size)
        val imported=document.records.first()
        assertEquals(source.uid,imported.uid);assertEquals(source.note,imported.note)
        assertEquals(source.bill,imported.bill.copy(categoryDecision=null))
        assertEquals(CategorySource.IMPORT,imported.bill.categoryDecision!!.source);assertTrue(imported.bill.categoryDecision!!.manual);assertEquals("",imported.capture.text)
        assertEquals(full.bill,document.records[1].bill.copy(categoryDecision=null))
        val target=store();assertEquals(3,LedgerCsvImport.preview(target,document).ready)
        assertEquals(3,LedgerCsvImport.apply(target,document).imported)
        assertEquals(3,target.all().size)
        assertEquals(0L,target.all().single { it.uid==full.uid }.bill.amountCents)
    }

    @Test fun repeatedImportAndNativeOrderIdentitySkipWithoutOverwritingLocalEdits() {
        val target=store();val source=record();val document=read(csv(listOf(source)))
        assertEquals(1,LedgerCsvImport.apply(target,document).imported)
        val saved=target.all().single();target.saveEdited(saved.copy(note="保留本地备注"))
        assertEquals(1,LedgerCsvImport.preview(target,document).duplicates)
        assertEquals(0,LedgerCsvImport.apply(target,document).imported)
        assertEquals("保留本地备注",target.all().single().note)
        val differentUid=read(csv(listOf(source.copy(uid=UUID.randomUUID().toString()))))
        assertEquals(1,LedgerCsvImport.preview(target,differentUid).duplicates)
        assertEquals(0,LedgerCsvImport.apply(target,differentUid).imported)
        val newOrder=read(csv(listOf(source.copy(uid=UUID.randomUUID().toString(),bill=source.bill.copy(transactionId="512781660045037740")))))
        assertEquals(1,LedgerCsvImport.apply(target,newOrder).imported)
    }

    @Test fun qianjiHeaderMapsDatesCategoriesAndPreservesIdenticalSeparateRows() {
        val first=record().copy(bill=record().bill.copy(transactionId=null),note="独立消费")
        val second=first.copy(uid=UUID.randomUUID().toString())
        val document=read(csv(listOf(first,second),LedgerCsvExport.Format.QIANJI))
        assertEquals(2,document.records.size);assertTrue(document.errors.isEmpty())
        assertEquals("导入验证商户",document.records[0].bill.merchant)
        assertEquals(12345L,document.records[0].bill.amountCents)
        assertNotEquals(document.records[0].uid,document.records[1].uid)
        val target=store();assertEquals(2,LedgerCsvImport.apply(target,document).imported)
        assertEquals(2,LedgerCsvImport.preview(target,document).duplicates)
        assertEquals(0,LedgerCsvImport.apply(target,document).imported)
    }

    @Test fun qianjiPrimaryAndSecondaryLabelsBecomeOneFullDirectoryName() {
        val document = read(("时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片\n" +
            "2026-11-08 12:00,合成一级,二级,支出,12.34,,,,,,,,\n").toByteArray())
        assertTrue(document.errors.toString(), document.errors.isEmpty())
        val target = store(); val wallets = target.walletSnapshot()
        assertEquals(1, LedgerCsvImport.apply(target, document).imported)
        assertEquals("合成一级/二级", target.all().single().bill.category)
        val category = target.categoryDirectory(Direction.EXPENSE).single { it.name == "合成一级/二级" }
        assertEquals(1L, category.referenceCount)
        assertEquals(wallets.settings, target.walletSnapshot().settings)
        assertEquals(wallets.movements, target.walletSnapshot().movements)
    }

    @Test fun csvCategoryNameCanBeUsedAgainAfterItsOriginalIdIsRenamed() {
        fun document(time: String, amount: String) = read(("时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片\n" +
            "$time,合成导入原名,,支出,$amount,,,,,,,,\n").toByteArray())
        val target = store(); val wallets = target.walletSnapshot()
        assertEquals(1, LedgerCsvImport.apply(target, document("2026-11-08 12:00", "12.34")).imported)
        val original = target.categoryDirectory(Direction.EXPENSE).single { it.name == "合成导入原名" }
        target.saveCategory(original.id, "合成导入改名", Direction.EXPENSE, "heart")
        val later = document("2026-11-08 13:00", "23.45")
        assertEquals(1, LedgerCsvImport.apply(target, later).imported)
        val directory = target.categoryDirectory(Direction.EXPENSE)
        val renamed = directory.single { it.name == "合成导入改名" }
        val reused = directory.single { it.name == "合成导入原名" }
        assertEquals(original.id, renamed.id); assertEquals("heart", renamed.icon)
        assertNotEquals(original.id, reused.id)
        assertEquals(mapOf("合成导入改名" to 1234L, "合成导入原名" to 2345L), target.all().associate { it.bill.category to it.bill.amountCents })
        assertEquals(0, LedgerCsvImport.apply(target, later).imported)
        assertEquals(reused.id, target.categoryDirectory(Direction.EXPENSE).single { it.name == "合成导入原名" }.id)
        assertEquals(wallets.settings, target.walletSnapshot().settings); assertEquals(wallets.movements, target.walletSnapshot().movements)
    }

    @Test fun invalidRowsArePreviewedAndNeverSilentlyRoundedOrImported() {
        val bytes=csv(listOf(record())).toString(Charsets.UTF_8)
        val invalid=bytes.replace("\"123.45\"","\"123.456\"")
        val document=read(invalid.toByteArray())
        assertEquals(1,document.errors.size);assertEquals(2,document.errors.single().row)
        val target=store();assertEquals(0,LedgerCsvImport.preview(target,document).ready)
        assertEquals(0,LedgerCsvImport.apply(target,document).imported)
        assertTrue(target.all().isEmpty())
        val date=bytes.replace(Regex("2026-[0-9]{2}-[0-9]{2}"),"2026-02-31")
        assertEquals(1,read(date.toByteArray()).errors.size)
    }

    @Test fun parserRejectsTruncationUnknownHeadersMalformedUtf8AndOversizedInputs() {
        rejected("时间,金额\n2026-10-19,1.00".toByteArray())
        rejected(csv(listOf(record())).copyOfRange(0,200))
        rejected(byteArrayOf(0xC3.toByte(),0x28))
        rejected(ByteArray(LedgerCsvImport.MAX_BYTES+1) { 65 })
        val bad=csv(listOf(record())).toString(Charsets.UTF_8).replace("\"日用购物\"","\"日用购物\"X")
        rejected(bad.toByteArray())
    }

    @Test fun knownSchemaRestoresSpreadsheetEscapingButPreservesOrdinaryApostrophesAndExactCents() {
        val source=record(1).copy(note="\t=SUM(1)",bill=record(1).bill.copy(merchant="=1+2",category="'原样分类"))
        val parsed=read(csv(listOf(source))).records.single()
        assertEquals(1L,parsed.bill.amountCents);assertEquals(source.note,parsed.note)
        assertEquals(source.bill.merchant,parsed.bill.merchant);assertEquals(source.bill.category,parsed.bill.category)
        for(note in listOf("'=SUM(1)","''保留双引号","'123456789012345678","'普通前缀")) {
            assertEquals(note,read(csv(listOf(source.copy(note=note)))).records.single().note)
        }
        val max=record(Money.MAX_CENTS)
        assertEquals(Money.MAX_CENTS,read(csv(listOf(max))).records.single().bill.amountCents)
    }

    @Test fun databaseFailureRollsBackWholeConfirmedBatchAndDoesNotChangePermissions() {
        val target=store();val channels=ChannelSettings(context)
        val enabled=channels.enabled(ChannelSettings.Channel.OCR)
        val automatic=channels.automaticEnabled();val packages=channels.selectedAutoPackages()
        val first=record();val second=record().copy(bill=record().bill.copy(merchant="拒绝导入",transactionId="512781660045037741"))
        target.writableDatabase.execSQL("CREATE TRIGGER csv_test_abort BEFORE INSERT ON records WHEN NEW.merchant='拒绝导入' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        val document=read(csv(listOf(first,second)))
        assertEquals(2,LedgerCsvImport.preview(target,document).ready)
        try { LedgerCsvImport.apply(target,document);fail("partial import accepted") } catch(_:Exception) { }
        assertTrue(target.all().isEmpty())
        assertEquals(enabled,channels.enabled(ChannelSettings.Channel.OCR));assertEquals(automatic,channels.automaticEnabled())
        assertEquals(packages,channels.selectedAutoPackages())
    }

    @Test fun previewDoesNotPromiseDuplicateRowsCreatedBeforeConfirmationWillBeInserted() {
        val target=store();val source=record();val document=read(csv(listOf(source)))
        assertEquals(1,LedgerCsvImport.preview(target,document).ready)
        target.merge(listOf(source.copy(uid=UUID.randomUUID().toString())))
        val result=LedgerCsvImport.apply(target,document)
        assertEquals(0,result.imported);assertEquals(1,result.duplicates);assertEquals(1,target.all().size)
    }

    @Test fun invalidGeneralUidIsPreviewedRatherThanCreatingAnUnrestorableBackup() {
        val original=record()
        for(uid in listOf("order-123","1-1-1-1-1","ABCDEF00-1234-4567-8901-123456789012")) {
            val document=read(csv(listOf(original.copy(uid=uid))))
            assertEquals(1,document.errors.size)
            assertTrue(document.errors.single().message.contains("UUID"))
            val target=store()
            assertEquals(0,LedgerCsvImport.preview(target,document).ready)
            assertEquals(0,LedgerCsvImport.apply(target,document).imported)
            assertTrue(target.all().isEmpty())
        }
    }

    @Test fun qianjiPositiveExcludedRowCannotSilentlyBecomeCountedExpense() {
        val encoded=csv(listOf(record().copy(note="普通备注")),LedgerCsvExport.Format.QIANJI).toString(Charsets.UTF_8)
        // The official template's ninth column is the accounting marker.
        val rows=encoded.split("\r\n").toMutableList()
        val cells=rows[1].split("\",\"").toMutableList()
        cells[8]="不计收支"
        rows[1]=cells.joinToString("\",\"")
        val document=read(rows.joinToString("\r\n").toByteArray())
        assertEquals(1,document.errors.size)
        assertTrue(document.errors.single().message.contains("不计收支"))
        val target=store();assertEquals(0,LedgerCsvImport.apply(target,document).imported)
        assertTrue(target.all().isEmpty())
    }

    @Test fun reorderedQianjiColumnsKeepStableUidAndCannotDuplicateAnImportedBill() {
        val source=record().copy(note="普通备注",bill=record().bill.copy(transactionId=null))
        val bytes=csv(listOf(source,source.copy(uid=UUID.randomUUID().toString())),LedgerCsvExport.Format.QIANJI)
        val original=read(bytes)
        // This fixture has no quoted commas or line breaks; reverse full quoted cells independently.
        val reordered=bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimEnd('\r','\n')
            .split("\r\n").joinToString("\r\n") { line ->
                line.removePrefix("\"").removeSuffix("\"").split("\",\"").reversed().joinToString("\",\"","\"","\"")
            }+"\r\n"
        val parsed=read(reordered.toByteArray())
        assertTrue(parsed.errors.isEmpty())
        assertEquals(original.records.map { it.uid },parsed.records.map { it.uid })
        val target=store();assertEquals(2,LedgerCsvImport.apply(target,original).imported)
        assertEquals(2,LedgerCsvImport.preview(target,parsed).duplicates)
        assertEquals(0,LedgerCsvImport.apply(target,parsed).imported)
        assertEquals(2,target.all().size)
        // The general format takes its explicit UID rather than constructing a row signature.
        assertEquals(source.uid,read(csv(listOf(source))).records.single().uid)
    }
}
