package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class TransactionRejectionTest {
    @Test fun completedRefundsContinueToAdjustmentValidationEvenWhenNetIsZero() {
        assertNull(TransactionRejection.reason("订单详情\n实付款¥14.9\n退款成功 支付宝¥14.90"))
        assertNull(TransactionRejection.reason("订单详情\n实付款¥14.90\n退款成功 支付宝¥4.90"))
    }

    @Test fun feedbackIsSpecificAndDoesNotMistakeServiceButtonsForRefunds() {
        assertEquals("交易已关闭，不予记账",TransactionRejection.reason("交易关闭\n退款成功 支付宝¥14.90\n实付款¥14.9"))
        assertEquals("退款处理中，暂不记账",TransactionRejection.reason("退款处理中\n退款金额¥14.90\n实付款¥14.90"))
        assertNull(TransactionRejection.reason("退款成功\n120.00\n查看原账单\n退款方式 余额"))
        assertEquals("付款未成功，不予记账", TransactionRejection.reason("支付失败\n支付成功\n金额10.00"))
        assertNull(TransactionRejection.reason("交易成功\n申请退款\n极速退款\n实付款¥14.90"))
    }
}
