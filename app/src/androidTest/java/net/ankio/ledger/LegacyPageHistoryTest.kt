package net.ankio.ledger

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LegacyPageHistoryTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun response():JSONObject {
        val bill=JSONObject().put("is_bill",true).put("amount","12.34").put("direction","expense").put("merchant","合成页面自动商户")
            .put("category","购物").put("transaction_id",UUID.randomUUID().toString()).put("occurred_at","2026-10-17 02:32:51")
            .put("raw_text","支付成功 12.34元 合成测试商户").put("reliable",true)
        return JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
    }
    @Test fun incompleteLegacyPageResultStillRequiresFieldsAndKeepsHistoricalSource() = runBlocking {

        val before=app.store.all().map { it.id }.toSet();val task=app.tasks.create(Source.OCR,"page_auto")
        app.tasks.diagnose(task,"trigger","page_auto; synthetic-test")
        val image=Bitmap.createBitmap(128,256,Bitmap.Config.ARGB_8888)
        try {
            val processor=CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,guard->assertTrue(guard());response() }))
            val saved=processor.process(image,Source.OCR,"com.tencent.mm",task) { true } as IngestResult.Saved
            assertEquals(RecordState.REVIEW,saved.record.state);assertEquals("REVIEW",app.tasks.status(task));assertNotNull(app.tasks.image(task))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                // This historical fixture has a fixed transaction date, which need not
                // fall in the device's current month used by the first paged snapshot.
                web.run("month=localMonth(${saved.record.bill.occurredAt});filter='expense';viewKey='';refresh()")
                web.await("document.querySelector('[data-pending=\"${saved.record.id}\"]')?.textContent.includes('自动记账')")
                assertEquals("page",web.text("records.find(r=>r.id===${saved.record.id}).captureMode"))
            }
        } finally { image.recycle();app.tasks.delete(task);app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) };app.recognitionSession.invalidate();app.changed() }
    }
    @Test fun pageSwitchAfterModelResponseCancelsBeforeSavingFinancialRecord() = runBlocking {

        val before=app.store.all().map { it.uid }.toSet();val task=app.tasks.create(Source.OCR,"page_auto")
        val image=Bitmap.createBitmap(128,256,Bitmap.Config.ARGB_8888)
        try {
            val processor=CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,_->response() }))
            val failure=runCatching { processor.process(image,Source.OCR,"com.tencent.mm",task,beforeAccept={ throw CancellationException("合成切页") }) { true } }.exceptionOrNull()
            assertTrue(failure is CancellationException);assertEquals("CANCELLED",app.tasks.status(task));assertEquals(before,app.store.all().map { it.uid }.toSet())
        } finally { image.recycle();app.tasks.delete(task);app.recognitionSession.invalidate();app.changed() }
    }
    @Test fun failedAutomaticTaskKeepsItsSourceWhenOpenedInEditor() {
        val task=app.tasks.create(Source.OCR,"page_auto");app.tasks.update(task,"FAILED","合成识别失败")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.await("document.querySelector('[data-task=\"$task\"]')?.textContent.includes('自动记账')")
            web.run("document.querySelector('[data-task=\"$task\"]').click()")
            web.await("document.querySelector('#record-panel').open && document.querySelector('#source-label').textContent==='交易自动识别'")
        } } finally { app.tasks.delete(task);app.changed() }
    }
}
