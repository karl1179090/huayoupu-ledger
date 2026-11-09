package net.ankio.ledger.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.*
import java.util.UUID

/** Durable selection and the financial effect actually committed to the ledger. */
enum class WalletEligibility { ACTIVE, LEGACY_EXCLUDED, RESTORE_EXCLUDED, IMPORT_EXCLUDED }

data class RecordWalletLink(
    val recordUid: String,
    val eligibility: WalletEligibility,
    val accountUid: String? = null,
    val selectionSource: String? = null,
    val manualLocked: Boolean = false,
    val paymentEvidence: String? = null,
    val evidenceFingerprint: String? = null,
    val appliedAccountUid: String? = null,
    val appliedCents: Long = 0,
    val reviewCode: String? = null,
    val revision: Long = 0,
    val accountReference: String? = null,
)

data class WalletMovement(
    val movementUid: String,
    val operationUid: String,
    val accountUid: String,
    val recordUid: String?,
    val kind: String,
    val deltaCents: Long,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val createdAt: Long,
    val summary: String = "",
)

data class WalletSettings(val defaultAccountUid: String? = null, val appBindings: Map<String, String> = emptyMap(), val revision: Long = 0)
data class WalletSnapshot(val settings: WalletSettings, val links: List<RecordWalletLink>, val movements: List<WalletMovement>,
    val transfers: List<RecordTransferDetails> = emptyList(), val effects: Map<String, Map<String, Long>> = emptyMap(),
    val aliases: Map<String,String> = emptyMap(), val audits: List<ImportAudit> = emptyList())
data class RestoreSummary(val imported:Int,val excludedRecords:Int,val retainedAccounts:Int,val restoredAccounts:Int,
    val identityConflicts:Int=0,val aliasConflicts:Int=0,val skippedRecords:Int=0) { internal var recordMapping:Map<String,String?> = emptyMap() }
class WalletException(val code: String, message: String): IllegalArgumentException(message)

