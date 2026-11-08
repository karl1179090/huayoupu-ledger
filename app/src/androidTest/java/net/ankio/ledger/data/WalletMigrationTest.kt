package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic SQLite fixtures retain the actual pre-wallet constraints and upgrade chains. */
@RunWith(AndroidJUnit4::class)
class WalletMigrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val accountUid=UUID.randomUUID().toString()
    private val recordUid=UUID.randomUUID().toString()
    private val now=1_790_000_000_000L
    private fun fixture(name:String,version:Int) {
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db->
            db.execSQL("""CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL,origin TEXT NOT NULL,raw_text TEXT NOT NULL,received_at INTEGER NOT NULL,
                amount INTEGER,direction TEXT,merchant TEXT NOT NULL,category TEXT NOT NULL,occurred_at INTEGER NOT NULL,
                transaction_id TEXT,reliable INTEGER NOT NULL,time_explicit INTEGER NOT NULL,state TEXT NOT NULL,
                reason TEXT NOT NULL,duplicate_id INTEGER,note TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX records_time ON records(occurred_at)")
            db.execSQL("CREATE INDEX records_transaction ON records(transaction_id)")
            db.execSQL("CREATE INDEX records_received ON records(received_at)")
            db.execSQL("CREATE TABLE capture_events(event_key TEXT PRIMARY KEY NOT NULL,received_at INTEGER NOT NULL)")
            db.insertOrThrow("records",null,ContentValues().apply {
                put("id",41);put("uid",recordUid);put("source","OCR");put("origin","com.tencent.mm")
                put("raw_text","合成历史账单");put("received_at",now);put("amount",10000);put("direction","EXPENSE")
                put("merchant","合成历史商户");put("category","购物");put("occurred_at",now)
                put("transaction_id","SYNTHETIC-LEGACY-1");put("reliable",1);put("time_explicit",1)
                put("state","POSTED");put("reason","");put("note","")
            })
            if(version>=2) {
                db.execSQL("""CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,description TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>=0),
                    kind TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('ASSET','LIABILITY')))""")
                db.execSQL("CREATE TABLE custom_categories(name TEXT NOT NULL,direction TEXT NOT NULL,PRIMARY KEY(direction,name))")
                db.execSQL("INSERT INTO accounts(id,uid,name,description,amount,kind,type) VALUES(17,?,'合成历史卡','保留说明',12345,'bank_icbc','ASSET')",arrayOf(accountUid))
                db.execSQL("INSERT INTO accounts(id,uid,name,description,amount,kind,type) VALUES(99,?,'合成已删卡','',0,'cash','ASSET')",arrayOf(UUID.randomUUID().toString()))
                db.execSQL("DELETE FROM accounts WHERE id=99")
            }
            if(version>=3)db.execSQL("CREATE TABLE capture_images(record_id INTEGER PRIMARY KEY NOT NULL,image BLOB)")
            if(version==4)db.execSQL("CREATE TABLE merchant_icons(record_id INTEGER PRIMARY KEY NOT NULL,image BLOB)")
            if(version>=5) {
                db.execSQL("CREATE TABLE merchant_icons(record_id INTEGER PRIMARY KEY NOT NULL,merchant_key TEXT NOT NULL)")
                db.execSQL("CREATE INDEX merchant_icons_key ON merchant_icons(merchant_key)")
                db.execSQL("CREATE TABLE merchant_icon_assets(merchant_key TEXT PRIMARY KEY NOT NULL,image BLOB NOT NULL)")
            }
            if(version>=7) {
                listOf("refund_provider TEXT","refund_original INTEGER","refund_amount INTEGER","refund_mode TEXT","refund_id TEXT").forEach {
                    db.execSQL("ALTER TABLE records ADD COLUMN $it")
                }
                db.execSQL("""CREATE TABLE capture_history(record_id INTEGER NOT NULL,sequence INTEGER NOT NULL,at INTEGER NOT NULL,
                    kind TEXT NOT NULL,amount INTEGER,refunded INTEGER NOT NULL,raw_text TEXT NOT NULL,refund_mode TEXT NOT NULL,
                    refund_id TEXT,refund_amount INTEGER,PRIMARY KEY(record_id,sequence))""")
            }
            if(version>=8) {
                db.execSQL("ALTER TABLE records ADD COLUMN transaction_identities TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE record_identities(record_id INTEGER NOT NULL,scope TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(record_id,scope))")
                db.execSQL("CREATE INDEX identities_lookup ON record_identities(scope,value)")
                db.execSQL("CREATE TABLE record_deletions(record_id INTEGER PRIMARY KEY NOT NULL,expires_at INTEGER NOT NULL)")
            }
            db.version=version
        }
    }
    @Test fun allVersionsOneThroughEightUpgradeWithoutReplayingHistoricalBills() {
        for(version in 1..8) {
            val name="wallet-migration-$version-${UUID.randomUUID()}.db"
            try {
                fixture(name,version)
                LedgerStore(context,name).use { store->
                    assertEquals(12,store.readableDatabase.version)
                    val historical=store.get(41)!!
                    assertEquals(recordUid,historical.uid);assertEquals(WalletEligibility.LEGACY_EXCLUDED,historical.wallet!!.eligibility)
                    assertEquals(0L,historical.wallet.appliedCents)
                    if(version>1) {
                        val migrated=store.getAccount(17)!!
                        assertEquals(accountUid,migrated.uid);assertEquals(12345L,migrated.amountCents)
                        assertEquals("保留说明",migrated.description);assertEquals("icbc",migrated.bankCode);assertNull(migrated.cardLast4)
                    }
                    val account=store.saveAccount(LedgerAccount(name="合成新钱包",amountCents=-2000))
                    if(version>1)assertTrue(account.id>99)
                    store.setDefaultWallet(account.uid)
                    store.saveEdited(historical.copy(bill=historical.bill.copy(amountCents=12000),note="历史改动"))
                    store.delete(historical.id);assertEquals(-2000L,store.getAccount(account.id)!!.amountCents)
                }
                repeat(2) { LedgerStore(context,name).use { store->assertEquals(-2000L,store.accounts().last().amountCents) } }
            } finally { context.deleteDatabase(name) }
        }
    }
    @Test fun failedUpgradeRollsBackSchemaBalancesAndVersionThenCanRetry() {
        val name="wallet-migration-rollback-${UUID.randomUUID()}.db"
        try {
            fixture(name,8)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TABLE wallet_movements(synthetic INTEGER)")
            }
            val broken=LedgerStore(context,name)
            try { broken.writableDatabase;fail("upgrade should fail") } catch(_:Exception) { } finally { broken.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db->
                assertEquals(8,db.version)
                db.rawQuery("SELECT amount FROM accounts WHERE id=17",null).use { assertTrue(it.moveToFirst());assertEquals(12345L,it.getLong(0)) }
                db.rawQuery("PRAGMA table_info(accounts)",null).use { c->
                    val columns=buildSet { while(c.moveToNext())add(c.getString(1)) };assertFalse("archived" in columns)
                }
                db.execSQL("DROP TABLE wallet_movements")
            }
            LedgerStore(context,name).use { store->assertEquals(12,store.readableDatabase.version);assertEquals(12345L,store.getAccount(17)!!.amountCents) }
        } finally { context.deleteDatabase(name) }
    }
    private fun v9Expected()=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("transfers/ledger-v9-expected.json").bufferedReader().use { it.readText() })
    private fun v9Fixture(name:String): Pair<Map<String,Long>,Long> {
        val script=InstrumentationRegistry.getInstrumentation().context.assets.open("transfers/ledger-v9.sql").bufferedReader().use { it.readText() }
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db->
            script.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';').filter { it.isNotBlank() }.forEach(db::execSQL)
        }
        val expected=v9Expected();val json=expected.getJSONObject("accountBalances")
        return json.keys().asSequence().associateWith { json.getLong(it) } to expected.getLong("movementCount")
    }
    @Test fun v9UpgradeRetainsAllFinancialFactsAndNeverCreatesMovements() {
        val name="wallet-v9-${UUID.randomUUID()}.db"
        try {
            val (balances,movements)=v9Fixture(name)
            repeat(3) {
                LedgerStore(context,name).use { store->
                    assertEquals(12,store.readableDatabase.version)
                    assertEquals(balances,store.accounts().associate { it.uid to it.amountCents })
                    assertEquals(movements,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM wallet_movements",null).use { c->c.moveToFirst();c.getLong(0) })
                    assertEquals(2,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM record_wallet_effects",null).use { c->c.moveToFirst();c.getInt(0) })
                    val columns=store.readableDatabase.rawQuery("PRAGMA table_info(record_wallet_links)",null).use { c->buildSet { while(c.moveToNext())add(c.getString(1)) } }
                    assertFalse("applied_cents" in columns);assertFalse("applied_account_uid" in columns)
                    val expected=v9Expected();val links=expected.getJSONArray("links")
                    for(index in 0 until links.length()) {
                        val source=links.getJSONObject(index);val uid=source.getString("recordUid")
                        val link=store.walletSnapshot().links.firstOrNull { it.recordUid==uid }
                        if(link!=null) { assertEquals(source.getString("eligibility"),link.eligibility.name);assertEquals(source.getLong("revision"),link.revision) }
                        val effects=source.getJSONObject("expectedEffects")
                        assertEquals(effects.keys().asSequence().associateWith { effects.getLong(it) },WalletAccounting.effects(store.readableDatabase,uid))
                    }
                    assertEquals(expected.getString("defaultAccountUid"),store.walletSettings().defaultAccountUid)
                    assertEquals(1,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM wallet_submissions",null).use { c->c.moveToFirst();c.getInt(0) })
                    assertEquals(setOf(WalletEligibility.ACTIVE,WalletEligibility.LEGACY_EXCLUDED,WalletEligibility.RESTORE_EXCLUDED),store.walletSnapshot().links.map { link->link.eligibility }.toSet())
                    assertEquals(1,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM record_deletions",null).use { c->c.moveToFirst();c.getInt(0) })
                    assertTrue(store.all().any { record->record.bill.refund?.refundedCents==10000L && record.walletEffects.isEmpty() })
                }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun invalidV9FinancialFactFailsAtomicallyAndCanBeRetried() {
        val name="wallet-v9-invalid-${UUID.randomUUID()}.db"
        try {
            val (balances,movements)=v9Fixture(name)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db->
                db.execSQL("UPDATE record_wallet_links SET eligibility='RESTORE_EXCLUDED' WHERE applied_cents<>0")
            }
            LedgerStore(context,name).use { broken->try { broken.writableDatabase;fail("invalid migration must fail") } catch(_:Exception) { } }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db->
                assertEquals(9,db.version)
                db.rawQuery("SELECT amount FROM accounts WHERE uid=?",arrayOf(balances.keys.first())).use { c->assertTrue(c.moveToFirst());assertEquals(balances.values.first(),c.getLong(0)) }
                assertEquals(movements,db.rawQuery("SELECT COUNT(*) FROM wallet_movements",null).use { c->c.moveToFirst();c.getLong(0) })
                db.execSQL("UPDATE record_wallet_links SET eligibility='ACTIVE' WHERE applied_cents<>0")
            }
            LedgerStore(context,name).use { assertEquals(12,it.writableDatabase.version) }
        } finally { context.deleteDatabase(name) }
    }

}
