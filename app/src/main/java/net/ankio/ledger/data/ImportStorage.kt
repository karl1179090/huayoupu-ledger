package net.ankio.ledger.data

import android.content.ContentValues
import net.ankio.ledger.core.Money
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A consistent read snapshot protects independent file exports from concurrent edits. */
fun LedgerStore.exportFileSnapshot():LedgerCsvExport.Snapshot=synchronized(this) {
    val db=readableDatabase;db.beginTransactionNonExclusive()
    try { val records=all();val result=LedgerCsvExport.Snapshot(records,accounts(),records.count { it.state==net.ankio.ledger.core.RecordState.REVIEW })
        db.setTransactionSuccessful();result
    } finally { db.endTransaction() }
}

/** Closed-link plans deliberately exclude balances/revisions changed only by unrelated calibrations. */
fun LedgerStore.importFactsToken(includeBalances:Boolean):String=synchronized(this) {
    val db=readableDatabase
    val facts=buildString {
        db.rawQuery("SELECT r.uid,r.transaction_identities,d.expires_at FROM records r LEFT JOIN record_deletions d ON d.record_id=r.id ORDER BY r.uid",null).use { c->
            while(c.moveToNext()) { append(c.getString(0));append(':');append(c.getString(1));append(':');append(if(c.isNull(2))"" else c.getLong(2));append(';') }
        }
        accounts().sortedBy { it.uid }.forEach { a->
            // Length prefixes prevent names containing separators from creating ambiguous digests.
            listOf(a.uid,a.name,a.description,a.type.name,a.kind,a.bankCode.orEmpty(),a.cardLast4.orEmpty(),a.archived.toString()).forEach { append(it.length);append(':');append(it) }
            if(includeBalances) { append(':');append(a.amountCents);append(':');append(a.revision) }
            append(';')
        }
        walletImportAliases().toSortedMap().forEach { (source,target)->append(source);append('=');append(target);append(';') }
        if(includeBalances) {
            val settings=walletSettings();append(settings.defaultAccountUid)
            settings.appBindings.toSortedMap().forEach { (name,uid)->append(name.length);append(':');append(name);append('=');append(uid);append(';') }
        }
    }
    PortableCsv.digest(facts.toByteArray())
}

private fun LedgerStore.batch(token:String,kind:String):Pair<String,String>?=readableDatabase.query("import_batches",arrayOf("batch_uid","kind","result_json"),
    "submission_token=?",arrayOf(token),null,null,null).use { c->if(!c.moveToFirst())null else {
    require(c.getString(1)==kind) { "提交标识已用于其他导入" };c.getString(0) to c.getString(2)
} }

fun LedgerStore.commitBillImport(plan:ImportPlan,linkWalletBalances:Boolean,cancellationCheck:()->Unit={}):LedgerCsvImport.Result=synchronized(this) {
    require(plan.kind=="BILLS")
    val db=writableDatabase;db.beginTransaction()
    try {
        batch(plan.submissionToken,"BILLS")?.let { (uid,json)->
            val result=if(json.trimStart().startsWith("{"))LedgerCsvImport.Result.decode(json) else {
                val a=JSONArray(json);LedgerCsvImport.Result(a.length(),0,0,recordUids=(0 until a.length()).map(a::getString),batchUid=uid)
            }
            db.setTransactionSuccessful();return@synchronized result
        }
        if(plan.records.isEmpty()) {
            db.setTransactionSuccessful()
            return@synchronized LedgerCsvImport.Result(0,plan.duplicates,plan.errors.size,0,0)
        }
        if(plan.factsDigest!=importFactsToken(linkWalletBalances))throw WalletException("IMPORT_PREVIEW_STALE","账本或钱包已变化，请重新预览")
        val saved=importTransactions(plan.records,linkWalletBalances,submissionToken=plan.submissionToken,fileSha256=plan.fileSha256,optionsDigest=plan.optionsDigest,cancellationCheck=cancellationCheck)
        if(saved.size!=plan.records.size)throw WalletException("IMPORT_PREVIEW_STALE","重复状态已变化，请重新预览")
        val batchUid=requireNotNull(batch(plan.submissionToken,"BILLS")).first
        val result=LedgerCsvImport.Result(saved.size,plan.duplicates,plan.errors.size,saved.count { it.state==net.ankio.ledger.core.RecordState.POSTED },
            saved.count { it.state==net.ankio.ledger.core.RecordState.REVIEW },saved.map { it.uid },batchUid)
        db.update("import_batches",ContentValues().apply { put("result_json",result.json()) },"submission_token=?",arrayOf(plan.submissionToken))
        cancellationCheck()
        db.setTransactionSuccessful();result
    } finally { db.endTransaction() }
}

