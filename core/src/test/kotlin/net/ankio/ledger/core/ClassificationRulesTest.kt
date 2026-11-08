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
    @Test fun punctuationAndSymbolsRemainSignificantAfterFormatNormalization() {
        val rule=ClassificationRule("literal","合成字面关键词",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(operator=ClassificationOperator.EQUALS,value="Ａ＋Ｂ")))
        val snapshot=ClassificationSnapshot("literal-v1",listOf(rule),emptyList())
        assertEquals(CategorySource.CUSTOM_RULE,ClassificationEngine.classify(snapshot,ClassificationInput(Direction.EXPENSE,"a + b")).source)
        assertEquals(CategorySource.FALLBACK,ClassificationEngine.classify(snapshot,ClassificationInput(Direction.EXPENSE,"A-B")).source)
        val emoji=snapshot.copy(customRules=listOf(rule.copy(conditions=listOf(ClassificationCondition(value="🧋")))))
        assertEquals(CategorySource.CUSTOM_RULE,ClassificationEngine.classify(emoji,ClassificationInput(Direction.EXPENSE,"合成🧋茶饮")).source)
        assertEquals(CategorySource.FALLBACK,ClassificationEngine.classify(emoji,ClassificationInput(Direction.EXPENSE,"合成☕饮品")).source)
    }

    @Test fun theSameMerchantCanBeClassifiedByStructuredOrderContent() {
        val ride = ClassificationRule("ride", "合成出行", true, Direction.EXPENSE, "cat:expense:transport", 0,
            listOf(ClassificationCondition(value="合成平台"),
                ClassificationCondition(ClassificationField.valueOf("ORDER_CONTENT"), ClassificationOperator.CONTAINS, "打车")))
        val meal = ride.copy(id="meal", name="合成餐饮", categoryId="cat:expense:dining", order=1,
            conditions=ride.conditions.dropLast(1)+ClassificationCondition(ClassificationField.valueOf("ORDER_CONTENT"),
                ClassificationOperator.EQUALS,"Ａ＋Ｂ 午餐"))
        val snapshot=ClassificationSnapshot("compound-v1",listOf(ride,meal),emptyList())
        val receipt=ReceiptEvidence(productDescription=ReceiptField("a + b\t午餐","商品说明：a + b\t午餐"))
        val bill=ParsedBill(1200,Direction.EXPENSE,"合成平台","其他",1_798_776_000_000,null,true,"",true,receipt=receipt)
        val input=ClassificationInput.from(bill,Capture(Source.IMAGE,"image","广告：打车\n手写备注：打车"))
        val decision=ClassificationEngine.classify(snapshot,input)
        assertEquals("meal",decision.ruleId)
        assertEquals("cat:expense:dining",decision.categoryId)
        assertEquals("a + b\t午餐",bill.receipt!!.productDescription!!.value)
        assertEquals(1200L,bill.amountCents)
    }

    @Test fun anyConditionsAndNegativeConditionsStillRejectMissingFields() {
        val negative=ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.valueOf("NOT_CONTAINS"),"打车")
        val rule=ClassificationRule("negative","合成非出行",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(value="合成平台"),negative),ClassificationMatchMode.valueOf("ANY"))
        val snapshot=ClassificationSnapshot("negative-v1",listOf(rule),emptyList())
        fun classify(merchant:String?,vararg contents:String)=ClassificationEngine.classify(snapshot,ClassificationInput(Direction.EXPENSE,merchant,contents.toList()))
        assertEquals(CategorySource.FALLBACK,classify(null).source)
        assertEquals(CategorySource.FALLBACK,classify("另一商家","打车").source)
        assertEquals("negative",classify("另一商家","午餐").ruleId)
        assertEquals("negative",classify("合成平台").ruleId)
        val all=snapshot.copy(customRules=listOf(rule.copy(matchMode=ClassificationMatchMode.ALL)))
        assertEquals(CategorySource.FALLBACK,ClassificationEngine.classify(all,ClassificationInput(Direction.EXPENSE,"合成平台")).source)
        assertEquals(CategorySource.FALLBACK,ClassificationEngine.classify(all,ClassificationInput(Direction.EXPENSE,"合成平台",listOf("午餐","打车"))).source)
        assertEquals("negative",ClassificationEngine.classify(all,ClassificationInput(Direction.EXPENSE,"合成平台",listOf("午餐","堂食"))).ruleId)
    }

    @Test fun sourceLimitsSeparateLivePackagesFromExplicitImagePlatformEvidence() {
        val base=ClassificationRule("source","合成来源",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(value="合成平台")),source="LIVE:com.tencent.mm")
        val bill=ParsedBill(100,Direction.EXPENSE,"合成平台","其他",1_798_776_000_000,null,true,"",true)
        fun decision(rule:ClassificationRule,source:Source,origin:String,b:ParsedBill=bill,text:String="") =
            ClassificationEngine.classify(ClassificationSnapshot("sources",listOf(rule),emptyList()),ClassificationInput.from(b,Capture(source,origin,text)))
        assertEquals(CategorySource.FALLBACK,decision(base,Source.IMAGE,"com.tencent.mm").source)
        assertEquals("source",decision(base,Source.OCR,"shake:com.tencent.mm").ruleId)
        assertEquals(CategorySource.FALLBACK,decision(base,Source.OCR,"shake:com.synthetic.viewer",text="微信支付").source)
        val image=base.copy(source="IMAGE:wechat")
        val arbitrary=bill.copy(paymentMethod=PaymentMethodEvidence("UNKNOWN","DEBIT",platform="wechat",reason="PAYMENT_METHOD_MISSING"))
        assertEquals(CategorySource.FALLBACK,decision(image,Source.IMAGE,"image",arbitrary,"广告：微信支付").source)
        val explicit=bill.copy(receipt=ReceiptEvidence(receiptIssuer=ReceiptField("微信支付","支付机构：微信支付")))
        assertEquals("source",decision(image,Source.IMAGE,"image",explicit).ruleId)
        assertEquals(CategorySource.FALLBACK,decision(image,Source.OCR,"com.tencent.mm",explicit).source)
        val payment=bill.copy(paymentMethod=PaymentMethodEvidence("ALIPAY_BALANCE","DEBIT",platform="alipay",rawLabel="付款方式",rawValue="支付宝余额"))
        assertEquals("source",decision(base.copy(source="IMAGE:alipay"),Source.IMAGE,"image",payment).ruleId)
        val conflict=explicit.copy(paymentMethod=payment.paymentMethod)
        assertEquals(CategorySource.FALLBACK,decision(image,Source.IMAGE,"image",conflict).source)
    }

    @Test fun ruleExplanationRetainsTheExactMatchingDefinitionAndBuiltinState() {
        val builtin=ClassificationDefaults.migrationBaseline.builtinRules.first()
        val frozen=ClassificationSnapshot("frozen-builtins",emptyList(),listOf(builtin))
        val decision=ClassificationEngine.classify(frozen,ClassificationInput(Direction.EXPENSE,builtin.conditions.single().value))
        assertEquals(builtin,decision.rule)
        assertEquals(CategorySource.BUILTIN_RULE,decision.source)
        assertEquals("内置规则：交通",decision.explanation)
        assertEquals(CategorySource.FALLBACK,ClassificationEngine.classify(frozen.copy(builtinRules=listOf(builtin.copy(enabled=false))),
            ClassificationInput(Direction.EXPENSE,builtin.conditions.single().value)).source)
    }

}
