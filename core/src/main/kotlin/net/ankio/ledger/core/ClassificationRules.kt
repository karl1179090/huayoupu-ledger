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
        /** Source choices are explicit hypothetical evidence, never inferred from entered text. */
        fun hypothetical(direction:Direction,merchant:String?,orderContent:List<String>,source:String?):ClassificationInput {
            require(direction in listOf(Direction.EXPENSE,Direction.INCOME) && (merchant?.length ?: 0)<=2000 &&
                orderContent.size<=3 && orderContent.all { it.length<=2000 } && ClassificationSources.valid(source)) { "测试输入无效或过长" }
            return ClassificationInput(direction,merchant,orderContent,
                when { source==null -> null;source.startsWith("LIVE") -> Source.OCR;else -> Source.IMAGE },
                source?.takeIf { it.startsWith("LIVE:") }?.removePrefix("LIVE:"),
                source?.takeIf { it.startsWith("IMAGE:") }?.removePrefix("IMAGE:"))
        }

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

data class ClassificationConditionResult(val condition: ClassificationCondition, val matched: Boolean)
data class ClassificationRuleResult(val rule: ClassificationRule, val source: CategorySource,
    val directionMatches: Boolean, val sourceMatches: Boolean, val conditions: List<ClassificationConditionResult>,
    val conditionsMatch: Boolean, val matched: Boolean, val selected: Boolean = false, val interceptedByRuleId: String? = null)
data class ClassificationPreview(val input: ClassificationInput, val decision: CategoryDecision, val rules: List<ClassificationRuleResult>)

/** Pure local matching. The original merchant and all financial fields remain caller owned. */
object ClassificationEngine {
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    fun classify(snapshot: ClassificationSnapshot, input: ClassificationInput, manual: CategoryDecision? = null): CategoryDecision {
        manual?.takeIf { it.manual }?.let { return it }
        return decision(snapshot,input,ordered(snapshot).map { (rule,source) -> evaluate(rule,source,input) }.firstOrNull { it.matched })
    }

    /** Hypothetical new recognition: existing manual decisions are intentionally not inputs. */
    fun preview(snapshot: ClassificationSnapshot, input: ClassificationInput): ClassificationPreview {
        val results=ordered(snapshot).map { (rule,source) -> evaluate(rule,source,input) }.toList()
        val selected=results.indexOfFirst { it.matched }
        val winner=results.getOrNull(selected)
        return ClassificationPreview(input,decision(snapshot,input,winner),results.mapIndexed { index,result ->
            result.copy(selected=index==selected,interceptedByRuleId=winner?.rule?.id?.takeIf { result.matched && index>selected })
        })
    }

    private fun ordered(snapshot:ClassificationSnapshot):Sequence<Pair<ClassificationRule,CategorySource>> {
        fun rules(values:List<ClassificationRule>,source:CategorySource)=values
            .sortedWith(compareBy<ClassificationRule> { it.order }.thenBy { it.id }).asSequence().map { it to source }
        return rules(snapshot.customRules,CategorySource.CUSTOM_RULE)+rules(snapshot.builtinRules,CategorySource.BUILTIN_RULE)
    }

    private fun evaluate(rule:ClassificationRule,source:CategorySource,input:ClassificationInput):ClassificationRuleResult {
        val conditions=rule.conditions.map { condition ->
            val raw=when(condition.field) {
                ClassificationField.MERCHANT -> listOfNotNull(input.merchant)
                ClassificationField.ORDER_CONTENT -> input.orderContent
            }
            val actual=raw.map(::normalized).filter(String::isNotEmpty)
            val expected=normalized(condition.value)
            ClassificationConditionResult(condition,actual.isNotEmpty() && expected.isNotEmpty() && when(condition.operator) {
                ClassificationOperator.CONTAINS -> actual.any { it.contains(expected) }
                ClassificationOperator.EQUALS -> expected in actual
                ClassificationOperator.NOT_CONTAINS -> actual.none { it.contains(expected) }
            })
        }
        val conditionsMatch=conditions.isNotEmpty() && when(rule.matchMode) {
            ClassificationMatchMode.ALL -> conditions.all { it.matched }
            ClassificationMatchMode.ANY -> conditions.any { it.matched }
        }
        val directionMatches=rule.direction==input.direction
        val sourceMatches=ClassificationSources.matches(rule.source,input)
        return ClassificationRuleResult(rule,source,directionMatches,sourceMatches,conditions,conditionsMatch,
            rule.enabled && directionMatches && sourceMatches && conditionsMatch)
    }

    private fun decision(snapshot:ClassificationSnapshot,input:ClassificationInput,winner:ClassificationRuleResult?):CategoryDecision =
        if(winner!=null)CategoryDecision(winner.rule.categoryId,winner.source,ruleId=winner.rule.id,ruleVersion=snapshot.version,
            explanation=if(winner.source==CategorySource.CUSTOM_RULE)"命中规则：${winner.rule.name}" else "内置规则：${winner.rule.name}",rule=winner.rule)
        else CategoryDecision("cat:${input.direction.name.lowercase(Locale.ROOT)}:other",CategorySource.FALLBACK,
            ruleVersion=snapshot.version,explanation="未命中规则，归入其他")
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
