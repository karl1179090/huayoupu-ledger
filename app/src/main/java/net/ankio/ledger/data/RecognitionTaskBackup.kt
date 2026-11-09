package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Source
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/** Private portable task payload. No local row IDs, media owners or capture authorizations. */
internal object RecognitionTaskBackup {
    val columns=listOf("source","origin","created_at","status","message","diagnostic","operation_uid","classification_snapshot",
        "recognition_at","recognition_zone","trusted_original_capture_date","node_snapshot","node_image_hash","recognition_evidence")
    fun validate(rows:JSONArray) {
        require(rows.length()<=100_000)
        val seen=mutableSetOf<String>()
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            val uid=row.getString("operation_uid")
            require(UUID.fromString(uid).toString()==uid && seen.add(uid))
            Source.valueOf(row.getString("source"));require(row.getString("origin").length<=1024)
            require(row.getLong("created_at") in 1..4_102_444_800_000L)
            require(row.getString("status") in RecognitionTasks.STATUSES && row.getString("message").length<=500)
            require(row.getString("diagnostic").length<=32768);JSONObject(row.getString("diagnostic"))
            ClassificationCodec.readSnapshot(row.getString("classification_snapshot"))
            if(!row.isNull("recognition_at")) { require(row.getLong("recognition_at") in 1..4_102_444_800_000L);ZoneId.of(row.getString("recognition_zone")) }
            else require(row.isNull("recognition_zone"))
            if(!row.isNull("trusted_original_capture_date"))java.time.LocalDate.parse(row.getString("trusted_original_capture_date"))
            if(!row.isNull("imageState"))require(row.getString("imageState") in RecognitionTasks.STATUSES+setOf("HISTORY","UNDO"))
            if(!row.isNull("imageCreatedAt"))require(row.getLong("imageCreatedAt")>0)
            if(!row.isNull("recognition_evidence"))require(row.getString("recognition_evidence").toByteArray().size<=8192)
            val node=if(row.isNull("node_snapshot"))null else NodeSnapshotCodec.decode(row.getString("node_snapshot"))
            if(node!=null)require(row.getString("source")==Source.OCR.name && row.getString("origin") in
                setOf("shake:${node.packageName}","shortcut:${node.packageName}","payment_auto:${node.packageName}")) { "节点证据与识别来源不一致" }
            val evidence=if(row.isNull("recognition_evidence"))null else JSONObject(row.getString("recognition_evidence")).let { j ->
                if(j.has("route"))RecognitionEvidenceCodec.decode(j)
                else {
                    require(j.keys().asSequence().toSet()==setOf("version","phaseTimingsMs","screenshot") && j.getInt("version")==1)
                    val image=j.getJSONObject("screenshot");require(image.getString("state")=="AVAILABLE" && image.isNull("reason"))
                    val timing=j.getJSONObject("phaseTimingsMs")
                    for(key in timing.keys())require(key in setOf("nodeRead","screenshot","route","ocr","parse","save") && (timing.get(key) is Int || timing.get(key) is Long) && timing.getLong(key)>=0)
                    null
                }
            }
            if(evidence?.route=="NODE")require(node!=null && evidence.snapshot?.fingerprint==node.fingerprint)
            val hash=if(row.isNull("imageHash"))null else row.getString("imageHash").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            if(!row.isNull("node_image_hash"))require(node!=null && (hash==null || hash==row.getString("node_image_hash")))
            if(node!=null && row.isNull("node_image_hash"))require(evidence?.route=="NODE" && evidence.snapshot?.fingerprint==node.fingerprint &&
                evidence.screenshot.state in setOf("UNAVAILABLE","TIMEOUT") && evidence.screenshot.reason==evidence.screenshot.state && hash==null)
            if(!row.isNull("recordUid"))UUID.fromString(row.getString("recordUid"))
        }
    }
    fun restore(tasks:RecognitionTasks,db:SQLiteDatabase,rows:JSONArray,records:Map<String,String?>,categories:Map<String,LedgerCategory>,images:(String)->ByteArray?) {
        fun canonical(snapshot:net.ankio.ledger.core.ClassificationSnapshot):net.ankio.ledger.core.ClassificationSnapshot {
            fun resolved(rule:net.ankio.ledger.core.ClassificationRule)=rule.copy(categoryId=CategoryReplacementStorage.resolve(db,rule.categoryId))
            return snapshot.copy(customRules=snapshot.customRules.map(::resolved),builtinRules=snapshot.builtinRules.map(::resolved))
        }
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val operation=row.getString("operation_uid");val key="recognition:$operation"
            val snapshot=ClassificationCodec.readSnapshot(row.getString("classification_snapshot"))
            fun target(rule:net.ankio.ledger.core.ClassificationRule)=rule.copy(categoryId=categories[rule.categoryId]?.id ?: rule.categoryId)
            var mapped=snapshot.copy(customRules=snapshot.customRules.map(::target),builtinRules=snapshot.builtinRules.map(::target))
            var payload=ClassificationCodec.snapshot(mapped).toString()
            val reservation=db.rawQuery("SELECT payload FROM classification_snapshots WHERE operation_key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
            if(reservation!=null) {
                val held=ClassificationCodec.readSnapshot(reservation)
                require(canonical(held)==canonical(mapped)) { "识别任务分类快照冲突" }
                mapped=held;payload=reservation
            }
            val existing=db.rawQuery("SELECT id,source,origin,created_at,classification_snapshot,node_snapshot,node_image_hash,recognition_at,recognition_zone FROM recognition.tasks WHERE operation_uid=?",arrayOf(operation)).use { c ->
                if(!c.moveToFirst())false else {
                    require(c.getString(1)==row.getString("source") && c.getString(2)==row.getString("origin") && c.getLong(3)==row.getLong("created_at") &&
                        canonical(ClassificationCodec.readSnapshot(c.getString(4)))==canonical(mapped) && c.getString(5)==row.optString("node_snapshot").takeUnless { row.isNull("node_snapshot") } &&
                        c.getString(6)==row.optString("node_image_hash").takeUnless { row.isNull("node_image_hash") }) { "识别任务标识与本地证据冲突" }
                    val image=tasks.backupImageHash(c.getLong(0))
                    require(image==null || row.isNull("imageHash") || image==row.getString("imageHash")) { "识别任务标识与本地原图冲突" }
                    if(!c.isNull(7) && !row.isNull("recognition_at"))require(c.getLong(7)==row.getLong("recognition_at") && c.getString(8)==row.getString("recognition_zone")) { "识别任务时钟冲突" }
                    if(c.isNull(7) && !row.isNull("recognition_at"))db.execSQL("UPDATE recognition.tasks SET recognition_at=?,recognition_zone=? WHERE id=?",arrayOf(row.getLong("recognition_at"),row.getString("recognition_zone"),c.getLong(0)))
                    true
                }
            }
            if(existing)continue
            var status=row.getString("status")
            if(status=="RECOGNIZING")status="QUEUED"
            if(status=="CAPTURING")status="FAILED"
            val uid=row.optString("recordUid").takeUnless { row.isNull("recordUid") }?.let { records[it] ?: it }
            val recordId=uid?.let { db.rawQuery("SELECT id FROM records WHERE uid=? AND NOT EXISTS(SELECT 1 FROM record_deletions WHERE record_id=records.id)",arrayOf(it)).use { c -> if(c.moveToFirst())c.getLong(0) else null } }
            val values=ContentValues()
            columns.forEach { column -> if(row.isNull(column))values.putNull(column) else if(column in setOf("created_at","recognition_at"))values.put(column,row.getLong(column)) else values.put(column,row.getString(column)) }
            values.put("classification_snapshot",payload);values.put("status",status);values.putNull("shake_authorization")
            values.put("record_id",recordId)
            val id=db.insertOrThrow("recognition.tasks",null,values)
            AtomicBackupTransaction.checkpoint("tasks")
            if(!row.isNull("imageHash")) {
                val image=requireNotNull(images(operation));require(PortableCsv.digest(image)==row.getString("imageHash"));BackupMediaValidator.validate(image)
                tasks.restoreImage(db,id,image,row.optLong("imageCreatedAt",row.getLong("created_at")),row.optString("imageState",status))
            }
            val resumable=status=="CANCELLED" && !row.isNull("imageHash") &&
                RecognitionTasks.retryableBatchSource(Source.valueOf(row.getString("source")),row.getString("origin"),false) &&
                RecognitionBatchStore.hasUnfinished(db,key)
            if(RecognitionTasks.retainsClassificationSnapshot(status,resumable)) {
                val held=db.rawQuery("SELECT payload FROM classification_snapshots WHERE operation_key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
                require(held==null || held==payload) { "识别任务分类快照冲突" }
                ClassificationStorage.hold(db,key,mapped,tasks.databaseName)
                db.execSQL("UPDATE classification_snapshots SET active=1,task_database=? WHERE operation_key=?",arrayOf(tasks.databaseName,key))
            }
        }
    }
}