fun LedgerStore.commitWalletImport(preview:WalletCsvImport.Preview,cancellationCheck:()->Unit={}):WalletCsvImport.Result=synchronized(this) {
    val plan=preview.plan;require(plan.kind=="WALLETS" && preview.unresolved==0)
    val db=writableDatabase;db.beginTransaction()
    try {
        batch(plan.submissionToken,"WALLETS")?.let { (_,json)->val result=WalletCsvImport.Result.decode(json);db.setTransactionSuccessful();return@synchronized result }
        if(preview.entries.isEmpty()) {
            db.setTransactionSuccessful()
            return@synchronized WalletCsvImport.Result(0,0,0,plan.duplicates,plan.errors.size,emptyList(),"")
        }
        if(plan.factsDigest!=importFactsToken(true))throw WalletException("IMPORT_PREVIEW_STALE","钱包已变化，请重新预览")
        val batchUid=UUID.randomUUID().toString();val before=accounts().associateBy { it.uid }
        val distinct=preview.entries.distinctBy { it.localAccountUid }
        WalletAccounting.validateChanges(db,distinct.associate { e->e.localAccountUid to
            (if(e.action==WalletCsvImport.Action.NEW)0L else plan.impacts.getValue(e.localAccountUid)) }.filterKeys { it in before })
        distinct.forEach { e->
            cancellationCheck()
            val local=before[e.localAccountUid]
            val target=if(local==null) {
                require(e.action==WalletCsvImport.Action.NEW)
                saveAccount(e.source.copy(id=0,uid=e.localAccountUid,amountCents=0,revision=0))
            } else {
                require(e.action==WalletCsvImport.Action.MERGE && local.type==e.source.type) { "账户类型冲突" }
                if(e.updateMetadata)saveAccount(e.source.copy(id=local.id,uid=local.uid,amountCents=local.amountCents,
                    archived=local.archived || e.source.archived,revision=local.revision)) else local
            }
            if(local==null || e.overwriteBalance) {
                val delta=Math.subtractExact(e.source.amountCents,target.amountCents)
                WalletAccounting.move(db,target.uid,null,delta,"WALLET_IMPORT_SNAPSHOT",batchUid,"导入钱包余额快照")
            }
        }
        preview.entries.forEach { e->cancellationCheck();if(e.source.uid!=e.localAccountUid)saveWalletImportAlias(e.source.uid,e.localAccountUid,e.replaceAlias) }
        val imported=distinct.count { it.action==WalletCsvImport.Action.NEW }
        val updated=distinct.count { it.action==WalletCsvImport.Action.MERGE && (it.updateMetadata || it.overwriteBalance) }
        val result=WalletCsvImport.Result(imported,updated,distinct.size-imported-updated,plan.duplicates,plan.errors.size,distinct.map { it.localAccountUid },batchUid)
        db.insertOrThrow("import_batches",null,ContentValues().apply {
            put("batch_uid",batchUid);put("kind","WALLETS");put("file_sha256",plan.fileSha256);put("options_digest",plan.optionsDigest)
            put("submission_token",plan.submissionToken);put("result_json",result.json());put("created_at",System.currentTimeMillis())
        })
        cancellationCheck()
        db.setTransactionSuccessful();result
    } finally { db.endTransaction() }
}

/** A restart can show the original committed summary without replaying a wallet snapshot. */
fun LedgerStore.importReceipt(token:String):String?=synchronized(this) {
    readableDatabase.query("import_batches",arrayOf("result_json"),"submission_token=?",arrayOf(token),null,null,null).use { c->
        if(c.moveToFirst())c.getString(0) else null
    }
}

internal fun <T> LedgerStore.importReadSnapshot(read:()->T):T=synchronized(this) {
    val db=readableDatabase;db.beginTransactionNonExclusive()
    try { val result=read();db.setTransactionSuccessful();result } finally { db.endTransaction() }
}
