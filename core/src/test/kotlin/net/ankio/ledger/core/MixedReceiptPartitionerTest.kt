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

    @Test fun payLaterServiceHeaderStillKeepsTheCompletedDebitIndependent() {
        val page=lines("先享后付服务通知","优先从零钱支付","交易已关闭")+
            lines("扣款成功","交易金额：12.34元","商户名称：合成咖啡","通过零钱扣款",top=600)
        val candidate=TransactionPageParser.parse(page,1080,1800,time,origin="shake:com.tencent.mm").single()
        assertEquals(1234L,candidate.bill.amountCents)
        assertEquals("WECHAT_BALANCE",candidate.bill.paymentMethod!!.medium)
        assertFalse(candidate.rawText!!.contains("先享后付"))
    }

    @Test fun serviceUseNoticeAndTitlelessActualBalanceCardKeepOnlyTheLowerCardsBusinessFields() {
        val upper=lines("「先享后付」服务使用通知","商户名称：合成上卡商家","服务商：合成上卡服务","优先从零钱支付",
            "交易金额：888.00元","交易时间：2020-01-01 08:00","银行作者：招商银行")
        val lower=listOf(LocalOcrLine("合成下卡咖啡",350,650,730,695),LocalOcrLine("通过零钱扣款",360,730,720,770),
            LocalOcrLine("￥",350,820,410,890),LocalOcrLine("12.34",420,820,730,890),
            LocalOcrLine("扣费服务",100,980,300,1015),LocalOcrLine("合成地铁服务方",360,980,880,1015),
            LocalOcrLine("扣费内容",100,1040,300,1075),LocalOcrLine("午餐套餐",360,1040,880,1075),
            LocalOcrLine("查看订单详情",100,1120,600,1155))
        for(origin in listOf("shake:com.tencent.mm","image")) for(contentLabel in listOf("扣费内容","扣费內容")) {
            val observed=lower.map { if(it.text=="扣费内容")it.copy(text=contentLabel) else it }
            val candidate=TransactionPageParser.parse((upper+observed).reversed(),1080,1800,time,origin=origin).single()
            val bill=candidate.bill;val receipt=bill.receipt!!
            assertEquals(origin,1234L,bill.amountCents);assertEquals("合成下卡咖啡",bill.merchant)
            assertEquals("合成地铁服务方",receipt.serviceProvider!!.value)
            assertEquals("午餐套餐",receipt.transactionDescription!!.value)
            assertEquals("扣费服务\n合成地铁服务方",receipt.serviceProvider!!.rawText)
            assertNull(receipt.receiptIssuer);assertNull(receipt.bank)
            assertEquals("WECHAT_BALANCE",bill.paymentMethod!!.medium);assertTrue(bill.reliable)
            assertEquals(time.recognitionAt,bill.occurredAt)
            assertEquals(650,receipt.blockOrigin!!.top)
            if(origin=="image")assertNull(receipt.sourceApp) else assertEquals("com.tencent.mm",receipt.sourceApp!!.value)
            assertFalse(candidate.rawText!!.contains("上卡"));assertFalse(candidate.rawText!!.contains("查看订单详情"))
            val resolved=WalletResolver.resolve(listOf(WalletCandidate("wechat",kind="wechat"),WalletCandidate("cash",kind="cash")),
                sourcePackage=origin.takeUnless { it=="image" },evidence=bill.paymentMethod,defaultAccountUid="cash")
            assertEquals("wechat",resolved.accountUid)
        }
    }

    @Test fun paymentScreenshotSheetWithMicroBankNotificationExcludesOuterDateAndTheUnlabelledFeed() {
        val page=listOf(LocalOcrLine("付款截图 · 1 / 1",100,40,900,80),LocalOcrLine("服务号",400,200,680,240),
            LocalOcrLine("1条通知",100,270,400,305),LocalOcrLine("招商银行微银行: 交易成功提醒",100,360,780,400),
            LocalOcrLine("4分钟前",820,360,1000,400),LocalOcrLine("交易金额:",100,460,320,500),
            LocalOcrLine("人民币23.45",500,460,900,500),LocalOcrLine("全部通知 >",100,580,600,620),
            LocalOcrLine("2021/03/07 09:20",350,800,850,840),LocalOcrLine("贴图",100,1000,300,1040),
            LocalOcrLine("推荐",700,1000,820,1040),LocalOcrLine("关注",850,1000,1000,1040),
            LocalOcrLine("商户名称：合成推荐餐厅",100,1100,900,1140),LocalOcrLine("交易金额：777.00元",100,1160,900,1200),
            LocalOcrLine("交易说明：地铁广告",100,1220,900,1260))
        val candidate=TransactionPageParser.parse(page.reversed(),1080,1800,time,origin="image").single()
        val bill=candidate.bill;val receipt=bill.receipt!!
        assertEquals(2345L,bill.amountCents);assertNull(bill.direction);assertEquals("",bill.merchant)
        assertEquals("招商银行",receipt.receiptIssuer!!.value);assertEquals("cmb",receipt.bank!!.bankCode)
        assertNull(receipt.merchant);assertNull(receipt.transactionDescription)
        assertEquals(time.recognitionAt,bill.occurredAt);assertEquals("RECOGNITION_FALLBACK",receipt.time!!.source)
        assertFalse(bill.reliable);assertEquals("NESTED_BANK",receipt.blockOrigin!!.kind)
        for(unrelated in listOf("2021","777","地铁","推荐","4分钟前","全部通知"))assertFalse(unrelated,candidate.rawText!!.contains(unrelated))
    }
}
