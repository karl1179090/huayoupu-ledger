package net.ankio.ledger.core

import kotlin.math.abs

/** Intent and labelled facts are parsed before ordinary expense/refund inference. */
object TransferEvidenceParser {
    private val own = Regex("(?:本人|自己)(?:的)?(?:账户|账号|银行卡|储蓄卡)(?:之间|间|互转|转账)|本人互转|账户互转|转入本人(?:的)?(?:账户|账号|银行卡|储蓄卡)|到账(?:账户|银行卡)[：:]?本人|收款人[：:]?本人")
    private val externalParty = Regex("转账给(?!自己|本人)|收款人(?!自己|本人).+")
    private val topUp = Regex("^(?:零钱|余额|微信零钱|支付宝余额)?充值(?:成功|完成|到账)$")
    private val withdrawal = Regex("^(?:零钱|余额)?提现(?:成功|完成|到账)$")
    private val transferHint = Regex("^(?:(?:零钱|余额|微信零钱|支付宝余额)?(?:充值|提现)(?:申请|已|成功|失败|退回|撤销|处理中|中|金额)|本人账户|账户互转|本人互转)")
    private val unsupported = Regex("信用卡|花呗|白条|还款|零钱通|余额宝|基金|理财|外币|换汇|兑换|USD|HKD|EUR|美元|港币|欧元", RegexOption.IGNORE_CASE)
    private val merchantTopUp = Regex("话费|流量|游戏|公交卡|交通卡|会员充值|油卡|电费|水费|购物卡")
    private val pending = Regex("申请已提交|申请成功|预计.*到账|待到账|处理中|受理中|审核中|提现中|充值中|转账中")
    private val failed = Regex("充值失败|提现失败|转账失败|交易失败")
    private val reversed = Regex("已退回|已退还|充值退回|提现退回|转账退回|已撤销|已撤回")
    private val external = Regex("转账给|收款人|收款方|来自|付款人")
    private val labels = linkedMapOf(
        "DEBIT" to Regex("^(实际扣款|扣款金额|实际支出|支出总额)[：:]?"),
        "CREDIT" to Regex("^(实际到账|到账金额|实际入账|入账金额)[：:]?"),
        "FEE" to Regex("^(手续费|服务费)(?!率|优惠|减免|方式|模式)[：:]?"),
        "AMOUNT" to Regex("^(转账金额|充值金额|提现金额|转出金额|转入金额)[：:]?"),
    )
    private val otherField = Regex("^(?:转[出入]账户|到账银行卡|付款方式|支付方式|收款账户|到账账户|银行卡|交易|订单|转账单号|充值单号|提现单号|手续费方式|费用方式|余额|优惠|奖励|额度|剩余|时间)")
    private val extra = Regex("额外扣费|额外收取|额外扣除|另收(?:取)?手续费|另扣(?:除)?手续费|手续费另收")
    private val deducted = Regex("到账扣费|从(?:提现|转出|转账)金额中扣|扣除手续费后到账|含手续费")
    private val explicitFree = Regex("^(?:(?:本次|本笔|此次)(?:充值|提现|转账)?[：:]?)?(?:免手续费|不收取手续费|手续费免费|服务费免费)$")

