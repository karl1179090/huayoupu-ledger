package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Imported audit has no submission token and cannot be resumed as a financial operation. */
data class ImportAudit(val batchUid:String,val kind:String,val fileSha256:String,val optionsDigest:String,
    val summary:String,val createdAt:Long,val recordUids:List<String> = emptyList())

internal object ImportAuditStore {
    fun create(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS import_audit_history(batch_uid TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,
            file_sha256 TEXT NOT NULL,options_digest TEXT NOT NULL,result_json TEXT NOT NULL,created_at INTEGER NOT NULL)""")
    }
    fun read(db:SQLiteDatabase):List<ImportAudit> {
        val associations=db.query("import_batch_records",null,null,null,null,null,"batch_uid,record_uid").use { c->
            buildMap<String,MutableList<String>> { while(c.moveToNext())getOrPut(c.getString(c.getColumnIndexOrThrow("batch_uid"))) { mutableListOf() }.add(c.getString(c.getColumnIndexOrThrow("record_uid"))) }
        }
        val audits=linkedMapOf<String,ImportAudit>()
        for(table in listOf("import_audit_history","import_batches"))db.query(table,null,null,null,null,null,"created_at,batch_uid").use { c->
            while(c.moveToNext()) {
                fun text(name:String)=c.getString(c.getColumnIndexOrThrow(name))
                val uid=text("batch_uid");val raw=text("result_json")
                val summary=if(raw.trimStart().startsWith("[")) { val uids=JSONArray(raw)
                    JSONObject().put("legacy",true).put("imported",uids.length()).put("recordUids",uids).put("batchUid",uid).toString()
                } else raw
                audits[uid]=ImportAudit(uid,text("kind"),text("file_sha256"),text("options_digest"),summary,c.getLong(c.getColumnIndexOrThrow("created_at")),associations[uid].orEmpty())
            }
        }
        return audits.values.toList()
    }
    fun restore(db:SQLiteDatabase,audits:List<ImportAudit>) {
        audits.forEach { audit->
            val present=db.rawQuery("SELECT 1 FROM import_batches WHERE batch_uid=? UNION ALL SELECT 1 FROM import_audit_history WHERE batch_uid=? LIMIT 1",arrayOf(audit.batchUid,audit.batchUid)).use { it.moveToFirst() }
            if(!present) {
                db.insertOrThrow("import_audit_history",null,ContentValues().apply {
                    put("batch_uid",audit.batchUid);put("kind",audit.kind);put("file_sha256",audit.fileSha256);put("options_digest",audit.optionsDigest)
                    put("result_json",audit.summary);put("created_at",audit.createdAt)
                })
                audit.recordUids.forEach { uid->db.insertOrThrow("import_batch_records",null,ContentValues().apply { put("batch_uid",audit.batchUid);put("record_uid",uid) }) }
            }
        }
    }
}
