package net.ankio.ledger.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AmountOcrArtifactsTest {
    private val capturedAt = LocalDateTime.of(2026, 10, 19, 13, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun parse(vararg text: String, origin: String = "com.xunmeng.pinduoduo") =
        LocalOcrBillParser.parse(text.map(::LocalOcrLine), 0, 0, capturedAt, origin = origin)

    @Test fun separateHelpGlyphBeforeFreeShippingDoesNotErasePaidAmountOrOrderIdentity() {
        for (glyph in listOf("O", "Ｏ", "o")) {
            val bill = parse("先用后付 实付: ¥6.89 $glyph (免运费)",
                "订单編号: 260925-183731556732484", "店铺名称: 合成厨具店")
            assertNotNull(glyph, bill)
            assertEquals(689L, bill!!.amountCents)
            assertEquals("260925-183731556732484", bill.transactionId)
            assertTrue(bill.reason, bill.reliable)
        }
    }

    @Test fun lettersInsideNumbersOrUnsupportedSuffixesAreNeverGuessedAsDigits() {
        for (value in listOf("6.8O", "6.89O", "6.O9", "6.89 OO", "6.89 O 8")) {
            val bill = parse("先用后付 实付: ¥$value (免运费)")
            assertTrue(value, bill == null || bill.amountCents == null || !bill.reliable)
        }
        val bill = parse("先用后付 实付: ¥6.89 O")
        assertTrue(bill == null || bill.amountCents == null || !bill.reliable)
    }

    @Test fun helpGlyphDoesNotOverridePendingStateOrConflictingTotals() {
        assertNull(parse("先用后付 实付: ¥6.89 O (免运费)", "待付款"))
        assertNull(parse("先用后付 实付: ¥6.89 O (免运费)", "实付: ¥7.89 (免运费)"))
    }

    private fun refund(originalLabel: String, refundedLabel: String, vararg extra: String) =
        parse("已部分退款", "$originalLabel ¥100.00", "$refundedLabel ¥20.00",
            "商户名称: 合成咖啡店", "支付时间: 2026年10月19日12:11:32",
            "交易单号: 4200001234202610191234567890", *extra, origin = PaymentApps.WECHAT)

    @Test fun mixedAmountLabelGlyphsPreserveCumulativeRefundArithmetic() {
        for (original in listOf("原付款金额額", "原付款金額", "原付款金额")) {
            for (refunded in listOf("累计退款金額", "累计退款金额額", "累计退款金额")) {
                val bill = refund(original, refunded)!!
                assertEquals("$original / $refunded", 8000L, bill.amountCents)
                assertTrue(bill.reason, bill.reliable)
                assertEquals(10000L, bill.refund!!.originalAmountCents)
                assertEquals(2000L, bill.refund!!.refundedCents)
                assertEquals("cumulative", bill.refund!!.mode)
                assertEquals("4200001234202610191234567890", bill.transactionId)
            }
        }
    }

    @Test fun amountLabelVariantsDoNotRelaxRefundCompletionOrConflictChecks() {
        for (status in listOf("退款处理中", "退款失败", "退款审核中")) {
            assertNull(status, refund("原付款金額", "累计退款金額", status))
        }
        val conflict = refund("原付款金額", "累计退款金額", "累计退款金额:30.00")!!
        assertFalse(conflict.reliable)
        assertNull(conflict.amountCents)
        val excessive = refund("原付款金額", "累计退款金額", "原付款金额:10.00")!!
        assertFalse(excessive.reliable)
        assertNull(excessive.amountCents)
    }

    @Test fun singleRefundVariantStillNeedsItsOwnIdentityAndRecognizedOriginalPayment() {
        val fields = arrayOf("退款成功", "原付款金額:100.00", "本次退款金額:20.00", "退款方式:零钱",
            "原支付时间:2026年10月19日12:11:32", "原交易单号:4200001234202610191234567890")
        assertFalse(StandaloneRefundReceipt.matches(fields.joinToString("\n")))
        val incomplete = parse(*fields, origin = PaymentApps.WECHAT)!!
        assertFalse(incomplete.reliable)
        assertNull(incomplete.amountCents)
        val complete = parse(*fields, "退款单号:202610191234567890123456", origin = PaymentApps.WECHAT)!!
        assertEquals(8000L, complete.amountCents)
        assertEquals("single", complete.refund!!.mode)
        assertTrue(complete.reason, complete.reliable)
    }

    @Test fun standaloneOrFullyRefundedReceiptIsStillNotNewExpense() {
        assertTrue(StandaloneRefundReceipt.matches("退款成功\n退款方式:零钱\n退款金額:20.00"))
        val pending = parse("退款成功", "退款方式:零钱", "退款金額:20.00", origin = PaymentApps.WECHAT)!!
        assertFalse(pending.reliable); assertNull(pending.amountCents)
        assertEquals(2000L, pending.standaloneRefund!!.refundedCents)
        // A status word alone cannot replace the verified cumulative amount.
        val conflicting = refund("原付款金額", "累计退款金額", "已全额退款")!!
        assertEquals(8000L, conflicting.amountCents); assertFalse(conflicting.reliable)
    }
}
