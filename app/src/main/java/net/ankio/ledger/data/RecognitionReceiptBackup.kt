package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable completed identities are facts even when their bill has since been removed. */
internal object RecognitionReceiptBackup {
    fun export(db:SQLiteDatabase):JSONArray=db.rawQuery("SELECT token,kind,result_uid,created_at FROM wallet_submissions WHERE kind IN ('CAPTURE_RESULT','CAPTURE_EVENT_RESULT') ORDER BY token",null).use { c ->
        JSONArray().apply { while(c.moveToNext())put(JSONObject().put("token",c.getString(0)).put("kind",c.getString(1)).put("recordUid",c.getString(2)).put("createdAt",c.getLong(3))) }
    }
    fun events(db:SQLiteDatabase):JSONArray=db.rawQuery("SELECT event_key,received_at FROM capture_events ORDER BY event_key",null).use { c ->
        JSONArray().apply { while(c.moveToNext())put(JSONObject().put("key",c.getString(0)).put("at",c.getLong(1))) }
    }
    fun validate(rows:JSONArray,events:JSONArray) {
        require(rows.length()<=200_000 && events.length()<=200_000)
        val tokens=mutableSetOf<String>();val keys=mutableSetOf<String>()
        for(i in 0 until rows.length()) { val r=rows.getJSONObject(i);val token=r.getString("token")
            require(token.length in 1..160 && tokens.add(token))
            require(r.getString("kind") in setOf("CAPTURE_RESULT","CAPTURE_EVENT_RESULT"))
            if(r.getString("kind")=="CAPTURE_EVENT_RESULT")require(token.startsWith("capture-event:"))
            UUID.fromString(r.getString("recordUid"));require(r.getLong("createdAt")>0)
        }
        for(i in 0 until events.length()) { val e=events.getJSONObject(i);require(e.getString("key").length in 1..160 && keys.add(e.getString("key")) && e.getLong("at")>0) }
    }
    fun restore(db:SQLiteDatabase,rows:JSONArray,events:JSONArray,mapping:Map<String,String?>) {
        for(i in 0 until rows.length()) {
            val r=rows.getJSONObject(i);val original=r.getString("recordUid");val uid=mapping[original] ?: original
            val token=r.getString("token");val kind=r.getString("kind")
            val prior=WalletAccounting.submission(db,token,kind)
            require(prior==null || prior==uid) { "识别提交标识与本地回执冲突" }
            if(prior==null)db.insertOrThrow("wallet_submissions",null,ContentValues().apply {
                put("token",token);put("kind",kind);put("result_uid",uid);put("created_at",r.getLong("createdAt"))
            })
        }
        for(i in 0 until events.length()) { val e=events.getJSONObject(i)
            db.insertWithOnConflict("capture_events",null,ContentValues().apply { put("event_key",e.getString("key"));put("received_at",e.getLong("at")) },SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
}
