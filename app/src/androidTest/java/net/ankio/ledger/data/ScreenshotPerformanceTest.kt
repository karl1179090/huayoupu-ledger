package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class ScreenshotPerformanceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="screenshot-perf-${UUID.randomUUID()}"
    private val images=ScreenshotStore(context,namespace)
    private val ledgerName="$namespace-ledger.db"
    private val store=LedgerStore(context,ledgerName,images).prepareSyntheticDefaultWallet()
    private val now=System.currentTimeMillis()

    @After fun close() {
        store.close();images.close()
        context.deleteDatabase(ledgerName);context.deleteDatabase("$namespace.db")
        File(context.filesDir,namespace).deleteRecursively();context.deleteSharedPreferences("${namespace}_policy")
    }

    private fun report(name:String,samples:List<Long>,operations:Int) {
        val stage=InstrumentationRegistry.getArguments().getString("perfStage","check")
        require(stage in setOf("before","after","check"))
        val result=JSONObject().put("name",name).put("operationsPerSample",operations)
            .put("samplesMs",JSONArray(samples.map { it/1_000_000.0 }))
            .put("medianMs",samples.sorted()[samples.size/2]/1_000_000.0)
        android.util.Log.i("LedgerPerformance","$stage $result")
        File(context.cacheDir,"performance-$stage-$name.json").writeText(result.toString())
    }

    @Test fun historyLookupReturnsOnlyRequestedEvidence() {
        val record=LedgerRecord(capture=Capture(Source.OCR,"synthetic","合成截图",now),
            bill=ParsedBill(100,Direction.EXPENSE,"合成商户","购物",now,null,true,"",true),state=RecordState.POSTED)
        store.merge(listOf(record));val id=store.all().single().id
        fun owner(sequence:Int)=if(sequence==1)"ledger:$ledgerName:$id" else "ledger:$ledgerName:$id:history:$sequence"
        val bytes=byteArrayOf(1,2,3,4)
        val hash=images.put(owner(300),bytes,now,"HISTORY")
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            for(sequence in 1..300)db.execSQL("INSERT INTO capture_history VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id,sequence,now,if(sequence==1)"payment" else "payment_supplement",100,0,"合成原文".repeat(100),"cumulative",null,null))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val refs=images.writableDatabase
        refs.beginTransaction()
        try {
            for(sequence in 1..299)refs.execSQL("INSERT INTO refs VALUES(?,?,?,?)",arrayOf<Any>(owner(sequence),hash,now,"HISTORY"))
            refs.setTransactionSuccessful()
        } finally { refs.endTransaction() }
        repeat(5) { assertArrayEquals(bytes,store.historyScreenshot(id,300)) }
        report("history-300",List(5) { measureNanoTime { repeat(5) { store.historyScreenshot(id,300) } } },5)
        images.put(owner(301),bytes,now,"HISTORY")
        assertNull("An image reference without a history slot must not be served",store.historyScreenshot(id,301))
        images.release(owner(2));assertNull(store.historyScreenshot(id,2))
        assertEquals(300,store.captureHistory(id).size)
        assertTrue(File(context.filesDir,"$namespace/$hash.jpg").delete())
        assertNull("Expired image files must still return null",store.historyScreenshot(id,300))
    }

    @Test fun referenceChangesPreserveOtherAssetsInALargeStore() {
        val db=images.writableDatabase
        // Metadata-only background assets isolate lookup cost from image encoding and file writes.
        db.beginTransaction()
        try {
            for(index in 1..10_000) {
                val hash=index.toString(16).padStart(64,'0')
                db.execSQL("INSERT INTO assets VALUES(?,1)",arrayOf(hash))
                db.execSQL("INSERT INTO refs VALUES(?,?,?,?)",arrayOf<Any>("background-$index",hash,now,"HISTORY"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val first=byteArrayOf(8,9);val second=byteArrayOf(9,8)
        val hash=images.put("guard-first",first,now,"HISTORY")
        images.put("guard-second",second,now,"HISTORY")
        val releaseSamples=mutableListOf<Long>()
        repeat(5) {
            db.beginTransaction()
            try {
                repeat(100) { index->db.execSQL("INSERT INTO refs VALUES(?,?,?,?)",arrayOf<Any>("released-$index",hash,now,"HISTORY")) }
                releaseSamples+=measureNanoTime { repeat(100) { index->images.release("released-$index") } }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            assertArrayEquals(first,images.image("guard-first"))
        }
        report("release-10000",releaseSamples,100)
        images.put("replaced",first,now,"HISTORY")
        report("replace-10000",List(5) { measureNanoTime {
            repeat(10) { index->images.put("replaced",if(index%2==0)second else first,now,"HISTORY") }
        } },10)
        assertArrayEquals(first,images.image("replaced"));assertArrayEquals(second,images.image("guard-second"))
        assertEquals(10_002,images.stats().getInt("count"))
        images.release("replaced");images.release("guard-first")
        assertFalse(File(context.filesDir,"$namespace/$hash.jpg").exists())
        assertArrayEquals(second,images.image("guard-second"))
        assertEquals(10_001,images.stats().getInt("count"))
    }
}
