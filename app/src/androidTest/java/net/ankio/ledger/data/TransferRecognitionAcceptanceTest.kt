package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Independent adversarial checks of recognition intent, confidence, persistence and financial replay. */
@RunWith(AndroidJUnit4::class)
class TransferRecognitionAcceptanceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="recognition-independent-${UUID.randomUUID()}.db"
    private var store=LedgerStore(context,name)
    private val now=1_793_937_600_000L
    @After fun cleanup(){store.close();context.deleteDatabase(name)}
    private fun wallets():Pair<LedgerAccount,LedgerAccount> = store.saveAccount(LedgerAccount(name="合成独立卡",amountCents=100000,kind="bank",bankCode="cmb",cardLast4="0007")) to
        store.saveAccount(LedgerAccount(name="合成独立零钱",amountCents=20000,kind="wechat"))
    private fun text(id:String=UUID.randomUUID().toString(),fee:String?="手续费0.00元")=listOf("充值成功","充值金额100.00元","付款方式招商银行储蓄卡（0007）","到账账户微信零钱","微信转账单号$id")+listOfNotNull(fee)
    private fun parse(lines:List<String>,origin:String=PaymentApps.WECHAT)=requireNotNull(LocalOcrBillParser.parse(lines.map(::LocalOcrLine),0,0,now,origin=origin))
    private fun ingest(lines:List<String>,bill:ParsedBill=parse(lines),review:Boolean=false):LedgerRecord {
        val result=store.ingest(Capture(Source.IMAGE,PaymentApps.WECHAT,lines.joinToString("\n"),now),bill,requireReview=review)
        return when(result){is IngestResult.Saved->store.get(result.record.id)!!
            IngestResult.DuplicateSkipped->requireNotNull(store.matchingIdentity(Capture(Source.IMAGE,PaymentApps.WECHAT,lines.joinToString("\n"),now),bill))
            else->error("Unexpected $result")}
    }
    private fun balance(wallet:LedgerAccount)=store.getAccountByUid(wallet.uid)!!.amountCents

    @Test fun feeExemptionPromotionsAndExhaustedAllowancesNeverProveThisTransferIsFree() {
        val (a,b)=wallets()
        for(marketing in listOf("剩余免手续费额度0.00元","免手续费额度已用完","活动赠送免手续费券","最高可享免手续费")) {
            val lines=text(fee=null)+marketing
            val parsed=parse(lines)
            assertNull(marketing,parsed.transfer!!.feeCents)
            assertFalse(marketing,parsed.reliable)
            val saved=ingest(lines,parsed)
            assertEquals(RecordState.REVIEW,saved.state);assertTrue(saved.walletEffects.isEmpty())
        }
        assertEquals(100000L,balance(a));assertEquals(20000L,balance(b))
    }
    @Test fun explicitReviewRequestCannotBeOverriddenByCompleteTransferEvidence() {
        val (a,b)=wallets();val lines=text();val saved=ingest(lines,review=true)
        assertEquals(RecordState.REVIEW,saved.state);assertTrue(saved.walletEffects.isEmpty())
        assertEquals(100000L,balance(a));assertEquals(20000L,balance(b))
        val repeated=ingest(lines)
        assertEquals(saved.uid,repeated.uid);assertEquals(RecordState.REVIEW,repeated.state)
        assertTrue(repeated.walletEffects.isEmpty())
        val confirmed=store.saveEdited(repeated.copy(note="合成明确人工确认"))
        assertEquals(RecordState.POSTED,confirmed.state)
        assertNotEquals("TRANSFER_REVIEW_REQUIRED",confirmed.transfer!!.reviewCode)
        assertEquals(90000L,balance(a));assertEquals(30000L,balance(b))
    }
    @Test fun unreliableRecognitionCannotPostDespiteOtherwiseCompleteStructuredEvidence() {
        val (a,b)=wallets();val lines=text();val saved=ingest(lines,parse(lines).copy(reliable=false,reason="合成识别仍需人工核对"))
        assertEquals(RecordState.REVIEW,saved.state);assertTrue(saved.walletEffects.isEmpty())
        assertEquals(100000L,balance(a));assertEquals(20000L,balance(b))
    }
    @Test fun unsupportedAndSensitiveScreensNeverRelaxTheOrdinaryCaptureGate() {
        val lines=text()
        fun page(values:List<String>,origin:String=PaymentApps.WECHAT,activity:String=PaymentPageDetector.WECHAT_FRAMEWORK_RESULT,editable:Boolean=false)=PaymentPage(origin,7,activity,values,editable)
        for(extra in listOf("请输入支付密码","验证码","信用卡还款","花呗还款","余额宝","USD100.00","申请已提交，预计两小时到账","充值退回","充值失败"))
            assertNull(extra,PaymentPageDetector.detect(page(lines+extra)))
        assertNull(PaymentPageDetector.detect(page(lines,editable=true)))
        assertNull(PaymentPageDetector.detect(page(lines,activity="UnknownWindow")))
        assertNull(PaymentPageDetector.detect(page(emptyList())))
        assertNull(PaymentPageDetector.detect(page(lines,activity="com.tencent.mm.plugin.wallet.TransactionDetailActivity")))
        assertNull(PaymentPageDetector.detect(page(lines,origin="com.example.syntheticbank",activity="com.example.syntheticbank.ResultActivity")))
        assertNull(PaymentPageDetector.detect(page(listOf("支付成功","¥100.00","¥99.00","完成"))))
        assertFalse(RecognitionPolicy.AUTOMATIC_PAYMENT.allows(ParsedBill(100,Direction.INCOME,"合成收入","其他",now,null,true,""),PaymentApps.WECHAT))
    }
    @Test fun externalRecipientAndSameTailCannotBecomeAnOwnedDestination() {
        val (a,b)=wallets();store.setDefaultWallet(a.uid)
        val lines=listOf("转账成功","转账金额100.00元","收款人合成朋友","收款银行卡招商银行（0007）","付款方式招商银行（0007）")
        val parsed=parse(lines)
        assertEquals(Direction.EXPENSE,parsed.direction);assertNull(parsed.transfer)
        val saved=ingest(lines,parsed)
        assertEquals(Direction.EXPENSE,saved.bill.direction);assertEquals(90000L,balance(a));assertEquals(20000L,balance(b))
        val contradicted=parse(text()+"收款人合成朋友")
        assertFalse(contradicted.reliable);assertNull(contradicted.direction)
    }
    @Test fun changedFeeModeOrObservedLegsPreservePostedEffectsAcrossReopen() {
        val (a,b)=wallets();val id=UUID.randomUUID().toString()
        val original=ingest(text(id,"手续费1.00元")+"费用方式额外扣费")
        val effects=original.walletEffects
        for(lines in listOf(text(id,"手续费1.00元")+"费用方式到账扣费",text(id,"手续费1.00元")+listOf("实际扣款100.00元","实际到账100.00元"))) {
            val conflict=ingest(lines)
            assertEquals(original.id,conflict.id);assertEquals(effects,conflict.walletEffects)
            assertNotNull(conflict.wallet!!.reviewCode)
        }
        store.close();store=LedgerStore(context,name)
        assertEquals(effects,store.get(original.id)!!.walletEffects)
        assertEquals(89900L,balance(a));assertEquals(30000L,balance(b));assertEquals(1,store.all().size)
    }
    @Test fun handChosenDestinationAndFeeRemainLockedWhenNewEvidenceTargetsAnotherOwnedWallet() {
        val (a,b)=wallets();val other=store.saveAccount(LedgerAccount(name="合成另一零钱",kind="wechat",amountCents=0))
        store.setAppWallet(PaymentApps.WECHAT,b.uid)
        val lines=text();val original=ingest(lines)
        val manual=store.saveEdited(original.copy(transfer=original.transfer!!.copy(toAccountUid=other.uid,feeCents=100)))
        assertTrue(manual.transfer!!.toManualLocked);assertTrue(manual.transfer!!.feeManualLocked)
        val again=ingest(lines)
        assertEquals(other.uid,again.transfer!!.toAccountUid);assertEquals(100L,again.transfer!!.feeCents)
        assertEquals(manual.walletEffects,again.walletEffects);assertEquals(89900L,balance(a));assertEquals(20000L,balance(b));assertEquals(10000L,balance(other))
    }
    @Test fun strongPendingIdentityMayCompleteButSoftDeletionStillBlocksIt() {
        val a=store.saveAccount(LedgerAccount(name="合成独立卡",amountCents=100000,kind="bank",bankCode="cmb",cardLast4="0007"))
        val id=UUID.randomUUID().toString();val first=ingest(text(id,fee=null))
        assertEquals(RecordState.REVIEW,first.state)
        val b=store.saveAccount(LedgerAccount(name="合成独立零钱",amountCents=20000,kind="wechat"))
        val completed=ingest(text(id));assertEquals(first.uid,completed.uid);assertEquals(RecordState.POSTED,completed.state)
        store.softDelete(first.id,now)
        val retry=store.ingest(Capture(Source.OCR,PaymentApps.WECHAT,text(id).joinToString("\n"),now),parse(text(id)))
        assertTrue(retry is IngestResult.Skipped);assertEquals(100000L,balance(a));assertEquals(20000L,balance(b));assertTrue(store.all().isEmpty())
    }
    @Test fun codecRetainsAllLabelledEvidenceAndRejectsInjectedLocalWalletIdsOrUtf8Overflow() {
        val original=parse(text()+listOf("实际扣款100.00元","实际到账100.00元")).transfer!!
        val encoded=TransferEvidenceCodec.encode(original)
        assertEquals(original,TransferEvidenceCodec.decode(encoded))
        val injected=JSONObject(encoded).put("fromAccountUid",UUID.randomUUID().toString()).toString()
        assertTrue(runCatching {TransferEvidenceCodec.decode(injected)}.isFailure)
        val unicode=JSONObject(encoded).put("reviewCodes",org.json.JSONArray().put("中".repeat(6000))).toString()
        assertTrue(runCatching {TransferEvidenceCodec.decode(unicode)}.isFailure)
    }

    @Test fun rechargeAndWithdrawalCannotUseAnotherPlatformsBalanceUnderTheRealSourcePackage() {
        val (a,b)=wallets()
        val alipay=store.saveAccount(LedgerAccount(name="合成独立支付宝",amountCents=30000,kind="alipay"))
        val wrongTopup=text().map { it.replace("到账账户微信零钱","到账账户支付宝余额") }
        val parsed=parse(wrongTopup)
        assertFalse(parsed.reliable)
        assertFalse(TransferEvidenceParser.automaticCandidate(parsed.transfer!!,PaymentApps.WECHAT))
        assertNull(PaymentPageDetector.detect(PaymentPage(PaymentApps.WECHAT,7,PaymentPageDetector.WECHAT_FRAMEWORK_RESULT,wrongTopup)))
        val saved=ingest(wrongTopup,parsed)
        assertEquals(RecordState.REVIEW,saved.state);assertTrue(saved.walletEffects.isEmpty())
        val withdrawal=listOf("提现成功","提现金额100.00元","转出账户支付宝余额","到账银行卡招商银行储蓄卡（0007）","手续费0.00元")
        assertFalse(parse(withdrawal).reliable)
        assertFalse(TransferEvidenceParser.automaticCandidate(parse(withdrawal).transfer!!,PaymentApps.WECHAT))
        assertEquals(100000L,balance(a));assertEquals(20000L,balance(b));assertEquals(30000L,balance(alipay))
    }

    @Test fun ambiguousTransactionTypePreservesItsCompleteEvidenceAfterReopenWithoutAnyDefaultWallet() {
        val (a,b)=wallets();store.setDefaultWallet(a.uid)
        val lines=text()+"收款人合成朋友"
        val parsed=parse(lines)
        assertNull(parsed.direction);assertEquals(TransferIntent.AMBIGUOUS,parsed.transfer!!.intent)
        val saved=ingest(lines,parsed)
        assertEquals(RecordState.REVIEW,saved.state);assertNull(saved.bill.direction)
        assertNull(saved.wallet!!.accountUid);assertTrue(saved.walletEffects.isEmpty())
        store.close();store=LedgerStore(context,name)
        val reopened=store.get(saved.id)!!
        assertEquals(parsed.transfer,reopened.bill.transfer)
        assertNotNull(reopened.transfer!!.recognitionEvidence)
        assertNull(reopened.transfer!!.fromAccountUid);assertNull(reopened.transfer!!.toAccountUid)
        assertEquals(100000L,balance(a));assertEquals(20000L,balance(b))
    }
}
