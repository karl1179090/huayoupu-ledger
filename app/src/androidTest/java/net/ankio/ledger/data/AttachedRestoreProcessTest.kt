package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Real process interruption probe, invoked in separate instrumentation processes. */
class AttachedRestoreProcessTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun opened():Triple<LedgerStore,RecognitionTasks,ScreenshotStore> {
        val media=ScreenshotStore(context,"attach-proof-media")
        val ledger=LedgerStore(context,"attach-proof-ledger.db",media)
        val tasks=RecognitionTasks(context,"attach-proof-tasks.db",media,ledger)
        // All production onOpen/upgrade work completes before attachment.
        if(InstrumentationRegistry.getArguments().getString("order")=="tasks") {
            tasks.writableDatabase;media.writableDatabase;ledger.writableDatabase
        } else { ledger.writableDatabase;tasks.writableDatabase;media.writableDatabase }
        return Triple(ledger,tasks,media)
    }
    @Test fun interrupt() {
        val (ledger,tasks,media)=opened()
        val helpers=listOf(ledger,tasks,media)
        helpers.forEach { it.setWriteAheadLoggingEnabled(true);it.writableDatabase }
        helpers.forEach { it.setWriteAheadLoggingEnabled(false) }
        val db=ledger.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS recognition",arrayOf(tasks.writableDatabase.path))
        db.execSQL("ATTACH DATABASE ? AS media",arrayOf(media.writableDatabase.path))
        val report=StringBuilder()
        for(schema in listOf("main","recognition","media")) {
            val mode=db.rawQuery("PRAGMA $schema.journal_mode",null).use { it.moveToFirst();it.getString(0) }
            assertTrue(mode in setOf("delete","truncate","persist"))
            db.execSQL("PRAGMA $schema.synchronous=FULL")
            val sync=db.rawQuery("PRAGMA $schema.synchronous",null).use { it.moveToFirst();it.getInt(0) }
            assertEquals(2,sync)
            report.append("$schema:$mode:$sync\n")
            db.execSQL("CREATE TABLE IF NOT EXISTS $schema.restore_probe(value INTEGER NOT NULL)")
            db.execSQL("DELETE FROM $schema.restore_probe")
            db.execSQL("INSERT INTO $schema.restore_probe VALUES(0)")
        }
        val phase=InstrumentationRegistry.getArguments().getString("phase") ?: "main"
        fun stop(at:String) { if(at==phase) {
            File(context.filesDir,"attach-proof.txt").writeText(report.toString()+"phase=$at\nversion="+SQLiteDatabase::class.java.name)
            android.os.Process.killProcess(android.os.Process.myPid())
            error("kill returned")
        } }
        db.beginTransaction()
        try {
            for(schema in listOf("main","recognition","media")) {
                assertTrue(db.inTransaction())
                db.execSQL("UPDATE $schema.restore_probe SET value=1")
                // A fresh rawQuery on the same SQLiteDatabase sees uncommitted writes.
                assertEquals(1,db.rawQuery("SELECT value FROM $schema.restore_probe",null).use { it.moveToFirst();it.getInt(0) })
                stop(schema)
            }
            db.setTransactionSuccessful();stop("successful")
        } finally { db.endTransaction() }
        stop("committed")
        error("unknown kill phase")
    }
    @Test fun reopen() {
        val (ledger,tasks,media)=opened()
        try {
            val expected=if(InstrumentationRegistry.getArguments().getString("phase")=="committed")1 else 0
            for(helper in listOf(ledger,tasks,media))assertEquals(expected,helper.readableDatabase.rawQuery("SELECT value FROM restore_probe",null).use { it.moveToFirst();it.getInt(0) })
        } finally { tasks.close();ledger.close();media.close() }
    }
}
