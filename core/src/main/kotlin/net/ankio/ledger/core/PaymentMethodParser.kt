package net.ankio.ledger.core

import kotlin.math.abs

/** Account evidence is local to an explicit field; OCR reading order is not authoritative. */
object PaymentMethodParser {
    private val debitLabel = Regex("^(付款方式|支付方式|扣款账户|扣款方式|付款账户|支付账户)[：:]?")
    private val creditLabel = Regex("^(收款方式|收款账户|到账账户|到账方式|入账账户|入账方式)[：:]?")
    private val otherField = Regex("^(?:付款方|收款方|对方账户|对方账号|交易号|交易单号|订单号|订单编号|商户|商品|退款|支付时间|付款时间|交易时间|到账时间|金额|原付|累计|实付|优惠|电话)")
    private val unsupported = Regex("零钱通|余额宝|花呗|白条|信用卡|信用支付|先用后付|亲属卡|亲情卡")
    private val combined = Regex("组合(?:支付|付款)?|多种(?:方式|渠道)|分笔付款|[+＋]|及|和")
    private val fourDigits = Regex("(?<![0-9A-Za-z])([0-9]{4})(?![0-9A-Za-z])")
    private val cardHint = Regex("银行卡|储蓄卡|借记卡|卡尾号|尾号|卡号")

    fun parse(lines: List<LocalOcrLine>, direction: Direction, origin: String = ""): PaymentMethodEvidence? {
        val clean = lines.map { it.copy(text = normalize(it.text).trim()) }.filter { it.text.isNotBlank() }
        val label = if (direction == Direction.INCOME) creditLabel else debitLabel
        val role = if (direction == Direction.INCOME) "CREDIT" else "DEBIT"
        val sourcePlatform = when (WalletResolver.sourcePackage(origin)) {
            PaymentApps.WECHAT -> "wechat"
            PaymentApps.ALIPAY -> "alipay"
            else -> null
        }
        val brands = clean.flatMap { line ->
            val text = line.text.filterNot(Char::isWhitespace)
            buildList {
                if (text == "微信" || Regex("微信支付|微信账单|财付通|WeChatPay", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("wechat")
                if (text == "支付宝" || Regex("支付宝|Alipay", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("alipay")
            }
        }.distinct()
        val platform = sourcePlatform ?: brands.singleOrNull()
        val fields = clean.mapIndexedNotNull { index, line ->
            val compact = line.text.filterNot(Char::isWhitespace)
            val match = label.find(compact) ?: return@mapIndexedNotNull null
            val inline = compact.substring(match.range.last + 1).trim(':', '：')
            val value = if (inline.isNotBlank()) inline else associatedValue(clean, index)
            if (value.isBlank()) return@mapIndexedNotNull null
            decode(match.groupValues[1], value, platform, role)
        }
        if (fields.isEmpty()) return platform?.let {
            PaymentMethodEvidence("UNKNOWN", role, platform = it, reason = "PAYMENT_METHOD_MISSING")
        }
        val distinct = fields.distinctBy { listOf(it.medium, it.bankCode, it.cardLast4, it.platform, it.ambiguous) }
        if (distinct.size > 1 || fields.any { it.ambiguous }) return PaymentMethodEvidence(
            "UNKNOWN", role, platform = platform,
            rawLabel = fields.joinToString(" / ") { it.rawLabel }.take(80),
            rawValue = fields.joinToString(" / ") { it.rawValue }.take(240),
            ambiguous = true, reason = "MULTIPLE_PAYMENT_METHODS",
        )
        return distinct.single()
    }

    private fun associatedValue(lines: List<LocalOcrLine>, index: Int): String {
        val label = lines[index]
        if (label.bottom > label.top && label.right > label.left) {
            val h = label.bottom - label.top
            val row = lines.filter { it !== label && it.left >= label.right - h &&
                abs((it.top + it.bottom - label.top - label.bottom) / 2.0) <= maxOf(h, it.bottom - it.top) * .65 &&
                !isField(it.text) }.sortedBy { it.left }
            if (row.isNotEmpty()) return row.joinToString(" ") { it.text }.take(240)
            // Stacked values can themselves span several OCR blocks on the nearest row.
            val below = lines.filter { it !== label && it.top >= label.bottom && it.top - label.bottom <= h * 1.8 &&
                abs(it.left - label.left) <= h * 2 && !isField(it.text) }.minByOrNull { it.top }
            if (below != null) {
                return lines.filter { it !== label && it.left >= below.left - h &&
                    abs((it.top + it.bottom - below.top - below.bottom) / 2.0) <= maxOf(h, it.bottom - it.top) * .65 &&
                    !isField(it.text) }.sortedBy { it.left }.joinToString(" ") { it.text }.take(240)
            }
            return ""
        }
        // Text-only fixtures and accessibility-derived OCR retain adjacent field order.
        return lines.drop(index + 1).take(2).takeWhile { !isField(it.text) }.joinToString(" ") { it.text }.take(240)
    }

    private fun isField(value: String): Boolean {
        val compact = value.filterNot(Char::isWhitespace)
        return debitLabel.containsMatchIn(compact) || creditLabel.containsMatchIn(compact) || otherField.containsMatchIn(compact)
    }

    private fun decode(label: String, raw: String, context: String?, role: String): PaymentMethodEvidence {
        val value = normalize(raw).filterNot(Char::isWhitespace)
            .split(Regex("交易(?:单号|号)|订单(?:编号|单号|号)|对方(?:账户|账号)|付款方|收款方|商户|商品|手机|电话|优惠|红包|立减|奖励"), limit = 2).first()
        val banks = BankCodes.find(value)
        val isCard = banks.isNotEmpty() || cardHint.containsMatchIn(value)
        val tails = if (isCard) fourDigits.findAll(value).map { it.groupValues[1] }.distinct().toList() else emptyList()
        val wechat = value.contains("微信零钱") || value.contains("零钱") && !value.contains("零钱通")
        val alipay = value.contains("支付宝余额") || value == "余额" && context == "alipay"
        val methodCount = (if (isCard) 1 else 0) + (if (wechat) 1 else 0) + (if (alipay) 1 else 0)
        val ambiguous = banks.size > 1 || tails.size > 1 || methodCount > 1 || combined.containsMatchIn(value)
        val medium = when {
            ambiguous -> "UNKNOWN"
            unsupported.containsMatchIn(value) -> "UNSUPPORTED"
            isCard -> "BANK_CARD"
            wechat && (context == "wechat" || value.contains("微信零钱")) -> "WECHAT_BALANCE"
            alipay && (context == "alipay" || value.contains("支付宝余额")) -> "ALIPAY_BALANCE"
            else -> "UNKNOWN"
        }
        val explicitPlatform = when {
            value.contains("微信零钱") -> "wechat"
            value.contains("支付宝余额") -> "alipay"
            else -> context
        }
        return PaymentMethodEvidence(medium, role, banks.singleOrNull(), tails.singleOrNull(), explicitPlatform,
            label.take(80), raw.take(240), ambiguous, when {
                ambiguous -> "MULTIPLE_PAYMENT_METHODS"
                medium == "UNSUPPORTED" -> "UNSUPPORTED_PAYMENT_METHOD"
                medium == "UNKNOWN" -> "INSUFFICIENT_PAYMENT_METHOD"
                else -> null
            })
    }
}
