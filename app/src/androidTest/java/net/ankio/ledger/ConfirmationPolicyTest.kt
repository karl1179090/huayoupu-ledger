package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConfirmationPolicyTest {
    @get:org.junit.Rule val wallet = CaptureWalletFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun reopeningNeverPromotesHistoricalRemindersWithoutUserConfirmation() {
        val name="reminder-upgrade-${UUID.randomUUID()}.db"
        val store=net.ankio.ledger.data.LedgerStore(app,name)
        val now=System.currentTimeMillis()
        val old=ParsedBill(1,Direction.EXPENSE,"未知商户","其他",now,null,false,"交易提醒缺少商户信息，请核对后确认",false)
        val capture=Capture(Source.NOTIFICATION,"com.eg.android.AlipayGphone","交易提醒\n你有一笔0.01元的支出，点此查看详情。",now)
        try {
            val release=(store.ingest(capture,old) as IngestResult.Saved).record
            val duplicate=(store.ingest(capture,old) as IngestResult.Saved).record
            val incomplete=(store.ingest(capture.copy(text="交易提醒\n你有一笔0.02元的支出，点此查看详情。"),old.copy(amountCents=2,reason="交易时间无效，请核对")) as IngestResult.Saved).record
            val income=(store.ingest(capture.copy(text="交易提醒\n你有一笔20.00元的收入，点此查看详情。"),old.copy(amountCents=2000,direction=Direction.INCOME,category="其他收入")) as IngestResult.Saved).record
            store.close()
            net.ankio.ledger.data.LedgerStore(app,name).use { reopened ->
                assertEquals(RecordState.REVIEW,reopened.get(release.id)!!.state)
                assertEquals("未知商户",reopened.get(release.id)!!.bill.merchant)
                assertEquals(RecordState.REVIEW,reopened.get(duplicate.id)!!.state)
                assertEquals(RecordState.REVIEW,reopened.get(incomplete.id)!!.state)
                assertEquals(RecordState.REVIEW,reopened.get(income.id)!!.state)
                assertEquals("未知商户",reopened.get(income.id)!!.bill.merchant)
                assertEquals(4,reopened.all().size)
            }
        } finally { store.close();app.deleteDatabase(name) }
    }

    @Test fun reliableRemoteResultsPostWithoutConfirmation()=runBlocking {
        val ids=mutableListOf<Long>()
        try {
            for(source in listOf(Source.OCR,Source.IMAGE)) {
                val now=System.currentTimeMillis()
                val bill=ParsedBill(3299,Direction.EXPENSE,"确认测试-${UUID.randomUUID()}","餐饮",now,UUID.randomUUID().toString(),true,"",true)
                val saved=app.coordinator.acceptRecognition(Capture(source,"com.tencent.mm","支付成功 32.99元"),bill,allowed={true}) as IngestResult.Saved
                ids+=saved.record.id
                assertTrue("Model confidence must remain available",saved.record.bill.reliable)
                assertEquals("Validated AI bills should post directly",RecordState.POSTED,saved.record.state)
            }
        } finally { ids.forEach(app.store::delete);app.changed() }
    }

    @Test fun completedTransfersAndAmountEvidencePostButPendingOrNoAmountCannot()=runBlocking {
        val ids=mutableListOf<Long>()
        try {
            for(source in listOf(Source.OCR,Source.IMAGE)) {
                val merchant="合成转账对象-${java.util.UUID.randomUUID()}"
                val json=JSONObject().put("is_bill",true).put("amount","0.01").put("direction","expense")
                    .put("merchant",merchant).put("category","其他").put("occurred_at","2026-10-17 02:32:51")
                    .put("raw_text","转账成功 -0.01 $merchant 转账时间 2026年10月17日 02:32:51").put("reliable",false)
                val bill=net.ankio.ledger.ocr.SyntheticRecognition.parseRecognition(json.toString(),1_792_253_600_000L).bill!!
                val saved=app.coordinator.acceptRecognition(Capture(source,"com.tencent.mm",bill.merchant),bill,allowed={true}) as IngestResult.Saved
                ids+=saved.record.id;assertEquals(RecordState.POSTED,saved.record.state)
            }
            for(raw in listOf("等待对方收款 -0.01 合成转账对象 转账时间 2026年10月17日 02:32:51","转账成功 合成转账对象")) {
                val json=JSONObject().put("is_bill",true).put("amount","0.01").put("direction","expense")
                    .put("merchant","合成转账对象").put("category","其他").put("occurred_at","2026-10-17 02:32:51").put("raw_text",raw).put("reliable",true)
                assertFalse(net.ankio.ledger.ocr.SyntheticRecognition.parseRecognition(json.toString(),1_792_253_600_000L).bill!!.reliable)
            }
            val amountOnly=JSONObject().put("is_bill",true).put("amount","0.01").put("direction","expense")
                .put("merchant","合成转账对象").put("raw_text","付款 -0.01 合成转账对象").put("reliable",true)
            assertTrue(net.ankio.ledger.ocr.SyntheticRecognition.parseRecognition(amountOnly.toString(),1_792_253_600_000L).bill!!.reliable)
        } finally { ids.forEach(app.store::delete);app.changed() }
    }
    @Test fun successfulWeChatAlipayAndImportTasksAppearAsPostedAndSkipExactReplay()=runBlocking {

        val ids=mutableListOf<Long>();val tasks=mutableListOf<Long>();val before=app.store.all().map { it.id }.toSet()
        val image=android.graphics.Bitmap.createBitmap(32,64,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            for((source,origin) in listOf(Source.OCR to "com.tencent.mm",Source.OCR to "com.eg.android.AlipayGphone",Source.IMAGE to "image")) {
                val merchant="直接入账-${UUID.randomUUID()}";val tx=UUID.randomUUID().toString()
                val bill=JSONObject().put("is_bill",true).put("amount","0.01").put("direction","expense").put("merchant",merchant)
                    .put("category","其他").put("occurred_at","2026-10-17 02:32:51").put("transaction_id",tx).put("reliable",true)
                    .put("raw_text","转账成功 -0.01 $merchant 2026年10月17日 02:32:51 交易单号 $tx")
                val client=net.ankio.ledger.ocr.OcrClient(app,net.ankio.ledger.ocr.SyntheticRecognitionBackend { _,_,_,_ ->
                    JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
                })
                val processor=net.ankio.ledger.capture.CaptureProcessor(app,client)
                val task=app.tasks.create(source,if(source==Source.IMAGE)"image" else "shake");tasks+=task
                val result=processor.process(image,source,origin,task) { true } as IngestResult.Saved;ids+=result.record.id
                assertEquals(RecordState.POSTED,result.record.state);assertEquals("SUCCEEDED",app.tasks.status(task))
                val attempt=app.tasks.snapshot().let { rows -> (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id")==task } }
                val decision=JSONObject(attempt.getJSONObject("diagnostic").getString("decision"))
                assertEquals("POSTED",decision.getString("state"));assertTrue(decision.getBoolean("hasAmount"))
                assertFalse(decision.toString().contains(merchant));assertFalse(decision.getBoolean("possibleDuplicate"))
                assertNotNull(app.store.screenshot(result.record.id))
                val replay=processor.process(image,source,origin) { true }
                if(source==Source.IMAGE) {
                    assertTrue(replay is IngestResult.Saved)
                    assertEquals(RecordState.REVIEW,(replay as IngestResult.Saved).record.state)
                } else assertEquals(IngestResult.DuplicateSkipped,replay)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("month='2026-10';filter='expense';refresh()")
                for(id in ids) {
                    web.await("!!document.querySelector('[data-record=\"$id\"]')")
                    assertEquals("true",web.value("!document.querySelector('[data-pending=\"$id\"]')"))
                }
            }
        } finally { image.recycle();tasks.forEach(app.tasks::delete);app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) };app.recognitionSession.invalidate();app.changed() }
    }
    @Test fun postedDetailsCanEditButCannotReplaceTheCapture() {
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.OCR,"com.tencent.mm","测试 ${UUID.randomUUID()}",now),
            ParsedBill(2188,Direction.EXPENSE,"可编辑-${UUID.randomUUID()}","餐饮",now,null,true,"",true)) as IngestResult.Saved).record
        val image=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream().use { out -> image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
        image.recycle();app.store.attachScreenshot(record.id,bytes)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("openRecord(${record.id})")
                assertEquals("false",web.value("document.querySelector('#amount').disabled"))
                assertEquals("保存修改",web.text("document.querySelector('#import-form .primary').textContent"))
                assertEquals("true",web.value("document.querySelector('.scanner').hidden && !document.querySelector('#capture-gallery').hidden"))
                val input=JSONObject().put("id",record.id).put("amount","1.00").put("direction","INCOME")
                    .put("merchant","被修改").put("category","其他").put("occurredAt",now).put("note","").put("submissionToken",UUID.randomUUID().toString()).put("expectedRevision",app.store.get(record.id)!!.wallet!!.revision)
                lateinit var bridge:LedgerWebBridge
                scenario.onActivity { bridge=LedgerWebBridge(it) }
                assertTrue(JSONObject(bridge.save(input.toString())).getBoolean("ok"))
                assertEquals(record.uid,app.store.get(record.id)!!.uid)
                assertEquals(100L,app.store.get(record.id)!!.bill.amountCents)
                assertArrayEquals(bytes,app.store.screenshot(record.id))
                web.await("document.querySelector('.history-image').getBoundingClientRect().height<=80")
                web.run("document.querySelector('.history-image').click()")
                web.await("document.querySelector('#sheet').open && document.querySelector('.capture-full').naturalWidth===24")
                web.run("window.LedgerUI.back()")
                web.await("!document.querySelector('#sheet').open && document.querySelector('#record-panel').open")
                assertArrayEquals(bytes,app.store.screenshot(record.id))
            }
        } finally { app.store.delete(record.id);app.changed() }
    }

    @Test fun currentMonthCurveDoesNotInventFutureDays() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("month='2026-10';drawChart([],new Date(2026,9,16,12))")
            assertFalse(web.text("document.querySelector('.chart svg').getAttribute('aria-label')").contains("10月31日"))
            assertTrue(web.text("document.querySelector('.chart svg').getAttribute('aria-label')").contains("10月16日"))
        }
    }

    @Test fun homepageHasExplicitTransactionTypeTabsAndPreservesAllWithoutHiddenSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            assertEquals("全部,支出,收入,转账",web.text("Array.from(document.querySelectorAll('[data-filter]')).map(b=>b.textContent.trim()).join(',')"))
            assertEquals("true",web.value("!!document.querySelector('#search-toggle') && document.querySelector('.search-box').hidden"))
            web.run("restoreState({page:'ledger',filter:'all',query:'old hidden filter'});refresh()")
            assertEquals("all",web.text("window.LedgerUI.getState().filter"))
            assertEquals("",web.text("query"))
        }
    }

    @Test fun bothCurvesStartAtZeroRiseWithoutAxesOrGrid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("month='2037-02';filter='expense';drawChart([{state:'POSTED',direction:'EXPENSE',amountCents:5188,occurredAt:new Date(2037,1,3).getTime()}],new Date(2038,0,1))")
            assertEquals("[0,0,0,5188]",web.text("JSON.stringify(JSON.parse(document.querySelector('.chart svg').dataset.dailyCents).slice(0,4))"))
            assertEquals("true",web.value("!document.querySelector('.y-axis,.chart .axis,.chart line,.chart-grid')"))
            assertEquals("true",web.value("Number(document.querySelector('.chart svg').dataset.maxCents)>=5188"))
            web.run("filter='income';drawChart([],new Date(2038,0,1))")
            assertEquals("true",web.value("JSON.parse(document.querySelector('.chart svg').dataset.dailyCents).every(v=>v===0)"))
            assertFalse(web.text("document.querySelector('.chart svg').getAttribute('aria-label')").contains("结余"))
        }
    }

    @Test fun dailyCurveReconcilesMixedBillsEditsDeletionAndDirectionFilters() {
        val ids=mutableListOf<Long>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                lateinit var bridge:LedgerWebBridge;scenario.onActivity { bridge=LedgerWebBridge(it) }
                val prefix="曲线-${UUID.randomUUID()}"
                fun input(day:Int,amount:String,direction:String,id:Long=0):JSONObject=JSONObject().put("id",id).put("submissionToken",UUID.randomUUID().toString()).put("expectedRevision",app.store.get(id)?.wallet?.revision ?: 0)
                    .put("amount",amount).put("direction",direction).put("merchant",prefix).put("category","其他").put("note","")
                    .put("occurredAt",java.time.LocalDate.of(2037,2,day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                for(data in listOf(input(1,"100.00","INCOME"),input(3,"25.00","EXPENSE"),input(3,"12.00","EXPENSE"))) {
                    ids+=JSONObject(bridge.save(data.toString())).getLong("id")
                }
                val pending=(app.store.ingest(Capture(Source.IMAGE,"image",prefix),ParsedBill(99999,Direction.EXPENSE,prefix,"其他",
                    input(2,"1","EXPENSE").getLong("occurredAt"),null,false,"待核对")) as IngestResult.Saved).record
                ids+=pending.id
                web.run("month='2037-02';filter='expense';refresh()")
                assertEquals("[0,0,0,3700]",web.text("document.querySelector('.chart svg').dataset.dailyCents"))
                assertTrue(JSONObject(bridge.save(input(3,"18.00","EXPENSE",ids[2]).toString())).getBoolean("ok"))
                web.run("refresh()")
                assertEquals("4300",web.text("document.querySelector('.chart svg').dataset.endCents"))
                assertTrue(JSONObject(bridge.delete(ids[1])).getBoolean("ok"))
                web.run("refresh()")
                assertEquals("1800",web.text("document.querySelector('.chart svg').dataset.endCents"))
                web.run("document.querySelector('[data-filter=expense]').click()")
                assertEquals("1800",web.text("document.querySelector('.chart svg').dataset.endCents"))
                web.run("document.querySelector('[data-filter=income]').click()")
                assertEquals("10000",web.text("document.querySelector('.chart svg').dataset.endCents"))
            }
        } finally { ids.forEach(app.store::delete);app.changed() }
    }
}
