package net.ankio.ledger.core

/** A deliberately narrow, single bank notification adapter. Hosting-app and balance text are not account evidence. */
object BankReceiptParser {
    private val heading = Regex("交易成功提醒|交易提醒|动账通知|消费提醒|账户变动通知")
    private val typeLabel = Regex("^(?:交易类型|交易方式)[：:]?")
    private val accountLabel = Regex("^(?:交易卡号|交易账号|交易账户|扣款账户|付款账户|卡号|账号|账户(?!余额|变动|信息)|卡尾号|尾号)[：:]?")
    private val amountLabel = Regex("^(?:交易金额|消费金额|支出金额|收入金额|发生金额|金额)[：:]?")
    private val tail = Regex("(?:尾号|尾数|[＊*]{2,})[：: ]*([0-9]{4})(?![0-9])|(?<![0-9])([0-9]{4})(?![0-9])")
    private val expenseTypes = setOf("网上快捷支付", "快捷支付", "消费", "刷卡消费", "银联消费")
    private val incomeTypes = setOf("工资入账", "工资收入", "利息入账")

    fun matches(lines: List<LocalOcrLine>): Boolean = lines.any {
        val text=normalize(it.text).filterNot(Char::isWhitespace)
        heading.matches(text) || BankCodes.find(text).isNotEmpty() && heading.containsMatchIn(text)
    }

    fun parse(lines: List<LocalOcrLine>, capturedAt: Long, origin: String,
        timeContext: RecognitionTimeContext = RecognitionTimeContext(capturedAt, java.time.ZoneId.systemDefault().id)): ParsedBill? {
        val scoped = ReceiptEvidenceParser.businessLines(lines)
        val text=scoped.joinToString("\n") { it.text }
        if(TransactionRejection.reason(text)!=null || Regex("待付款|待支付|支付中|付款中|交易处理中").containsMatchIn(text))return null
        val boundary=Regex("^(?:交易(?:卡号|账号|账户|金额|类型|方式|时间|日期|单号|成功提醒)|扣款账户|付款账户|卡号|账号|账户|余额|尾号|商户名称|商家名称|服务商|优惠|优先|返回|完成)")
        fun fields(label: Regex) = scoped.mapIndexedNotNull { index, line ->
            val text = normalize(line.text).trim()
            val match = label.find(text) ?: return@mapIndexedNotNull null
            val inline = text.substring(match.range.last + 1).trim(':', '：', ' ')
            val associated=if(inline.isNotBlank())emptyList() else if(line.bottom>line.top && line.right>line.left) {
                val h=line.bottom-line.top
                val row=scoped.filter { it!==line && it.left>=line.right-h && !boundary.containsMatchIn(it.text.trim()) &&
                    kotlin.math.abs((it.top+it.bottom-line.top-line.bottom)/2.0)<=maxOf(h,it.bottom-it.top)*.65 }.sortedBy { it.left }
                row.ifEmpty { listOfNotNull(scoped.filter { it!==line && it.top>=line.bottom && it.top-line.bottom<=h*1.8 &&
                    kotlin.math.abs(it.left-line.left)<=h*2 && !boundary.containsMatchIn(it.text.trim()) }.minByOrNull { it.top }) }
            } else listOfNotNull(scoped.getOrNull(index+1)?.takeIf { !boundary.containsMatchIn(it.text.trim()) })
            val value=inline.ifBlank { associated.joinToString(" ") { it.text.trim() } }
            val raw=(listOf(line)+associated).joinToString("\n") { it.text }
            if(value.isBlank() || value.length>512 || raw.length>2048)null else ReceiptField(value,raw)
        }.distinctBy { it.value }
        fun values(label: Regex)=fields(label).map { normalize(it.value).filterNot(Char::isWhitespace) }
        val amounts = values(amountLabel).mapNotNull(Money::parseCents).filter { it in 1..Money.MAX_CENTS }.distinct()
        if (amounts.isEmpty()) return null
        val accounts = values(accountLabel)
        val banks = scoped.flatMap { BankCodes.find(it.text) }.toSet()
        val tails = accounts.flatMap { tail.findAll(it).map { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }.toList() }.distinct()
        val types = values(typeLabel)
        val direction = when (types.singleOrNull()) {
            in expenseTypes -> Direction.EXPENSE
            in incomeTypes -> Direction.INCOME
            else -> null
        }
        val malformedTail = accounts.any { value ->
            (Regex("尾号|尾数|[＊*]{2,}|[0-9]").containsMatchIn(value)) && !tail.containsMatchIn(value)
        }
        val ambiguous = malformedTail || banks.size != 1 || tails.size > 1 || scoped.count { heading.containsMatchIn(it.text) } > 1
        val reasons = buildList {
            if (amounts.size != 1) add("交易金额不明确")
            if (direction == null) add("请确认银行交易收支方向")
            if (ambiguous) add("银行账户证据冲突，请确认实际账户")
        }
        val evidence = PaymentMethodEvidence("BANK_CARD", when(direction) { Direction.INCOME -> "CREDIT"; Direction.EXPENSE -> "DEBIT"; else -> "UNKNOWN" },
            banks.singleOrNull(), tails.singleOrNull(), rawLabel="交易账户", rawValue=accounts.joinToString(" / ").take(160),
            ambiguous=ambiguous)
        val bankCode=banks.singleOrNull()
        val issuer=bankCode?.let { code -> scoped.firstOrNull { BankCodes.normalize(it.text)==code }
            ?: scoped.firstOrNull { code in BankCodes.find(it.text) } }
        val merchant=fields(ReceiptEvidenceParser.merchantLabel).singleOrNull()?.value.orEmpty()
        val base = ReceiptEvidenceParser.parse(scoped, merchant, origin)
        val unknownIssuer=scoped.firstOrNull { it.text.trim().endsWith("银行") && it.text.length in 1..512 }
        val receipt = base.copy(
            receiptIssuer=issuer?.let { line -> BankCodes.entries.firstOrNull { it.code==bankCode }?.label?.let { ReceiptField(it,line.text.take(2048)) } }
                ?: base.receiptIssuer ?: unknownIssuer?.let { ReceiptField(it.text.trim(),it.text) },
            bank=BankReceiptEvidence(bankCode, tails.singleOrNull(), fields(typeLabel).singleOrNull(), direction, ambiguous))
        val time=TransactionTimeResolver.resolve(scoped,timeContext)
        return ParsedBill(amounts.singleOrNull(), direction, merchant, "其他", time.accountingAt,
            LocalOcrBillParser.transactionId(scoped), reasons.isEmpty(), reasons.joinToString("；"),
            timeExplicit=time.explicit, paymentMethod=evidence, receipt=receipt.copy(time=time))
    }
}
