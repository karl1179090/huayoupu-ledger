package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BankReceiptParserTest {
    private val now = 1_798_776_000_000L
    private fun parse(vararg fields: String, policy: RecognitionPolicy = RecognitionPolicy.USER_INITIATED) =
        LocalOcrBillParser.parse((listOf("招商银行", "交易成功提醒") + fields).map(::LocalOcrLine),
            0, 0, now, policy=policy, origin="shake:com.tencent.mm")

    @Test fun nativeBankSourceRuleUsesCapturedPackageAcrossExistingTriggerPrefixes() {
        val raw="招商银行\n交易成功提醒\n交易类型：网上快捷支付\n交易金额：12.00元\n交易卡号：尾号0007\n交易说明：午餐"
        val bill=LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,now,origin="image")!!
        val rule=ClassificationRule("native-bank","原生午餐",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"午餐")),
            source="LIVE:${PaymentApps.WECHAT}")
        val snapshot=ClassificationSnapshot("synthetic-native-bank",listOf(rule),emptyList())
        for(prefix in listOf("shake:","shortcut:","payment_auto:")) {
            val input=ClassificationInput.from(bill,Capture(Source.OCR,prefix+PaymentApps.WECHAT,raw,now))
            assertEquals(prefix,PaymentApps.WECHAT,input.sourcePackage)
            assertEquals(prefix,"native-bank",ClassificationEngine.classify(snapshot,input).ruleId)
        }
    }

    @Test fun missingYearBankImageDateAndCompoundInputsKeepIssuerOutOfMerchantAndPlatform() {
        val at=Instant.parse("2026-11-08T04:00:00Z").toEpochMilli()
        val time=RecognitionTimeContext(at,"Asia/Shanghai","2026-11-02")
        val lines=listOf("招商银行","交易成功提醒","交易类型：网上快捷支付","交易金额：12.00元",
            "交易卡号：尾号0007","交易说明：午餐","交易日期：11月01日","微信支付广告")
        val bill=LocalOcrBillParser.parse(lines.map(::LocalOcrLine),0,0,at,origin="image",timeContext=time)!!
        assertTrue(bill.reliable)
        assertEquals("",bill.merchant)
        assertEquals("招商银行",bill.receipt!!.receiptIssuer!!.value)
        assertEquals("YEAR_INFERRED",bill.receipt!!.time!!.source)
        assertEquals("DATE",bill.receipt!!.time!!.precision)
        assertEquals("2026-11-01",bill.receipt!!.time!!.localDate)
        assertEquals(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli(),bill.occurredAt)
        val input=ClassificationInput.from(bill,Capture(Source.IMAGE,"image",lines.joinToString("\n"),at))
        assertEquals(Source.IMAGE,input.captureSource)
        assertNull(input.platform)
        fun rule(id:String,order:Int,field:ClassificationField,op:ClassificationOperator,value:String,source:String)=
            ClassificationRule(id,id,true,Direction.EXPENSE,"cat:expense:dining",order,
                listOf(ClassificationCondition(field,op,value)),source=source)
        val snapshot=ClassificationSnapshot("synthetic-bank-image",listOf(
            rule("sender",0,ClassificationField.MERCHANT,ClassificationOperator.CONTAINS,"招商银行","IMAGE"),
            rule("missing",1,ClassificationField.MERCHANT,ClassificationOperator.NOT_CONTAINS,"打车","IMAGE"),
            rule("carrier",2,ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"午餐","IMAGE:wechat"),
            rule("business",3,ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"午餐","IMAGE")),emptyList())
        assertEquals("business",ClassificationEngine.classify(snapshot,input).ruleId)
        assertEquals("cmb",bill.paymentMethod!!.bankCode)
        assertEquals("0007",bill.paymentMethod!!.cardLast4)
    }

    @Test fun explicitPaymentIssuerAllowsImagePlatformCompoundRuleWithoutChangingBankAccountEvidence() {
        val at=Instant.parse("2026-11-08T04:00:00Z").toEpochMilli()
        val raw="支付成功\n交易金额：12.00元\n商户名称：合成咖啡\n商品说明：午餐\n付款方式：招商银行(0007)\n凭证来源：微信支付"
        val bill=LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,at,origin="image")!!
        val rule=ClassificationRule("explicit","明确平台午餐",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(ClassificationField.MERCHANT,ClassificationOperator.CONTAINS,"合成"),
                ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"午餐")),
            matchMode=ClassificationMatchMode.ALL,source="IMAGE:wechat")
        val snapshot=ClassificationSnapshot("synthetic-explicit-image",listOf(rule),emptyList())
        val image=ClassificationInput.from(bill,Capture(Source.IMAGE,"image",raw,at))
        assertEquals("wechat",image.platform)
        assertEquals("explicit",ClassificationEngine.classify(snapshot,image).ruleId)
        assertNull(ClassificationEngine.classify(snapshot,ClassificationInput.from(bill,Capture(Source.OCR,PaymentApps.WECHAT,raw,at))).ruleId)
        assertEquals("cmb",bill.paymentMethod!!.bankCode)
        assertEquals("0007",bill.paymentMethod!!.cardLast4)
    }

    @Test fun actualAccountFieldsDoNotDowngradeTailsOrMixPreferencesAndCounterparties() {
        val explicit=parse("交易类型：网上快捷支付","交易金额：12.00元","付款方式：招商银行(9999)",
            "支付方式：优先使用工商银行(1234)")!!
        assertEquals("cmb",explicit.paymentMethod!!.bankCode)
        assertEquals("9999",explicit.paymentMethod!!.cardLast4)
        assertFalse(explicit.paymentMethod!!.ambiguous)
        val noTail=parse("交易类型：网上快捷支付","交易金额：12.00元","付款方式","招商银行","账户余额：1234.56元")!!
        assertNull(noTail.paymentMethod!!.cardLast4)
        assertFalse(noTail.paymentMethod!!.ambiguous)
        val counterparty=parse("交易类型：网上快捷支付","交易金额：12.00元","交易卡号：尾号0007",
            "收款账户：工商银行(1234)")!!
        assertEquals("cmb",counterparty.paymentMethod!!.bankCode)
        assertEquals("0007",counterparty.paymentMethod!!.cardLast4)
        assertFalse(counterparty.paymentMethod!!.ambiguous)
        val acquirer=parse("交易类型：网上快捷支付","交易金额：12.00元","交易卡号：尾号0007","收单机构：工商银行")!!
        assertEquals("cmb",acquirer.paymentMethod!!.bankCode)
        assertFalse(acquirer.paymentMethod!!.ambiguous)
        val conflicting=parse("交易类型：网上快捷支付","交易金额：12.00元","交易卡号：尾号0007","通过零钱扣款")!!
        assertTrue(conflicting.paymentMethod!!.ambiguous)
        assertFalse(conflicting.reliable)
    }

    @Test fun unreadableCompetingTransactionAmountCannotBeDiscardedToPostTheOtherValue() {
        val bill=parse("交易类型：网上快捷支付","交易金额：12.00元","交易金额：无法读取","交易卡号：尾号0007")!!
        assertNull(bill.amountCents)
        assertFalse(bill.reliable)
    }

    @Test fun bankTimeUsesTheFrozenRecognitionContextOrItsOwnExplicitTransactionDate() {
        val at=Instant.parse("2026-11-08T04:00:00Z").toEpochMilli()
        val context=RecognitionTimeContext(at,"Asia/Shanghai")
        val lines=listOf("招商银行","交易成功提醒","交易类型：网上快捷支付","交易金额：12.00元","交易卡号：尾号0007")
        val fallback=LocalOcrBillParser.parse(lines.map(::LocalOcrLine),0,0,at-172800000,origin="image",timeContext=context)!!
        assertEquals(at,fallback.occurredAt)
        assertEquals("RECOGNITION_FALLBACK",fallback.receipt!!.time!!.source)
        assertEquals("交易类型：网上快捷支付",fallback.receipt!!.bank!!.transactionType!!.rawText)
        val dated=LocalOcrBillParser.parse((lines+"交易时间：2026-11-07 09:10"+"通知时间：2026-11-08 12:00").map(::LocalOcrLine),
            0,0,at-172800000,origin="image",timeContext=context)!!
        assertEquals(Instant.parse("2026-11-07T01:10:00Z").toEpochMilli(),dated.occurredAt)
        assertEquals("2026-11-07",dated.receipt!!.time!!.localDate)
        assertTrue(dated.timeExplicit)
    }

    @Test fun unknownOrMissingBankIssuerCannotBorrowAnotherBanksTailOrAssumeExpense() {
        val lines = listOf("合成银行", "交易成功提醒", "交易类型：网上快捷支付", "交易金额：12.00元", "交易卡号：尾号0007")
        val bill = LocalOcrBillParser.parse(lines.map(::LocalOcrLine), 0, 0, now, origin="image")!!
        assertEquals("BANK_CARD", bill.paymentMethod!!.medium)
        assertNull(bill.paymentMethod!!.bankCode)
        assertTrue(bill.paymentMethod!!.ambiguous)
        assertFalse(bill.reliable)
        assertEquals("合成银行", bill.receipt!!.receiptIssuer!!.value)
        val missing = LocalOcrBillParser.parse(listOf("交易成功提醒", "交易金额：12.00元", "账户余额：1234.56元").map(::LocalOcrLine),
            0, 0, now, origin="image")!!
        assertNull(missing.direction)
        assertTrue(missing.paymentMethod!!.ambiguous)
    }

    @Test fun balanceMarketingPendingAndZeroAmountsAreNotCompletedTransactions() {
        val cases = listOf(arrayOf("账户余额：1234.56元"), arrayOf("优惠金额：12.00元", "积分：20"),
            arrayOf("交易类型：网上快捷支付", "交易金额：0.00元"),
            arrayOf("交易类型：网上快捷支付", "交易金额：12.00元", "交易状态：付款中"))
        cases.forEach { assertNull(it.joinToString("/"), parse(*it)) }
    }

    @Test fun malformedPrintedTailIsUncertainRatherThanANoTailBankMatch() {
        val bill = parse("交易类型：网上快捷支付", "交易金额：12.00元", "交易卡号：尾号OOO7")!!
        assertTrue(bill.paymentMethod!!.ambiguous)
        assertFalse(bill.reliable)
    }

    @Test fun splitUnorderedBankFieldsStayOnTheirOwnRowsAndKeepExplicitMerchant() {
        val lines = listOf(LocalOcrLine("招商银行",20,10,180,30), LocalOcrLine("交易成功提醒",20,50,200,70),
            LocalOcrLine("交易金额",20,100,120,120), LocalOcrLine("12.00元",240,100,340,120),
            LocalOcrLine("交易类型",20,140,120,160), LocalOcrLine("网上快捷支付",240,140,400,160),
            LocalOcrLine("交易卡号",20,180,120,200), LocalOcrLine("尾号0007",240,180,350,200),
            LocalOcrLine("商户名称",20,220,120,240), LocalOcrLine("合成咖啡",240,220,350,240)).reversed()
        val bill = LocalOcrBillParser.parse(lines, 500, 800, now, origin="image")!!
        assertEquals(1200L, bill.amountCents)
        assertEquals(Direction.EXPENSE, bill.direction)
        assertEquals("0007", bill.paymentMethod!!.cardLast4)
        assertEquals("合成咖啡", bill.merchant)
        assertEquals("招商银行", bill.receipt!!.receiptIssuer!!.value)
        assertEquals("网上快捷支付", bill.receipt!!.bank!!.transactionType!!.value)
        assertTrue(bill.receipt!!.bank!!.transactionType!!.rawText.contains("网上快捷支付"))
        assertTrue(bill.reliable)
    }

    @Test fun balanceDigitsNeverBecomeACardTail() {
        val bill = parse("交易类型：网上快捷支付", "交易金额：12.00元", "账户余额：1234.56元")!!
        assertNull(bill.paymentMethod!!.cardLast4)
        assertFalse(bill.paymentMethod!!.ambiguous)
    }

    @Test fun passivePolicyNeverAcceptsAnAdaptedBankNotification() {
        val fields = arrayOf("交易类型：网上快捷支付", "交易金额：12.00元", "交易卡号：尾号0007")
        assertNull(parse(*fields, policy=RecognitionPolicy.AUTOMATIC_PAYMENT))
        val bill = parse(*fields)!!
        assertFalse(RecognitionPolicy.AUTOMATIC_PAYMENT.allows(bill, PaymentApps.WECHAT))
        assertTrue(RecognitionPolicy.USER_INITIATED.allows(bill, PaymentApps.WECHAT))
    }

    @Test fun genericSuccessDoesNotInventADebitRoleFromTheBalance() {
        val bill = parse("交易金额：12.00元", "交易卡号：尾号0007", "账户余额：988.00元")!!
        assertNull(bill.direction)
        assertEquals("UNKNOWN", bill.paymentMethod!!.role)
        assertFalse(bill.reliable)
    }

    @Test fun bankReceiptUsesActualCardEvidenceAndMappedTypeWithoutInventingMerchant() {
        val bill = parse("交易类型：网上快捷支付", "交易金额：12.00元", "交易卡号：尾号0007", "账户余额：988.00元")!!
        assertEquals(1200L, bill.amountCents)
        assertEquals(Direction.EXPENSE, bill.direction)
        assertEquals("", bill.merchant)
        assertEquals("cmb", bill.paymentMethod!!.bankCode)
        assertEquals("0007", bill.paymentMethod!!.cardLast4)
        assertEquals("BANK_CARD", bill.paymentMethod!!.medium)
        assertTrue(bill.reliable)
    }
    @Test fun inlineAccountProofDoesNotInventUnsupportedTypesOrHideConflictingOrMalformedTails() {
        fun bill(type:String,vararg extra:String)=LocalOcrBillParser.parse((listOf("招商银行微银行","交易成功提醒",
            "交易类型：$type","交易金额：23.71元")+extra).map(::LocalOcrLine),0,0,now,origin="image")!!
        val unknown=bill("非适配交易（尾号0007）")
        assertNull(unknown.direction);assertFalse(unknown.reliable);assertEquals("0007",unknown.paymentMethod!!.cardLast4)
        val conflicting=bill("网上快捷支付（尾号0007）","交易卡号：尾号0008")
        assertFalse(conflicting.reliable);assertTrue(conflicting.paymentMethod!!.ambiguous)
        val malformed=bill("网上快捷支付（尾号OOO7）")
        assertNull(malformed.direction);assertFalse(malformed.reliable);assertTrue(malformed.paymentMethod!!.ambiguous)
    }

}
