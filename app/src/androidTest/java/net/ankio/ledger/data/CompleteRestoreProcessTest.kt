package net.ankio.ledger.data

import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses the actual archive restore boundary, then kills the Linux process instead of unwinding. */
class CompleteRestoreProcessTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun target():Triple<LedgerStore,RecognitionTasks,ScreenshotStore> {
        val media=ScreenshotStore(context,"restore-proof-media")
        val ledger=LedgerStore(context,"restore-proof-ledger.db",media)
        val tasks=RecognitionTasks(context,"restore-proof-tasks.db",media,ledger)
        tasks.writableDatabase;ledger.writableDatabase;media.writableDatabase
        return Triple(ledger,tasks,media)
    }
    @Test fun prepareSource() {
        val sourceMedia=ScreenshotStore(context,"restore-proof-source-media")
        val source=LedgerStore(context,"restore-proof-source.db",sourceMedia)
        val tasks=RecognitionTasks(context,"restore-proof-source-tasks.db",sourceMedia,source)
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成恢复进程钱包",amountCents=10000));source.setDefaultWallet(wallet.uid)
            val task=tasks.create(Source.IMAGE,"image");tasks.queued(task,syntheticBackupImage(141))
            tasks.beginRecognition(task) { RecognitionTimeContext(1730611800000L,"America/New_York") }
            val key=tasks.operationKey(task)!!
            val capture=Capture(Source.IMAGE,"image","合成恢复进程凭证",1730611800000L)
            val bill=ParsedBill(1200,Direction.EXPENSE,"合成恢复商家","其他",1730611800000L,null,true,"")
            source.ingestRecognition(capture,bill,key,screenshot=syntheticBackupImage(142))
            File(context.filesDir,"restore-proof-source.zip").outputStream().use { LedgerArchive.write(source,ChannelSettings(context),it) }
            File(context.filesDir,"restore-proof-source-key").writeText(key)
            val (target,targetTasks,targetMedia)=target();targetTasks.close();target.close();targetMedia.close()
        } finally { tasks.close();source.close();sourceMedia.close() }
    }
    @Test fun interruptRestore() {
        val (ledger,tasks,media)=target()
        val phase=InstrumentationRegistry.getArguments().getString("phase")!!
        AtomicBackupTransaction.processProbe={ at ->
            if(at=="prepared") {
                val db=ledger.writableDatabase
                val report=org.json.JSONObject().put("sqliteVersion",db.rawQuery("SELECT sqlite_version()",null).use { it.moveToFirst();it.getString(0) })
                val databases=org.json.JSONArray()
                db.rawQuery("PRAGMA database_list",null).use { c -> while(c.moveToNext())databases.put(org.json.JSONObject().put("schema",c.getString(1)).put("path",c.getString(2))) }
                report.put("databases",databases)
                for(schema in listOf("main","recognition","media"))report.put(schema,org.json.JSONObject()
                    .put("journalMode",db.rawQuery("PRAGMA $schema.journal_mode",null).use { it.moveToFirst();it.getString(0) })
                    .put("synchronous",db.rawQuery("PRAGMA $schema.synchronous",null).use { it.moveToFirst();it.getInt(0) }))
                File(context.filesDir,"restore-proof-connection.json").writeText(report.toString(2))
            }
            if(at==phase) {
            File(context.filesDir,"restore-proof-killed-at").writeText(at)
            android.os.Process.killProcess(android.os.Process.myPid())
        } }
        File(context.filesDir,"restore-proof-source.zip").inputStream().use { input ->
            LedgerArchive.read(input,context.cacheDir).use { LedgerArchive.restoreDetailed(ledger,it) }
        }
        error("kill point was not reached: $phase")
    }
    @Test fun reopenAndRepeatMerge() {
        val (ledger,tasks,media)=target()
        try {
            val committed=InstrumentationRegistry.getArguments().getString("phase")=="committed"
            assertEquals(if(committed)1 else 0,ledger.all().size)
            assertEquals(if(committed)1 else 0,tasks.pendingQueued().size)
            assertEquals(if(committed)1 else 0,ledger.accounts().size)
            if(!committed)assertEquals(0L,media.stats().getLong("count"))
            repeat(2) { File(context.filesDir,"restore-proof-source.zip").inputStream().use { input ->
                LedgerArchive.read(input,context.cacheDir).use { LedgerArchive.restoreDetailed(ledger,it) }
            } }
            val key=File(context.filesDir,"restore-proof-source-key").readText()
            assertEquals(8800L,ledger.accounts().single().amountCents)
            val task=tasks.pendingQueued().single().first
            assertEquals(key,tasks.operationKey(task));assertNotNull(tasks.image(task))
            assertEquals(1,ledger.all().size);assertNotNull(ledger.screenshot(ledger.all().single().id))
            assertTrue(RecognitionCommitRecovery.recover(tasks,ledger,task))
            assertEquals("SUCCEEDED",tasks.status(task))
            assertEquals(8800L,ledger.accounts().single().amountCents)
        } finally { tasks.close();ledger.close();media.close() }
    }
}
