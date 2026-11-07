package net.ankio.ledger.core

import java.text.Normalizer

/** Terminal/pending states stop ingestion. Completed refunds continue to adjustment validation. */
object TransactionRejection {
    private val closed = Regex("交易(?:已)?关闭|订单(?:已)?关闭|订单已取消|交易已取消")
    private val refundPending = Regex("退款处理中|退款申请中|退款审核中|退款受理中|退款中|待退款|等待退款")

    fun reason(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val compact = normalized.filterNot(Char::isWhitespace)
        if (closed.containsMatchIn(compact)) return "交易已关闭，不予记账"
        // “申请退款” and “极速退款” are service buttons on ordinary paid orders.
        if (refundPending.containsMatchIn(compact)) return "退款处理中，暂不记账"
        if (compact.contains("退款失败")) return "退款未完成，暂不记账"
        if (Regex("支付失败|付款失败|交易失败|转账失败|扣款失败").containsMatchIn(compact)) return "付款未成功，不予记账"
        return null
    }
}
