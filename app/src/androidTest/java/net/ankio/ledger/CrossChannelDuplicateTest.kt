package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CrossChannelDuplicateTest {
    @Test fun genericNotificationAndAiDoNotBothCountTowardsTotalsInEitherOrder() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        for(imageFirst in listOf(false,true)) {
            val name="cross-channel-${UUID.randomUUID()}.db"
            LedgerStore(app,name).prepareSyntheticDefaultWallet().use { store ->
                try {
                    val now=System.currentTimeMillis()
                    val notification=Capture(Source.NOTIFICATION,"com.eg.android.AlipayGphone","交易提醒\n你有一笔28.50元的支出，点此查看详情。",now)
                    val simple=ParsedBill(2850,Direction.EXPENSE,"支付宝支出","其他",now,null,true,"历史通知样例",false)
                    val capture=Capture(Source.OCR,notification.origin,"支付宝 支付成功 示例咖啡店 -28.50",now+3_600_000)
                    val detailed=simple.copy(merchant="示例咖啡店",category="餐饮",transactionId="SYNTHETIC-001",timeExplicit=true)
                    val first=if(imageFirst)store.ingest(capture,detailed) else store.ingest(notification,simple)
                    val second=if(imageFirst)store.ingest(notification,simple) else store.ingest(capture,detailed)
                    val original=(first as IngestResult.Saved).record
                    val possible=(second as IngestResult.Saved).record
                    assertEquals(RecordState.POSTED,original.state)
                    assertEquals(RecordState.REVIEW,possible.state)
                    assertEquals(original.id,possible.duplicateId)
                    assertEquals(2850L,store.all().filter { it.state==RecordState.POSTED }.sumOf { it.bill.amountCents!! })
                    // Explicitly different order IDs remain separate real purchases.
                    val separate=store.ingest(capture,detailed.copy(transactionId="SYNTHETIC-002",amountCents=2999)) as IngestResult.Saved
                    assertEquals(RecordState.POSTED,separate.record.state)
                } finally { store.close();app.deleteDatabase(name) }
            }
        }
    }
}
