package net.ankio.ledger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionRecoveryOptimizationTest {
    @Test fun startupRecoveryFailsOldCapturesAndPreservesNewSessionCaptures() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="recovery-${System.nanoTime()}.db"
        val oldTasks=RecognitionTasks(context,name)
        val oldId=oldTasks.create(Source.OCR,"payment_auto:com.tencent.mm")
        oldTasks.close()
        val tasks=RecognitionTasks(context,name)
        val newId=tasks.create(Source.OCR,"payment_auto:com.tencent.mm")
        try {
            tasks.recoverInterrupted(preserveCurrentSession=true)
            assertEquals("FAILED",tasks.status(oldId))
            assertEquals("CAPTURING",tasks.status(newId))
            assertTrue(tasks.queued(newId,byteArrayOf(1,2,3)))
            tasks.recoverInterrupted(preserveCurrentSession=true)
            assertEquals("QUEUED",tasks.status(newId))
        } finally { tasks.delete(newId);tasks.close();context.deleteDatabase(name) }
    }

    @Test fun startupRecoveryBeforeAnyNewTaskStillRecoversOldCaptures() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="recovery-empty-${System.nanoTime()}.db"
        val oldTasks=RecognitionTasks(context,name)
        val oldId=oldTasks.create(Source.IMAGE,"image")
        oldTasks.close()
        val tasks=RecognitionTasks(context,name)
        try {
            tasks.recoverInterrupted(preserveCurrentSession=true)
            assertEquals("FAILED",tasks.status(oldId))
            val newId=tasks.create(Source.IMAGE,"image")
            assertEquals("CAPTURING",tasks.status(newId))
        } finally { tasks.close();context.deleteDatabase(name) }
    }
}