    /** null means no self-transfer suspicion; ordinary external transfers keep the old parser. */
    fun parse(lines: List<LocalOcrLine>, origin: String = ""): TransferEvidence? {
        val clean = lines.map { it.copy(text = normalize(it.text).filterNot(Char::isWhitespace)) }.filter { it.text.isNotBlank() }
        val compact = clean.joinToString("|") { it.text }
        val topUpPage = clean.any { topUp.matches(it.text.removePrefix("当前状态").removePrefix("交易状态").trimStart(':', '：')) }
        val withdrawalPage = clean.any { withdrawal.matches(it.text.removePrefix("当前状态").removePrefix("交易状态").trimStart(':', '：')) }
        val ownPage = own.containsMatchIn(compact)
        val twoEnded = clean.any { Regex("^(?:转出账户|扣款账户)").containsMatchIn(it.text) } &&
            clean.any { Regex("^(?:转入账户|到账账户|到账银行卡)").containsMatchIn(it.text) }
        if (!topUpPage && !withdrawalPage && !ownPage && !twoEnded && clean.none { transferHint.containsMatchIn(it.text) }) return null
        // A phone/game top-up is a merchant expense, never a movement into a platform balance.
        if (merchantTopUp.containsMatchIn(compact)) return null
        if (!ownPage && !topUpPage && !withdrawalPage && external.containsMatchIn(compact)) return null
        val source = when (WalletResolver.sourcePackage(origin)) {
            PaymentApps.WECHAT -> "wechat"
            PaymentApps.ALIPAY -> "alipay"
            else -> null
        }
        val namedPlatforms = buildList {
            if (compact.contains("微信零钱") || clean.any { it.text == "微信" || it.text == "微信支付" }) add("wechat")
            if (compact.contains("支付宝余额") || clean.any { it.text == "支付宝" }) add("alipay")
        }.distinct()
        val platform = source ?: namedPlatforms.singleOrNull()
        val scenario = when {
            topUpPage -> TransferScenario.TOP_UP
            withdrawalPage -> TransferScenario.WITHDRAWAL
            ownPage -> TransferScenario.OWN_ACCOUNTS
            else -> TransferScenario.UNKNOWN
        }
        val status = when {
            reversed.containsMatchIn(compact) -> TransferTerminalState.REVERSED
            failed.containsMatchIn(compact) -> TransferTerminalState.FAILED
            pending.containsMatchIn(compact) -> TransferTerminalState.PENDING
            topUpPage || withdrawalPage || ownPage && Regex("转账成功|转出成功|转入成功|交易成功|已完成").containsMatchIn(compact) -> TransferTerminalState.COMPLETED
            else -> TransferTerminalState.UNKNOWN
        }
        val codes = mutableListOf<String>()
        val self = ownPage || (topUpPage || withdrawalPage) && platform != null
        var intent = if (self) TransferIntent.SELF_TRANSFER else TransferIntent.AMBIGUOUS
        if (self && externalParty.containsMatchIn(compact.replace("：", "").replace(":", ""))) {
            intent = TransferIntent.AMBIGUOUS
            codes += "TRANSFER_IDENTITY_CONFLICT"
        }
        if (unsupported.containsMatchIn(compact)) {
            intent = TransferIntent.AMBIGUOUS
            codes += "TRANSFER_ACCOUNT_UNSUPPORTED"
        }
        if (!self) codes += "TRANSFER_IDENTITY_CONFLICT"
        if (status != TransferTerminalState.COMPLETED) codes += "TRANSFER_STATUS_REQUIRED"
        var from = PaymentMethodParser.parseTransferEnd(clean, "DEBIT", origin)
        var to = PaymentMethodParser.parseTransferEnd(clean, "CREDIT", origin)
        fun platformEnd(role: String): PaymentMethodEvidence? = platform?.let {
            PaymentMethodEvidence(if (it == "wechat") "WECHAT_BALANCE" else "ALIPAY_BALANCE", role,
                platform = it, rawLabel = if (role == "DEBIT") "转出账户" else "到账账户",
                rawValue = if (it == "wechat") "微信零钱" else "支付宝余额")
        }
        // Explicit recharge/withdrawal semantics and the actual supported package identify its balance.
        if (self && scenario == TransferScenario.TOP_UP && (to == null || to.medium == "UNKNOWN")) to = platformEnd("CREDIT")
        if (self && scenario == TransferScenario.WITHDRAWAL && (from == null || from.medium == "UNKNOWN")) from = platformEnd("DEBIT")
        val balanceEnd = when(scenario) { TransferScenario.TOP_UP -> to; TransferScenario.WITHDRAWAL -> from; else -> null }
        if(platform != null && balanceEnd != null && balanceEnd.medium in setOf("WECHAT_BALANCE", "ALIPAY_BALANCE") &&
            balanceEnd.medium != if(platform=="wechat")"WECHAT_BALANCE" else "ALIPAY_BALANCE") codes += "TRANSFER_PAYMENT_EVIDENCE_CONFLICT"
        val cardEnd = when(scenario) { TransferScenario.TOP_UP -> from; TransferScenario.WITHDRAWAL -> to; else -> null }
        if(cardEnd != null && cardEnd.medium !in setOf("BANK_CARD", "UNKNOWN")) codes += "TRANSFER_ACCOUNT_UNSUPPORTED"
        if (listOf(from, to).any { it == null || it.ambiguous || it.medium == "UNKNOWN" }) codes += "TRANSFER_WALLET_REQUIRED"
        if (listOfNotNull(from, to).any { it.medium == "UNSUPPORTED" }) codes += "TRANSFER_ACCOUNT_UNSUPPORTED"

        val observed = mutableListOf<TransferAmountEvidence>()
        val invalidRoles = mutableSetOf<String>()
        for ((role, label) in labels) for ((index, line) in clean.withIndex()) {
            val match = label.find(line.text) ?: continue
            val inline = line.text.substring(match.range.last + 1).trim(':', '：')
            val value = if (inline.isNotBlank()) inline else associatedValue(clean, index)
            val amount = money(value)
            if (amount == null) invalidRoles += role
            else observed += TransferAmountEvidence(role, match.groupValues[1], amount)
        }
        val facts = observed.groupBy { it.role }
        if (facts.any { it.value.map { fact -> fact.amountCents }.distinct().size > 1 } || invalidRoles.isNotEmpty())
            codes += "TRANSFER_AMOUNT_CONFLICT"
        fun amount(role: String) = facts[role]?.map { it.amountCents }?.distinct()?.singleOrNull()
        var principal = amount("AMOUNT")
        val debit = amount("DEBIT")
        val credit = amount("CREDIT")
        var fee = amount("FEE")
        if (fee == null && clean.any { explicitFree.matches(it.text) }) fee = 0
        if (fee == null && debit != null && credit != null) {
            if (debit >= credit) fee = debit - credit else codes += "TRANSFER_AMOUNT_CONFLICT"
        }
        val explicitModes = buildList {
            if (extra.containsMatchIn(compact)) add(FeeMode.EXTRA)
            if (deducted.containsMatchIn(compact)) add(FeeMode.DEDUCTED)
        }.distinct()
        var mode = explicitModes.singleOrNull()
        if (explicitModes.size > 1) codes += listOf("TRANSFER_FEE_AMBIGUOUS", "TRANSFER_AMOUNT_CONFLICT")
        if (fee == 0L) mode = FeeMode.EXTRA
        if (principal == null && debit != null && credit != null && mode != null)
            principal = if (mode == FeeMode.EXTRA) credit else debit
        if (principal != null && fee != null) {
            val knownPrincipal = principal
            val knownFee = fee
            val possibilities = FeeMode.entries.filter { candidate ->
                runCatching { TransferCalculator.verifyObserved(knownPrincipal, knownFee, candidate, debit, credit) }.isSuccess
            }
            if (mode == null && possibilities.size == 1) mode = possibilities.single()
            if (mode != null && mode !in possibilities || possibilities.isEmpty()) codes += "TRANSFER_AMOUNT_CONFLICT"
        }
        if (principal == null || principal <= 0) codes += "INVALID_AMOUNT"
        if (fee == null || mode == null) codes += "TRANSFER_FEE_AMBIGUOUS"
        return TransferEvidence(intent, scenario, status, from, to, principal, fee, mode, debit, credit,
            observed.distinct(), codes.distinct())
    }

