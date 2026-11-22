package net.ankio.ledger.core

/** Null leaves unsupported layouts on the existing parser; empty means a service-only page. */
object MixedReceiptPartitioner {
    private val service=Regex("^(?:(?:自动扣费|先享后付)?服务(?:开通(?:通知|成功)|通知)|[「『]?先享后付[」』]?服务使用通知)$|^(?:付款|支付)偏好(?:通知|设置)$|^免密支付开通成功$")
    private val debit=Regex("^(?:扣款|付款|支付|扣费)(?:成功|失败)$")
    private val failedDebit=Regex("^(?:扣款|付款|支付|扣费)失败$")
    private val actualBalanceDebit=Regex("^(?:本次)?通过(?:微信)?零钱扣款[。.]?$")
    private val peripheral=Regex("^(?:广告|为你推荐|猜你喜欢|相关推荐|积分推荐)(?:[：:>〉\\s]|$)")
    private val feed=Regex("^(?:贴图(?:推荐)?(?:关注)?|推荐(?:关注)?|关注)$")
    private val viewer=Regex("^(?:图片查看器|图片详情|查看原图|保存图片|账单记录(?:时间)?[：:].*|付款截图[·•.]?[0-9]{1,3}/[0-9]{1,3})$")
    private val notificationEnd=Regex("^全部通知[>〉]?$" )
    private val notificationTime=Regex("^(?:刚刚|[0-9]+(?:分钟|小时|天)前)$")
    private val ignored=Regex("^(?:账户余额|可用余额|余额|积分|状态栏|通知时间|账单记录时间|记录时间|查看时间|保存图片|查看原图|查看订单详情|返回)(?:[：:>〉\\s]|$)")
    private val amount=Regex("^(?:交易金额|消费金额|支出金额|收入金额|发生金额|金额)[：:]?")
    private val cardTail = Regex("^(?:交易卡号|交易账号|交易账户|卡号|账号|卡尾号|尾号)[：:]?.*[0-9]{4}$")
    private data class CardBoundary(val start: Int, val heading: Int, val isTransaction: Boolean)

