package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.RecognitionBackend
import net.ankio.ledger.ocr.RecognitionResult
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Synthetic OCR lines through real candidate submission, SQLite and public bridge reads. */
@RunWith(AndroidJUnit4::class)
class ReceiptEvidenceAcceptanceTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun shortMissingPeripheralAndInvalidReceiptsRemainTruthfulAtTheDetailBoundary() = runBlocking {
        app.taskRecovery.await()
        val tasks = mutableListOf<Long>()
        val ids = mutableListOf<Long>()
        val cases = listOf(
            listOf("支付成功", "付款金额：12.00元", "商户名称：合成"),
            listOf("支付成功", "付款金额：13.00元"),
            listOf("支付成功", "付款金额：14.00元", "商户名称：合成", "广告", "商品说明：火锅优惠券", "订单备注：地铁广告"),
            listOf("支付成功", "付款金额：15.00元", "付款金额：16.00元", "商品说明：早餐套餐"),
            listOf("待付款", "付款金额：17.00元", "商品说明：早餐套餐"),
        )
        try {
            for ((index, fields) in cases.withIndex()) {
                val task = app.tasks.create(Source.IMAGE, "image"); tasks += task
                val lines = fields + "交易单号：SYNTHETIC-BOUNDARY-${UUID.randomUUID()}"
                val before = app.store.accounts().associate { it.uid to it.amountCents }
                val backend = RecognitionBackend { bytes, prepared, _, _, policy, origin ->
                    prepared(bytes)
                    RecognitionResult(lines.joinToString("\n"), LocalOcrBillParser.parse(lines.map(::LocalOcrLine),
                        0, 0, System.currentTimeMillis(), policy=policy, origin=origin))
                }
                val result = CaptureProcessor(app, OcrClient(app, backend)).processPrepared(
                    "synthetic-boundary-$index".toByteArray(), Source.IMAGE, "image", task) { true }
                if (index == 4) {
                    assertTrue(result == IngestResult.Ignored || result is IngestResult.Skipped)
                    assertEquals(before, app.store.accounts().associate { it.uid to it.amountCents })
                    continue
                }
                val saved = (result as IngestResult.Saved).record; ids += saved.id
                if (index == 3) {
                    assertEquals(RecordState.REVIEW, saved.state)
                    assertTrue(saved.walletEffects.isEmpty())
                    assertEquals(before, app.store.accounts().associate { it.uid to it.amountCents })
                } else assertEquals(RecordState.POSTED, saved.state)
            }
            withBridge { bridge -> ids.forEachIndexed { index, id ->
                val record = JSONObject(bridge.recordDetails(id)).getJSONObject("record")
                val evidence = record.getJSONObject("receipt")
                assertTrue(evidence.isNull("sourceApp"))
                assertTrue(evidence.isNull("serviceProvider"))
                assertTrue(evidence.isNull("receiptIssuer"))
                assertTrue(evidence.isNull("orderRemark"))
                if (index == 0 || index == 2) assertEquals("合成", evidence.getJSONObject("merchant").getString("value"))
                else { assertEquals("", record.getString("merchant")); assertTrue(evidence.isNull("merchant")) }
                if (index != 3) assertTrue(evidence.isNull("productDescription"))
                else assertEquals("早餐套餐", evidence.getJSONObject("productDescription").getString("value"))
            } }
        } finally {
            ids.forEach(app.store::delete)
            tasks.forEach { task ->
                app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
                app.tasks.delete(task)
            }
        }
    }

    @Test fun ocrCandidateKeepsReceiptAcrossReopenAndAllRecordReads() = runBlocking {
        app.taskRecovery.await()
        val task = app.tasks.create(Source.OCR, "shake:com.tencent.mm")
        val id = "SYNTHETIC-RECEIPT-${UUID.randomUUID()}"
        val lines = listOf("支付成功", "付款金额：26.90元", "商户名称：合成咖啡",
            "商品说明：早餐 套餐", "交易说明：到店消费", "订单备注：少糖",
            "服务商：合成扣费服务", "凭证来源：合成支付机构", "交易单号：$id")
        val backend = RecognitionBackend { bytes, prepared, _, _, policy, origin ->
            prepared(bytes)
            RecognitionResult(lines.joinToString("\n"), LocalOcrBillParser.parse(lines.map(::LocalOcrLine),
                0, 0, System.currentTimeMillis(), policy=policy, origin=origin))
        }
        var recordId = 0L
        try {
            val result = CaptureProcessor(app, OcrClient(app, backend)).processPrepared(
                "synthetic-receipt".toByteArray(), Source.OCR, "shake:com.tencent.mm", task) { true } as IngestResult.Saved
            recordId = result.record.id
            assertEquals(RecordState.POSTED, result.record.state)
            assertEquals("SUCCEEDED", app.tasks.status(task))
            LedgerStore(app).use { reopened ->
                val receipt = requireNotNull(reopened.get(recordId)!!.bill.receipt)
                assertEquals("早餐 套餐", receipt.productDescription!!.value)
                assertEquals("少糖", receipt.orderRemark!!.value)
            }
            withBridge { bridge ->
                val detail = JSONObject(bridge.recordDetails(recordId)).getJSONObject("record")
                checkFields(detail.getJSONObject("receipt"))
                val month = Instant.ofEpochMilli(result.record.bill.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString().take(7)
                for (response in listOf(bridge.snapshot(), bridge.snapshotFirst(month, "", "", ""))) {
                    val root = JSONObject(response)
                    assertTrue(root.toString(), root.getBoolean("ok"))
                    val rows = root.getJSONArray("records")
                    val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id") == recordId }
                    checkFields(row.getJSONObject("receipt"))
                    assertFalse(row.has("rawText"))
                }
            }
        } finally {
            if (recordId > 0) app.store.delete(recordId)
            app.tasks.operationKey(task)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
            app.tasks.delete(task)
        }
    }

    private fun checkFields(receipt: JSONObject) {
        assertEquals(1, receipt.getInt("version"))
        assertEquals("single", receipt.getString("blockId"))
        assertEquals("合成咖啡", receipt.getJSONObject("merchant").getString("value"))
        assertEquals("早餐 套餐", receipt.getJSONObject("productDescription").getString("value"))
        assertEquals("商品说明：早餐 套餐", receipt.getJSONObject("productDescription").getString("rawText"))
        assertEquals("ocr", receipt.getJSONObject("productDescription").getString("source"))
        assertEquals("single", receipt.getJSONObject("productDescription").getString("blockId"))
        assertEquals("到店消费", receipt.getJSONObject("transactionDescription").getString("value"))
        assertEquals("少糖", receipt.getJSONObject("orderRemark").getString("value"))
        assertEquals("合成扣费服务", receipt.getJSONObject("serviceProvider").getString("value"))
        assertEquals("合成支付机构", receipt.getJSONObject("receiptIssuer").getString("value"))
        assertEquals("com.tencent.mm", receipt.getJSONObject("sourceApp").getString("value"))
    }

    private fun withBridge(action: (LedgerWebBridge) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var bridge: LedgerWebBridge
            scenario.onActivity { bridge = LedgerWebBridge(it) }
            action(bridge)
        }
    }
}
