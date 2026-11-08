package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class ClassificationRulesTest {
    @Test fun firstEnabledMatchingRuleWinsWithoutChangingTheMerchant() {
        val input = ClassificationInput(Direction.EXPENSE, "  ＡＢＣ 咖啡  ")
        val disabled = ClassificationRule("disabled", "停用", false, Direction.EXPENSE, "cat:expense:shopping", 0,
            listOf(ClassificationCondition(value="abc")))
        val first = disabled.copy(id="first", name="咖啡用途", enabled=true, categoryId="cat:expense:dining", order=1)
        val second = first.copy(id="second", categoryId="cat:expense:other", order=2)
        val result = ClassificationEngine.classify(ClassificationSnapshot("v-test", listOf(disabled, first, second), emptyList()), input)
        assertEquals("cat:expense:dining", result.categoryId)
        assertEquals("first", result.ruleId)
        assertEquals(CategorySource.CUSTOM_RULE, result.source)
        assertEquals("命中规则：咖啡用途", result.explanation)
        assertEquals("  ＡＢＣ 咖啡  ", input.merchant)
    }
    @Test fun equalityDirectionAndMissingMerchantUseTheSameDirectionFallback() {
        val rule = ClassificationRule("equal", "精确商家", true, Direction.INCOME, "cat:income:salary", 0,
            listOf(ClassificationCondition(operator=ClassificationOperator.EQUALS, value="ＡＢＣ公司")))
        val snapshot = ClassificationSnapshot("v2", listOf(rule), emptyList())
        assertEquals("cat:income:salary", ClassificationEngine.classify(snapshot, ClassificationInput(Direction.INCOME, "abc 公司")).categoryId)
        assertEquals(CategorySource.FALLBACK, ClassificationEngine.classify(snapshot, ClassificationInput(Direction.INCOME, "abc公司分店")).source)
        assertEquals("cat:expense:other", ClassificationEngine.classify(snapshot, ClassificationInput(Direction.EXPENSE, "ABC公司")).categoryId)
        assertEquals("cat:income:other", ClassificationEngine.classify(snapshot, ClassificationInput(Direction.INCOME, null)).categoryId)
    }

    @Test fun manualChoiceWinsAndDisabledCustomRulesFallThroughToBuiltins() {
        val custom = ClassificationRule("custom", "自定", false, Direction.EXPENSE, "cat:expense:shopping", 0,
            listOf(ClassificationCondition(value="咖啡")))
        val builtin = custom.copy(id="builtin", name="餐饮", enabled=true, categoryId="cat:expense:dining")
        val snapshot = ClassificationSnapshot("v3", listOf(custom), listOf(builtin))
        assertEquals(CategorySource.BUILTIN_RULE, ClassificationEngine.classify(snapshot, ClassificationInput(Direction.EXPENSE, "咖啡馆")).source)
        val manual = CategoryDecision("personal", CategorySource.MANUAL, manual=true, explanation="人工选择分类")
        assertEquals(manual, ClassificationEngine.classify(snapshot, ClassificationInput(Direction.EXPENSE, "咖啡馆"), manual))
    }
}
