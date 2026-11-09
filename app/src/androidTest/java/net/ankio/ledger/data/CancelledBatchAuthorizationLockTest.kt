package net.ankio.ledger.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise production cancellation/recovery while another commit owns the channels monitor. */
class CancelledBatchAuthorizationLockTest:CancelledBatchAuthorizationScenario() {
    private fun completesWithoutChannelsMonitor(action:()->Unit) {
        val entered=CountDownLatch(1);val completed=CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        val worker=Thread({
            entered.countDown()
            try { action() } catch(error:Throwable) { failure.set(error) } finally { completed.countDown() }
        },"synthetic-cancel-reservation-lock")
        var completedWhileMonitorHeld=false;var blockedState:Thread.State?=null
        try {
            synchronized(app.channels) {
                worker.start();assertTrue("Worker must start",entered.await(5,TimeUnit.SECONDS))
                completedWhileMonitorHeld=completed.await(2,TimeUnit.SECONDS);blockedState=worker.state
            }
        } finally {
            // Release channels before joining even on RED; never leave a real Java monitor deadlock.
            worker.join(10000)
        }
        assertFalse("Worker must finish after the monitor is released",worker.isAlive)
        assertNull("Production cancellation/recovery failed",failure.get())
        assertTrue("Production cancellation/recovery waited for channels; worker state=$blockedState",completedWhileMonitorHeld)
    }
    @Test fun cancellationAndStartupReconciliationCompleteWhileAnotherCommitOwnsChannels()=runBlocking {
        val s=partial()
        try {
            assertTrue(app.tasks.update(s.task,"QUEUED","合成并发取消"))
            completesWithoutChannelsMonitor { assertTrue(app.tasks.update(s.task,"CANCELLED","合成并发取消")) }
            completesWithoutChannelsMonitor { app.tasks.recoverInterrupted() }
            assertEquals("CANCELLED",app.tasks.status(s.task))
            assertEquals(1L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            unchangedFinancialFacts(s)
        } finally { cleanup(s) }
    }
}
