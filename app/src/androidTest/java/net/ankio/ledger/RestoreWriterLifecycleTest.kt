package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

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
}
