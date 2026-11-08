package net.ankio.ledger.core

/** Explicit business labels only. It never treats the whole page as order content. */
internal object ReceiptEvidenceParser {
    val merchantLabel = Regex("^(?:商[户戶](?:全称|名称|金称|各称)|商家名称|店铺名称|收款方(?:全称|名称)?(?!备注|服务)|付款方(?:全称|名称)?(?!备注|服务)|交易对方|对方账户|对方账号)[：:]?")
    private val product = Regex("^(?:商品说明|商品名称|商品)(?:[：:]\\s*|\\s+|$)")
    private val description = Regex("^(?:交易说明|交易用途)[：:]?")
    private val remark = Regex("^订单备注[：:]?")
    private val service = Regex("^(?:服务商|扣费服务商|支付服务商)[：:]?")
    private val serviceAttribution = Regex("^服务由\\s*(.+?)\\s*提供[。.·]?$" )
    private val issuer = Regex("^(?:凭证来源|出具机构|出具方|收单机构|支付机构|银行作者)[：:]?")
    private val boundary = Regex("^(?:付款|支付|交易|到账|金额|商[户戶家]|店铺|收款|订单|商品|服务商|扣费服务商|支付服务商|凭证来源|出具|收单机构|支付机构|银行作者|优惠|积分|广告|返回|完成)")
    private val peripheral = Regex("^(?:广告|为你推荐|猜你喜欢|优惠推荐|更多推荐|相关推荐|积分推荐)(?:[：:>〉\\s]|$)")

    /** Single-receipt scope ends at a marked recommendation section, independent of OCR order. */
    fun businessLines(lines: List<LocalOcrLine>): List<LocalOcrLine> {
        val markers = lines.filter { peripheral.containsMatchIn(it.text.trim()) }
        val top = markers.filter { it.bottom > it.top }.minOfOrNull { it.top }
        return if (top != null) lines.filter { it.top < top }
        else lines.take(lines.indexOfFirst { peripheral.containsMatchIn(it.text.trim()) }.takeIf { it >= 0 } ?: lines.size)
    }

    fun merchantLines(lines: List<LocalOcrLine>): List<LocalOcrLine> {
        val scoped = businessLines(lines)
        val excluded = scoped.flatMapIndexed { index, line ->
            val text = line.text.trim()
            val label = listOf(product, description, remark, service, issuer).firstNotNullOfOrNull { it.find(text) }
            when {
                label != null -> listOf(line) + if (text.substring(label.range.last + 1).isBlank()) associated(scoped, index) else emptyList()
                serviceAttribution.matches(text) -> listOf(line)
                else -> emptyList()
            }
        }.toSet()
        return scoped.filter { it !in excluded && BankCodes.normalize(it.text) == null }
    }

    fun parse(lines: List<LocalOcrLine>, merchant: String, origin: String): ReceiptEvidence {
        val scoped = businessLines(lines)
        fun fields(label: Regex): List<ReceiptField> {
            val fields = scoped.mapIndexedNotNull { index, line ->
                val text = line.text.trim()
                val match = label.find(text) ?: return@mapIndexedNotNull null
                val inline = text.substring(match.range.last + 1).trim()
                val associated = if (inline.isNotEmpty()) emptyList() else associated(scoped, index)
                val value = inline.ifEmpty { associated.joinToString(" ") { it.text.trim() } }
                val raw = (listOf(line) + associated).joinToString("\n") { it.text }
                if (value.isBlank() || value.length > 512 || raw.length > 2048) null
                else ReceiptField(value, raw)
            }.distinctBy { it.value }
            return fields
        }
        fun field(label: Regex): ReceiptField? = fields(label).singleOrNull()
        fun merchantText(value: String) = normalize(value).trim().trim('|', '丨').filterNot(Char::isWhitespace)
        val namedMerchant = merchantText(merchant)
        val merchantSources = merchantLines(scoped).filter { it.text.length <= 2048 }
        val directMerchant = merchantSources.firstOrNull {
            merchantText(it.text).trimEnd('>', '〉') == namedMerchant && namedMerchant.isNotBlank()
        }?.let { ReceiptField(merchant, it.text) }
        val labeledMerchant = fields(merchantLabel).firstOrNull { merchantText(it.value) == namedMerchant }
            ?.copy(value=merchant)
        // The remaining supported names are explicitly named scan recipients or store prefixes.
        // Never use arbitrary substring containment: another role may share a short merchant prefix.
        val derivedMerchant = merchantSources.firstOrNull { line ->
            val text = merchantText(line.text)
            val recipient = text.replace(Regex("^(?:扫二维码付款|二维码付款|扫码付款)[-—:：]?给?|^转账给"), "")
            namedMerchant.isNotBlank() && (recipient != text && recipient.startsWith(namedMerchant) ||
                Regex("(?:旗舰店|专卖店|专营店|官方店)$").containsMatchIn(namedMerchant) && text.startsWith(namedMerchant))
        }?.let { ReceiptField(merchant, it.text) }
        val sourcePackage = origin.substringAfterLast(':').takeIf {
            Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(it) && it.length <= 512
        }
        val attributedServices = scoped.mapNotNull { line ->
            serviceAttribution.matchEntire(line.text.trim())?.groupValues?.get(1)?.trim()?.takeIf {
                it.isNotBlank() && it.length <= 512 && line.text.length <= 2048
            }?.let { ReceiptField(it, line.text) }
        }
        val services = (fields(service) + attributedServices).distinctBy { it.value }
        return ReceiptEvidence(
            merchant = directMerchant ?: labeledMerchant ?: derivedMerchant,
            productDescription = field(product), transactionDescription = field(description), orderRemark = field(remark),
            serviceProvider = services.singleOrNull(), receiptIssuer = field(issuer),
            sourceApp = sourcePackage?.takeIf { origin.length <= 2048 }?.let { ReceiptField(it, origin, "capture") },
        )
    }

    private fun associated(lines: List<LocalOcrLine>, index: Int): List<LocalOcrLine> {
        val label = lines[index]
        fun businessValue(line: LocalOcrLine) = !boundary.containsMatchIn(line.text.trim())
        if (label.bottom > label.top && label.right > label.left) {
            val h = label.bottom - label.top
            val row = lines.filter { it !== label && it.left >= label.right - h && businessValue(it) &&
                kotlin.math.abs((it.top + it.bottom - label.top - label.bottom) / 2.0) <= maxOf(h, it.bottom - it.top) * .65 }
                .sortedBy { it.left }
            if (row.isNotEmpty()) return row
            return listOfNotNull(lines.filter { it.top >= label.bottom && it.top - label.bottom <= h * 1.8 &&
                kotlin.math.abs(it.left - label.left) <= h * 2 && businessValue(it) }.minByOrNull { it.top })
        }
        return listOfNotNull(lines.getOrNull(index + 1)?.takeIf(::businessValue))
    }
}
