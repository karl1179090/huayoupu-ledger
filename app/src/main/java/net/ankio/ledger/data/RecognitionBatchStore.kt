package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.*

/** ledger.db owns both immutable batch decisions and independently atomic child commits. */
internal object RecognitionBatchStore {
    fun create(db:SQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS recognition_batches(
        operation_key TEXT PRIMARY KEY NOT NULL,batch_json TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_batch_day_amount ON records(state,amount,accounting_date,occurred_at)")
    }
    private fun read(store:LedgerStore,key:String):FrozenRecognitionBatch?=store.readableDatabase.rawQuery(
        "SELECT batch_json FROM recognition_batches WHERE operation_key=?",arrayOf(key)).use {
        if(it.moveToFirst())RecognitionBatchCodec.decode(it.getString(0)) else null
    }
    private fun write(db:SQLiteDatabase,batch:FrozenRecognitionBatch) {
        db.insertWithOnConflict("recognition_batches",null,ContentValues().apply {
            put("operation_key",batch.operationKey);put("batch_json",RecognitionBatchCodec.encode(batch))
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }
    fun result(store:LedgerStore,key:String):RecognitionBatchResult?=read(store,key)?.let { batch ->
        RecognitionBatchResult(key.removePrefix("recognition:"),batch.items.map { item ->
            val record=item.recordUID?.let { uid -> store.readableDatabase.rawQuery("SELECT id FROM records WHERE uid=?",arrayOf(uid)).use {
                if(it.moveToFirst())store.get(it.getLong(0)) else null
            } }
            val bill=item.candidate.bill
            BatchItemResult(item.candidate.id,item.operationKey,item.status,item.reason,bill.amountCents,bill.direction,bill.merchant,
                DayAmountKey.from(bill,batch.zoneId)?.localDate,item.recordUID,record?.id)
        })
    }
    fun freeze(store:LedgerStore,capture:Capture,candidates:List<TransactionCandidate>,key:String,zoneId:String,
        snapshot:ClassificationSnapshot,policy:RecognitionPolicy):RecognitionBatchResult {
        result(store,key)?.let { return it }
        require(candidates.size in 1..32 && candidates.all { it.id!=CandidateId.SINGLE })
        require(candidates.map { it.id }.distinct().size==candidates.size)
        require(key.isNotBlank() && key.length<=160)
        val db=store.writableDatabase;db.beginTransaction()
        try {
            // Read the baseline once before writing any child; new siblings cannot enter it.
            val history=candidates.mapNotNull { DayAmountKey.from(it.bill,zoneId) }.distinct().mapNotNull { key ->
                val day=java.time.LocalDate.parse(key.localDate)
                val range=AccountingDates.calendar(day,day.plusDays(1),java.time.ZoneId.of(zoneId))
                db.rawQuery("SELECT r.uid FROM records r WHERE r.state='POSTED' AND r.amount=? AND (${range.sql}) AND "+
                    "NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id) LIMIT 1",
                    (listOf(key.amountCents.toString())+range.args).toTypedArray()).use { cursor ->
                    if(cursor.moveToFirst())key to cursor.getString(0) else null
                }
            }.toMap()
            val items=candidates.map { candidate ->
                val local=capture.copy(text=(candidate.rawText ?: capture.text).take(32768))
                val bill=store.classify(candidate.bill,snapshot,local)
                val historical=if(candidates.size>1 && bill.refund==null && bill.standaloneRefund==null && bill.direction!=Direction.TRANSFER && bill.transfer==null)
                    DayAmountKey.from(bill,zoneId)?.let(history::get) else null
                val rejection=TransactionRejection.reason(local.text)
                val denied= !policy.allows(bill,capture.origin)
                val reason=when { historical!=null->"已有同日同金额账单";rejection!=null->rejection;denied->"该交易需通过摇一摇或截图导入核对";else->"" }
                FrozenBatchItem(candidate.copy(bill=bill,rawText=local.text),candidateOperationKey(key,candidate.id),historical,
                    if(reason.isNotBlank())"SKIPPED" else "PENDING",reason,historical)
            }
            write(db,FrozenRecognitionBatch(key,capture.copy(text=capture.text.take(32768)),zoneId,items))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return requireNotNull(result(store,key))
    }
    fun submit(store:LedgerStore,key:String,id:CandidateId,screenshot:ByteArray?):RecognitionBatchResult {
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val batch=requireNotNull(read(store,key));val item=batch.items.single { it.candidate.id==id }
            if(item.status !in setOf("PENDING","FAILED")) { db.setTransactionSuccessful();return requireNotNull(result(store,key)) }
            val capture=batch.capture.copy(text=item.candidate.rawText ?: batch.capture.text)
            val previous=store.matchingIdentity(capture,item.candidate.bill)
            val outcome=store.ingestRecognition(capture,item.candidate.bill,item.operationKey,screenshot=screenshot,independentCandidate=batch.items.size>1)
            val record=when(outcome) {
                is IngestResult.Saved->outcome.record
                IngestResult.DuplicateSkipped->store.committedCapture(item.operationKey)?.record ?: store.matchingIdentity(capture,item.candidate.bill)
                else->null
            }
            val status=when {
                outcome is IngestResult.Saved && record?.state==RecordState.REVIEW->"REVIEW"
                outcome is IngestResult.Saved && previous?.state==RecordState.POSTED->"SKIPPED"
                outcome is IngestResult.Saved && outcome.updated->"ADDED"
                outcome is IngestResult.Saved && outcome.supplemented->"SKIPPED"
                outcome is IngestResult.Saved->"ADDED"
                outcome==IngestResult.Ignored->"FAILED"
                else->"SKIPPED"
            }
            val reason=when { status=="REVIEW"->record?.reason.orEmpty();outcome is IngestResult.Skipped->outcome.message
                status=="ADDED"->"已记账";status=="SKIPPED" && outcome is IngestResult.Saved && outcome.supplemented->"已补充已有账单"
                status=="SKIPPED"->"交易已处理";else->"未识别到账单" }
            write(db,batch.copy(items=batch.items.map { if(it.candidate.id==id)it.copy(status=status,reason=reason,recordUID=record?.uid) else it }))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return requireNotNull(result(store,key))
    }
    fun failed(store:LedgerStore,key:String,id:CandidateId) {
        val batch=requireNotNull(read(store,key));write(store.writableDatabase,batch.copy(items=batch.items.map {
            if(it.candidate.id==id && it.status in setOf("PENDING","FAILED"))it.copy(status="FAILED",reason="该项保存失败，请重试") else it
        }))
    }
    fun export(store:LedgerStore):org.json.JSONArray=store.readableDatabase.rawQuery("SELECT batch_json FROM recognition_batches ORDER BY operation_key",null).use { c ->
        org.json.JSONArray().apply { while(c.moveToNext())put(org.json.JSONObject(c.getString(0))) }
    }
    fun restore(store:LedgerStore,batches:org.json.JSONArray,records:Map<String,String?> = emptyMap(),categories:Map<String,LedgerCategory> = emptyMap()) {
        fun mapped(uid:String?)=uid?.let { records[it] ?: it }
        for(index in 0 until batches.length()) {
            val incoming=RecognitionBatchCodec.decode(batches.getJSONObject(index).toString())
            val batch=incoming.copy(items=incoming.items.map { item ->
                val bill=item.candidate.bill
                val category=bill.categoryDecision?.categoryId?.let(categories::get)
                    ?: categories.values.firstOrNull { it.direction==bill.direction && it.name==bill.category }
                item.copy(candidate=item.candidate.copy(bill=if(category==null)bill else bill.copy(category=category.name,
                    categoryDecision=bill.categoryDecision?.copy(categoryId=category.id))),
                    recordUID=mapped(item.recordUID),historyRecordUID=mapped(item.historyRecordUID))
            })
            val local=read(store,batch.operationKey)
            if(local==null)write(store.writableDatabase,batch)
            else {
                fun frozen(b:FrozenRecognitionBatch)=b.copy(items=b.items.map { item ->
                    val bill=item.candidate.bill
                    val category=bill.categoryDecision?.categoryId?.let { CategoryDirectory.find(store.readableDatabase,CategoryReplacementStorage.resolve(store.readableDatabase,it)) }
                    item.copy(status="PENDING",reason="",recordUID=null,candidate=item.candidate.copy(bill=if(category==null)bill else bill.copy(category=category.name,categoryDecision=bill.categoryDecision?.copy(categoryId=category.id))))
                })
                require(frozen(local)==frozen(batch)) { "识别批次标识与本地冻结证据冲突" }
                val combined=local.copy(items=local.items.zip(batch.items).map { (old,new) ->
                    if(old.status in setOf("PENDING","FAILED"))new
                    else { if(new.status !in setOf("PENDING","FAILED"))require(old.status==new.status && old.recordUID==new.recordUID) { "识别批次回执冲突" };old }
                })
                write(store.writableDatabase,combined)
            }
        }
    }
}
