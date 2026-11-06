package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaptureSupplementTest {
    @Test fun upgradeWithdrawsRejectedMachineDraftsButPreservesPostedManualAndEditedRecords() {
        val raw="交易关闭\n退款成功 支付宝¥14.90\n实付款¥14.9"
        val bad=(store.ingest(capture(raw,PaymentApps.TAOBAO),bill().copy(amountCents=null,reliable=false),screenshot=first) as IngestResult.Saved).record
        val edited=bad.copy(id=0,uid=UUID.randomUUID().toString(),note="用户保留")
        val posted=bad.copy(id=0,uid=UUID.randomUUID().toString(),bill=bill(),state=RecordState.POSTED)
        val manual=bad.copy(id=0,uid=UUID.randomUUID().toString(),capture=Capture(Source.MANUAL,"manual",raw))
        store.merge(listOf(edited,posted,manual))
        assertEquals(mapOf(bad.id to "交易已关闭，不予记账"),store.discardRejectedDrafts())
        assertEquals(setOf(edited.uid,posted.uid,manual.uid),store.all().map { it.uid }.toSet())
        assertTrue(store.discardRejectedDrafts().isEmpty())
    }
    @Test fun repeatedShoppingOrderWithDisclosureGlyphAddsScreenshotToSameRecord() {
        val raw="交易成功\n店铺名称：合成旗舰店\n实付款¥39.48\n订单信息共8项√\n5127298851523000911|复制"
        fun parsed(text:String,at:Long)=LocalOcrBillParser.parse(text.lines().map { LocalOcrLine(it) },0,0,at,origin=PaymentApps.TAOBAO)!!
        val one=capture(raw,PaymentApps.TAOBAO)
        val original=(store.ingest(one,parsed(raw,now),screenshot=first) as IngestResult.Saved).record
        val two=one.copy(text=raw+"\nm",receivedAt=now+6_000)
        val repeated=store.ingest(two,parsed(two.text,two.receivedAt),screenshot=second) as IngestResult.Saved
        assertEquals(original.id,repeated.record.id)
        assertTrue(repeated.supplemented)
        assertEquals(1,store.all().size)
        assertEquals(2,store.captureHistory(original.id).size)
        val other=two.copy(text=raw.replace("5127298851523000911","5127298851523000912"))
        store.ingest(other,parsed(other.text,other.receivedAt),screenshot=third)
        assertEquals("Same merchant and price with different order numbers are separate payments",2,store.all().size)
    }

    @Test fun batchedHistoryKeepsEveryRecordAndScreenshotStateInSequenceOrder() {
        val original=payment()
        store.ingest(capture("付款详情"),bill(),screenshot=second)
        val other=store.saveEdited(original.copy(id=0,uid=UUID.randomUUID().toString(),note="另一笔"))
        store.restoreHistory(other.id,store.captureHistory(other.id).map { RestoredCaptureHistory(it,null) }+
            RestoredCaptureHistory(RecordCaptureHistory(2,now,"payment_supplement",10000,0,"另一笔",true),third))
        val grouped=store.captureHistories()
        assertEquals(setOf(original.id,other.id),grouped.keys)
        assertEquals(store.captureHistory(original.id),grouped[original.id])
        assertEquals(store.captureHistory(other.id),grouped[other.id])
        assertEquals(listOf(1,2),grouped.getValue(original.id).map { it.sequence })
        assertEquals(setOf(original.id),store.screenshotIds())
        assertTrue(grouped.getValue(original.id).all { it.hasScreenshot })
        assertEquals(listOf(false,true),grouped.getValue(other.id).map { it.hasScreenshot })
        assertTrue(store.captureHistories(emptySet()).values.flatten().none { it.hasScreenshot })
    }

    @Test fun upgradeMergesPostedShoppingOrderCopiesAndRetainsBothScreenshotsAndOriginalEdits() {
        val a=LedgerRecord(capture=capture("实付款¥22.5\n订单信息共5项√\n512786188900737738" ,origin="com.taobao.taobao"),
            bill=bill(null).copy(amountCents=2250,timeExplicit=false),state=RecordState.POSTED,note="保留备注")
        val b=a.copy(uid=UUID.randomUUID().toString(),capture=a.capture.copy(receivedAt=now+8*3_600_000),
            bill=a.bill.copy(occurredAt=now+8*3_600_000),note="")
        val different=b.copy(uid=UUID.randomUUID().toString(),capture=b.capture.copy(text="实付款¥22.5\n订单编号512786188900737739"))
        store.merge(listOf(a,b,different))
        val originals=store.all().associateBy { it.uid }
        listOf(a to first,b to second).forEach { (record,image) ->
            store.restoreHistory(originals.getValue(record.uid).id,listOf(RestoredCaptureHistory(
                RecordCaptureHistory(1,record.capture.receivedAt,"payment",2250,0,record.capture.text,true),image)))
        }
        val mapping=store.reconcilePendingDuplicates()
        val kept=originals.getValue(a.uid).id
        assertEquals(mapOf(originals.getValue(b.uid).id to kept),mapping)
        assertEquals(2,store.all().size);assertEquals("保留备注",store.get(kept)!!.note)
        assertEquals(now,store.get(kept)!!.bill.occurredAt)
        assertEquals("512786188900737738",store.get(kept)!!.bill.transactionId)
        assertEquals(2,store.captureHistory(kept).size);assertArrayEquals(second,store.historyScreenshot(kept,2))
        assertTrue(store.reconcilePendingDuplicates().isEmpty())
    }
    @Test fun repeatedTaobaoOrderAcrossHoursAppendsEvidenceAndDoesNotCreateAnotherExpense() {
        val order=bill("512786188900737738").copy(amountCents=2250,timeExplicit=false)
        val firstCapture=capture("订单信息 共5项 512786188900737738",origin="com.taobao.taobao")
        val original=(store.ingest(firstCapture,order,screenshot=first) as IngestResult.Saved).record
        val later=store.ingest(firstCapture.copy(receivedAt=now+8*3_600_000,text="已发货 订单信息 共5项 512786188900737738"),
            order.copy(occurredAt=now+8*3_600_000),screenshot=second) as IngestResult.Saved
        assertEquals(original.id,later.record.id);assertEquals(1,store.all().size)
        assertEquals(2,store.captureHistory(original.id).size)
    }
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="supplement-${UUID.randomUUID()}"
    private val images=ScreenshotStore(context,namespace)
    private var store=LedgerStore(context,"$namespace-ledger.db",images).prepareSyntheticDefaultWallet()
    private val now=System.currentTimeMillis()
    private val first=byteArrayOf(1,2,3)
    private val second=byteArrayOf(4,5,6)
    private val third=byteArrayOf(7,8,9)
    private fun capture(text:String="合成支付成功页",origin:String="com.tencent.mm")=Capture(Source.OCR,origin,text,now)
    private fun bill(id:String?="SYNTHETIC-ORDER")=ParsedBill(10000,Direction.EXPENSE,"合成商户","购物",now,id,true,"",true)
    private fun payment()=(store.ingest(capture(),bill(),screenshot=first) as IngestResult.Saved).record

    @After fun close() {
        store.all().forEach { store.delete(it.id) };store.close();images.close()
        context.deleteDatabase("$namespace-ledger.db");context.deleteDatabase("$namespace.db")
        File(context.filesDir,namespace).deleteRecursively();context.deleteSharedPreferences("${namespace}_policy")
    }

    @Test fun automaticThenShakeAppendsDifferentImageToSameRecordAndRepeatedImageDoesNotGrowStorage() {
        val original=payment()
        store.saveEdited(original.copy(note="已编辑备注",bill=original.bill.copy(category="交通")))
        val supplemented=store.ingest(capture("单笔付款详情"),bill(),screenshot=second) as IngestResult.Saved
        assertTrue(supplemented.supplemented);assertFalse(supplemented.updated)
        assertEquals(original.id,supplemented.record.id);assertEquals(original.uid,supplemented.record.uid)
        assertEquals(10000L,supplemented.record.bill.amountCents)
        assertEquals("已编辑备注",supplemented.record.note);assertEquals("交通",supplemented.record.bill.category)
        assertEquals(1,store.all().size);assertEquals(2,store.captureHistory(original.id).size)
        assertArrayEquals(first,store.historyScreenshot(original.id,1));assertArrayEquals(second,store.historyScreenshot(original.id,2))
        repeat(3) { assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture("重复摇一摇"),bill(),screenshot=second)) }
        assertEquals(2,images.stats().getInt("count"))
        store.close();store=LedgerStore(context,"$namespace-ledger.db",images)
        assertEquals(listOf("payment","payment_supplement"),store.captureHistory(original.id).map { it.kind })
        val backup=LedgerBackup.decode(ByteArrayInputStream(LedgerBackup.encode(store,ChannelSettings(context)).toByteArray()))
        store.delete(original.id);assertEquals(1,LedgerBackup.restore(store,backup))
        val restored=store.all().single()
        assertEquals("已编辑备注",restored.note);assertEquals(10000L,restored.bill.amountCents)
        assertArrayEquals(second,store.historyScreenshot(restored.id,2))
    }

    @Test fun repeatedManualBillWithoutOrderNumberAddsScreenshotInsteadOfReviewRecord() {
        val original=(store.ingest(capture("同一笔付款完整详情"),bill(null),screenshot=first) as IngestResult.Saved).record
        store.saveEdited(original.copy(note="保留备注",bill=original.bill.copy(category="交通")))
        val next=store.ingest(capture("同一笔付款完整详情"),bill(null),screenshot=second) as IngestResult.Saved
        assertEquals(original.id,next.record.id);assertEquals(RecordState.POSTED,next.record.state)
        assertTrue(next.supplemented);assertEquals(1,store.all().size)
        assertEquals("保留备注",next.record.note);assertEquals("交通",next.record.bill.category)
        assertEquals(2,store.captureHistory(original.id).size)
    }

    @Test fun reliableRepeatOfExactPendingBillPostsThatBillInsteadOfClaimingAnInvisibleDuplicate() {
        val pending=(store.ingest(capture("旧版识别缺少时间"),bill().copy(reliable=false,reason="时间待核对"),screenshot=first) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state)
        val result=store.ingest(capture("补充完整付款详情"),bill(),screenshot=second)
        assertTrue(result is IngestResult.Saved)
        val saved=(result as IngestResult.Saved).record
        assertEquals(pending.id,saved.id)
        assertEquals(RecordState.POSTED,saved.state)
        assertEquals(RecordState.POSTED,store.get(pending.id)!!.state)
        assertEquals(1,store.all().size)
        assertEquals(2,store.captureHistory(pending.id).size)
    }

    @Test fun strongIdentityCompletionCannotOverrideReviewOrUnreliableInputOrAmbiguousTransferType() {
        for((index,required) in listOf(true,false).withIndex()) {
            val fields=bill("SYNTHETIC-REVIEW-GATE-$index")
            val pending=(store.ingest(capture("合成缺项$index"),fields.copy(reliable=false),screenshot=first) as IngestResult.Saved).record
            val result=store.ingest(capture("合成后续$index"),fields.copy(reliable=required),screenshot=second,requireReview=required) as IngestResult.Saved
            assertEquals(pending.uid,result.record.uid);assertEquals(RecordState.REVIEW,result.record.state);assertTrue(result.record.walletEffects.isEmpty())
        }
        val fields=bill("SYNTHETIC-AMBIGUOUS-TYPE")
        val evidence=TransferEvidence(TransferIntent.AMBIGUOUS,terminalState=TransferTerminalState.COMPLETED,amountCents=10000,feeCents=0,feeMode=FeeMode.EXTRA)
        val ambiguous=(store.ingest(capture("本人或对外待核对"),fields.copy(direction=null,transfer=evidence),screenshot=first) as IngestResult.Saved).record
        val result=store.ingest(capture("普通付款后续"),fields,screenshot=second) as IngestResult.Saved
        assertEquals(ambiguous.uid,result.record.uid);assertEquals(RecordState.REVIEW,result.record.state);assertNull(result.record.bill.direction)
        assertEquals(evidence,result.record.bill.transfer);assertTrue(result.record.walletEffects.isEmpty())
    }

    @Test fun strongIdentityCompletionPreservesManualWalletAndEveryEligibilityAndPostsEffectsOnce() {
        for(eligibility in WalletEligibility.entries) {
            val chosen=store.saveAccount(LedgerAccount(name="合成人工卡$eligibility",amountCents=50000,kind="bank",bankCode="cmb",cardLast4="0007"))
            val other=store.saveAccount(LedgerAccount(name="合成新默认$eligibility",amountCents=50000,kind="bank",bankCode="icbc",cardLast4="0008"))
            val fields=bill("SYNTHETIC-COMPLETE-${eligibility.name}")
            val pending=(store.ingest(capture("合成缺项$eligibility"),fields.copy(reliable=false),screenshot=first) as IngestResult.Saved).record
            WalletAccounting.saveLink(store.writableDatabase,pending.wallet!!.copy(eligibility=eligibility,
                accountUid=chosen.uid,selectionSource="MANUAL",manualLocked=true))
            store.setDefaultWallet(other.uid)
            val reliable=fields.copy(paymentMethod=PaymentMethodEvidence("BANK_CARD","DEBIT","icbc","0008"))
            val result=store.ingest(capture("合成可靠补全$eligibility"),reliable,screenshot=second) as IngestResult.Saved
            val saved=result.record
            assertEquals(pending.uid,saved.uid);assertEquals(RecordState.POSTED,saved.state);assertEquals(eligibility,saved.wallet!!.eligibility)
            assertTrue(saved.wallet!!.manualLocked);assertEquals(chosen.uid,saved.wallet!!.accountUid)
            assertEquals(if(eligibility==WalletEligibility.ACTIVE)40000L else 50000L,store.getAccount(chosen.id)!!.amountCents)
            assertEquals(50000L,store.getAccount(other.id)!!.amountCents)
            val balances=store.accounts().associate {it.uid to it.amountCents};val movements=store.walletSnapshot().movements
            store.ingest(capture("同笔已正式账单再补充$eligibility"),reliable,screenshot=third)
            assertEquals(balances,store.accounts().associate {it.uid to it.amountCents});assertEquals(movements,store.walletSnapshot().movements)
            assertEquals(saved.walletEffects,store.get(saved.id)!!.walletEffects)
        }
    }

    @Test fun severalHistoricalOrdersRemainSeparateAfterRepeatCaptureAndDatabaseReopen() {
        val entries=listOf(1256L,563L,2250L,2000L).mapIndexed { index,amount ->
            val fields=bill("TAOBAO-ORDER-$index").copy(amountCents=amount,merchant="同一家旗舰店",
                occurredAt=now-3*86_400_000L+index*1000)
            val original=(store.ingest(capture("订单$index"),fields,screenshot=first) as IngestResult.Saved).record
            val repeat=store.ingest(capture("订单$index 更多详情"),fields,screenshot=second) as IngestResult.Saved
            assertEquals(original.id,repeat.record.id);assertEquals(RecordState.POSTED,repeat.record.state)
            original.id to amount
        }
        store.close();store=LedgerStore(context,"$namespace-ledger.db",images)
        assertEquals(4,store.all().size)
        entries.forEach { (id,amount) ->
            assertEquals(amount,store.get(id)!!.bill.amountCents)
            assertEquals(RecordState.POSTED,store.get(id)!!.state)
            assertEquals(2,store.captureHistory(id).size)
        }
    }

    @Test fun ambiguousPreviousDuplicateDraftKeepsSeparateEvidence() {
        val original=payment()
        val pending=(store.ingest(capture("旧版相似账单"),bill(null),screenshot=second,requireReview=true) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,pending.state)
        assertTrue(store.reconcilePendingDuplicates().isEmpty())
        assertEquals(2,store.all().size);assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
        assertEquals(1,store.captureHistory(original.id).size)
        assertEquals(RecordState.REVIEW,store.get(pending.id)!!.state)
        assertArrayEquals(second,store.historyScreenshot(pending.id,1))
        assertTrue(store.reconcilePendingDuplicates().isEmpty())
    }

    @Test fun merchantLabelRepairNeverChangesMoneyStateOrNote() {
        val original=(store.ingest(capture(),bill().copy(merchant="全称"),screenshot=first,requireReview=true) as IngestResult.Saved).record
        val before=store.get(original.id)!!
        assertFalse(store.repairMerchantLabel(original.id,bill().copy(amountCents=999),null))
        assertTrue(store.repairMerchantLabel(original.id,bill().copy(merchant="合成云计算有限公司"),null))
        val after=store.get(original.id)!!
        assertEquals(before.bill.amountCents,after.bill.amountCents);assertEquals(before.bill.occurredAt,after.bill.occurredAt)
        assertEquals(before.state,after.state);assertEquals(before.note,after.note)
        assertEquals("合成云计算有限公司",after.bill.merchant)
    }

    @Test fun standaloneRefundCleanupPreservesOriginalAdjustedPaymentAndManualEntries() {
        val raw="退款成功 120.00 查看原账单 退款方式 余额"
        val receipt=bill().copy(amountCents=null,reliable=false,merchant="退款来源",
            standaloneRefund=StandaloneRefundEvidence("wechat",12000,"SYNTHETIC-COMPLETED-REFUND",null))
        val wrong=(store.ingest(capture(raw),receipt,screenshot=first) as IngestResult.Saved).record
        val rejected=(store.ingest(capture(raw+" 交易关闭"),receipt.copy(
            standaloneRefund=receipt.standaloneRefund!!.copy(refundId="SYNTHETIC-CLOSED-REFUND"))) as IngestResult.Saved).record
        val manual=store.saveEdited(wrong.copy(id=0,uid=UUID.randomUUID().toString(),capture=Capture(Source.MANUAL,"manual",raw),bill=bill().copy(merchant="手动保留")))
        val adjusted=(store.ingest(capture("支付成功 已部分退款 原付款金额100 累计成功退款30"),bill().copy(merchant="调整后消费",transactionId="ADJUSTED",amountCents=7000,refund=RefundInfo("wechat",10000,3000)),screenshot=second) as IngestResult.Saved).record
        assertEquals(listOf(rejected.id),store.discardStandaloneRefundReceipts())
        assertEquals(RecordState.REVIEW,store.get(wrong.id)!!.state);assertEquals(0L,store.get(wrong.id)!!.wallet!!.appliedCents)
        assertNotNull(store.get(manual.id));assertNotNull(store.get(adjusted.id));assertEquals(7000L,store.get(adjusted.id)!!.bill.amountCents)
    }

    @Test fun refundedPaymentCanGainEvidenceWithoutResettingNetAmountAndRestoredHistoryKeepsIt() {
        val original=payment()
        store.ingest(capture("成功退款30"),bill().copy(amountCents=7000,refund=RefundInfo("wechat",10000,3000)),screenshot=second)
        val supplemented=store.ingest(capture("原付款详情"),bill(),screenshot=third) as IngestResult.Saved
        assertTrue(supplemented.supplemented);assertEquals(7000L,supplemented.record.bill.amountCents)
        assertEquals(3000L,supplemented.record.bill.refund!!.refundedCents)
        val history=store.captureHistory(original.id).map { RestoredCaptureHistory(it,store.historyScreenshot(original.id,it.sequence)) }
        assertEquals(3,history.size)
        store.delete(original.id);store.merge(listOf(supplemented.record))
        val restored=store.all().single();store.restoreHistory(restored.id,history)
        assertArrayEquals(third,store.historyScreenshot(restored.id,3));assertEquals(7000L,store.get(restored.id)!!.bill.amountCents)
        assertEquals(IngestResult.DuplicateSkipped,store.ingest(capture(),bill(),screenshot=third))
    }

    @Test fun missingOrderNumberCanSupplementUniqueMatchButConflictingOrderRemainsSeparate() {
        val original=payment()
        val uncertain=store.ingest(capture(),bill(null),screenshot=second) as IngestResult.Saved
        assertTrue(uncertain.supplemented);assertEquals(RecordState.POSTED,uncertain.record.state)
        assertEquals(original.id,uncertain.record.id)
        val distinct=store.ingest(capture("另一笔付款"),bill("OTHER-ORDER"),screenshot=third) as IngestResult.Saved
        assertFalse(distinct.supplemented);assertEquals(RecordState.POSTED,distinct.record.state)
        assertEquals(2,store.captureHistory(original.id).size)
    }

    @Test fun sameRefundWithNewImageAddsEvidenceOnceWithoutAnotherDeduction() {
        val original=payment()
        val refund=bill().copy(amountCents=7000,refund=RefundInfo("wechat",10000,3000))
        store.ingest(capture("成功退款30"),refund,screenshot=second)
        val supplemented=store.ingest(capture("另一张相同退款详情"),refund,screenshot=third) as IngestResult.Saved
        assertTrue(supplemented.supplemented);assertFalse(supplemented.updated)
        assertEquals(7000L,store.get(original.id)!!.bill.amountCents)
        assertFalse((store.ingest(capture(),refund,screenshot=third) as IngestResult.Saved).supplemented)
        assertEquals(listOf("payment","partial_refund","refund_supplement"),store.captureHistory(original.id).map { it.kind })
        val entries=store.captureHistory(original.id).map { RestoredCaptureHistory(it,store.historyScreenshot(original.id,it.sequence)) }
        store.delete(original.id);store.merge(listOf(supplemented.record))
        val restored=store.all().single();store.restoreHistory(restored.id,entries)
        assertEquals(7000L,restored.bill.amountCents);assertArrayEquals(third,store.historyScreenshot(restored.id,3))
    }

    @Test fun failedAppendRollsBackHistoryAndReleasesItsImage() {
        val original=payment()
        store.writableDatabase.execSQL("CREATE TRIGGER reject_supplement BEFORE INSERT ON capture_history WHEN NEW.sequence=2 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        assertTrue(runCatching { store.ingest(capture(),bill(),screenshot=second) }.isFailure)
        assertEquals(1,store.captureHistory(original.id).size);assertEquals(1,images.stats().getInt("count"))
        assertEquals(10000L,store.get(original.id)!!.bill.amountCents)
    }

    @Test fun backupRestoreAcceptsSupplementsBeforeAndAfterRefundAndDeletesAllReferences() {
        val original=payment()
        store.ingest(capture("付款详情"),bill(),screenshot=second)
        store.ingest(capture("成功退款30"),bill().copy(amountCents=7000,refund=RefundInfo("wechat",10000,3000)),screenshot=third)
        store.ingest(capture("另一张原付款详情"),bill(),screenshot=byteArrayOf(10,11,12))
        val decoded=LedgerBackup.decode(ByteArrayInputStream(LedgerBackup.encode(store,ChannelSettings(context)).toByteArray()))
        store.delete(original.id)
        assertEquals(1,LedgerBackup.restore(store,decoded))
        val restored=store.all().single();assertEquals(7000L,restored.bill.amountCents)
        assertEquals(listOf("payment","payment_supplement","partial_refund","payment_supplement"),store.captureHistory(restored.id).map { it.kind })
        assertEquals(4,images.stats().getInt("count"))
        store.delete(restored.id);assertEquals(0,images.stats().getInt("count"))
    }
}
