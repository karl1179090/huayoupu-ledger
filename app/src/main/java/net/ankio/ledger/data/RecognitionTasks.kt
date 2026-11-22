package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.Source
import net.ankio.ledger.capture.ShakeAuthorization
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Recognition attempts are durable local data, independently of a valid financial transaction. */
class RecognitionTasks(context:Context,private val name:String="recognition.db",private val screenshots:ScreenshotStore=ScreenshotStore.get(context),
    private val classificationStore:LedgerStore?=(context.applicationContext as? net.ankio.ledger.LedgerApp)?.store):SQLiteOpenHelper(context,name,null,9) {
    internal fun stageBackupImage(id:Long,directory:java.io.File)=screenshots.stageFile(imageOwner(id),directory)
    internal fun backupImageHash(id:Long)=screenshots.fingerprint(imageOwner(id))
    internal fun restoreImage(db:SQLiteDatabase,id:Long,image:ByteArray,at:Long,status:String) { screenshots.requireRestoreDatabase(db);screenshots.put(imageOwner(id),image,at,status) }
    internal fun fullBackup():JSONArray {
        val db = readableDatabase
        val columns = RecognitionTaskBackup.columns.filterNot { it == "classification_snapshot" }
        // A full-size frozen configuration and node evidence require separate CursorWindows.
        val rows = db.rawQuery("SELECT id,record_id,${columns.joinToString(",")} FROM tasks ORDER BY id", null).use { c ->
            db.rawQuery("SELECT id,classification_snapshot FROM tasks ORDER BY id", null).use { snapshots ->
                JSONArray().apply {
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        check(snapshots.moveToNext() && snapshots.getLong(0) == id)
                        val row = JSONObject().put("classification_snapshot", snapshots.getString(1))
                        columns.forEach { column ->
                            val index = c.getColumnIndexOrThrow(column)
                            row.put(column, if (c.isNull(index)) JSONObject.NULL else if (column in setOf("created_at", "recognition_at")) c.getLong(index) else c.getString(index))
                        }
                        row.put("localId", id).put("localRecordId", if (c.isNull(1)) JSONObject.NULL else c.getLong(1))
                        screenshots.referenceInfo(imageOwner(id))?.let { row.put("imageCreatedAt", it.first).put("imageState", it.second) }
                        row.put("imageHash", screenshots.fingerprint(imageOwner(id))?.takeIf { screenshots.contains(imageOwner(id)) } ?: JSONObject.NULL)
                        put(row)
                    }
                }
            }
        }
        for(i in 0 until rows.length()) { val row=rows.getJSONObject(i)
            val localId = row.optLong("localRecordId", 0L).takeIf { it > 0 }
            val receipt = classificationStore?.committedCapture("recognition:${row.getString("operation_uid")}")
            if (localId != null && receipt?.record != null && receipt.record.id != localId) {
                val projected = RecognitionCommitRecovery.project(receipt.effect, receipt.record)
                row.put("status", projected.status).put("message", projected.feedback.message)
            }
            val uid = receipt?.recordUid ?: localId?.let { classificationStore?.recordUidForBackup(it) }
            row.remove("localRecordId");row.put("recordUid",uid ?: JSONObject.NULL)
        };return rows
    }
    // Read the immutable ledger migration baseline before SQLiteOpenHelper acquires its own lock.
    private val migrationBaseline=classificationStore?.classificationMigrationBaseline() ?: net.ankio.ledger.core.ClassificationDefaults.migrationBaseline
    private var classificationsReconciled=false
    /** Keep the same store -> task helper -> media lock order as complete restore. */
    internal fun <T> withClassificationEvidence(action:()->T):T = synchronized(this) { synchronized(screenshots,action) }
    internal fun reconcileClassificationSnapshots(ledger:SQLiteDatabase,restored:Boolean=false,pauseCapture:Boolean=false) {
        val tasks=if(restored)ledger else readableDatabase
        val table=if(restored)"recognition.tasks" else "tasks"
        val states=taskStates(tasks,table,"status IN ('CAPTURING','QUEUED','RECOGNIZING','FAILED','CANCELLED')")
        val grants=if(pauseCapture)null else authorizationSettings.captureGrants()
        val active=states.filter { state ->
            if(state.status in ACTIVE_STATUSES) !pauseCapture || !state.nativeCapture else canRetryBatch(ledger,state,grants)
        }.associate { it.operationKey to it.id }
        ClassificationStorage.reconcile(ledger,name,active) { id ->
            tasks.rawQuery("SELECT classification_snapshot FROM $table WHERE id=?",arrayOf(id.toString())).use {
                check(it.moveToFirst());ClassificationCodec.readSnapshot(it.getString(0))
            }
        }
    }
    private fun reconcileAtStartup() {
        classificationStore?.let { store -> withClassificationEvidence {
            val db=store.writableDatabase
            db.beginTransaction()
            try { reconcileClassificationSnapshots(db);db.setTransactionSuccessful() } finally { db.endTransaction() }
        } }
        classificationsReconciled=true
    }
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
    private val authorizationSettings by lazy { app?.channels ?: net.ankio.ledger.capture.ChannelSettings(context) }
    override fun onCreate(db:SQLiteDatabase) { db.execSQL("""CREATE TABLE tasks(
        id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, origin TEXT NOT NULL,
        created_at INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL,
        record_id INTEGER, image BLOB, diagnostic TEXT NOT NULL DEFAULT '{}', shake_authorization TEXT,operation_uid TEXT NOT NULL,classification_snapshot TEXT NOT NULL,
        recognition_at INTEGER,recognition_zone TEXT,trusted_original_capture_date TEXT,node_snapshot TEXT,node_image_hash TEXT,recognition_evidence TEXT,
        automatic_authorization_generation INTEGER)""")
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
        if(oldVersion<6) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN classification_snapshot TEXT")
            db.execSQL("UPDATE tasks SET classification_snapshot=?",arrayOf(ClassificationCodec.snapshot(migrationBaseline).toString()))
        }
        if(oldVersion<7) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN recognition_at INTEGER")
            db.execSQL("ALTER TABLE tasks ADD COLUMN recognition_zone TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN trusted_original_capture_date TEXT")
        }
        if(oldVersion<8) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN node_snapshot TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN node_image_hash TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN recognition_evidence TEXT")
        }
        if(oldVersion<9)db.execSQL("ALTER TABLE tasks ADD COLUMN automatic_authorization_generation INTEGER")
    }
    /** Null until the engine actually begins processing; old records/tasks are never backfilled. */
    fun recognitionTime(id:Long):net.ankio.ledger.core.RecognitionTimeContext? = readableDatabase.rawQuery(
        "SELECT recognition_at,recognition_zone,trusted_original_capture_date FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || it.isNull(0))null else net.ankio.ledger.core.RecognitionTimeContext(it.getLong(0),it.getString(1),
            if(it.isNull(2))null else it.getString(2))
    }
    /** Native screenshot acquisition only. Import/file/notification clocks are not capture evidence. */
    @Synchronized fun recordOriginalCaptureDay(id:Long,capturedAt:Long,zoneId:String) {
        require(capturedAt>0)
        val day=java.time.Instant.ofEpochMilli(capturedAt).atZone(java.time.ZoneId.of(zoneId)).toLocalDate().toString()
        val native=readableDatabase.rawQuery("SELECT source,origin FROM tasks WHERE id=?",arrayOf(id.toString())).use {
            it.moveToFirst() && it.getString(0)==Source.OCR.name &&
                (it.getString(1).startsWith("payment_auto:") || it.getString(1).startsWith("shake:") || it.getString(1).startsWith("shortcut:"))
        }
        require(native) { "原始采集日只能来自现场截图" }
        writableDatabase.update("tasks",ContentValues().apply { put("trusted_original_capture_date",day) },
            "id=? AND trusted_original_capture_date IS NULL AND recognition_at IS NULL",arrayOf(id.toString()))
    }
    /** Persist before inference, atomically retaining the first context across retries and helpers. */
    @Synchronized fun beginRecognition(id:Long,clock:()->net.ankio.ledger.core.RecognitionTimeContext):net.ankio.ledger.core.RecognitionTimeContext {
        val db=writableDatabase;db.beginTransaction()
        try {
            recognitionTime(id)?.let { db.setTransactionSuccessful();return it }
            check(exists(id)) { "识别任务已不存在" }
            val originalDay=db.rawQuery("SELECT trusted_original_capture_date FROM tasks WHERE id=?",arrayOf(id.toString())).use {
                if(it.moveToFirst() && !it.isNull(0))it.getString(0) else null
            }
            val measured=clock()
            val time=if(originalDay==null)measured else measured.copy(trustedOriginalCaptureDate=originalDay)
            check(db.update("tasks",ContentValues().apply {
                put("recognition_at",time.recognitionAt);put("recognition_zone",time.zoneId)
                put("trusted_original_capture_date",time.trustedOriginalCaptureDate)
            },"id=? AND recognition_at IS NULL",arrayOf(id.toString()))==1)
            db.setTransactionSuccessful();return time
        } finally { db.endTransaction() }
    }
    /** Complete immutable configuration, fixed when this task was created, including legacy baseline. */
    fun classificationSnapshot(id:Long):net.ankio.ledger.core.ClassificationSnapshot? = readableDatabase.rawQuery("SELECT classification_snapshot FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst())null else ClassificationCodec.readSnapshot(it.getString(0))
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
    fun create(source:Source,origin:String,authorization:ShakeAuthorization?=null,automaticGeneration:Long?=null):Long = synchronized(classificationStore ?: this) {
        require(authorization==null || source==Source.OCR && origin==authorization.origin)
        require(automaticGeneration==null || source==Source.OCR && origin.startsWith("payment_auto:") && automaticGeneration>=0)
        val db=writableDatabase
        if(!classificationsReconciled)reconcileAtStartup()
        val operation=UUID.randomUUID().toString();val key="recognition:$operation"
        val snapshot=classificationStore?.holdClassificationSnapshot(key,taskDatabase=name) ?: net.ankio.ledger.core.ClassificationDefaults.migrationBaseline
        try {
            db.insertOrThrow("tasks",null,ContentValues().apply {
                put("source",source.name);put("origin",origin);put("created_at",System.currentTimeMillis())
                put("status","CAPTURING");put("message","正在获取账单截图");put("operation_uid",operation)
                put("classification_snapshot",ClassificationCodec.snapshot(snapshot).toString())
                if(authorization!=null)put("shake_authorization",authorization.encode())
                if(automaticGeneration!=null)put("automatic_authorization_generation",automaticGeneration)
            }).also { if(firstSessionTaskId==null)firstSessionTaskId=it }
        } catch(error:Throwable) { classificationStore?.forgetClassificationSnapshot(key);throw error }
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
    fun update(id:Long,status:String,message:String,recordId:Long?=null,replaceRecordLink:Boolean=false):Boolean = synchronized(classificationStore ?: this) {
        require(status in STATUSES)
        val updated=writableDatabase.update("tasks",ContentValues().apply {
            put("status",status);put("message",message.take(500));if(recordId!=null || replaceRecordLink) put("record_id",recordId)
        },"id=?"+(if(status in setOf("FAILED","CANCELLED")) " AND status IN ('CAPTURING','QUEUED','RECOGNIZING')" else ""),arrayOf(id.toString()))==1
        if(updated)withClassificationEvidence {
            val state=taskState(id)
            val resumable=classificationStore?.let { store -> state?.let { canRetryBatch(store.readableDatabase,it,authorizationSettings.captureGrants()) } }==true
            // A cancelled partial batch still owns resumable evidence; retain it under the failed-task policy.
            val imageState=if(resumable && status=="CANCELLED")"FAILED" else status
            screenshots.state(imageOwner(id),imageState)
            if(status !in ACTIVE_STATUSES) {
                screenshots.cleanup()
                if(!resumable || !screenshots.contains(imageOwner(id)))state?.let { classificationStore?.releaseClassificationSnapshot(it.operationKey) }
            }
        }
        if(updated)app?.executionLog?.event("model","task",status,JSONObject().put("taskId",id).put("recordId",recordId))
        updated
    }
    @Synchronized fun prepared(id:Long,image:ByteArray) { storeImage(id,image,"RECOGNIZING","正在识别记账中") }
    /** Save first, then expose a resumable queue entry. */
    @Synchronized fun queued(id:Long,image:ByteArray,snapshot:net.ankio.ledger.core.NodeSnapshot?=null):Boolean {
        require(image.isNotEmpty() && image.size<=5*1024*1024)
        if(snapshot!=null) {
            require(net.ankio.ledger.core.CapturePrivacy.manualAllowed(snapshot.page()))
            val origin=readableDatabase.rawQuery("SELECT source,origin FROM tasks WHERE id=?",arrayOf(id.toString())).use {
                if(!it.moveToFirst() || it.getString(0)!=Source.OCR.name)return false
                it.getString(1)
            }
            require(origin in setOf("shake:${snapshot.packageName}","shortcut:${snapshot.packageName}","payment_auto:${snapshot.packageName}"))
            if(writableDatabase.update("tasks",ContentValues().apply { put("node_snapshot",NodeSnapshotCodec.json(snapshot).toString());put("node_image_hash",imageHash(image)) },
                "id=? AND status IN ('CAPTURING','QUEUED','RECOGNIZING')",arrayOf(id.toString()))!=1)return false
        }
        return storeImage(id,image,"QUEUED","已获取截图，等待识别")
    }
    /** Only a prequalified original node capture may be queued without image bytes. */
    @Synchronized fun queuedWithoutScreenshot(id:Long,snapshot:net.ankio.ledger.core.NodeSnapshot,
        route:net.ankio.ledger.core.NodeRoute,reason:net.ankio.ledger.capture.ScreenshotFailureReason):Boolean {
        require(reason in setOf(net.ankio.ledger.capture.ScreenshotFailureReason.UNAVAILABLE,net.ankio.ledger.capture.ScreenshotFailureReason.TIMEOUT))
        require(route.candidate!=null && route.verification!=null && net.ankio.ledger.core.CapturePrivacy.manualAllowed(snapshot.page()))
        val valid=readableDatabase.rawQuery("SELECT source,origin FROM tasks WHERE id=?",arrayOf(id.toString())).use {
            it.moveToFirst() && it.getString(0)==Source.OCR.name && it.getString(1) in
                setOf("shake:${snapshot.packageName}","shortcut:${snapshot.packageName}","payment_auto:${snapshot.packageName}") }
        require(valid && !screenshots.contains(imageOwner(id)))
        val db=writableDatabase;db.beginTransaction()
        try {
            if(db.update("tasks",ContentValues().apply {
                put("node_snapshot",NodeSnapshotCodec.json(snapshot).toString());putNull("node_image_hash")
                put("status","QUEUED");put("message","正在识别记账中")
            },"id=? AND status='CAPTURING'",arrayOf(id.toString()))!=1)return false
            recordRoute(id,route,snapshot)
            val evidence=requireNotNull(storedRecognitionEvidence(id))
            evidence.put("screenshot",JSONObject().put("state",reason.name).put("reason",reason.name))
            writeRecognitionEvidence(id,evidence);db.setTransactionSuccessful();return true
        } finally { db.endTransaction() }
    }
    fun hasMissingNodeEvidence(id:Long):Boolean=readableDatabase.rawQuery("SELECT node_snapshot,node_image_hash,recognition_evidence FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || it.isNull(0) || !it.isNull(1) || it.isNull(2) || screenshots.contains(imageOwner(id)))false
        else runCatching { RecognitionEvidenceCodec.decode(JSONObject(it.getString(2))).let { evidence ->
            evidence.route=="NODE" && evidence.screenshot.state in setOf("UNAVAILABLE","TIMEOUT") && evidence.screenshot.reason==evidence.screenshot.state &&
                evidence.snapshot?.fingerprint==NodeSnapshotCodec.decode(it.getString(0)).fingerprint } }.getOrDefault(false)
    }
    @Synchronized fun markNodeRecognizing(id:Long) {
        check(hasMissingNodeEvidence(id))
        writableDatabase.update("tasks",ContentValues().apply { put("status","RECOGNIZING");put("message","正在识别记账中") },
            "id=? AND status IN ('QUEUED','RECOGNIZING')",arrayOf(id.toString()))
    }
    private fun imageHash(image:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
    fun nodeSnapshot(id:Long,image:ByteArray?=null):net.ankio.ledger.core.NodeSnapshot?=readableDatabase.rawQuery("SELECT node_snapshot,node_image_hash FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || it.isNull(0))null else {
            check(image==null || imageHash(image)==it.getString(1)) { "节点与截图证据不一致" }
            NodeSnapshotCodec.decode(it.getString(0))
        }
    }
    private fun storedRecognitionEvidence(id:Long):JSONObject?=readableDatabase.rawQuery("SELECT recognition_evidence FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(!it.moveToFirst() || it.isNull(0))null else JSONObject(it.getString(0))
    }
    fun recognitionEvidence(id:Long):JSONObject?=storedRecognitionEvidence(id)?.takeIf { it.has("route") }
    /** Bounded provenance only; node content is kept in a separate private column. */
    @Synchronized fun recordRoute(id:Long,route:net.ankio.ledger.core.NodeRoute?,snapshot:net.ankio.ledger.core.NodeSnapshot?,reason:String?=null) {
        val current=storedRecognitionEvidence(id) ?: JSONObject().put("version",1).put("screenshot",JSONObject().put("state","AVAILABLE").put("reason",JSONObject.NULL))
            .put("phaseTimingsMs",JSONObject())
        current.put("route",if(route?.candidate!=null)"NODE" else "OCR")
            .put("reason",reason ?: route?.reason?.name?.lowercase() ?: "legacy_image")
            .put("adapter",route?.verification?.let(NodeSnapshotCodec::verification) ?: JSONObject.NULL)
            .put("snapshot",snapshot?.let(NodeSnapshotCodec::summary) ?: JSONObject.NULL)
        if(snapshot!=null)current.getJSONObject("phaseTimingsMs").put("nodeRead",snapshot.readCompletedMs-snapshot.readStartedMs)
        writeRecognitionEvidence(id,current)
    }
    @Synchronized fun phaseTiming(id:Long,phase:String,millis:Long) {
        require(phase in setOf("nodeRead","screenshot","route","ocr","parse","save") && millis>=0)
        val current=storedRecognitionEvidence(id) ?: JSONObject().put("version",1).put("phaseTimingsMs",JSONObject())
            .put("screenshot",JSONObject().put("state","AVAILABLE").put("reason",JSONObject.NULL))
        current.getJSONObject("phaseTimingsMs").put(phase,millis);writeRecognitionEvidence(id,current)
    }
    private fun writeRecognitionEvidence(id:Long,evidence:JSONObject) {
        require(evidence.toString().toByteArray().size<=8192)
        writableDatabase.update("tasks",ContentValues().apply { put("recognition_evidence",evidence.toString()) },"id=?",arrayOf(id.toString()))
    }
    /** Explicit private backup seam. Portable identity is operationKey + stable ledger UID, never a row/media owner. */
    fun portableNodeEvidence():JSONArray=readableDatabase.rawQuery("SELECT id,record_id,node_snapshot,recognition_evidence,node_image_hash FROM tasks WHERE node_snapshot IS NOT NULL ORDER BY id",null).use { c ->
        JSONArray().apply { while(c.moveToNext()) {
            val id=c.getLong(0)
            put(JSONObject().put("operationKey",operationKey(id)).put("recordUid",if(c.isNull(1))JSONObject.NULL else classificationStore?.get(c.getLong(1))?.uid ?: JSONObject.NULL)
                .put("snapshot",JSONObject(c.getString(2))).put("imageFingerprint",if(c.isNull(4))JSONObject.NULL else c.getString(4))
                .put("recognitionEvidence",if(c.isNull(3))JSONObject.NULL else JSONObject(c.getString(3))))
        } }
    }
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
    fun isImageImport(id:Long):Boolean=readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE id=? AND source='IMAGE' AND origin='image'",
        arrayOf(id.toString())).use { it.moveToFirst() }
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
    fun origin(id:Long):String?=readableDatabase.rawQuery("SELECT origin FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(it.moveToFirst())it.getString(0) else null
    }
    fun status(id:Long):String?=readableDatabase.rawQuery("SELECT status FROM tasks WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst())it.getString(0) else null }
    fun recordId(id:Long):Long?=readableDatabase.rawQuery("SELECT record_id FROM tasks WHERE id=?",arrayOf(id.toString())).use {
        if(it.moveToFirst()&&!it.isNull(0))it.getLong(0) else null
    }
    fun metadataForRecords(ids:Set<Long>):JSONArray {
        if(ids.isEmpty())return JSONArray()
        require(ids.all { it>0 })
        return readableDatabase.rawQuery("SELECT record_id,origin,diagnostic,MAX(id) FROM tasks WHERE record_id IN (${ids.joinToString(",")}) GROUP BY record_id",null).use { c ->
            JSONArray().apply { while(c.moveToNext())put(JSONObject().put("recordId",c.getLong(0)).put("origin",c.getString(1)).put("diagnostic",JSONObject(c.getString(2))).put("recognitionEvidence",recognitionEvidence(c.getLong(3)) ?: JSONObject.NULL)) }
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
    fun snapshot(limit:Int=200,start:Long?=null,end:Long?=null):JSONArray {
        val rows=readableDatabase.rawQuery("SELECT id,source,origin,created_at,status,message,record_id,image IS NOT NULL,diagnostic,operation_uid FROM tasks"+
        (if(start!=null&&end!=null)" WHERE (created_at>=? AND created_at<?) OR status IN ('CAPTURING','QUEUED','RECOGNIZING')" else "")+
        " ORDER BY id DESC LIMIT ${limit.coerceIn(1,500)}",if(start!=null&&end!=null)arrayOf(start.toString(),end.toString()) else null).use { c ->
        JSONArray().apply { while(c.moveToNext()) put(JSONObject().put("id",c.getLong(0)).put("source",c.getString(1))
            .put("origin",c.getString(2)).put("createdAt",c.getLong(3)).put("status",c.getString(4)).put("message",c.getString(5))
            .put("recordId",if(c.isNull(6))JSONObject.NULL else c.getLong(6)).put("hasScreenshot",screenshots.contains(imageOwner(c.getLong(0))))
            .put("diagnostic",JSONObject(c.getString(8))).put("operationUID",c.getString(9))
            .put("recognitionEvidence",recognitionEvidence(c.getLong(0)) ?: JSONObject.NULL)) }
    }
        // Close the task cursor before reading ledger.db; task DB locks never nest a store lock.
        classificationStore?.let { store -> synchronized(store) {
            val grants=authorizationSettings.captureGrants()
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index)
                val batch=store.recognitionBatch("recognition:${row.getString("operationUID")}") ?: continue
                val canRetry=taskState(row.getLong("id"))?.let { canRetryBatch(store.readableDatabase,it,grants,!batch.complete) }==true
                if (batch.complete && row.getString("status") == "REVIEW") {
                    row.put("status",batch.taskStatus).put("message",batch.message)
                }
                row.put("batch",batch.json().put("canRetry",canRetry))
            }
        } }
        return rows
    }
    fun diagnosticExport():JSONArray=readableDatabase.rawQuery("SELECT id,source,created_at,status,diagnostic FROM tasks ORDER BY id DESC LIMIT 100",null).use { c ->
        JSONArray().apply { while(c.moveToNext())put(JSONObject().put("id",c.getLong(0)).put("source",c.getString(1))
            .put("createdAt",c.getLong(2)).put("status",c.getString(3)).put("diagnostic",JSONObject(c.getString(4)))) }
    }
    fun image(id:Long):ByteArray? { readableDatabase;return screenshots.image(imageOwner(id)) }
    private data class TaskState(val id:Long,val source:Source,val origin:String,val status:String,val operationKey:String,
        val authorization:ShakeAuthorization?,val automaticGeneration:Long?) {
        val imageImport get()=source==Source.IMAGE && origin=="image"
        val automaticPackage get()=origin.removePrefix("payment_auto:").takeIf { source==Source.OCR && origin.startsWith("payment_auto:") }
        val manualCapture get()=origin.startsWith("shake:") || origin.startsWith("shortcut:")
        val nativeCapture get()=manualCapture || automaticPackage!=null
        fun hasOriginalGrant(grants:ChannelSettings.CaptureGrants?):Boolean = imageImport || grants!=null && (
            source==Source.OCR && authorization?.takeIf { it.origin==origin }?.let(grants::allows)==true ||
            automaticPackage?.let { pkg -> automaticGeneration?.let { grants.allowsAutomatic(pkg,it) } }==true)
    }
    private fun taskState(c:Cursor)=TaskState(c.getLong(0),Source.valueOf(c.getString(1)),c.getString(2),c.getString(3),
        "recognition:${c.getString(4)}",ShakeAuthorization.decode(c.getString(5)),if(c.isNull(6))null else c.getLong(6))
    private fun taskStates(db:SQLiteDatabase,table:String,where:String,args:Array<String>?=null):List<TaskState> =
        db.rawQuery("SELECT id,source,origin,status,operation_uid,shake_authorization,automatic_authorization_generation FROM $table WHERE $where",args)
            .use { c -> buildList { while(c.moveToNext())add(taskState(c)) } }
    private fun taskState(id:Long):TaskState?=taskStates(readableDatabase,"tasks","id=?",arrayOf(id.toString())).singleOrNull()

    /** Live permission and queue capacity are checked by the queue before changing state. */
    private fun canRetryBatch(db:SQLiteDatabase,state:TaskState,grants:ChannelSettings.CaptureGrants?,unfinished:Boolean?=null):Boolean =
        state.status in BATCH_RETRY_STATUSES && state.hasOriginalGrant(grants) &&
            (unfinished ?: RecognitionBatchStore.hasUnfinished(db,state.operationKey)) && screenshots.contains(imageOwner(state.id))

    internal fun queueBatchRetry(id:Long):Boolean {
        val store=classificationStore ?: return false
        return synchronized(store) { withClassificationEvidence {
            val state=taskState(id) ?: return@withClassificationEvidence false
            val db=store.writableDatabase
            if(!canRetryBatch(db,state,authorizationSettings.captureGrants()))return@withClassificationEvidence false
            ClassificationStorage.activate(db,state.operationKey,name) { requireNotNull(classificationSnapshot(id)) }
            update(id,"QUEUED","正在重试未完成项")
        } }
    }

    /** Old queued single recognition can still run under current consent. Frozen batches need their original grant. */
    internal fun automaticQueueGrantValid(id:Long):Boolean {
        val store=classificationStore ?: return false
        return synchronized(store) {
            val state=taskState(id) ?: return@synchronized false
            if(state.automaticPackage==null)return@synchronized false
            if(state.automaticGeneration==null) !RecognitionBatchStore.hasUnfinished(store.readableDatabase,state.operationKey)
            else state.hasOriginalGrant(authorizationSettings.captureGrants())
        }
    }

    /** Revocation also affects terminal tasks outside the live queue. */
    internal fun reconcileRevokedBatchReservations(originPrefix:String,packageName:String?=null) {
        val store=classificationStore ?: return
        synchronized(store) { withClassificationEvidence {
            val states=taskStates(readableDatabase,"tasks","status IN ('FAILED','CANCELLED') AND origin "+
                (if(packageName==null)"LIKE ?" else "=?"),arrayOf(originPrefix+(packageName ?: "%")))
            val db=store.readableDatabase
            val grants=authorizationSettings.captureGrants()
            states.filterNot { canRetryBatch(db,it,grants) }.forEach { store.releaseClassificationSnapshot(it.operationKey) }
        } }
    }
    fun delete(id:Long) = synchronized(classificationStore ?: this) {
        val key=operationKey(id)
        writableDatabase.delete("tasks","id=?",arrayOf(id.toString()))
        key?.let { classificationStore?.releaseClassificationSnapshot(it) };screenshots.release(imageOwner(id))
    }
    fun deleteForRecord(id:Long) = synchronized(classificationStore ?: this) {
        val ids=readableDatabase.rawQuery("SELECT id FROM tasks WHERE record_id=?",arrayOf(id.toString())).use { c -> buildList { while(c.moveToNext())add(c.getLong(0)) } }
        val keys=ids.mapNotNull(::operationKey)
        writableDatabase.delete("tasks","record_id=?",arrayOf(id.toString()))
        keys.forEach { classificationStore?.releaseClassificationSnapshot(it) };ids.forEach { screenshots.release(imageOwner(it)) }
    }
    fun recoverInterrupted(preserveCurrentSession:Boolean=false,capturePausedForRestore:Boolean=false) = synchronized(classificationStore ?: this) {
        reconcileAtStartup()
        // Use persisted grants before the queue's live permission gate and without acquiring channels under the store.
        val captures=taskStates(readableDatabase,"tasks","status IN ('CAPTURING','QUEUED','RECOGNIZING') AND (origin LIKE 'shake:%' OR origin LIKE 'shortcut:%' OR origin LIKE 'payment_auto:%')")
        val grants=authorizationSettings.captureGrants()
        captures.filter { task -> capturePausedForRestore || if(task.manualCapture) !task.hasOriginalGrant(grants) else !automaticQueueGrantValid(task.id) }
            .forEach { update(it.id,"CANCELLED","识别授权已关闭，未新增账单") }
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
            val resume=status=="RECOGNIZING" && (origin.startsWith("payment_auto:") || origin.startsWith("shake:") || origin.startsWith("shortcut:") || isImageImport(id)) && (screenshots.contains(imageOwner(id)) || hasMissingNodeEvidence(id))
            update(id,if(resume)"QUEUED" else "FAILED",if(resume)"已恢复截图，等待识别" else "上次识别被系统中断，请重新摇动或导入截图")
        }
    }
    init { classificationStore?.bindBackupTasks(this) }
    companion object {
        val STATUSES=setOf("CAPTURING","QUEUED","RECOGNIZING","SUCCEEDED","REVIEW","FAILED","NO_BILL","DUPLICATE","CANCELLED","SKIPPED")
        private val ACTIVE_STATUSES=setOf("CAPTURING","QUEUED","RECOGNIZING")
        private val BATCH_RETRY_STATUSES=setOf("FAILED","CANCELLED")
    }
}
