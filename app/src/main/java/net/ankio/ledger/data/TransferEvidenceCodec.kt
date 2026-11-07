package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Bounded diagnostic evidence, separate from the user's current transfer facts and applied effects. */
object TransferEvidenceCodec {
    private const val MAX_BYTES = 16_384
    private val roles = setOf("AMOUNT", "DEBIT", "CREDIT", "FEE")
    private val media = setOf("UNKNOWN", "BANK_CARD", "WECHAT_BALANCE", "ALIPAY_BALANCE", "UNSUPPORTED", "LIABILITY")
    private val keys = setOf("version", "intent", "scenario", "terminalState", "from", "to", "amountCents", "feeCents", "feeMode",
        "debitCents", "creditCents", "amountLabels", "reviewCodes")

    fun encode(evidence: TransferEvidence): String {
        val json = JSONObject().apply {
            put("version", evidence.evidenceVersion); put("intent", evidence.intent.name); put("scenario", evidence.scenario.name)
            put("terminalState", evidence.terminalState.name); put("from", evidence.from?.let(::payment)); put("to", evidence.to?.let(::payment))
            put("amountCents", evidence.amountCents); put("feeCents", evidence.feeCents); put("feeMode", evidence.feeMode?.name)
            put("debitCents", evidence.debitCents); put("creditCents", evidence.creditCents)
            put("amountLabels", JSONArray().apply { evidence.amountLabels.forEach { fact ->
                put(JSONObject().put("role", fact.role).put("label", fact.label).put("amountCents", fact.amountCents))
            } })
            put("reviewCodes", JSONArray(evidence.reviewCodes))
        }.toString()
        decode(json) // Encoders and backup validators enforce the same limits and exact types.
        return json
    }

    fun decode(text: String): TransferEvidence {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "转账识别证据过长" }
        val json = JSONObject(text)
        require(json.keys().asSequence().all { it in keys }) { "转账证据字段无效" }
        require(integer(json, "version", required = true) == 1L) { "不支持的转账证据版本" }
        val labels = json.get("amountLabels") as? JSONArray ?: error("转账金额证据无效")
        val codes = json.get("reviewCodes") as? JSONArray ?: error("转账核对原因无效")
        require(labels.length() <= 32 && codes.length() <= 16) { "转账证据项目过多" }
        val observed = (0 until labels.length()).map { index ->
            val item = labels.get(index) as? JSONObject ?: error("转账金额证据无效")
            require(item.keys().asSequence().all { it in setOf("role", "label", "amountCents") })
            val role = string(item, "role", 16, true)!!; require(role in roles)
            TransferAmountEvidence(role, string(item, "label", 80, true)!!, cents(item, "amountCents", true)!!)
        }
        val reviewCodes = (0 until codes.length()).map { index ->
            val value = codes.get(index) as? String ?: error("转账核对原因类型无效")
            require(Regex("[A-Z][A-Z0-9_]{0,63}").matches(value)); value
        }
        return TransferEvidence(
            TransferIntent.valueOf(string(json, "intent", 32, true)!!),
            TransferScenario.valueOf(string(json, "scenario", 32, true)!!),
            TransferTerminalState.valueOf(string(json, "terminalState", 32, true)!!),
            optionalPayment(json, "from"), optionalPayment(json, "to"),
            cents(json, "amountCents"), cents(json, "feeCents"), string(json, "feeMode", 16)?.let(FeeMode::valueOf),
            cents(json, "debitCents"), cents(json, "creditCents"), observed, reviewCodes, 1)
    }

    private fun payment(value: PaymentMethodEvidence) = JSONObject().apply {
        put("medium", value.medium); put("role", value.role); put("bankCode", value.bankCode); put("cardLast4", value.cardLast4)
        put("platform", value.platform); put("rawLabel", value.rawLabel); put("rawValue", value.rawValue)
        put("ambiguous", value.ambiguous); put("reason", value.reason)
    }

    private fun optionalPayment(json: JSONObject, key: String): PaymentMethodEvidence? {
        if (json.isNull(key)) return null
        val item = json.get(key) as? JSONObject ?: error("转账账户证据无效")
        require(item.keys().asSequence().all { it in setOf("medium", "role", "bankCode", "cardLast4", "platform", "rawLabel", "rawValue", "ambiguous", "reason") })
        val medium = string(item, "medium", 32, true)!!; require(medium in media)
        val role = string(item, "role", 16, true)!!; require(role in setOf("DEBIT", "CREDIT"))
        val bank = string(item, "bankCode", 32); require(bank == null || Regex("[a-z][a-z0-9_]{1,31}").matches(bank))
        val tail = string(item, "cardLast4", 4); require(tail == null || Regex("[0-9]{4}").matches(tail))
        val platform = string(item, "platform", 16); require(platform == null || platform in setOf("wechat", "alipay"))
        val ambiguous = item.get("ambiguous") as? Boolean ?: error("转账账户证据类型无效")
        return PaymentMethodEvidence(medium, role, bank, tail, platform, string(item, "rawLabel", 80, true)!!,
            string(item, "rawValue", 240, true)!!, ambiguous, string(item, "reason", 160))
    }

    private fun string(json: JSONObject, key: String, max: Int, required: Boolean = false): String? {
        if (json.isNull(key)) { require(!required) { "转账证据缺少字段" }; return null }
        val value = json.get(key) as? String ?: error("转账证据文本类型无效")
        require(value.length <= max) { "转账证据文本过长" }; return value
    }

    private fun integer(json: JSONObject, key: String, required: Boolean = false): Long? {
        if (json.isNull(key)) { require(!required) { "转账证据缺少整数" }; return null }
        val value = json.get(key)
        require(value is Int || value is Long) { "转账证据必须使用整数" }
        return (value as Number).toLong()
    }

    private fun cents(json: JSONObject, key: String, required: Boolean = false): Long? = integer(json, key, required)?.also {
        require(it in 0..Money.MAX_CENTS) { "转账证据金额越界" }
    }
}
