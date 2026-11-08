package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** All receipts and wallets are synthetic; public storage and backup boundaries own the assertions. */
@RunWith(AndroidJUnit4::class)
class ReceiptEvidencePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stores = mutableListOf<Pair<String, LedgerStore>>()
    private val now = 1_798_776_000_000L
    private fun store(): LedgerStore {
        val name = "receipt-evidence-${UUID.randomUUID()}.db"
        return LedgerStore(context, name).prepareSyntheticDefaultWallet().also { stores += name to it }
    }
    @After fun close() { stores.forEach { (name, store) -> store.close(); context.deleteDatabase(name) } }
    private fun receipt(id: String, vararg fields: String): Pair<Capture, ParsedBill> {
        val raw = (listOf("支付成功", "付款金额：12.00元", "交易单号：$id") + fields).joinToString("\n")
        val capture = Capture(Source.OCR, "shake:com.tencent.mm", raw, now)
        return capture to LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine), 0, 0, now, origin=capture.origin)!!
    }
    private fun ingest(store: LedgerStore, receipt: Pair<Capture, ParsedBill>) =
        (store.ingest(receipt.first, receipt.second) as IngestResult.Saved).record

    @Test fun v11UpgradeKeepsOldEvidenceMissingAndPreservesCategoryTimeAndBalances() {
        val name = "receipt-v11-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
            val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("categories/ledger-v10.sql")
                .bufferedReader().use { it.readText() }
            // Actual prior schema, independent of v12 onCreate or migration implementation.
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').filter(String::isNotBlank).forEach(db::execSQL)
                db.execSQL("""CREATE TABLE category_directory(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,
                    direction TEXT NOT NULL CHECK(direction IN ('EXPENSE','INCOME')),normalized_name TEXT NOT NULL,
                    icon TEXT NOT NULL,sort_order INTEGER NOT NULL,is_fallback INTEGER NOT NULL DEFAULT 0,UNIQUE(direction,name))""")
                db.execSQL("CREATE INDEX categories_normalized ON category_directory(direction,normalized_name)")
                db.execSQL("ALTER TABLE records ADD COLUMN category_id TEXT")
                db.execSQL("CREATE INDEX records_category_id ON records(category_id)")
                db.execSQL("INSERT INTO category_directory VALUES('SYNTHETIC-KEPT-CATEGORY','用户保留','EXPENSE','用户保留','bus',0,0)")
                db.execSQL("UPDATE records SET category='用户保留',category_id='SYNTHETIC-KEPT-CATEGORY',raw_text=? WHERE id=1",
                    arrayOf("支付成功\n商户名称：合成咖啡\n商品说明：早餐套餐"))
                db.version = 11
            }
            repeat(2) { LedgerStore(context, name).use { ledger ->
                assertEquals(13, ledger.readableDatabase.version)
                val old = ledger.get(1)!!
                assertNull(old.bill.receipt)
                assertTrue(ledger.all().all { it.bill.receipt == null })
                assertEquals("合成商户1", old.bill.merchant)
                assertEquals("用户保留", old.bill.category)
                assertEquals(1_793_937_600_000L, old.bill.occurredAt)
                assertEquals(10000L, old.bill.amountCents)
                assertEquals("SYNTHETIC-KEPT-CATEGORY", ledger.categoryFor(old)!!.id)
                assertEquals(listOf(100000L, 20000L, -5000L, 10000L), ledger.accounts().map { it.amountCents })
                assertEquals(2, ledger.walletSnapshot().movements.size)
            } }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldBackupKeepsAbsentReceiptAndDoesNotInferItFromRawTextOrUserNote() {
        val source = store()
        val saved = ingest(source, receipt("SYNTHETIC-LEGACY-RECEIPT", "商户名称：合成", "商品说明：早餐套餐"))
        val json = JSONObject(LedgerBackup.encode(source, ChannelSettings(context))).put("version", 6)
        json.getJSONArray("records").getJSONObject(0).apply { remove("receipt"); put("note", "手写火锅") }
        val target = store()
        assertEquals(1, LedgerBackup.restore(target, LedgerBackup.decode(json.toString().byteInputStream())))
        val restored = target.all().single()
        assertNull(restored.bill.receipt)
        assertEquals(saved.bill.category, restored.bill.category)
        assertEquals(saved.bill.occurredAt, restored.bill.occurredAt)
        assertEquals("手写火锅", restored.note)
        assertTrue(restored.capture.text.contains("商品说明"))
    }

    @Test fun malformedOrUnboundedReceiptBackupsAreRejectedBeforeAnyRestore() {
        val source = store()
        ingest(source, receipt("SYNTHETIC-INVALID-RECEIPT", "商户名称：合成", "商品说明：早餐套餐"))
        val encoded = LedgerBackup.encode(source, ChannelSettings(context))
        val target = store()
        val balances = target.accounts()
        val invalid = listOf<(JSONObject) -> Unit>(
            { it.put("version", 2) },
            { it.put("merchant", "合成") },
            { it.getJSONObject("merchant").put("value", 12) },
            { it.getJSONObject("merchant").put("source", "guess") },
            { it.getJSONObject("merchant").put("blockId", "other") },
            { it.getJSONObject("merchant").put("rawText", "合".repeat(2049)) },
            { evidence -> listOf("merchant", "productDescription", "transactionDescription", "orderRemark", "serviceProvider", "receiptIssuer", "sourceApp").forEach {
                evidence.put(it, JSONObject().put("value", "合".repeat(512)).put("rawText", "合".repeat(2048)).put("source", "ocr").put("blockId", "single"))
            } },
        )
        for (mutation in invalid) {
            val json = JSONObject(encoded)
            mutation(json.getJSONArray("records").getJSONObject(0).getJSONObject("receipt"))
            assertNotNull(runCatching { LedgerBackup.restore(target, LedgerBackup.decode(json.toString().byteInputStream())) }.exceptionOrNull())
            assertTrue(target.all().isEmpty())
            assertEquals(balances, target.accounts())
        }
    }

    @Test fun jsonAndZipBackupKeepReceiptAndFinancialFactsWithoutReplayingEffects() {
        val source = store()
        val recognized = ingest(source, receipt("SYNTHETIC-RECEIPT-BACKUP", "商户名称：合成", "商品说明：早餐套餐", "订单备注：少糖"))
        val saved = source.saveEdited(recognized.copy(note="手写火锅", bill=recognized.bill.copy(category="交通")))
        val sourceWallet = source.getAccountByUid(saved.wallet!!.accountUid!!)!!
        val channels = ChannelSettings(context)
        val json = LedgerBackup.encode(source, channels)
        val zipped = ByteArrayOutputStream().also { LedgerArchive.write(source, channels, it) }.toByteArray()
        for (bytes in listOf(json.toByteArray(), zipped)) {
            val target = store()
            LedgerArchive.read(bytes.inputStream(), context.cacheDir).use { prepared ->
                assertEquals(1, LedgerArchive.restore(target, prepared))
                val restored = target.all().single()
                assertEquals(saved.bill.receipt, restored.bill.receipt)
                assertEquals("交通", restored.bill.category)
                assertEquals("手写火锅", restored.note)
                assertEquals("少糖", restored.bill.receipt!!.orderRemark!!.value)
                assertEquals(sourceWallet.amountCents, target.getAccountByUid(sourceWallet.uid)!!.amountCents)
                val movements = target.walletSnapshot().movements
                assertEquals(0, LedgerArchive.restore(target, prepared))
                assertEquals(movements, target.walletSnapshot().movements)
                assertEquals(sourceWallet.amountCents, target.getAccountByUid(sourceWallet.uid)!!.amountCents)
            }
        }
    }

    @Test fun provenSupplementFillsMissingReceiptFieldsWithoutReclassifyingOrReapplyingMoney() {
        val store = store()
        val original = ingest(store, receipt("SYNTHETIC-RECEIPT-SUPPLEMENT", "商户名称：合成"))
        val edited = store.saveEdited(original.copy(note="用户手写火锅备注",
            bill=original.bill.copy(category="交通")))
        val balances = store.accounts().associate { it.uid to it.amountCents }
        val movements = store.walletSnapshot().movements
        val full = receipt("SYNTHETIC-RECEIPT-SUPPLEMENT", "商户名称：合成咖啡", "商品说明：早餐套餐", "订单备注：少糖")
        val saved = ingest(store, full)
        assertEquals(original.uid, saved.uid)
        assertEquals("交通", saved.bill.category)
        assertEquals("用户手写火锅备注", saved.note)
        assertEquals(edited.bill.merchant, saved.bill.merchant)
        val evidence = requireNotNull(saved.bill.receipt)
        assertEquals("合成", evidence.merchant!!.value)
        assertEquals("早餐套餐", evidence.productDescription!!.value)
        assertEquals("少糖", evidence.orderRemark!!.value)
        assertEquals(balances, store.accounts().associate { it.uid to it.amountCents })
        assertEquals(movements, store.walletSnapshot().movements)
        store.ingest(full.first, full.second)
        assertEquals(balances, store.accounts().associate { it.uid to it.amountCents })
        assertEquals(movements, store.walletSnapshot().movements)
        assertEquals(1, store.all().size)
    }
}
