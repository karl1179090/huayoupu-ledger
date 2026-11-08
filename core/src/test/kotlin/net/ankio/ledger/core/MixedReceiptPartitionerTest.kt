package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class MixedReceiptPartitionerTest {
    private val time=RecognitionTimeContext(1794110400000,"Asia/Shanghai")
    private fun lines(vararg text:String,top:Int=100)=text.mapIndexed { i,s ->
        LocalOcrLine(s,100,top+i*50,900,top+i*50+35)
    }
    private fun parse(page:List<LocalOcrLine>)=TransactionPageParser.parseBlocks(
        MixedReceiptPartitioner.partition(page,1080,1800).orEmpty(),1080,1800,time,origin="shake:com.tencent.mm")

    @Test fun serviceNoticeCannotRejectOrSupplyFieldsToTheSeparateActualDebitCard() {
        val notice=lines("服务开通通知","服务商：合成广告服务","支付方式：优先从零钱支付","交易已关闭","交易金额：888.00元")
        val debit=lines("扣款成功","交易金额：12.34元","商户名称：合成咖啡","扣费服务商：合成扣费平台",
            "银行作者：招商银行","商品说明：午餐","通过零钱扣款",top=650)
        val candidate=parse(notice+debit).single()
        val bill=candidate.bill
        assertEquals(1234L,bill.amountCents)
        assertEquals(Direction.EXPENSE,bill.direction)
        assertEquals("合成咖啡",bill.merchant)
        assertEquals("合成扣费平台",bill.receipt!!.serviceProvider!!.value)
        assertEquals("招商银行",bill.receipt!!.receiptIssuer!!.value)
        assertEquals("午餐",bill.receipt!!.productDescription!!.value)
        assertEquals("WECHAT_BALANCE",bill.paymentMethod!!.medium)
        assertTrue(bill.reliable)
        assertFalse(candidate.rawText!!.contains("888.00"))
        assertFalse(candidate.rawText!!.contains("交易已关闭"))
    }

    @Test fun nestedBankSummaryKeepsOnlyItsOwnAmountAndFallsBackToFrozenRecognitionTime() {
        val outer=lines("图片查看器","账单记录时间：2020-01-01 08:00","交易时间：2020-01-02 09:00",top=30)
        val inner=lines("招商银行","交易金额：23.45元","交易说明：日常用品","账户余额：9999.00元","积分：12345",top=400)
        val advertising=lines("为你推荐","商户名称：合成广告火锅","交易金额：888.00元","交易时间：2019-01-01 08:00","保存图片",top=1000)
        val candidate=parse((advertising+outer+inner).reversed()).single()
        val bill=candidate.bill
        assertEquals(2345L,bill.amountCents)
        assertNull(bill.direction)
        assertEquals("",bill.merchant)
        assertEquals("招商银行",bill.receipt!!.receiptIssuer!!.value)
        assertEquals("日常用品",bill.receipt!!.transactionDescription!!.value)
        assertEquals(time.recognitionAt,bill.occurredAt)
        assertEquals("RECOGNITION_FALLBACK",bill.receipt!!.time!!.source)
        assertEquals("UNKNOWN",bill.paymentMethod!!.role)
        assertNull(bill.paymentMethod!!.cardLast4)
        assertFalse(bill.reliable)
        assertEquals("招商银行\n交易金额：23.45元\n交易说明：日常用品",candidate.rawText)
    }

    @Test fun actualBankCardBeforeServiceNoticeKeepsIssuerAccountDateAndBlockPosition() {
        val debit=lines("招商银行","交易成功提醒","交易类型：网上快捷支付","交易金额：34.56元",
            "交易卡号：尾号0007","交易日期：2026-11-01",top=100)
        val notice=lines("付款偏好通知","优先从零钱支付","交易已关闭","交易金额：999.00元",top=700)
        val candidate=parse((notice+debit).reversed()).single()
        val bill=candidate.bill
        assertEquals(3456L,bill.amountCents)
        assertEquals("0007",bill.paymentMethod!!.cardLast4)
        assertEquals("2026-11-01",bill.receipt!!.time!!.localDate)
        assertEquals("",bill.merchant)
        assertEquals("招商银行",bill.receipt!!.receiptIssuer!!.value)
        assertEquals("MIXED_DEBIT",bill.receipt!!.blockOrigin!!.kind)
        assertEquals(100,bill.receipt!!.blockOrigin!!.top)
        assertEquals(385,bill.receipt!!.blockOrigin!!.bottom)
        assertEquals(candidate.id.value,bill.receipt!!.blockId)
        assertEquals(candidate.id.value,bill.receipt!!.bank!!.transactionType!!.blockId)
        assertEquals(candidate.id.value,bill.receipt!!.receiptIssuer!!.blockId)
        assertTrue(bill.reliable)
    }

    @Test fun paymentPreferenceInActualCardCannotSelectTheHostingOrDefaultWallet() {
        val page=lines("服务开通通知","优先从零钱支付")+lines("扣款成功","交易金额：45.67元",
            "商户名称：合成商家","支付方式：优先从零钱支付",top=500)
        val bill=parse(page).single().bill
        val resolution=WalletResolver.resolve(listOf(WalletCandidate("wechat",kind="wechat"),WalletCandidate("cash",kind="cash")),
            sourcePackage="shake:com.tencent.mm",evidence=bill.paymentMethod,defaultAccountUid="cash",
            appBindings=mapOf(PaymentApps.WECHAT to "wechat"))
        assertNull(resolution.accountUid)
        assertEquals("ACTUAL_ACCOUNT_REQUIRED",resolution.reviewCode)
        assertEquals(4567L,bill.amountCents)
        assertEquals(Direction.EXPENSE,bill.direction)
        assertFalse(bill.reliable)
    }

    @Test fun serviceOnlyPageCannotCreateConsumptionFromARecommendedPaymentExample() {
        val page=lines("服务开通通知","优先从零钱支付","开通服务每月99.00元")+
            lines("为你推荐","支付成功","交易金额：888.00元","商户名称：合成火锅广告","通过零钱扣款",top=600)
        assertTrue(parse(page).isEmpty())
    }

    @Test fun unsupportedMixedGeometryCannotEscapeTheActualAccountGate() {
        val page=listOf("服务开通通知","优先从零钱支付","扣款成功","交易金额：12.34元",
            "支付方式：优先从零钱支付").map(::LocalOcrLine)
        assertTrue(parse(page).isEmpty())
    }
}
