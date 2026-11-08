package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.capture.ChannelSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Directory management is exercised through the real store and its public read models. */
@RunWith(AndroidJUnit4::class)
class CategoryDirectoryTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private lateinit var databaseName: String
    private val instant = 1_790_000_000_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "category-directory-${UUID.randomUUID()}.db"
        store = LedgerStore(context, databaseName)
    }

    @After fun tearDown() { store.close(); context.deleteDatabase(databaseName) }

    @Test fun referencedCategoryCannotBeDeletedAndItsBillIsUnchanged() {
        val category = CustomCategory("合成保留分类", Direction.EXPENSE)
        store.addCategory(category)
        store.merge(listOf(record(category.name)))
        val before = store.all().single()
        val error = runCatching { store.deleteCategory(category) }.exceptionOrNull()
        assertNotNull("Referenced category must be protected", error)
        assertTrue(store.customCategories().contains(category))
        assertEquals(before, store.all().single())
    }

    @Test fun directoryUsesStableIdsAndIndependentDirectionalNamesAcrossRestart() {
        val expense = store.saveCategory(name = "合成往来", direction = Direction.EXPENSE, icon = "gift")
        val income = store.saveCategory(name = "合成往来", direction = Direction.INCOME, icon = "wallet")
        assertNotEquals(expense.id, income.id)
        store.merge(listOf(record(expense.name), record(income.name, Direction.INCOME)))
        val before = store.all()
        val renamed = store.saveCategory(expense.id, "合成人情", Direction.EXPENSE, "heart")
        assertEquals(expense.id, renamed.id)
        assertEquals(before.map { if (it.bill.direction == Direction.EXPENSE) it.copy(bill = it.bill.copy(category = "合成人情")) else it }, store.all())
        val order = store.categoryDirectory(Direction.EXPENSE).map { it.id }.reversed()
        store.reorderCategories(Direction.EXPENSE, order)
        store.close(); store = LedgerStore(context, databaseName)
        assertEquals(order, store.categoryDirectory(Direction.EXPENSE).map { it.id })
        assertEquals("heart", store.categoryDirectory(Direction.EXPENSE).single { it.id == expense.id }.icon)
        assertEquals("合成往来", store.categoryDirectory(Direction.INCOME).single { it.id == income.id }.name)
        assertEquals(1L, store.categoryDirectory(Direction.EXPENSE).single { it.id == expense.id }.referenceCount)
    }

    @Test fun newRecognitionUsesCurrentPresetNameWhileHistoryAndFlatCsvNamesRemainDistinct() {
        store.prepareSyntheticDefaultWallet()
        store.merge(listOf(record("餐饮美食"), record("餐饮"), record("合成一级/二级")))
        val historical = store.all()
        val dining = store.categoryDirectory(Direction.EXPENSE).single { it.name == "餐饮" }
        store.saveCategory(dining.id, "合成餐食", Direction.EXPENSE, "coffee")
        val capture = Capture(Source.IMAGE, "synthetic-image", "合成新餐食 21.35", instant + 120_000)
        val bill = ParsedBill(2135, Direction.EXPENSE, "合成新商家", "餐饮美食", instant + 120_000, null, true, "", true)
        val saved = (store.ingest(capture, bill) as IngestResult.Saved).record
        assertEquals("合成餐食", saved.bill.category)
        val names = store.categoryDirectory(Direction.EXPENSE).map { it.name }
        assertTrue(names.containsAll(listOf("餐饮美食", "合成餐食", "合成一级/二级")))
        assertEquals(historical.filter { it.bill.category != "餐饮" }, store.all().filter { it.id in historical.filter { it.bill.category != "餐饮" }.map { it.id } })
        val month = java.time.Instant.ofEpochMilli(instant).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString().take(7)
        val filtered = LedgerReadView(store).records(month, direction = "EXPENSE", category = "category:合成餐食").getJSONArray("records")
        assertEquals(2, filtered.length())
        assertEquals(dining.id, filtered.getJSONObject(0).getString("categoryId"))
        assertEquals("coffee", filtered.getJSONObject(0).getString("categoryIcon"))
        val categories = LedgerStatistics(store).month(month).getJSONArray("categories")
        val total = (0 until categories.length()).map { categories.getJSONObject(it) }.single { it.getString("name") == "合成餐食" }
        assertEquals(dining.id, total.getString("categoryId")); assertEquals("coffee", total.getString("icon"))
        assertEquals("3369", total.getString("amount"))
    }

    @Test fun completeBackupRetainsDirectoryIdentityIconsOrderAndLocalEditsOnRepeatedRestore() {
        val custom = store.saveCategory(name = "合成备份分类", direction = Direction.EXPENSE, icon = "paw")
        store.merge(listOf(record(custom.name), record("合成一级/二级")))
        val dining = store.categoryDirectory(Direction.EXPENSE).single { it.name == "餐饮" }
        store.saveCategory(dining.id, "合成预设改名", Direction.EXPENSE, "coffee")
        store.reorderCategories(Direction.EXPENSE, store.categoryDirectory(Direction.EXPENSE).map { it.id }.reversed())
        val json = LedgerBackup.encode(store, ChannelSettings(context))
        assertEquals(8, JSONObject(json).getInt("version"))
        val backup = LedgerBackup.decode(json.byteInputStream())
        val targetName = "category-restore-${UUID.randomUUID()}.db"
        try { LedgerStore(context, targetName).use { target ->
            assertEquals(2, target.restoreBackup(backup))
            assertEquals(store.categoryDirectory(), target.categoryDirectory())
            val before = target.all()
            val local = target.saveCategory(custom.id, "合成本地改名", Direction.EXPENSE, "heart")
            assertEquals(0, target.restoreBackup(backup))
            assertEquals(local, target.categoryDirectory(Direction.EXPENSE).single { it.id == custom.id })
            assertEquals(before.map { if (it.bill.category == custom.name) it.copy(bill = it.bill.copy(category = "合成本地改名")) else it }, target.all())
            assertFalse(target.categoryDirectory(Direction.EXPENSE).any { it.name == custom.name })
        } } finally { context.deleteDatabase(targetName) }
    }

    @Test fun missingStableCategoryReferenceRejectsBackupBeforeMutation() {
        store.merge(listOf(record("合成备份校验")))
        val before = store.all(); val directory = store.categoryDirectory(); val wallets = store.walletSnapshot()
        val root = JSONObject(LedgerBackup.encode(store, ChannelSettings(context)))
        root.getJSONArray("records").getJSONObject(0).remove("categoryId")
        val error = runCatching { store.restoreBackup(LedgerBackup.decode(root.toString().byteInputStream())) }.exceptionOrNull()
        assertNotNull("Stable category references must be present in a directory snapshot", error)
        assertEquals(before, store.all()); assertEquals(directory, store.categoryDirectory()); assertEquals(wallets, store.walletSnapshot())
    }

    @Test fun managementRejectsNormalizedDuplicatesBadOrderAndSystemFallbackRemoval() {
        val created = store.saveCategory(name = "ＡＢ", direction = Direction.EXPENSE)
        val duplicate = runCatching { store.saveCategory(name = "a b", direction = Direction.EXPENSE) }.exceptionOrNull() as CategoryException
        assertEquals("CATEGORY_DUPLICATE", duplicate.code)
        assertNotEquals(created.id, store.saveCategory(name = "ab", direction = Direction.INCOME).id)
        val fallback = store.categoryDirectory(Direction.EXPENSE).single { it.isFallback }
        assertEquals("其他", fallback.name)
        assertEquals("CATEGORY_SYSTEM", (runCatching { store.removeCategory(fallback.id) }.exceptionOrNull() as CategoryException).code)
        assertEquals("CATEGORY_SYSTEM", (runCatching { store.saveCategory(fallback.id, "合成其他", Direction.EXPENSE) }.exceptionOrNull() as CategoryException).code)
        val before = store.categoryDirectory()
        assertNotNull(runCatching { store.reorderCategories(Direction.EXPENSE, listOf(created.id, created.id)) }.exceptionOrNull())
        for (name in listOf("", " ", "\n", "合成\t分类", "名".repeat(41), "转账手续费"))
            assertNotNull(runCatching { store.saveCategory(name = name, direction = Direction.EXPENSE) }.exceptionOrNull())
        assertNotNull(runCatching { store.saveCategory(name = "合成图标", direction = Direction.EXPENSE, icon = "invalid") }.exceptionOrNull())
        assertEquals(before, store.categoryDirectory())
        store.removeCategory(created.id)
        assertFalse(store.categoryDirectory(Direction.EXPENSE).any { it.id == created.id })
    }

    @Test fun failedRenameRollsBackDirectoryBillDisplaysAndFinancialFacts() {
        store.prepareSyntheticDefaultWallet()
        val category = store.saveCategory(name = "合成事务分类", direction = Direction.EXPENSE)
        store.saveEdited(record(category.name))
        val records = store.all(); val directory = store.categoryDirectory(); val wallets = store.walletSnapshot()
        store.writableDatabase.execSQL("CREATE TRIGGER synthetic_rename_failure BEFORE UPDATE ON records BEGIN SELECT RAISE(ABORT,'synthetic rename failure'); END")
        try { assertNotNull(runCatching { store.saveCategory(category.id, "合成改名失败", Direction.EXPENSE, "heart") }.exceptionOrNull()) }
        finally { store.writableDatabase.execSQL("DROP TRIGGER synthetic_rename_failure") }
        assertEquals(records, store.all()); assertEquals(directory, store.categoryDirectory()); assertEquals(wallets, store.walletSnapshot())
    }

    @Test fun legacyBackupRetainsUnknownLongAndFlatCategoryNamesOnRepeatedRestore() {
        val records = listOf(record("餐饮美食"), record("合成一级/二级"), record("合成陌生分类"), record("餐饮美食", Direction.INCOME))
        val root = JSONObject(LedgerBackup.encode(records, ChannelSettings(context))).put("version", 5)
        root.remove("categoryDirectory")
        val entries = root.getJSONArray("records"); repeat(entries.length()) { entries.getJSONObject(it).remove("categoryId") }
        val backup = LedgerBackup.decode(root.toString().byteInputStream())
        assertEquals(4, store.restoreBackup(backup))
        val before = store.all(); val wallets = store.walletSnapshot(); val directory = store.categoryDirectory()
        assertEquals(records.associate { it.uid to it.bill }, before.associate { it.uid to it.bill })
        assertEquals(0, store.restoreBackup(backup))
        assertEquals(before, store.all()); assertEquals(wallets, store.walletSnapshot()); assertEquals(directory, store.categoryDirectory())
        assertTrue(store.categoryDirectory(Direction.EXPENSE).any { it.name == "合成一级/二级" })
        assertNotEquals(store.categoryDirectory(Direction.EXPENSE).single { it.name == "餐饮美食" }.id,
            store.categoryDirectory(Direction.INCOME).single { it.name == "餐饮美食" }.id)
    }

    @Test fun newIncomeRecognitionUsesItsOwnFallbackForExpenseOnlyPresetOutput() {
        store.prepareSyntheticDefaultWallet()
        val incoming = record("餐饮美食", Direction.INCOME)
        val saved = (store.ingest(incoming.capture.copy(source = Source.IMAGE, text = "合成收入 12.34"), incoming.bill) as IngestResult.Saved).record
        assertEquals("其他", saved.bill.category)
        assertEquals("cat:income:other", store.categoryFor(saved)!!.id)
        assertFalse(store.categoryDirectory(Direction.INCOME).any { it.name == "餐饮美食" })
    }

    @Test fun failedRestoreRollsBackDirectoryAndRecordsWithExistingFinancialFacts() {
        store.merge(listOf(record("合成待恢复分类")))
        val backup = LedgerBackup.decode(LedgerBackup.encode(store, ChannelSettings(context)).byteInputStream())
        val targetName = "category-restore-failure-${UUID.randomUUID()}.db"
        try { LedgerStore(context, targetName).use { target ->
            target.prepareSyntheticDefaultWallet()
            target.saveCategory(name = "合成本地保留分类", direction = Direction.INCOME, icon = "wallet")
            val directory = target.categoryDirectory(); val records = target.all(); val wallets = target.walletSnapshot()
            target.writableDatabase.execSQL("CREATE TRIGGER synthetic_restore_failure BEFORE INSERT ON records BEGIN SELECT RAISE(ABORT,'synthetic restore failure'); END")
            assertNotNull(runCatching { target.restoreBackup(backup) }.exceptionOrNull())
            assertEquals(directory, target.categoryDirectory()); assertEquals(records, target.all()); assertEquals(wallets, target.walletSnapshot())
        } } finally { context.deleteDatabase(targetName) }
    }

    private fun record(name: String, direction: Direction = Direction.EXPENSE) = LedgerRecord(
        capture = Capture(Source.MANUAL, "synthetic", "", instant),
        bill = ParsedBill(1234, direction, "合成商家", name, instant, null, true, "", true),
        state = RecordState.POSTED)
}
