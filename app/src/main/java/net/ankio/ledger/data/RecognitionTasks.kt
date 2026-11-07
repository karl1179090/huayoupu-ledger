package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.ankio.ledger.core.Source
import net.ankio.ledger.capture.ShakeAuthorization
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Recognition attempts are durable local data, independently of a valid financial transaction. */
class RecognitionTasks(context:Context,private val name:String="recognition.db",private val screenshots:ScreenshotStore=ScreenshotStore.get(context)):SQLiteOpenHelper(context,name,null,5) {
    private fun imageOwner(id:Long)="task:$name:$id"
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        if(db.isReadOnly)return
        db.execSQL("CREATE INDEX IF NOT EXISTS tasks_created ON tasks(created_at DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tasks_status ON tasks(status,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tasks_record ON tasks(record_id,id DESC)")
        var migratedBytes=0L
        db.rawQuery("SELECT id,length(image),created_at,status FROM tasks WHERE image IS NOT NULL",null).use { c ->
            while(c.moveToNext()) {
                val image=LegacyScreenshots.read(db,"tasks","id",c.getLong(0),c.getInt(1))
                screenshots.put(imageOwner(c.getLong(0)),image,c.getLong(2),c.getString(3))
                db.execSQL("UPDATE tasks SET image=NULL WHERE id=?",arrayOf(c.getLong(0)))
                migratedBytes+=image.size
            }
        }
        LegacyScreenshots.compact(db,migratedBytes)
    }
    private val app=context.applicationContext as? net.ankio.ledger.LedgerApp
    override fun onCreate(db:SQLiteDatabase) { db.execSQL("""CREATE TABLE tasks(
        id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, origin TEXT NOT NULL,
        created_at INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL,
        record_id INTEGER, image BLOB, diagnostic TEXT NOT NULL DEFAULT '{}', shake_authorization TEXT,operation_uid TEXT NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX tasks_operation_uid ON tasks(operation_uid)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion<2)db.execSQL("ALTER TABLE tasks ADD COLUMN diagnostic TEXT NOT NULL DEFAULT '{}'")
        if(oldVersion<4)db.execSQL("ALTER TABLE tasks ADD COLUMN shake_authorization TEXT")
        if(oldVersion<5) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN operation_uid TEXT")
            db.query("tasks",arrayOf("id"),null,null,null,null,null).use { cursor->while(cursor.moveToNext()) {
                db.update("tasks",ContentValues().apply { put("operation_uid",UUID.randomUUID().toString()) },"id=?",arrayOf(cursor.getLong(0).toString()))
            } }
            db.execSQL("CREATE UNIQUE INDEX tasks_operation_uid ON tasks(operation_uid)")
        }
    }
    /** Bounded technical outcomes only; never store credentials, response bodies or bill text here. */
    @Synchronized fun diagnose(id:Long,key:String,value:String) {
        require(key in setOf("window","window_details","stage","icon","icon_details","trigger","timing","decision","capture_context","payment_identity","engine"))
        val json=readableDatabase.rawQuery("SELECT diagnostic FROM tasks WHERE id=?",arrayOf(id.toString())).use {
            if(!it.moveToFirst())return
            JSONObject(it.getString(0))
        }
        json.put(key,value.take(500))
        if(key=="stage")app?.executionLog?.event("model","recognition",value.take(80),JSONObject().put("taskId",id))
        writableDatabase.update("tasks",ContentValues().apply { put("diagnostic",json.toString()) },"id=?",arrayOf(id.toString()))
    }
    private var firstSessionTaskId:Long?=null
    @Synchronized fun create(source:Source,origin:String,authorization:ShakeAuthorization?=null):Long {
        require(authorization==null || source==Source.OCR && origin==authorization.origin)
        return writableDatabase.insertOrThrow("tasks",null,ContentValues().apply {
        put("source",source.name);put("origin",origin);put("created_at",System.currentTimeMillis())
        put("status","CAPTURING");put("message","正在获取账单截图")
        put("operation_uid",UUID.randomUUID().toString())
        if(authorization!=null)put("shake_authorization",authorization.encode())
    }).also { if(firstSessionTaskId==null)firstSessionTaskId=it }
    }
    /** Stable across helper/process recreation, never derived from an OCR payload or reused row ID. */
    fun operationKey(id:Long):String?=readableDatabase.rawQuery("SELECT operation_uid FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || it.isNull(0))null else "recognition:${it.getString(0)}"
    }
    /** A context with the wrong source or package is as untrusted as a missing legacy context. */
    fun shakeAuthorization(id:Long):ShakeAuthorization?=readableDatabase.rawQuery("SELECT source,origin,shake_authorization FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || it.getString(0)!=Source.OCR.name)return@use null
        ShakeAuthorization.decode(if(it.isNull(2))null else it.getString(2))?.takeIf { authorization -> it.getString(1)==authorization.origin }
    }
    @Synchronized fun update(id:Long,status:String,message:String,recordId:Long?=null):Boolean {
        require(status in STATUSES)
        val updated=writableDatabase.update("tasks",ContentValues().apply {
            put("status",status);put("message",message.take(500));if(recordId!=null) put("record_id",recordId)
        },"id=?"+(if(status in setOf("FAILED","CANCELLED")) " AND status IN ('CAPTURING','QUEUED','RECOGNIZING')" else ""),arrayOf(id.toString()))==1
        if(updated) {
            screenshots.state(imageOwner(id),status)
            if(status !in setOf("CAPTURING","QUEUED","RECOGNIZING"))screenshots.cleanup()
        }
        if(updated)app?.executionLog?.event("model","task",status,JSONObject().put("taskId",id).put("recordId",recordId))
        return updated
    }
    @Synchronized fun prepared(id:Long,image:ByteArray) { storeImage(id,image,"RECOGNIZING","正在识别记账中") }
    /** Save first, then expose a resumable queue entry. */
    @Synchronized fun queued(id:Long,image:ByteArray):Boolean = storeImage(id,image,"QUEUED","已获取截图，等待识别")
    private fun storeImage(id:Long,image:ByteArray,status:String,message:String):Boolean {
        require(image.isNotEmpty()&&image.size<=5*1024*1024)
        val createdAt=readableDatabase.rawQuery("SELECT created_at FROM tasks WHERE id=? AND status IN ('CAPTURING','QUEUED','RECOGNIZING')",arrayOf(id.toString())).use {
            if(it.moveToFirst())it.getLong(0) else return false
        }
        screenshots.put(imageOwner(id),image,createdAt,status)
        return writableDatabase.update("tasks",ContentValues().apply {
            putNull("image");put("status",status);put("message",message)
        },"id=? AND status IN ('CAPTURING','QUEUED','RECOGNIZING')",arrayOf(id.toString()))==1
    }
    fun pendingQueued():List<Pair<Long,String>> = readableDatabase.rawQuery("SELECT id,origin FROM tasks WHERE status='QUEUED' ORDER BY id",null).use { c ->
        buildList { while(c.moveToNext())add(c.getLong(0) to c.getString(1)) }
    }
    fun pendingShake(packageName:String?=null):List<Long> = readableDatabase.rawQuery(
        "SELECT id FROM tasks WHERE status IN ('CAPTURING','QUEUED','RECOGNIZING') AND "+(if(packageName==null)"origin LIKE 'shake:%'" else "origin=?"),
        packageName?.let { arrayOf("shake:$it") }).use { c -> buildList { while(c.moveToNext())add(c.getLong(0)) } }
    fun pendingShortcut(packageName:String?=null):List<Long> = readableDatabase.rawQuery(
        "SELECT id FROM tasks WHERE status IN ('CAPTURING','QUEUED','RECOGNIZING') AND "+(if(packageName==null)"origin LIKE 'shortcut:%'" else "origin=?"),
        packageName?.let { arrayOf("shortcut:$it") }).use { c -> buildList { while(c.moveToNext())add(c.getLong(0)) } }
    fun exists(id:Long):Boolean=readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE id=?",arrayOf(id.toString())).use { it.moveToFirst() }
    fun automatic(id:Long):Boolean=readableDatabase.rawQuery("SELECT origin FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        it.moveToFirst() && it.getString(0).startsWith("payment_auto:")
    }
    /** Generated by the native page gate, never accepted from the model or web UI. */
    fun paymentIdentity(id:Long):String?=readableDatabase.rawQuery("SELECT origin,diagnostic FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || !it.getString(0).startsWith("payment_auto:"))return@use null
        JSONObject(it.getString(1)).optString("payment_identity").takeIf { key ->
            Regex("payment_page:[0-9a-f-]{1,64}:[0-9a-f]{64}").matches(key)
        }
    }
    /** Upgrade bridge for old tasks without a native identity. Same image alone is
     * insufficient: require the same application AND native window within this boot. */
    fun legacyFrameRecords(id:Long):List<Long> {
        val current=readableDatabase.rawQuery("SELECT origin,diagnostic FROM tasks WHERE id=?",arrayOf(id.toString())).use {
            if(!it.moveToFirst())return emptyList()
            it.getString(0) to JSONObject(it.getString(1)).optString("window")
        }
        if(!current.first.startsWith("payment_auto:") || !Regex("[A-Za-z0-9_.]+; id=[0-9]+; automatic=true").matches(current.second))return emptyList()
        val hash=screenshots.fingerprint(imageOwner(id))?:return emptyList()
        val bootStart=System.currentTimeMillis()-android.os.SystemClock.elapsedRealtime()
        return readableDatabase.rawQuery("SELECT id,record_id,diagnostic FROM tasks WHERE id<? AND origin=? AND created_at>=? AND status IN ('SUCCEEDED','REVIEW') AND record_id IS NOT NULL ORDER BY id DESC LIMIT 128",
            arrayOf(id.toString(),current.first,bootStart.toString())).use { c ->
            buildList { while(c.moveToNext()) {
                val detail=JSONObject(c.getString(2))
                if(!detail.has("payment_identity") && detail.optString("window")==current.second && screenshots.fingerprint(imageOwner(c.getLong(0)))==hash)add(c.getLong(1))
            } }
        }
    }
    /** Native frame gate only; never accepted from web/model fields. Survives a queued restart. */
    fun livePaymentAt(id:Long):Long?=readableDatabase.rawQuery("SELECT origin,created_at,diagnostic FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || !it.getString(0).startsWith("payment_auto:") ||
            net.ankio.ledger.core.PaymentApps.entries.none { entry -> entry.packageName==it.getString(0).substringAfter(':') })return@use null
        val context=JSONObject(it.getString(2)).optString("capture_context")
        if(!context.startsWith("payment_success_result:"))return@use null
        context.substringAfter(':').toLongOrNull()?.takeIf { at -> at in it.getLong(1)..(it.getLong(1)+15_000) }
    }
    fun message(id:Long):String?=readableDatabase.rawQuery("SELECT message FROM tasks WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst())it.getString(0) else null }
    fun recordForPaymentIdentity(identity:String):Long?=readableDatabase.rawQuery("SELECT record_id,diagnostic FROM tasks WHERE record_id IS NOT NULL ORDER BY id DESC",null).use { c ->
        while(c.moveToNext())if(JSONObject(c.getString(1)).optString("payment_identity")==identity)return@use c.getLong(0)
        null
    }
    fun status(id:Long):String?=readableDatabase.rawQuery("SELECT status FROM tasks WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst())it.getString(0) else null }
    fun recordId(id:Long):Long?=readableDatabase.rawQuery("SELECT record_id FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(it.moveToFirst()&&!it.isNull(0))it.getLong(0) else null
    }
    fun metadataForRecords(ids:Set<Long>):JSONArray {
        if(ids.isEmpty())return JSONArray()
        require(ids.all { it>0 })
        return readableDatabase.rawQuery("SELECT record_id,origin,diagnostic,MAX(id) FROM tasks WHERE record_id IN (${ids.joinToString(",")}) GROUP BY record_id",null).use { c ->
            JSONArray().apply { while(c.moveToNext())put(JSONObject().put("recordId",c.getLong(0)).put("origin",c.getString(1)).put("diagnostic",JSONObject(c.getString(2)))) }
        }
    }
    @Synchronized fun reassignRecord(fromId:Long,toId:Long,message:String="已更新原账单退款") {
        require(fromId>0 && toId>0)
        writableDatabase.update("tasks",ContentValues().apply {
            put("record_id",toId);put("status","SUCCEEDED");put("message",message)
        },"record_id=?",arrayOf(fromId.toString()))
    }
    @Synchronized fun skipRefundReceipt(recordId:Long,message:String=net.ankio.ledger.core.StandaloneRefundReceipt.MESSAGE) {
        writableDatabase.update("tasks",ContentValues().apply {
            putNull("record_id");put("status","SKIPPED");put("message",message)
        },"record_id=?",arrayOf(recordId.toString()))
    }
    fun snapshot(limit:Int=200,start:Long?=null,end:Long?=null):JSONArray=readableDatabase.rawQuery("SELECT id,source,origin,created_at,status,message,record_id,image IS NOT NULL,diagnostic FROM tasks"+
        (if(start!=null&&end!=null)" WHERE (created_at>=? AND created_at<?) OR status IN ('CAPTURING','QUEUED','RECOGNIZING')" else "")+
        " ORDER BY id DESC LIMIT ${limit.coerceIn(1,500)}",if(start!=null&&end!=null)arrayOf(start.toString(),end.toString()) else null).use { c ->
        JSONArray().apply { while(c.moveToNext()) put(JSONObject().put("id",c.getLong(0)).put("source",c.getString(1))
            .put("origin",c.getString(2)).put("createdAt",c.getLong(3)).put("status",c.getString(4)).put("message",c.getString(5))
            .put("recordId",if(c.isNull(6))JSONObject.NULL else c.getLong(6)).put("hasScreenshot",screenshots.contains(imageOwner(c.getLong(0))))
            .put("diagnostic",JSONObject(c.getString(8)))) }
    }
    fun diagnosticExport():JSONArray=readableDatabase.rawQuery("SELECT id,source,created_at,status,diagnostic FROM tasks ORDER BY id DESC LIMIT 100",null).use { c ->
        JSONArray().apply { while(c.moveToNext())put(JSONObject().put("id",c.getLong(0)).put("source",c.getString(1))
            .put("createdAt",c.getLong(2)).put("status",c.getString(3)).put("diagnostic",JSONObject(c.getString(4)))) }
    }
    fun image(id:Long):ByteArray? { readableDatabase;return screenshots.image(imageOwner(id)) }
    @Synchronized fun delete(id:Long) {
        writableDatabase.delete("tasks","id=?",arrayOf(id.toString()));screenshots.release(imageOwner(id))
    }
    @Synchronized fun deleteForRecord(id:Long) {
        val ids=readableDatabase.rawQuery("SELECT id FROM tasks WHERE record_id=?",arrayOf(id.toString())).use { c -> buildList { while(c.moveToNext())add(c.getLong(0)) } }
        writableDatabase.delete("tasks","record_id=?",arrayOf(id.toString()));ids.forEach { screenshots.release(imageOwner(it)) }
    }
    @Synchronized fun recoverInterrupted(preserveCurrentSession:Boolean=false) {
        // Old queue entries never had app-specific consent. Reject them even while
        // accessibility is disconnected, before resume's permission gate can return.
        (pendingShake()+pendingShortcut()).forEach { id ->
            val authorization=shakeAuthorization(id)
            if(authorization==null || app?.channels?.shakeAuthorized(authorization)==false)
                update(id,"CANCELLED","请重新选择应用后摇一摇，未新增账单")
        }
        // Startup recovery may run after the first new screenshot request. Never
        // classify work created by this helper instance as a previous-process failure.
        val boundary=firstSessionTaskId.takeIf { preserveCurrentSession }
        val entries=readableDatabase.rawQuery("SELECT id,status,origin FROM tasks WHERE status IN ('CAPTURING','RECOGNIZING')"+
            (if(boundary!=null)" AND id<?" else ""),boundary?.let { arrayOf(it.toString()) }).use { c ->
            buildList { while(c.moveToNext())add(Triple(c.getLong(0),c.getString(1),c.getString(2))) }
        }
        for((id,status,origin) in entries) {
            if((origin.startsWith("shake:") || origin.startsWith("shortcut:")) && shakeAuthorization(id)==null) {
                update(id,"CANCELLED","请重新选择应用后摇一摇，未新增账单");continue
            }
            val resume=status=="RECOGNIZING" && (origin.startsWith("payment_auto:") || origin.startsWith("shake:") || origin.startsWith("shortcut:")) && screenshots.contains(imageOwner(id))
            update(id,if(resume)"QUEUED" else "FAILED",if(resume)"已恢复截图，等待识别" else "上次识别被系统中断，请重新摇动或导入截图")
        }
    }
    companion object { val STATUSES=setOf("CAPTURING","QUEUED","RECOGNIZING","SUCCEEDED","REVIEW","FAILED","NO_BILL","DUPLICATE","CANCELLED","SKIPPED") }
}
