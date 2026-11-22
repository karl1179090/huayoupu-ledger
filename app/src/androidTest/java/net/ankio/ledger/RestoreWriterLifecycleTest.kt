package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.UUID

class RestoreWriterLifecycleTest {
    @Test fun restoreWaitsForCompletionCallbackEvenAfterCoroutineBodyHasFinished()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();app.taskRecovery.await()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        app.recognitionWork.launch(onCompletion={ entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) }) { }
        check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
        var action=false
        val restoring=async(Dispatchers.IO) { app.restoreQuiescent { action=true } }
        withTimeout(5000) { while(app.recognitionWork.accepting)delay(5) }
        delay(50);assertFalse(action)
        release.countDown();restoring.await();assertTrue(action);assertTrue(app.recognitionWork.accepting)
    }
    @Test fun cancellingRestoreWhileDrainingStillReopensAdmissionAfterWriterFinishes()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();app.taskRecovery.await()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        app.recognitionWork.launch { try { started.complete(Unit);awaitCancellation() } finally { withContext(NonCancellable) { release.await() } } }
        started.await();var action=false
        val restoring=launch(Dispatchers.IO) { app.restoreQuiescent { action=true } }
        withTimeout(5000) { while(app.recognitionWork.accepting)delay(5) }
        restoring.cancel();assertFalse(app.recognitionWork.accepting)
        release.complete(Unit);restoring.join()
        assertTrue(app.recognitionWork.accepting);assertFalse(action)
    }
    @Test fun restoreWaitsForCancelledWriterAndItsCompletionBeforeOpeningDatabaseBoundary()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();app.taskRecovery.await()
        val started=CompletableDeferred<Unit>();val drained=CompletableDeferred<Unit>();var completed=false
        val writer=app.recognitionWork.launch(onCompletion={ completed=true }) {
            try { started.complete(Unit);awaitCancellation() }
            finally { withContext(NonCancellable) { delay(80);drained.complete(Unit) } }
        }
        started.await()
        val before=app.tasks.snapshot().length()
        app.restoreQuiescent {
            assertTrue(drained.isCompleted);assertTrue(completed);assertFalse(app.recognitionWork.accepting)
            app.importer.start(android.net.Uri.parse("content://invalid/synthetic"))
            assertEquals(before,app.tasks.snapshot().length())
            assertFalse(app.channels.automaticEnabled());assertFalse(app.channels.enabled(net.ankio.ledger.capture.ChannelSettings.Channel.OCR))
        }
        assertTrue(app.recognitionWork.accepting)
    }

    @Test fun configurationBecomingTooLargeAfterPreflightPreservesQueueAndOriginalGrant()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();app.taskRecovery.await()
        assumeFalse(app.channels.permitted(ChannelSettings.Channel.OCR))
        val prefix="restore-writer-${UUID.randomUUID()}";val database="$prefix.db"
        val pkg=PaymentApps.WECHAT
        val oldSelected=pkg in app.channels.selectedAutoPackages();val oldEnabled=app.channels.automaticEnabled()
        val pauseState=app.getSharedPreferences("backup_capture_state",android.content.Context.MODE_PRIVATE)
        val image=syntheticBackupImage(191)
        val draft=ClassificationRule("","合成恢复并发$prefix",true,Direction.EXPENSE,"cat:expense:other",0,
            listOf(ClassificationCondition(value=prefix)))
        val initial=app.store.saveClassificationRule(draft)
        var concurrent:ClassificationRule?=null;var task:Long?=null
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val preflight=CompletableDeferred<Unit>()
        try { LedgerStore(app,database).use { source ->
            val seed=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            val current=app.store.classificationRules()
            val incoming=List(2000-current.customRules.size) { index ->
                draft.copy(id="incoming-$index-$prefix",name="备份并发规则$index",order=index)
            }
            LedgerBackup.restore(source,seed.copy(classification=ClassificationBackup(current.copy(customRules=incoming,builtinRules=emptyList()),emptyList())))
            val backup=LedgerBackup.encode(source,app.channels)
            app.channels.setAutoPackage(pkg,true);app.channels.setAutomaticEnabled(true)
            val generation=app.channels.automaticGeneration(pkg)
            val jobId=app.tasks.create(Source.OCR,"payment_auto:$pkg",automaticGeneration=generation).also { task=it }
            assertTrue(app.tasks.queued(jobId,image))
            AtomicBackupTransaction.run(app.store,pauseCaptureOnCommit=true) { _,_ -> Unit }
            assertTrue(pauseState.edit().putBoolean("check_commit",true).commit())
            app.recognitionWork.launch { entered.complete(Unit);finish.await() }
            entered.await()
            AtomicBackupTransaction.processProbe={ if(it=="classificationPreflight")preflight.complete(Unit) }
            val restoring=async(Dispatchers.IO) { runCatching { app.restoreCompleteBackup(prepare={ LedgerArchive.read(backup.byteInputStream(),app.cacheDir) }) } }
            try {
                withTimeout(10000) { preflight.await() }
                assertFalse(app.recognitionWork.accepting);assertFalse(restoring.isCompleted)
                concurrent=app.store.saveClassificationRule(draft.copy(name="晚到的规则$prefix"))
            } finally { finish.complete(Unit) }
            val error=withTimeout(10000) { restoring.await() }.exceptionOrNull()
            assertTrue(error is ClassificationCapacityException)
            assertTrue(app.recognitionWork.accepting)
            assertTrue(app.channels.autoAllowed(pkg));assertEquals(generation,app.channels.automaticGeneration(pkg))
            assertEquals("QUEUED",app.tasks.status(jobId));assertArrayEquals(image,app.tasks.image(jobId))
            assertTrue(AtomicBackupTransaction.capturePausePending(app.store));assertTrue(pauseState.getBoolean("check_commit",false))
            assertTrue(app.store.classificationRules().customRules.none { it.id.startsWith("incoming-") && it.id.endsWith(prefix) })
        } } finally {
            AtomicBackupTransaction.processProbe=null
            finish.complete(Unit)
            AtomicBackupTransaction.clearCapturePause(app.store)
            pauseState.edit().remove("check_commit").commit()
            task?.let(app.tasks::delete)
            concurrent?.let { app.store.deleteClassificationRule(it.id) }
            app.store.deleteClassificationRule(initial.id)
            app.channels.setAutoPackage(pkg,oldSelected);app.channels.setAutomaticEnabled(oldEnabled)
            app.deleteDatabase(database)
        }
    }
}