    /** A partial candidate may be reviewed, but contradictory multi-amount screens cannot authorize pixels. */
    fun automaticCandidate(evidence: TransferEvidence, origin: String): Boolean =
        WalletResolver.sourcePackage(origin) in setOf(PaymentApps.WECHAT, PaymentApps.ALIPAY) && evidence.intent == TransferIntent.SELF_TRANSFER &&
            evidence.terminalState == TransferTerminalState.COMPLETED && evidence.amountCents?.let { it > 0 } == true &&
            evidence.reviewCodes.none { it in setOf("TRANSFER_AMOUNT_CONFLICT", "TRANSFER_ACCOUNT_UNSUPPORTED", "TRANSFER_PAYMENT_EVIDENCE_CONFLICT", "INVALID_AMOUNT") }

    private fun money(raw: String): Long? = Money.parseCents(raw.replace(Regex("[（(](?:含手续费|不含手续费)[）)]"), ""))

    private fun field(value: String) = labels.values.any { it.containsMatchIn(value) } || otherField.containsMatchIn(value)

    private fun associatedValue(lines: List<LocalOcrLine>, index: Int): String {
        val label = lines[index]
        if (label.bottom > label.top && label.right > label.left) {
            val h = label.bottom - label.top
            val row = lines.filter { it !== label && it.left >= label.right - h &&
                abs((it.top + it.bottom - label.top - label.bottom) / 2.0) <= maxOf(h, it.bottom - it.top) * .65 && !field(it.text) }
                .sortedBy { it.left }
            if (row.isNotEmpty()) return row.joinToString("") { it.text }
            return lines.filter { it !== label && it.top >= label.bottom && it.top - label.bottom <= h * 1.8 &&
                abs(it.left - label.left) <= h * 2 && !field(it.text) }.minByOrNull { it.top }?.text.orEmpty()
        }
        return lines.getOrNull(index + 1)?.takeIf { !field(it.text) }?.text.orEmpty()
    }
}