    fun partition(lines:List<LocalOcrLine>,width:Int,height:Int):List<TransactionBlock>? {
        val page=if(lines.all { it.bottom>it.top && it.right>it.left })lines.sortedWith(compareBy({it.top},{it.left})) else lines
        val viewerContext=page.any { viewer.matches(normalize(it.text).filterNot(Char::isWhitespace)) }
        fun isPeripheral(line:LocalOcrLine)=peripheral.containsMatchIn(line.text.trim()) ||
            viewerContext && feed.matches(normalize(line.text).filterNot(Char::isWhitespace))
        val business=ReceiptEvidenceParser.businessLines(page)
        val ordered=business.take(business.indexOfFirst(::isPeripheral).takeIf { it>=0 } ?: business.size)
        fun text(index:Int)=normalize(ordered[index].text).filterNot(Char::isWhitespace)
        fun bankHeading(index: Int) = BankReceiptParser.isHeading(ordered[index].text)
        if(ordered.indices.none { service.matches(text(it)) }) {
            if(ordered.indices.none { viewer.matches(text(it)) })return null
            val start=ordered.indexOfFirst { BankReceiptParser.issuerCode(it.text)!=null || BankReceiptParser.matches(listOf(it)) }
            if(start<0)return null
            val end=(start+1 until ordered.size).firstOrNull { index ->
                viewer.matches(text(index)) || notificationEnd.matches(text(index))
            } ?: ordered.size
            val contents = ordered.subList(start, end)
            if (contents.any { it.bottom <= it.top || it.right <= it.left } || width <= 0 || height <= 0) return emptyList()
            val bankCards = TransactionPageParser.bankBlocks(contents)
            // With only one formal heading, a second issuer banner can still mark a separate card.
            if (bankCards == null && contents.drop(1).any { !BankReceiptParser.isHeading(it.text) && BankReceiptParser.issuerCode(it.text) != null })
                return emptyList()
            val cards = bankCards ?: listOf(TransactionBlock(CandidateId("nested_0"), contents))
            return cards.mapIndexedNotNull { ordinal, card ->
                val scoped = card.lines
                val gap = (1 until scoped.size).firstOrNull { index ->
                    val before = scoped[index - 1]
                    val after = scoped[index]
                    after.top - before.bottom > maxOf(after.bottom - after.top, before.bottom - before.top) * 4
                } ?: scoped.size
                val selected = scoped.take(gap).filterNot { ignored.containsMatchIn(it.text.trim()) ||
                    notificationTime.matches(normalize(it.text).filterNot(Char::isWhitespace)) }
                if (bankCards == null && selected.none { amount.containsMatchIn(normalize(it.text).filterNot(Char::isWhitespace)) })
                    return@mapIndexedNotNull null
                val origin = origin("NESTED_BANK", selected, width, height) ?: return@mapIndexedNotNull null
                TransactionBlock(CandidateId("nested_$ordinal"), selected, origin)
            }
        }
        val boundaries=ordered.indices.mapNotNull { index ->
            when {
                bankHeading(index) -> {
                    val previous=ordered.getOrNull(index-1)
                    val start = if(previous!=null && !bankHeading(index-1) && BankReceiptParser.issuerCode(previous.text)!=null)index-1 else index
                    CardBoundary(start, index, true)
                }
                debit.matches(text(index)) -> CardBoundary(index, index, true)
                actualBalanceDebit.matches(text(index)) -> {
                    val boundary=(0 until index).lastOrNull { service.matches(text(it)) || debit.matches(text(it)) || bankHeading(it) }
                    // An executed-account line inside an already headed card stays in that card.
                    if(boundary!=null && (debit.matches(text(boundary)) || bankHeading(boundary)))null
                    else {
                        val previous=ordered.getOrNull(index-1);val beforePrevious=ordered.getOrNull(index-2)
                        val header=previous!=null && beforePrevious!=null && index-1>(boundary ?: -1) &&
                            previous.bottom>previous.top && previous.right>previous.left &&
                            previous.bottom<=ordered[index].top && ordered[index].top-previous.bottom<=maxOf(previous.bottom-previous.top,ordered[index].bottom-ordered[index].top)*2 &&
                            previous.top-beforePrevious.bottom>maxOf(previous.bottom-previous.top,beforePrevious.bottom-beforePrevious.top)*1.5
                        CardBoundary(if(header)index-1 else index, index, true)
                    }
                }
                service.matches(text(index)) || isPeripheral(ordered[index]) -> CardBoundary(index, index, false)
                else -> null
            }
        }.sortedBy { it.start }
        val starts = boundaries.filterIndexed { index, boundary ->
            if (!failedDebit.matches(text(boundary.heading)) || boundaries.getOrNull(index - 1)?.isTransaction != true) {
                true
            } else {
                val heading = ordered[boundary.heading]
                val previous = ordered[boundary.heading - 1]
                val gap = heading.bottom > heading.top && previous.bottom > previous.top &&
                    heading.top - previous.bottom > maxOf(heading.bottom - heading.top, previous.bottom - previous.top) * 2
                val end = boundaries.getOrNull(index + 1)?.start ?: ordered.size
                gap || hasDebitFields(ordered.subList(boundary.heading + 1, maxOf(boundary.heading + 1, end)))
            }
        }
        val headings = starts.filter { it.isTransaction }.map { ordered[it.heading] }
        if (!hasReliableVerticalCardOrder(ordered, headings) || starts.zipWithNext().any { (first, next) -> first.start == next.start })
            return emptyList()
        return starts.mapIndexedNotNull { ordinal,(start,_,isTransaction) ->
            if(!isTransaction)return@mapIndexedNotNull null
            val selected=ordered.subList(start,starts.getOrNull(ordinal+1)?.start ?: ordered.size)
                .filterNot { ignored.containsMatchIn(it.text.trim()) }
            val origin=origin("MIXED_DEBIT",selected,width,height) ?: return@mapIndexedNotNull null
            TransactionBlock(CandidateId("mixed_$ordinal"),selected,origin)
        }
    }

    private fun hasDebitFields(lines: List<LocalOcrLine>): Boolean {
        if (lines.any { line ->
                val text = normalize(line.text).filterNot(Char::isWhitespace)
                val monetary = amount.find(text)?.let { text.substring(it.range.last + 1) }
                    ?: text.takeIf { it.startsWith("¥") || it.startsWith("人民币") }
                cardTail.matches(text) || monetary?.let(Money::parseCents) != null
            }) return true
        return PaymentMethodParser.parse(lines, Direction.EXPENSE)?.let { it.medium != "UNKNOWN" && !it.ambiguous } == true
    }

    private fun origin(kind:String,lines:List<LocalOcrLine>,width:Int,height:Int):ReceiptBlockOrigin? {
        if(lines.isEmpty() || lines.any { it.bottom<=it.top || it.right<=it.left })return null
        return runCatching { ReceiptBlockOrigin(kind,width,height,lines.minOf { it.left },lines.minOf { it.top },
            lines.maxOf { it.right },lines.maxOf { it.bottom }) }.getOrNull()
    }
}
