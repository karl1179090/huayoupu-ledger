package net.ankio.ledger.core

/** Null leaves unsupported layouts on the existing parser; empty means a service-only page. */
object MixedReceiptPartitioner {
    private val service=Regex("^(?:自动扣费)?服务开通(?:通知|成功)$|^(?:付款|支付)偏好(?:通知|设置)$|^免密支付开通成功$")
    private val debit=Regex("^(?:扣款|付款|支付|扣费)成功$")
    private val bankHeading=Regex("^(?:.*银行[：: ]*)?(?:交易成功提醒|交易提醒|动账通知|消费提醒|账户变动通知)$")
    private val peripheral=Regex("^(?:广告|为你推荐|猜你喜欢|相关推荐|积分推荐)(?:[：:>〉\\s]|$)")
    private val viewer=Regex("^(?:图片查看器|图片详情|查看原图|保存图片|账单记录(?:时间)?[：:].*)$")
    private val ignored=Regex("^(?:账户余额|可用余额|余额|积分|状态栏|通知时间|账单记录时间|记录时间|查看时间|保存图片|查看原图|返回)(?:[：:>〉\\s]|$)")
    private val amount=Regex("^(?:交易金额|消费金额|支出金额|收入金额|发生金额|金额)[：:]?")

    fun partition(lines:List<LocalOcrLine>,width:Int,height:Int):List<TransactionBlock>? {
        val page=if(lines.all { it.bottom>it.top })lines.sortedWith(compareBy({it.top},{it.left})) else lines
        val ordered=ReceiptEvidenceParser.businessLines(page)
        fun text(index:Int)=normalize(ordered[index].text).filterNot(Char::isWhitespace)
        if(ordered.indices.none { service.matches(text(it)) }) {
            if(ordered.none { viewer.matches(normalize(it.text).filterNot(Char::isWhitespace)) } ||
                ordered.any { it.bottom<=it.top || it.right<=it.left } || width<=0 || height<=0)return null
            val start=ordered.indexOfFirst { BankCodes.normalize(text(ordered.indexOf(it)))!=null }
            if(start<0)return null
            val end=(start+1 until ordered.size).firstOrNull { index ->
                peripheral.containsMatchIn(ordered[index].text.trim()) || viewer.matches(text(index)) ||
                    ordered[index].top-ordered[index-1].bottom>maxOf(ordered[index].bottom-ordered[index].top,
                        ordered[index-1].bottom-ordered[index-1].top)*4
            } ?: ordered.size
            val selected=ordered.subList(start,end).filterNot { ignored.containsMatchIn(it.text.trim()) }
            if(selected.none { amount.containsMatchIn(normalize(it.text).filterNot(Char::isWhitespace)) })return null
            val origin=origin("NESTED_BANK",selected,width,height) ?: return null
            return listOf(TransactionBlock(CandidateId("nested_0"),selected,origin))
        }
        val starts=ordered.indices.mapNotNull { index ->
            when {
                bankHeading.matches(text(index)) -> {
                    val previous=ordered.getOrNull(index-1)
                    (if(previous!=null && BankCodes.normalize(normalize(previous.text).filterNot(Char::isWhitespace))!=null)index-1 else index) to true
                }
                debit.matches(text(index)) -> index to true
                service.matches(text(index)) || peripheral.containsMatchIn(ordered[index].text.trim()) -> index to false
                else -> null
            }
        }.sortedBy { it.first }
        return starts.mapIndexedNotNull { ordinal,(start,isTransaction) ->
            if(!isTransaction)return@mapIndexedNotNull null
            val selected=ordered.subList(start,starts.getOrNull(ordinal+1)?.first ?: ordered.size)
                .filterNot { ignored.containsMatchIn(it.text.trim()) }
            val origin=origin("MIXED_DEBIT",selected,width,height) ?: return@mapIndexedNotNull null
            TransactionBlock(CandidateId("mixed_$ordinal"),selected,origin)
        }
    }

    private fun origin(kind:String,lines:List<LocalOcrLine>,width:Int,height:Int):ReceiptBlockOrigin? {
        if(lines.isEmpty() || lines.any { it.bottom<=it.top || it.right<=it.left })return null
        return runCatching { ReceiptBlockOrigin(kind,width,height,lines.minOf { it.left },lines.minOf { it.top },
            lines.maxOf { it.right },lines.maxOf { it.bottom }) }.getOrNull()
    }
}
