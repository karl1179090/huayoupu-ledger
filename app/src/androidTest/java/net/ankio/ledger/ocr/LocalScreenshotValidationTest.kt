package net.ankio.ledger.ocr

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit local evidence only; never package a user's screenshot in an APK or test fixture. */
@RunWith(AndroidJUnit4::class)
class LocalScreenshotValidationTest {
    @Test fun suppliedScreenshotRunsThroughCompressionOcrAndPostedPersistence()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("sample")
        assumeNotNull("Only run when a local screenshot is explicitly supplied",name)
        require(name!!.matches(Regex("[A-Za-z0-9_-]+\\.(png|jpg)")))
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val file=File(app.cacheDir,name)
        val image=requireNotNull(BitmapFactory.decodeFile(file.path))
        val before=app.store.all().map { it.id }.toSet()
        app.recognitionSession.invalidate()
        val origin=InstrumentationRegistry.getArguments().getString("origin")?:"com.taobao.taobao"
        val automatic=InstrumentationRegistry.getArguments().getString("automatic")=="true"
        val task=app.tasks.create(Source.OCR,"${if(automatic)"payment_auto" else "shake"}:$origin")
        if(automatic)app.tasks.diagnose(task,"capture_context","payment_success_result:${System.currentTimeMillis()}")
        try {
            val prepared=app.recognizer.prepareScreenshot(image)
            val decoded=requireNotNull(BitmapFactory.decodeByteArray(prepared,0,prepared.size))
            try {
                val ocr=LocalOcr(ApplicationProvider.getApplicationContext()).use { it.recognize(decoded) }
                val report=ocr.json().put("icons",LocalIconLocator.locate(decoded,ocr).json())
                File(app.cacheDir,"local-sample-evidence.json").writeText(report.toString())
            } finally { decoded.recycle() }
            val result=CaptureProcessor(app).processPrepared(prepared,Source.OCR,origin,task) { true }
            if(InstrumentationRegistry.getArguments().getString("expectSkip")=="true") {
                assertTrue("A refund arrival must be skipped",result is IngestResult.Skipped)
                assertEquals(before,app.store.all().map { it.id }.toSet())
                assertEquals("SKIPPED",app.tasks.status(task));return@runBlocking
            }
            assertTrue("Expected a posted local bill, result=${result.javaClass.simpleName}; task=${app.tasks.status(task)}",result is IngestResult.Saved)
            val record=(result as IngestResult.Saved).record
            assertEquals(RecordState.POSTED,record.state)
            assertNotNull(record.bill.amountCents)
            InstrumentationRegistry.getArguments().getString("expectedTransactionId")?.let { expected ->
                assertEquals("Order identity from the supplied receipt",expected,record.bill.transactionId)
            }
            InstrumentationRegistry.getArguments().getString("expectedCents")?.toLong()?.let { expected ->
                assertEquals("Actual price from the supplied receipt",expected,record.bill.amountCents)
            }
            if(record.bill.merchant.isBlank())assertNull(record.bill.receipt!!.merchant)
            InstrumentationRegistry.getArguments().getString("expectedMerchant")?.let { expected ->
                assertEquals("Merchant from the supplied receipt",expected,record.bill.merchant)
            }
            assertArrayEquals(prepared,app.store.screenshot(record.id))
            if(InstrumentationRegistry.getArguments().getString("expectIcon")=="true")
                assertNotNull("The explicitly supplied merchant image must be extracted",app.store.merchantIcon(record.id))
            InstrumentationRegistry.getArguments().getString("repeatSample")?.let { repeatName ->
                require(repeatName.matches(Regex("[A-Za-z0-9_-]+\\.(png|jpg)")))
                val nextFile=File(app.cacheDir,repeatName)
                val nextTask=app.tasks.create(Source.OCR,"shake:$origin")
                try {
                    val repeat=CaptureProcessor(app).processPrepared(nextFile.readBytes(),Source.OCR,origin,nextTask) { true }
                    assertTrue("Repeated order must be saved as evidence or deduplicated",repeat is IngestResult.Saved || repeat==IngestResult.DuplicateSkipped)
                    if(repeat is IngestResult.Saved)assertEquals(record.id,repeat.record.id)
                    assertEquals("Repeated order must not create another record",before+record.id,app.store.all().map { it.id }.toSet())
                    assertEquals("Both real screenshots remain on the same bill",2,app.store.captureHistory(record.id).size)
                    assertEquals("已记账 ¥${net.ankio.ledger.core.Money.format(record.bill.amountCents!!)}",app.tasks.snapshot().let { rows ->
                        (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getLong("id")==nextTask }.getString("message")
                    })
                } finally { app.tasks.delete(nextTask);nextFile.delete() }
            }
        } finally {
            image.recycle();file.delete();app.tasks.delete(task)
            app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
            app.recognitionSession.invalidate();app.changed()
        }
    }
}
