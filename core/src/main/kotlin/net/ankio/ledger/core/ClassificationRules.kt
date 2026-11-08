package net.ankio.ledger.core

import java.text.Normalizer
import java.util.Locale

enum class CategorySource { CUSTOM_RULE, BUILTIN_RULE, FALLBACK, MANUAL, IMPORT, LEGACY }
enum class ClassificationField { MERCHANT, ORDER_CONTENT }
enum class ClassificationOperator { CONTAINS, EQUALS, NOT_CONTAINS }
enum class ClassificationMatchMode { ALL, ANY }

data class ClassificationCondition(val field: ClassificationField = ClassificationField.MERCHANT,
    val operator: ClassificationOperator = ClassificationOperator.CONTAINS, val value: String)

data class ClassificationRule(val id: String, val name: String, val enabled: Boolean, val direction: Direction,
    val categoryId: String, val order: Int, val conditions: List<ClassificationCondition>,
    val matchMode: ClassificationMatchMode = ClassificationMatchMode.ALL, val source: String? = null,
    val conflictReason: String? = null)

data class ClassificationSnapshot(val version: String, val customRules: List<ClassificationRule>, val builtinRules: List<ClassificationRule>)
data class ClassificationInput(val direction: Direction, val merchant: String?, val orderContent: List<String> = emptyList(),
    val captureSource: Source? = null, val sourcePackage: String? = null, val platform: String? = null) {
    companion object {
        fun from(bill: ParsedBill, capture: Capture? = null): ClassificationInput = ClassificationInput(
            requireNotNull(bill.direction), bill.merchant,
            listOfNotNull(bill.receipt?.productDescription?.value, bill.receipt?.transactionDescription?.value,
                bill.receipt?.orderRemark?.value), capture?.source,
            if(capture?.source==Source.OCR)WalletResolver.sourcePackage(capture.origin) else null,
            explicitPlatform(bill))

        private fun explicitPlatform(bill: ParsedBill): String? {
            fun issuer(value: String?) = when(value?.let(ClassificationEngine::normalized)) {
                "微信", "微信支付", "财付通", "wechatpay" -> "wechat"
                "支付宝", "alipay" -> "alipay"
                else -> null
            }
            val payment=bill.paymentMethod?.takeUnless { it.ambiguous }?.takeIf {
                it.rawLabel in setOf("付款方式","支付方式","扣款账户","扣款方式","付款账户","支付账户",
                    "收款方式","收款账户","到账账户","到账方式","入账账户","入账方式")
            }?.let { when(ClassificationEngine.normalized(it.rawValue)) {
                "微信零钱" -> "wechat"
                "支付宝余额" -> "alipay"
                else -> null
            } }
            return listOfNotNull(issuer(bill.receipt?.receiptIssuer?.value),payment).distinct().singleOrNull()
        }
    }
}

/** Capture mode is explicit; packages and platform evidence never come from free page text. */
object ClassificationSources {
    fun valid(value: String?): Boolean = value==null || value in setOf("LIVE","IMAGE","IMAGE:wechat","IMAGE:alipay") ||
        value.startsWith("LIVE:") && value.length<=517 && WalletResolver.sourcePackage(value.removePrefix("LIVE:"))==value.removePrefix("LIVE:")

    fun matches(limit: String?, input: ClassificationInput): Boolean = when {
        limit==null -> true
        limit=="LIVE" -> input.captureSource==Source.OCR
        limit=="IMAGE" -> input.captureSource==Source.IMAGE
        limit.startsWith("LIVE:") -> input.captureSource==Source.OCR && input.sourcePackage==limit.removePrefix("LIVE:")
        limit.startsWith("IMAGE:") -> input.captureSource==Source.IMAGE && input.platform==limit.removePrefix("IMAGE:")
        else -> false
    }
}

data class CategoryDecision(val categoryId: String?, val source: CategorySource, val manual: Boolean = false,
    val ruleId: String? = null, val ruleVersion: String? = null, val explanation: String, val rule: ClassificationRule? = null)

/** Pure local matching. The original merchant and all financial fields remain caller owned. */
object ClassificationEngine {
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    fun classify(snapshot: ClassificationSnapshot, input: ClassificationInput, manual: CategoryDecision? = null): CategoryDecision {
        manual?.takeIf { it.manual }?.let { return it }
        fun matching(rules: List<ClassificationRule>) = rules.sortedWith(compareBy<ClassificationRule> { it.order }.thenBy { it.id })
            .firstOrNull { rule -> rule.enabled && rule.direction == input.direction && ClassificationSources.matches(rule.source,input) && rule.conditions.isNotEmpty() && rule.conditions.map { condition ->
                val actual = when(condition.field) {
                    ClassificationField.MERCHANT -> listOfNotNull(input.merchant)
                    ClassificationField.ORDER_CONTENT -> input.orderContent
                }.map(::normalized).filter(String::isNotEmpty)
                val expected = normalized(condition.value)
                actual.isNotEmpty() && expected.isNotEmpty() && when(condition.operator) {
                    ClassificationOperator.CONTAINS -> actual.any { it.contains(expected) }
                    ClassificationOperator.EQUALS -> expected in actual
                    ClassificationOperator.NOT_CONTAINS -> actual.none { it.contains(expected) }
                }
            }.let { results -> when(rule.matchMode) {
                ClassificationMatchMode.ALL -> results.all { it }
                ClassificationMatchMode.ANY -> results.any { it }
            } } }
        matching(snapshot.customRules)?.let { return CategoryDecision(it.categoryId, CategorySource.CUSTOM_RULE,
            ruleId=it.id, ruleVersion=snapshot.version, explanation="命中规则：${it.name}",rule=it) }
        matching(snapshot.builtinRules)?.let { return CategoryDecision(it.categoryId, CategorySource.BUILTIN_RULE,
            ruleId=it.id, ruleVersion=snapshot.version, explanation="内置规则：${it.name}",rule=it) }
        return CategoryDecision("cat:${input.direction.name.lowercase(Locale.ROOT)}:other", CategorySource.FALLBACK,
            ruleVersion=snapshot.version, explanation="未命中规则，归入其他")
    }
}

/** Frozen upgrade baseline. Changing live config must never change this historical value. */
object ClassificationDefaults {
    val migrationBaseline: ClassificationSnapshot = ClassificationSnapshot("migration:merchant-v1", emptyList(), buildList {
        val groups = listOf(
            Triple("transport", "交通", "ETC 高速 停车 地铁 公交 出租 打车 滴滴 加油"),
            Triple("dining", "餐饮", "餐厅 餐饮 食堂 饭 咖啡 奶茶 外卖 美食 早餐 火锅"),
            Triple("daily", "日用", "超市 便利店 百货 日用"),
            Triple("shopping", "购物", "淘宝 京东 拼多多 天猫 购物"),
            Triple("medical", "医疗", "医院 药房 药店 医疗"))
        groups.forEach { (key, name, words) -> words.split(' ').forEach { word ->
            add(ClassificationRule("builtin:$key:${size}", name, true, Direction.EXPENSE, "cat:expense:$key", size,
                listOf(ClassificationCondition(value=word))))
        } }
    })
}
