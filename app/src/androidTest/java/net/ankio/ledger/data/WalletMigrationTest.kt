package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    @Test fun versionsOneSevenEightUpgradeWithoutReplayingHistoricalBills() {
        for(version in listOf(1,7,8)) {
            val name="wallet-migration-$version-${UUID.randomUUID()}.db"
            try {
                fixture(name,version)
                LedgerStore(context,name).use { store->
                    assertEquals(9,store.readableDatabase.version)
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
            LedgerStore(context,name).use { store->assertEquals(9,store.readableDatabase.version);assertEquals(12345L,store.getAccount(17)!!.amountCents) }
        } finally { context.deleteDatabase(name) }
    }
}
