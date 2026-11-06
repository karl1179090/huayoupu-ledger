package net.ankio.ledger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.Source
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerStore
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShakeDetailTest {
    @get:org.junit.Rule val wallet=CaptureWalletFixture()
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun shakeImageSentToModelIsTheImageReopenedWithFilledFields() = runBlocking {

        val merchant = "截图咖啡-${UUID.randomUUID()}"
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        var uploaded: ByteArray? = null
        var id: Long? = null
        val processor = CaptureProcessor(app, OcrClient(app, SyntheticRecognitionBackend { _, _, payload, guard ->
            assertTrue(guard())
            val content = payload!!.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            uploaded = Base64.decode(content.getJSONObject(1).getJSONObject("image_url").getString("url").substringAfter(','), Base64.NO_WRAP)
            val decoded = BitmapFactory.decodeByteArray(uploaded, 0, uploaded!!.size)
            try { assertEquals(120, decoded.width); assertEquals(240, decoded.height); assertTrue(Color.green(decoded.getPixel(20, 20)) > 240) }
            finally { decoded.recycle() }
            val bill = JSONObject().put("is_bill", true).put("amount", 28.5).put("direction", "expense")
                .put("merchant", merchant).put("category", "餐饮").put("occurred_at", JSONObject.NULL)
                .put("raw_text", "微信支付成功 商户 $merchant 金额 28.50元").put("reliable", false)
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", bill.toString()))))
        }))
        try {
            val saved = processor.process(bitmap, Source.OCR, "com.tencent.mm") { true } as IngestResult.Saved
            id = saved.record.id
            assertArrayEquals(uploaded, app.store.screenshot(id))
            // Reopen the database, then use a fresh page: this is not an in-memory import preview.
            val reopened = LedgerStore(app)
            try { assertArrayEquals(uploaded, reopened.screenshot(id)) } finally { reopened.close() }
            val rowAttribute=if(saved.record.state==RecordState.POSTED)"data-record" else "data-pending"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web = WebUiProbe(scenario); web.ready()
                web.run("refresh()")
                web.await("document.querySelector('[$rowAttribute=\"$id\"]')")
                web.run("document.querySelector('[$rowAttribute=\"$id\"]').click()")
                web.await("document.querySelector('#merchant').value===${JSONObject.quote(merchant)} && document.querySelector('#amount').value==='28.5' && document.querySelector('#category').textContent==='餐饮'")
                val thumbnail="(()=>{const i=document.querySelector('#capture-gallery .history-image img');return !document.querySelector('#capture-gallery').hidden&&i&&i.complete&&i.naturalWidth===120&&i.naturalHeight===240&&i.src.endsWith('/record-history/$id/1.jpg')})()"
                web.await(thumbnail)
                web.run("go('ledger');document.querySelector('[$rowAttribute=\"$id\"]').click()")
                web.await("$thumbnail && document.querySelector('#amount').value==='28.5'")
                scenario.recreate(); web.ready()
                web.await("$thumbnail && document.querySelector('#merchant').value===${JSONObject.quote(merchant)}")
                web.run("document.querySelector('#capture-gallery .history-image').click()")
                web.await("document.querySelector('#sheet .capture-full').naturalWidth===120 && document.querySelector('#sheet .capture-full').naturalHeight===240 && document.querySelector('#sheet .raw-text').textContent===${JSONObject.quote(saved.record.capture.text)}")
                web.run("closeSheet(false);document.querySelector('#raw-text-button').click()")
                web.await("document.querySelector('#sheet .raw-text').textContent===${JSONObject.quote(saved.record.capture.text)}")
            }
            app.store.delete(id)
            assertNull(app.store.screenshot(id))
        } finally { id?.let { app.store.delete(it) }; bitmap.recycle(); app.recognitionSession.invalidate(); app.changed() }
    }

    @Test fun nonBillResultStoresNeitherRecordNorScreenshot() = runBlocking {

        val before = app.store.all().map { it.uid }.toSet()
        val imagesBefore = app.store.screenshotIds()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val processor = CaptureProcessor(app, OcrClient(app, SyntheticRecognitionBackend { _, _, _, _ ->
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "{\"is_bill\":false,\"raw_text\":\"非账单\"}"))))
        }))
        try {
            assertEquals(IngestResult.Ignored, processor.process(bitmap, Source.OCR, "com.tencent.mm") { true })
            assertEquals(before, app.store.all().map { it.uid }.toSet())
            assertEquals(imagesBefore, app.store.screenshotIds())
        } finally { bitmap.recycle(); app.recognitionSession.invalidate() }
    }
}
