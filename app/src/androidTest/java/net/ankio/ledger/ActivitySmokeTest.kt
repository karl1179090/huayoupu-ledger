package net.ankio.ledger

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerStore
import net.ankio.ledger.data.prepareSyntheticDefaultWallet
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivitySmokeTest {
    @Test fun homeAndSettingsLaunchAndReturnWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web = WebUiProbe(scenario)
            web.ready()
            web.await("document.querySelector('#ledger').classList.contains('mobile-active') && document.querySelector('[aria-label=\"添加记账\"]')")
            web.run("document.querySelector('#ledger .bottom-nav button:last-child').click()")
            web.await("document.querySelector('#settings').classList.contains('mobile-active') && !document.querySelector('[data-channel=\"sms\"]') && !document.querySelector('[data-channel=\"notification\"]')")
            web.await("document.querySelector('[data-channel=\"ocr\"]').getAttribute('aria-label')==='摇一摇记账' && document.querySelector('#settings h1').textContent.trim()==='我的' && document.querySelector('.shake').textContent.includes('识别成功入账') && document.querySelector('.privacy-banner').textContent.includes('隐私') && !document.querySelector('.channel-explanation') && !document.querySelector('.permission-actions') && !document.querySelector('#settings').textContent.includes('离线 OCR')")
            web.run("document.querySelector('#settings .bottom-nav button:first-child').click()")
            web.await("document.querySelector('#ledger').classList.contains('mobile-active')")
            scenario.recreate()
            web.ready()
            web.await("document.querySelector('#ledger').classList.contains('mobile-active') && document.querySelector('.balance-number').textContent.includes('¥')")
        }
    }

    @Test fun remoteBillWritesToLocalLedgerAndKeepsDuplicateProtection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "remote-test-${UUID.randomUUID()}.db"
        val store = LedgerStore(context, name).prepareSyntheticDefaultWallet()
        try {
            val raw = "支付成功 支付金额 28.50元 商户 示例咖啡店 交易时间 2026-10-16 12:30:00 交易单号 TEST20261016"
            val json = org.json.JSONObject().put("is_bill",true).put("raw_text",raw).put("amount","28.50")
                .put("direction","expense").put("merchant","示例咖啡店").put("category","餐饮")
                .put("occurred_at","2026-10-16 12:30:00").put("transaction_id","TEST20261016").put("reliable",true)
            val recognition = net.ankio.ledger.ocr.SyntheticRecognition.parseRecognition(json.toString(), 1792209600000)
            val capture = Capture(Source.IMAGE, "image", recognition.rawText)
            val bill = requireNotNull(recognition.bill)
            val saved = store.ingest(capture, bill, "synthetic-capture-event") as IngestResult.Saved
            assertEquals(RecordState.POSTED, saved.record.state)
            assertEquals(2850L, store.get(saved.record.id)!!.bill.amountCents)
            assertEquals(IngestResult.DuplicateSkipped, store.ingest(capture, bill, "synthetic-capture-event"))
            // Without an identified provider, a second capture remains reviewable instead of silently disappearing.
            val repeated = store.ingest(capture, bill) as IngestResult.Saved
            assertEquals(RecordState.REVIEW, repeated.record.state)
            assertEquals(saved.record.id, repeated.record.duplicateId)
            store.close()
            val reopened = LedgerStore(context,name)
            try { assertEquals(2850L,reopened.get(saved.record.id)!!.bill.amountCents) } finally { reopened.close() }
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
