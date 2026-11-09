package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the real JavaScript boundary and SQLite store without replacing the native bridge. */
@RunWith(AndroidJUnit4::class)
class LedgerWebBridgeTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun unchangedTimeFlagKeepsExactRecognizedTimeAndActualEditHasManualBasis() = withBridge { bridge ->
        val at=java.time.Instant.parse("2026-12-31T16:01:23.456Z").toEpochMilli()
        val context=net.ankio.ledger.core.RecognitionTimeContext(at,"Asia/Shanghai")
        val raw="支付成功\n付款金额：12.00元"
        val bill=net.ankio.ledger.core.LocalOcrBillParser.parse(raw.lines().map { net.ankio.ledger.core.LocalOcrLine(it) },
            0,0,at,origin="image",timeContext=context)!!
        val original=(app.store.ingest(Capture(Source.IMAGE,"image",raw,at),bill) as IngestResult.Saved).record
        try {
            val unchanged=input(original.id).put("amount","12.00").put("merchant",original.bill.merchant)
                .put("category",original.bill.category).put("occurredAt",at-23456).put("timeEdited",false).put("note","合成备注")
            val saved=JSONObject(bridge.save(unchanged.toString()));assertTrue(saved.toString(),saved.getBoolean("ok"))
            val noteOnly=app.store.get(original.id)!!
            assertEquals(at,noteOnly.bill.occurredAt)
            assertEquals(original.bill.receipt,noteOnly.bill.receipt)
            val editedAt=java.time.Instant.parse("2026-12-30T04:25:00Z").toEpochMilli()
            val changed=input(original.id).put("amount","12.00").put("merchant",original.bill.merchant)
                .put("category",original.bill.category).put("occurredAt",editedAt).put("timeEdited",true)
            assertTrue(JSONObject(bridge.save(changed.toString())).getBoolean("ok"))
            val edited=app.store.get(original.id)!!
            assertEquals(editedAt,edited.bill.receipt!!.time!!.accountingAt)
            assertEquals("MANUAL",edited.bill.receipt!!.time!!.source)
        } finally { app.store.delete(original.id) }
    }

    @Test fun saveEditSnapshotAndDeleteKeepTheRealRecordIdentity() = withBridge { bridge ->
        var id = 0L
        try {
            val saved = JSONObject(bridge.save(input().toString()))
            assertTrue(saved.toString(), saved.getBoolean("ok"))
            id = saved.getLong("id")
            val original = app.store.get(id)!!
            assertEquals(1234L, original.bill.amountCents)
            assertEquals(Source.MANUAL, original.capture.source)
            val edited = JSONObject(bridge.save(input(id).put("amount", "1.01")
                .put("direction", "INCOME").put("category", "其他").put("note", "修改备注").toString()))
            assertTrue(edited.toString(), edited.getBoolean("ok"))
            val record = app.store.get(id)!!
            assertEquals(original.uid, record.uid)
            assertEquals(original.capture, record.capture)
            assertEquals(101L, record.bill.amountCents)
            assertEquals(Direction.INCOME, record.bill.direction)
            assertEquals("修改备注", record.note)
            val snapshot = JSONObject(bridge.snapshot())
            assertTrue(snapshot.toString(), snapshot.getBoolean("ok"))
            val array = snapshot.getJSONArray("records")
            val shown = (0 until array.length()).map { array.getJSONObject(it) }
                .single { it.getLong("id") == id }
            assertEquals(record.uid, shown.getString("uid"))
            assertEquals(101L, shown.getLong("amountCents"))
            assertEquals("POSTED", shown.getString("state"))
            assertFalse(shown.has("rawText"))
            assertEquals("",JSONObject(bridge.recordDetails(id)).getString("rawText"))
            assertTrue(shown.isNull("duplicateId"))
            val channels = snapshot.getJSONObject("channels")
            assertFalse(channels.has("sms"));assertFalse(channels.has("notification"))
            listOf("ocr").forEach { key ->
                assertTrue(channels.getJSONObject(key).get("enabled") is Boolean)
                assertTrue(channels.getJSONObject(key).get("permitted") is Boolean)
            }
            assertTrue(JSONObject(bridge.delete(id)).getBoolean("ok"))
            assertNull(app.store.get(id))
            assertFalse(JSONObject(bridge.delete(id)).getBoolean("ok"))
        } finally { if (id > 0) app.store.delete(id) }
    }

    @Test fun malformedNumbersTypesEnumsLengthsAndMissingRecordsAreRejected() = withBridge { bridge ->
        val invalid = listOf(
            input().put("id", 0.5),
            input().put("id", "0"),
            input().put("id", -1),
            input().put("id", 9_007_199_254_740_992L),
            input().put("occurredAt", 1.5),
            input().put("occurredAt", "1760000000000"),
            input().put("occurredAt", 0),
            input().put("occurredAt", 4_102_444_800_001L),
            input().put("amount", 12.34),
            input().put("amount", "1e2"),
            input().put("amount", "0.001"),
            input().put("amount", "0"),
            input().put("amount", "1000000000"),
            input().put("direction", "expense"),
            input().put("merchant", "商".repeat(1025)),
            input().put("note", "注".repeat(501)),
            input().put("confirmDuplicate", "true"),
            input(9_007_199_254_740_991L),
        )
        val before = app.store.all().map { it.uid }.toSet()
        val financialBefore=app.store.walletSnapshot()
        val selectedBefore=app.channels.selectedShakePackages()
        invalid.forEach { data ->
            val result = JSONObject(bridge.save(data.toString()))
            assertFalse("Unexpectedly accepted $data: $result", result.getBoolean("ok"))
            assertTrue(result.getString("error").isNotBlank())
            assertEquals(result.toString(),"INVALID_INPUT",result.getString("code"))
        }
        fun rejected(json:String,code:String="INVALID_INPUT") {
            val result=JSONObject(json)
            assertFalse(result.toString(),result.getBoolean("ok"))
            assertEquals(result.toString(),code,result.getString("code"))
            assertTrue(result.getString("error").isNotBlank())
        }
        rejected(bridge.save("not JSON"))
        rejected(bridge.delete(0));rejected(bridge.delete(-1))
        fun account()=JSONObject().put("id",0).put("name","合成无效输入账户").put("amount","1.00")
            .put("kind","bank").put("type","ASSET").put("bankCode","cmb").put("cardLast4","0007")
        for(data in listOf(account().put("amount","1e2"),account().put("amount","0.001"),
                account().put("cardLast4","123"),account().put("cardLast4","12O4"),account().put("cardLast4","１２３４"),
                account().put("bankCode","invalid bank"),account().put("amount","-1.00").put("type","LIABILITY"))) {
            rejected(bridge.saveAccount(data.toString()))
        }
        rejected(bridge.saveAccount("not JSON"))
        rejected(bridge.listInstalledApplications("",0,0,false))
        rejected(bridge.setShakeApplication("invalid",true))
        rejected(bridge.setAutoApplication("invalid",false))
        rejected(bridge.categoryRecords("not-a-month","餐饮","EXPENSE",0))
        rejected(bridge.setDefaultWallet("missing-synthetic-wallet"),"INVALID_WALLET")
        val default=app.store.walletSettings().defaultAccountUid!!
        rejected(bridge.archiveAccount(app.store.accounts().single { it.uid==default }.id),"DEFAULT_WALLET_IN_USE")
        assertEquals(before, app.store.all().map { it.uid }.toSet())
        assertEquals(financialBefore,app.store.walletSnapshot())
        assertEquals(selectedBefore,app.channels.selectedShakePackages())
    }

    @Test fun possibleDuplicateRequiresExplicitConfirmationAndPreservesCapturedSource() = withBridge { bridge ->
        val createdIds = mutableListOf<Long>()
        try {
            val now = System.currentTimeMillis()
            val merchant = "桥接重复测试-${UUID.randomUUID()}"
            val capture = Capture(Source.SMS, "95588", "合成测试 $merchant 18.50元", now)
            val bill = ParsedBill(1850, Direction.EXPENSE, merchant, "其他", now, null, true, "", true)
            val first = (app.store.ingest(capture, bill) as IngestResult.Saved).record
            createdIds += first.id
            val review = (app.store.ingest(capture, bill) as IngestResult.Saved).record
            createdIds += review.id
            assertEquals(RecordState.REVIEW, review.state)
            assertEquals(first.id, review.duplicateId)
            val blocked = JSONObject(bridge.save(input(review.id).toString()))
            assertFalse(blocked.getBoolean("ok"))
            assertTrue(blocked.getBoolean("duplicateRequired"))
            assertEquals(review, app.store.get(review.id))
            val accepted = JSONObject(bridge.save(input(review.id).put("confirmDuplicate", true).toString()))
            assertTrue(accepted.toString(), accepted.getBoolean("ok"))
            val posted = app.store.get(review.id)!!
            assertEquals(review.uid, posted.uid)
            assertEquals(review.capture, posted.capture)
            assertEquals(RecordState.POSTED, posted.state)
            assertNull(posted.duplicateId)
            assertEquals("", posted.reason)
        } finally { createdIds.forEach(app.store::delete) }
    }

    @Test fun incompleteCaptureRemainsVisibleAsNullableReviewInSnapshot() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        val capture = Capture(Source.IMAGE, "image", "合成未完整截图 ${UUID.randomUUID()}", now)
        val bill = ParsedBill(null, null, "", "其他", now, null, false, "请核对金额和方向")
        val record = (app.store.ingest(capture, bill) as IngestResult.Saved).record
        try {
            val array = JSONObject(bridge.snapshot()).getJSONArray("records")
            val shown = (0 until array.length()).map { array.getJSONObject(it) }
                .single { it.getLong("id") == record.id }
            assertTrue(shown.isNull("amountCents"))
            assertTrue(shown.isNull("direction"))
            assertEquals("REVIEW", shown.getString("state"))
            assertEquals("IMAGE", shown.getString("source"))
            assertFalse(shown.has("rawText"))
            assertEquals(capture.text,JSONObject(bridge.recordDetails(record.id)).getString("rawText"))
            assertEquals(bill.reason, shown.getString("reason"))
        } finally { app.store.delete(record.id) }
    }

    private fun input(id: Long = 0) = JSONObject().put("id", id).put("amount", "12.34")
        .put("direction", "EXPENSE").put("merchant", "桥接合成测试商户")
        .put("category", "餐饮").put("occurredAt", System.currentTimeMillis())
        .put("note", "测试备注").put("confirmDuplicate", false)
        .put("submissionToken",UUID.randomUUID().toString()).put("expectedRevision",app.store.get(id)?.wallet?.revision ?: 0)

    private fun withBridge(test: (LedgerWebBridge) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var bridge: LedgerWebBridge
            scenario.onActivity { bridge = LedgerWebBridge(it) }
            // JavascriptInterface calls normally run on WebView's background bridge thread.
            test(bridge)
        }
    }
}
