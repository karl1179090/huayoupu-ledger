package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerCandidateOptimizationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="candidate-${UUID.randomUUID()}"
    private val images=ScreenshotStore(context,namespace)
    private val store=LedgerStore(context,"$namespace-ledger.db",images).prepareSyntheticDefaultWallet()
    private val at=1_790_000_000_000L
    private fun capture(text:String="合成付款",time:Long=at,origin:String=PaymentApps.WECHAT)=Capture(Source.OCR,origin,text,time)
    private fun bill(id:String?=null,time:Long=at)=ParsedBill(10000,Direction.EXPENSE,"合成候选商户","购物",time,id,true,"",true)
    private fun refund(id:String,time:Long=at)=bill(id,time).copy(amountCents=7500,refund=RefundInfo("wechat",10000,2500))
    private fun seedUnrelatedHistory() {
        store.merge((1..300).map { index -> LedgerRecord(capture=Capture(Source.NOTIFICATION,"unrelated.test","不相关通知",at-index*86_400_000L),
            bill=bill("UNRELATED-$index",at-index*86_400_000L).copy(amountCents=10000+index.toLong()),state=RecordState.POSTED) })
    }

    @After fun cleanup() {
        store.close();images.close();context.deleteDatabase("$namespace-ledger.db");context.deleteDatabase("$namespace.db")
        File(context.filesDir,namespace).deleteRecursively();context.deleteSharedPreferences("${namespace}_policy")
    }

    @Test fun distantScopedAliasStillUpdatesGrossRefundAndPreservesEdits() {
        seedUnrelatedHistory()
        val oldTime=at-800*86_400_000L
        val order=LedgerRecord(capture=capture("淘宝订单号 ORDER-OLD-001",oldTime,"com.taobao.taobao"),
            bill=bill("ORDER-OLD-001",oldTime).copy(category="用户分类"),state=RecordState.POSTED,note="保留用户备注",
            identities=listOf(TransactionIdentity("app:com.taobao.taobao","ORDER-OLD-001"),TransactionIdentity("wechat","WECHAT-OLD-001")))
        store.merge(listOf(order))
        val original=store.all().single { it.uid==order.uid }
        val result=store.ingest(capture("微信交易单号 WECHAT-OLD-001",at),refund("WECHAT-OLD-001")) as IngestResult.Saved
        assertEquals(original.id,result.record.id);assertTrue(result.updated)
        assertEquals(7500L,result.record.bill.amountCents);assertEquals("保留用户备注",result.record.note)
        assertEquals("用户分类",result.record.bill.category)
        // A second adjustment must compare the original gross amount, not the net amount.
        val next=store.ingest(capture("微信交易单号 WECHAT-OLD-001",at+60_000),
            refund("WECHAT-OLD-001").copy(amountCents=6000,refund=RefundInfo("wechat",10000,4000))) as IngestResult.Saved
        assertEquals(original.id,next.record.id);assertEquals(6000L,next.record.bill.amountCents)
        assertEquals(301,store.all().size)
    }

    @Test fun allMatchingAliasesRemainAmbiguousEvenOutsideTimeWindow() {
        seedUnrelatedHistory()
        store.merge((1..2).map { index -> LedgerRecord(capture=capture(time=at-index*365*86_400_000L),
            bill=bill("SHARED-ALIAS-001",at-index*365*86_400_000L),state=RecordState.POSTED,note="分别保留$index") })
        val pending=store.ingest(capture("微信交易单号 SHARED-ALIAS-001"),refund("SHARED-ALIAS-001")) as IngestResult.Saved
        assertEquals(RecordState.REVIEW,pending.record.state)
        assertEquals(2,store.refundCandidates(pending.record.id).size)
        assertEquals(2,store.all().count { it.note.startsWith("分别保留") && it.bill.amountCents==10000L })
    }

    @Test fun repeatedPayloadIncludesExactBoundaryAndExcludesLaterCapture() {
        val original=(store.ingest(capture("相同合成付款页面"),bill()) as IngestResult.Saved).record
        val boundary=store.ingest(capture("相同合成付款页面",at+120_000),bill()) as IngestResult.Saved
        assertEquals(original.id,boundary.record.id)
        val later=store.ingest(capture("相同合成付款页面",at+120_001),bill()) as IngestResult.Saved
        assertNotEquals(original.id,later.record.id);assertEquals(RecordState.REVIEW,later.record.state)
    }

    @Test fun repairsStillFindLegacyDataImportedAfterAnEarlierRepairPass() {
        store.reconcilePendingDuplicates();store.discardRejectedDrafts();store.discardStandaloneRefundReceipts()
        val imported=LedgerRecord(capture=capture("交易单号 LEGACY-IMPORTED-001"),bill=bill("\u2003"),state=RecordState.POSTED,note="导入备注")
        val rejected=LedgerRecord(capture=capture("交 易 已 关 闭"),bill=bill(),state=RecordState.REVIEW)
        val edited=rejected.copy(uid=UUID.randomUUID().toString(),note="用户保留")
        store.merge(listOf(imported,rejected,edited))
        store.reconcilePendingDuplicates()
        val restored=store.all().single { it.uid==imported.uid }
        assertEquals("LEGACY-IMPORTED-001",restored.bill.transactionId);assertEquals("导入备注",restored.note)
        assertEquals(1,store.discardRejectedDrafts().size)
        assertNotNull(store.all().singleOrNull { it.uid==edited.uid })
        assertTrue(store.reconcilePendingDuplicates().isEmpty());assertTrue(store.discardRejectedDrafts().isEmpty())
    }

    @Test fun startupMerchantCandidatesExcludeUnrelatedSourcesAndUndoRows() {
        val records=listOf(
            LedgerRecord(capture=capture(),bill=bill().copy(merchant="全称"),state=RecordState.POSTED),
            LedgerRecord(capture=capture().copy(source=Source.NOTIFICATION),bill=bill().copy(merchant="全称"),state=RecordState.POSTED),
            LedgerRecord(capture=capture(),bill=bill().copy(merchant="正常商户"),state=RecordState.POSTED),
            LedgerRecord(capture=capture(),bill=bill().copy(merchant="名称"),state=RecordState.POSTED))
        store.merge(records)
        val deleted=store.all().single { it.uid==records.last().uid };assertTrue(store.softDelete(deleted.id))
        assertEquals(listOf(records.first().uid),store.merchantLabelRepairCandidates().map { it.uid })
        assertTrue(store.undoDelete(deleted.id))
        assertEquals(setOf(records.first().uid,records.last().uid),store.merchantLabelRepairCandidates().map { it.uid }.toSet())
    }

    @Test fun iconMetadataReadsOnlyRequestedRecordIds() {
        val first=(store.ingest(capture("第一笔"),bill("ICON-FIRST-001"),merchantIcon=byteArrayOf(1)) as IngestResult.Saved).record
        val second=(store.ingest(capture("第二笔"),bill("ICON-SECOND-001").copy(merchant="另一合成商户"),merchantIcon=byteArrayOf(2)) as IngestResult.Saved).record
        assertEquals(setOf(first.id),store.merchantIconKeys(setOf(first.id)).keys)
        assertFalse(store.merchantIconKeys(setOf(first.id)).containsKey(second.id))
        assertTrue(store.merchantIconKeys(emptySet()).isEmpty())
        assertEquals(setOf(first.id,second.id),store.merchantIconKeys().keys)
    }
}
