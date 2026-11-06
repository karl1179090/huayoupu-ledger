package net.ankio.ledger

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.security.MessageDigest

/** Removes only receipts created by this synthetic case, after all durable-dedup assertions. */
internal class CaptureReceiptIsolation(private val app:LedgerApp) {
    private data class Receipt(val token:String,val kind:String,val resultUid:String,val createdAt:Long) {
        fun hash():String=MessageDigest.getInstance("SHA-256")
            .digest("$token\u0000$kind\u0000$resultUid\u0000$createdAt".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun taskIds():Set<Long> = synchronized(app.tasks) {
        app.tasks.readableDatabase.rawQuery("SELECT id FROM tasks",null).use { rows ->
            buildSet { while(rows.moveToNext())add(rows.getLong(0)) }
        }
    }
    val entryTaskIds=taskIds()
    private val entry=synchronized(app.store) {
        val events=app.store.readableDatabase.rawQuery("SELECT event_key FROM capture_events",null).use { rows ->
            buildSet { while(rows.moveToNext())add(rows.getString(0)) }
        }
        receipts() to events
    }
    private val entryReceipts=entry.first
    private val entryTokens=entryReceipts.map { it.token }.toSet()
    private val entryEvents=entry.second
    fun createdTaskIds():List<Long> =(taskIds()-entryTaskIds).sorted()

    private fun receipts():Set<Receipt> = app.store.readableDatabase.rawQuery(
        "SELECT token,kind,result_uid,created_at FROM wallet_submissions",null
    ).use { rows -> buildSet {
        while(rows.moveToNext())add(Receipt(rows.getString(0),rows.getString(1),rows.getString(2),rows.getLong(3)))
    } }

    fun clean(newTaskIds:List<Long>) {
        assertTrue("Receipt cleanup is restricted to tasks created by this case",newTaskIds.none { it in entryTaskIds })
        val owned=buildSet {
            newTaskIds.forEach { id ->
                app.tasks.operationKey(id)?.let { add(it to "CAPTURE_RESULT") }
                app.tasks.paymentIdentity(id)?.let { add("capture-event:$it" to "CAPTURE_EVENT_RESULT") }
            }
        }
        val report=synchronized(app.store) {
            val db=app.store.writableDatabase
            db.beginTransaction()
            try {
                val before=receipts()
                val removed=before.filter { (it.token to it.kind) in owned && it.token !in entryTokens }.toSet()
                removed.forEach { db.delete("wallet_submissions","token=? AND kind=?",arrayOf(it.token,it.kind)) }
                newTaskIds.mapNotNull(app.tasks::paymentIdentity).filter { it !in entryEvents }.forEach {
                    db.delete("capture_events","event_key=?",arrayOf(it))
                }
                val after=receipts()
                assertEquals("Unrelated and entry receipts must remain byte-for-byte unchanged",before-removed,after)
                assertTrue("Receipts present at case entry must survive cleanup",after.containsAll(entryReceipts))
                db.setTransactionSuccessful()
                JSONObject().put("case",Throwable().stackTrace.firstOrNull { it.className.endsWith("Test") }?.className)
                    .put("newTasks",JSONArray(newTaskIds)).put("entry",JSONArray(entryReceipts.map { it.hash() }.sorted()))
                    .put("before",JSONArray(before.map { it.hash() }.sorted()))
                    .put("removed",JSONArray(removed.map { it.hash() }.sorted()))
                    .put("after",JSONArray(after.map { it.hash() }.sorted())).put("unrelatedUnchanged",true)
            } finally { db.endTransaction() }
        }
        // Hash-only evidence; the synthetic host runner copies this before AGP uninstalls the app.
        File(app.cacheDir,"capture-receipt-isolation.jsonl").appendText(report.toString()+"\n")
        Log.i("CaptureReceiptIsolation",report.toString())
    }
}
