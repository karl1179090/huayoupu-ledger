package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase

/** One Android transaction connection publishes ledger, task and media references together.
 * Callers drain asynchronous writers before entering; no coroutine suspension is permitted here.
 */
class RestoreOutcomeUncertainException(cause:Throwable):Exception(cause)

internal object AtomicBackupTransaction {
    internal var processProbe:((String)->Unit)?=null
    internal fun checkpoint(phase:String) { if(net.ankio.ledger.BuildConfig.DEBUG)processProbe?.invoke(phase) }
    fun create(db:SQLiteDatabase) { db.execSQL("CREATE TABLE backup_restore_runs(run_uid TEXT PRIMARY KEY NOT NULL,created_at INTEGER NOT NULL)") }

    fun <T> run(store:LedgerStore,action:(SQLiteDatabase,RecognitionTasks?)->T):T {
        val tasks=store.backupTasks();val media=store.backupScreenshots
        // Complete helper upgrades/onOpen before any attachment or outer transaction.
        store.writableDatabase;tasks?.writableDatabase;media.writableDatabase
        return synchronized(store) { synchronized(tasks ?: store) { synchronized(media) {
            val helpers=listOfNotNull(store,tasks,media)
            helpers.forEach { it.setWriteAheadLoggingEnabled(false) }
            val db=store.writableDatabase
            check(!db.inTransaction())
            val attached=mutableListOf<String>()
            var committed=false
            val run=java.util.UUID.randomUUID().toString()
            check(db.path!=":memory:")
            try {
                if(tasks!=null) { db.execSQL("ATTACH DATABASE ? AS recognition",arrayOf(tasks.writableDatabase.path));attached+="recognition" }
                db.execSQL("ATTACH DATABASE ? AS media",arrayOf(media.writableDatabase.path));attached+="media"
                for(schema in listOf("main")+attached) {
                    val mode=db.rawQuery("PRAGMA $schema.journal_mode",null).use { it.moveToFirst();it.getString(0).lowercase() }
                    check(mode in setOf("delete","truncate","persist")) { "恢复数据库日志模式无效" }
                    db.execSQL("PRAGMA $schema.synchronous=FULL")
                    check(db.rawQuery("PRAGMA $schema.synchronous",null).use { it.moveToFirst();it.getInt(0)==2 })
                }
                checkpoint("prepared")
                db.beginTransaction()
                val result:T
                try {
                    result=media.withRestoreDatabase(db) { action(db,tasks) }
                    db.execSQL("INSERT INTO backup_restore_runs(run_uid,created_at) VALUES(?,?)",arrayOf(run,System.currentTimeMillis()))
                    checkpoint("beforeCommit")
                    db.setTransactionSuccessful()
                } catch(error:Throwable) {
                    try { db.endTransaction() } catch(end:Throwable) { error.addSuppressed(end) }
                    throw error
                }
                try { db.endTransaction();checkpoint("afterEndTransaction") }
                catch(error:Throwable) {
                    val outcome=runCatching { check(!db.inTransaction());db.rawQuery("SELECT 1 FROM backup_restore_runs WHERE run_uid=?",arrayOf(run)).use { it.moveToFirst() } }.getOrNull()
                    if(outcome!=true) { if(outcome==false)throw error else throw RestoreOutcomeUncertainException(error) }
                }

                committed=true
                runCatching { checkpoint("committed") }
                result
            } finally {
                // Commit is complete before detach. Cleanup cannot turn success into a false rollback report.
                for(schema in attached.asReversed()) {
                    runCatching { db.execSQL("DETACH DATABASE $schema") }.onFailure {
                        android.util.Log.e("LedgerBackup",if(committed)"Restore committed; closing attachment after detach failure" else "Closing failed restore attachment",it)
                        runCatching { store.close() }
                    }
                }
            }
        } } }
    }
}
