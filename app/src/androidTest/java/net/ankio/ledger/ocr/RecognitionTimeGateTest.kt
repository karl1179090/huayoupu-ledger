package net.ankio.ledger.ocr

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.RecognitionTimeContext
import net.ankio.ledger.core.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecognitionTimeGateTest {
    @Test fun queuedEngineWaitDoesNotFreezeTimeUntilActualRecognitionBegins() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();app.taskRecovery.await()
        val task=app.tasks.create(Source.IMAGE,"image")
        val bitmap=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)
        val invalidPixels=try { ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))
            out.toByteArray().also { bytes ->
                val idat=bytes.indices.first { it+4<bytes.size && bytes.copyOfRange(it,it+4).contentEquals(byteArrayOf(73,68,65,84)) }
                bytes[idat+4]=0
            }
        } } finally { bitmap.recycle() }
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val prepared=CountDownLatch(1)
        val holder=launch(Dispatchers.IO) { LocalInferenceGate.shared.run { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) } }
        var clock=RecognitionTimeContext(Instant.parse("2026-12-31T15:59:00Z").toEpochMilli(),"Asia/Shanghai")
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val waiting=async(Dispatchers.IO) { runCatching {
                LocalRecognitionClient(app).recognizePrepared(invalidPixels,onPrepared={ app.tasks.prepared(task,it);prepared.countDown() },
                    recognitionTime={app.tasks.beginRecognition(task) { clock }})
            } }
            assertTrue(prepared.await(5,TimeUnit.SECONDS))
            assertNull(app.tasks.recognitionTime(task))
            clock=RecognitionTimeContext(Instant.parse("2026-12-31T16:01:00Z").toEpochMilli(),"Asia/Shanghai")
            release.countDown()
            assertTrue(waiting.await().exceptionOrNull() is RecognitionException)
            assertEquals(clock,app.tasks.recognitionTime(task))
            assertEquals("2027-01-01",app.tasks.recognitionTime(task)!!.recognitionDate.toString())
        } finally { release.countDown();holder.join();app.tasks.delete(task) }
    }
}
