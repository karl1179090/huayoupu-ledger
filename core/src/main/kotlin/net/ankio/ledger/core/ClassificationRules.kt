package net.ankio.ledger.core

import java.text.Normalizer
import java.util.Locale

enum class CategorySource { CUSTOM_RULE, BUILTIN_RULE, FALLBACK, MANUAL, IMPORT, LEGACY }
enum class ClassificationField { MERCHANT }
enum class ClassificationOperator { CONTAINS, EQUALS }
enum class ClassificationMatchMode { ALL }

data class ClassificationCondition(val field: ClassificationField = ClassificationField.MERCHANT,
    val operator: ClassificationOperator = ClassificationOperator.CONTAINS, val value: String)

data class ClassificationRule(val id: String, val name: String, val enabled: Boolean, val direction: Direction,
    val categoryId: String, val order: Int, val conditions: List<ClassificationCondition>,
    val matchMode: ClassificationMatchMode = ClassificationMatchMode.ALL, val source: String? = null,
    val conflictReason: String? = null)

data class ClassificationSnapshot(val version: String, val customRules: List<ClassificationRule>, val builtinRules: List<ClassificationRule>)
data class ClassificationInput(val direction: Direction, val merchant: String?)
data class CategoryDecision(val categoryId: String?, val source: CategorySource, val manual: Boolean = false,
    val ruleId: String? = null, val ruleVersion: String? = null, val explanation: String)

/** Pure local matching. The original merchant and all financial fields remain caller owned. */
object ClassificationEngine {
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    fun classify(snapshot: ClassificationSnapshot, input: ClassificationInput, manual: CategoryDecision? = null): CategoryDecision {
        manual?.takeIf { it.manual }?.let { return it }
        fun matching(rules: List<ClassificationRule>) = rules.sortedWith(compareBy<ClassificationRule> { it.order }.thenBy { it.id })
            .firstOrNull { rule -> rule.enabled && rule.direction == input.direction && rule.conditions.isNotEmpty() && rule.conditions.all { condition ->
                val actual = input.merchant?.let(::normalized).orEmpty()
                val expected = normalized(condition.value)
                actual.isNotEmpty() && expected.isNotEmpty() && when(condition.operator) {
                    ClassificationOperator.CONTAINS -> actual.contains(expected)
                    ClassificationOperator.EQUALS -> actual == expected
                }
            } }
        matching(snapshot.customRules)?.let { return CategoryDecision(it.categoryId, CategorySource.CUSTOM_RULE,
            ruleId=it.id, ruleVersion=snapshot.version, explanation="命中规则：${it.name}") }
        matching(snapshot.builtinRules)?.let { return CategoryDecision(it.categoryId, CategorySource.BUILTIN_RULE,
            ruleId=it.id, ruleVersion=snapshot.version, explanation="内置规则：${it.name}") }
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
