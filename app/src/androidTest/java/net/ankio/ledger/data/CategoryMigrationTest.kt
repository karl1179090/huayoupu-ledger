package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.Direction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual v10 tables are loaded from immutable synthetic SQL, independent of current schema creation. */
@RunWith(AndroidJUnit4::class)
class CategoryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(name: String) {
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("categories/ledger-v10.sql").bufferedReader().use { it.readText() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';').filter(String::isNotBlank).forEach(db::execSQL)
        }
    }

    @Test fun genuineV10UpgradePreservesNamesDirectionsFinancialFactsAndDeletedReferences() {
        val name = "category-v10-${UUID.randomUUID()}.db"
        try {
            fixture(name)
            var directory = emptyList<LedgerCategory>()
            repeat(2) { pass -> LedgerStore(context, name).use { store ->
                assertEquals(12, store.readableDatabase.version)
                val records = store.all().associateBy { it.id }
                assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 7L), records.keys)
                assertEquals(mapOf(1L to "餐饮美食", 2L to "餐饮", 3L to "餐饮", 4L to "合成陌生类别", 5L to "合成一级/二级", 7L to "日用购物"), records.mapValues { it.value.bill.category })
                assertEquals(mapOf(1L to 10000L, 2L to 2000L, 3L to 7000L, 4L to 9000L, 5L to 0L, 7L to 3000L), records.mapValues { it.value.bill.amountCents })
                assertTrue(records.values.all { it.bill.occurredAt == 1_793_937_600_000L })
                assertEquals(Direction.INCOME, records.getValue(2).bill.direction)
                assertEquals(listOf(100000L, 20000L, -5000L, 10000L), store.accounts().map { it.amountCents })
                val wallets = store.walletSnapshot()
                assertEquals(2, wallets.movements.size)
                assertEquals(setOf(-10000L, 2000L), wallets.effects.values.flatMap { it.values }.toSet())
                val expense = store.categoryDirectory(Direction.EXPENSE)
                val income = store.categoryDirectory(Direction.INCOME)
                assertNotEquals(expense.single { it.name == "餐饮" }.id, income.single { it.name == "餐饮" }.id)
                assertEquals(1L, expense.single { it.name == "合成撤销分类" }.referenceCount)
                assertFalse(expense.single { it.name == "合成撤销分类" }.canDelete)
                assertTrue(expense.any { it.name == "合成同名" }); assertTrue(income.any { it.name == "合成同名" })
                if (pass == 0) directory = store.categoryDirectory() else assertEquals(directory, store.categoryDirectory())
            } }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedV10UpgradeRollsBackDirectoryColumnNamesAndBalancesThenRetries() {
        val name = "category-v10-rollback-${UUID.randomUUID()}.db"
        try {
            fixture(name)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER synthetic_category_failure BEFORE UPDATE ON records BEGIN SELECT RAISE(ABORT,'synthetic category failure'); END")
            }
            LedgerStore(context, name).use { broken -> assertNotNull(runCatching { broken.writableDatabase }.exceptionOrNull()) }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(10, db.version)
                assertFalse(db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='category_directory'", null).use { it.moveToFirst() })
                assertFalse(db.rawQuery("PRAGMA table_info(records)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }.contains("category_id"))
                db.rawQuery("SELECT category,amount,occurred_at FROM records WHERE id=1", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("餐饮美食", c.getString(0)); assertEquals(10000L, c.getLong(1)); assertEquals(1_793_937_600_000L, c.getLong(2))
                }
                db.rawQuery("SELECT amount FROM accounts WHERE id=1", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(100000L, c.getLong(0)) }
                db.execSQL("DROP TRIGGER synthetic_category_failure")
            }
            LedgerStore(context, name).use { repaired ->
                assertEquals("餐饮美食", repaired.get(1)!!.bill.category)
                assertEquals(12, repaired.readableDatabase.version)
                assertEquals(100000L, repaired.getAccount(1)!!.amountCents)
            }
        } finally { context.deleteDatabase(name) }
    }
}
