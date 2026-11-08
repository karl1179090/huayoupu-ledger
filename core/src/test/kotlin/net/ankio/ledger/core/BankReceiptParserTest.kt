package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BankReceiptParserTest {
    private val now = 1_798_776_000_000L
    private fun parse(vararg fields: String, policy: RecognitionPolicy = RecognitionPolicy.USER_INITIATED) =
        LocalOcrBillParser.parse((listOf("招商银行", "交易成功提醒") + fields).map(::LocalOcrLine),
            0, 0, now, policy=policy, origin="shake:com.tencent.mm")

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
}
