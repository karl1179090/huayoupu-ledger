package net.ankio.ledger.core

/** Identifies refund receipts for review; this is never a reason to discard a completed adjustment. */
object StandaloneRefundReceipt {
    const val MESSAGE="退款回执待核对，请补充原付款详情"
    fun message(raw:String):String=if(Regex("已全[额颌]退款|已全部退款|全额退款成功").containsMatchIn(raw.filterNot(Char::isWhitespace)))"全额退款待关联原账单" else MESSAGE
    fun matches(raw:String):Boolean {
        val text=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFKC).filterNot(Char::isWhitespace)
        if(Regex("已全[额颌]退款|已全部退款|全额退款成功").containsMatchIn(text))return true
        return Regex("退款成功|退款已到账|退款到账成功").containsMatchIn(text) &&
            Regex("查看原账单|退款方式|退回方式|退款到账时间").containsMatchIn(text) &&
            !Regex("原(?:付款|支付|交易|订单)金[额額]{1,2}|原付[：:¥0-9]|累计(?:成功)?退款|已部分退款").containsMatchIn(text)
    }
}