/** Called only while holding LedgerStore's write lock and a ledger.db transaction. */
internal object WalletAccounting {
    fun create(db: SQLiteDatabase) {
        createLinks(db)
        createEffectsAndImports(db)
        db.execSQL("CREATE TABLE wallet_settings(singleton INTEGER PRIMARY KEY CHECK(singleton=1),default_account_uid TEXT,revision INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO wallet_settings(singleton) VALUES(1)")
        db.execSQL("CREATE TABLE app_wallet_bindings(package_name TEXT PRIMARY KEY NOT NULL,account_uid TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE wallet_movements(movement_uid TEXT PRIMARY KEY NOT NULL,operation_uid TEXT NOT NULL,
            account_uid TEXT NOT NULL,record_uid TEXT,kind TEXT NOT NULL,delta_cents INTEGER NOT NULL,
            balance_before INTEGER NOT NULL,balance_after INTEGER NOT NULL,created_at INTEGER NOT NULL,summary TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX movements_account_time ON wallet_movements(account_uid,created_at DESC,movement_uid)")
        db.execSQL("CREATE INDEX movements_record ON wallet_movements(record_uid)")
        db.execSQL("CREATE TABLE wallet_submissions(token TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,result_uid TEXT NOT NULL,created_at INTEGER NOT NULL)")
    }

    private fun createLinks(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE record_wallet_links(record_uid TEXT PRIMARY KEY NOT NULL,
            eligibility TEXT NOT NULL CHECK(eligibility IN ('ACTIVE','LEGACY_EXCLUDED','RESTORE_EXCLUDED','IMPORT_EXCLUDED')),
            account_uid TEXT,selection_source TEXT,manual_locked INTEGER NOT NULL DEFAULT 0,
            payment_evidence TEXT,evidence_fingerprint TEXT,review_code TEXT,revision INTEGER NOT NULL DEFAULT 0,account_reference TEXT)""")
        db.execSQL("CREATE INDEX wallet_links_account ON record_wallet_links(account_uid)")
    }
    private fun createEffectsAndImports(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE record_wallet_effects(record_uid TEXT NOT NULL,account_uid TEXT NOT NULL,
            applied_cents INTEGER NOT NULL CHECK(applied_cents<>0),PRIMARY KEY(record_uid,account_uid))""")
        db.execSQL("CREATE INDEX wallet_effects_account ON record_wallet_effects(account_uid)")
        TransferAccounting.create(db)
        db.execSQL("""CREATE TABLE import_batches(batch_uid TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,file_sha256 TEXT NOT NULL,
            options_digest TEXT NOT NULL,submission_token TEXT NOT NULL UNIQUE,result_json TEXT NOT NULL,created_at INTEGER NOT NULL)""")
        db.execSQL("CREATE TABLE import_batch_records(batch_uid TEXT NOT NULL,record_uid TEXT NOT NULL,PRIMARY KEY(batch_uid,record_uid))")
        db.execSQL("CREATE INDEX import_records_record ON import_batch_records(record_uid)")
        db.execSQL("""CREATE TABLE wallet_import_aliases(source_uid TEXT PRIMARY KEY NOT NULL,local_account_uid TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX wallet_aliases_account ON wallet_import_aliases(local_account_uid)")
        ImportAuditStore.create(db)
    }
    /** SQLiteOpenHelper wraps the whole schema upgrade in its own transaction. No balance is recomputed. */
    fun migrateV10(db: SQLiteDatabase) {
        val invalid=db.rawQuery("""SELECT 1 FROM record_wallet_links l LEFT JOIN records r ON r.uid=l.record_uid
            LEFT JOIN accounts a ON a.uid=l.applied_account_uid LEFT JOIN accounts selected ON selected.uid=l.account_uid
            WHERE r.uid IS NULL OR (l.account_uid IS NOT NULL AND (selected.uid IS NULL OR selected.type<>'ASSET'))
            OR (l.applied_account_uid IS NOT NULL AND (a.uid IS NULL OR a.type<>'ASSET'))
            OR (l.eligibility='ACTIVE' AND r.state='POSTED' AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)
                AND (l.account_uid IS NULL OR r.amount IS NULL OR r.amount<0 OR r.amount>${Money.MAX_CENTS} OR r.direction NOT IN ('EXPENSE','INCOME') OR r.direction IS NULL))
            OR l.applied_cents<>CASE WHEN l.eligibility='ACTIVE' AND r.state='POSTED'
                AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)
                THEN CASE r.direction WHEN 'INCOME' THEN r.amount WHEN 'EXPENSE' THEN -r.amount ELSE 0 END ELSE 0 END
            OR (l.applied_cents<>0 AND (l.applied_account_uid IS NULL OR l.account_uid<>l.applied_account_uid)) LIMIT 1""",null).use { it.moveToFirst() }
        check(!invalid) { "Invalid pre-v10 wallet financial state" }
        db.execSQL("ALTER TABLE record_wallet_links RENAME TO wallet_links_v9")
        db.execSQL("DROP INDEX wallet_links_account")
        createLinks(db);createEffectsAndImports(db)
        db.execSQL("""INSERT INTO record_wallet_links(record_uid,eligibility,account_uid,selection_source,manual_locked,payment_evidence,
            evidence_fingerprint,review_code,revision) SELECT record_uid,eligibility,account_uid,selection_source,manual_locked,
            payment_evidence,evidence_fingerprint,review_code,revision FROM wallet_links_v9""")
        db.execSQL("""INSERT INTO record_wallet_effects(record_uid,account_uid,applied_cents)
            SELECT record_uid,applied_account_uid,applied_cents FROM wallet_links_v9 WHERE applied_cents<>0""")
        db.execSQL("DROP TABLE wallet_links_v9")
    }
    fun readLink(c: Cursor): RecordWalletLink {
        fun text(key:String):String?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it)) null else c.getString(it) }
        fun number(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
        return RecordWalletLink(requireNotNull(text("record_uid")), WalletEligibility.valueOf(requireNotNull(text("eligibility"))),
            text("account_uid"),text("selection_source"),number("manual_locked")==1L,text("payment_evidence"),text("evidence_fingerprint"),
            reviewCode=text("review_code"),revision=number("revision"),accountReference=text("account_reference"))
    }
    fun effects(db: SQLiteDatabase, uid: String): Map<String,Long> = db.query("record_wallet_effects",null,
        "record_uid=?",arrayOf(uid),null,null,"account_uid").use { c->buildMap {
        while(c.moveToNext())put(c.getString(c.getColumnIndexOrThrow("account_uid")),c.getLong(c.getColumnIndexOrThrow("applied_cents")))
    } }
    fun saveEffects(db: SQLiteDatabase, uid: String, effects: Map<String,Long>) {
        db.delete("record_wallet_effects","record_uid=?",arrayOf(uid))
        effects.filterValues { it!=0L }.forEach { (account,amount)->db.insertOrThrow("record_wallet_effects",null,ContentValues().apply {
            put("record_uid",uid);put("account_uid",account);put("applied_cents",amount)
        }) }
    }
    fun project(link: RecordWalletLink, effects: Map<String,Long>): RecordWalletLink = link.copy(
        appliedAccountUid=effects.keys.singleOrNull() ?: link.accountUid,
        appliedCents=link.accountUid?.let { effects[it] } ?: 0L)
    fun link(db:SQLiteDatabase, uid:String):RecordWalletLink?=db.query("record_wallet_links",null,"record_uid=?",arrayOf(uid),null,null,null)
        .use { if(it.moveToFirst())project(readLink(it),effects(db,uid)) else null }
    fun saveLink(db:SQLiteDatabase,link:RecordWalletLink) {
        check(db.insertWithOnConflict("record_wallet_links",null,ContentValues().apply {
            put("record_uid",link.recordUid);put("eligibility",link.eligibility.name);put("account_uid",link.accountUid)
            put("selection_source",link.selectionSource);put("manual_locked",if(link.manualLocked)1 else 0)
            put("payment_evidence",link.paymentEvidence);put("evidence_fingerprint",link.evidenceFingerprint)
            put("review_code",link.reviewCode);put("revision",link.revision);put("account_reference",link.accountReference)
        },SQLiteDatabase.CONFLICT_REPLACE)!=-1L) { "钱包关联写入失败" }
    }
    fun settings(db:SQLiteDatabase):WalletSettings {
        val (uid,revision)=db.rawQuery("SELECT default_account_uid,revision FROM wallet_settings WHERE singleton=1",null)
            .use { check(it.moveToFirst());(if(it.isNull(0))null else it.getString(0)) to it.getLong(1) }
        val bindings=db.query("app_wallet_bindings",null,null,null,null,null,"package_name").use { c->buildMap {
            while(c.moveToNext())put(c.getString(c.getColumnIndexOrThrow("package_name")),c.getString(c.getColumnIndexOrThrow("account_uid")))
        } }
        return WalletSettings(uid,bindings,revision)
    }
    fun submission(db:SQLiteDatabase, token:String?,kind:String):String? {
        if(token==null)return null
        require(token.isNotBlank() && token.length<=160) { "提交标识无效" }
        return db.query("wallet_submissions",arrayOf("kind","result_uid"),"token=?",arrayOf(token),null,null,null).use {
            if(!it.moveToFirst())null else {
                require(it.getString(0)==kind) { "提交标识已用于其他操作" };it.getString(1)
            }
        }
    }
    fun remember(db:SQLiteDatabase,token:String?,kind:String,resultUid:String) {
        if(token==null)return
        db.insertOrThrow("wallet_submissions",null,ContentValues().apply {
            put("token",token);put("kind",kind);put("result_uid",resultUid);put("created_at",System.currentTimeMillis())
        })
    }
    fun validateChanges(db: SQLiteDatabase, deltas: Map<String,Long>) {
        deltas.filterValues { it!=0L }.forEach { (uid,delta)->
            val (before,type)=db.rawQuery("SELECT amount,type FROM accounts WHERE uid=?",arrayOf(uid)).use {
                if(!it.moveToFirst())throw WalletException("WALLET_MISSING","关联钱包已不存在")
                it.getLong(0) to it.getString(1)
            }
            val after=try { Math.addExact(before,delta) } catch(_:ArithmeticException) {
                throw WalletException("BALANCE_LIMIT","钱包余额超出允许范围")
            }
            if(after !in (if(type=="ASSET")-Money.MAX_CENTS else 0L)..Money.MAX_CENTS)
                throw WalletException("BALANCE_LIMIT","钱包余额超出允许范围")
        }
    }
    fun moveAll(db: SQLiteDatabase, deltas: Map<String,Long>, recordUid: String?, kind: String,
        operationUid: String, summary: String="") {
        validateChanges(db,deltas)
        deltas.toSortedMap().forEach { (uid,delta)->move(db,uid,recordUid,delta,kind,operationUid,summary) }
    }
    fun move(db:SQLiteDatabase,accountUid:String,recordUid:String?,delta:Long,kind:String,operationUid:String,summary:String="") {
        if(delta==0L)return
        val (before,type)=db.rawQuery("SELECT amount,type FROM accounts WHERE uid=?",arrayOf(accountUid)).use {
            if(!it.moveToFirst())throw WalletException("WALLET_MISSING","关联钱包已不存在")
            it.getLong(0) to it.getString(1)
        }
        val after=try { Math.addExact(before,delta) } catch(_:ArithmeticException) { throw WalletException("BALANCE_LIMIT","钱包余额超出允许范围") }
        if(after !in (if(type=="ASSET")-Money.MAX_CENTS else 0L)..Money.MAX_CENTS)
            throw WalletException("BALANCE_LIMIT","钱包余额超出允许范围")
        check(db.update("accounts",ContentValues().apply { put("amount",after) },"uid=?",arrayOf(accountUid))==1)
        db.execSQL("UPDATE accounts SET revision=revision+1 WHERE uid=?",arrayOf(accountUid))
        db.insertOrThrow("wallet_movements",null,ContentValues().apply {
            put("movement_uid",UUID.randomUUID().toString());put("operation_uid",operationUid);put("account_uid",accountUid)
            put("record_uid",recordUid);put("kind",kind);put("delta_cents",delta);put("balance_before",before)
            put("balance_after",after);put("created_at",System.currentTimeMillis());put("summary",summary.take(80))
        })
    }
    fun readMovement(c:Cursor):WalletMovement {
        fun text(key:String)=c.getString(c.getColumnIndexOrThrow(key))
        fun number(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
        return WalletMovement(text("movement_uid"),text("operation_uid"),text("account_uid"),text("record_uid"),text("kind"),
            number("delta_cents"),number("balance_before"),number("balance_after"),number("created_at"),text("summary"))
    }
}
