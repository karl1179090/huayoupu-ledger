package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class ClassificationPreviewTest {
    @Test fun previewDistinguishesSourceDirectionAndDisabledRulesFromConditionFailuresAndFallback() {
        val base=ClassificationRule("live","合成现场规则",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(value="咖啡")),source="LIVE:com.tencent.mm")
        val builtin=base.copy(id="builtin",name="餐饮",source=null)
        val snapshot=ClassificationSnapshot("preview-eligibility",listOf(base,base.copy(id="income",direction=Direction.INCOME,order=1),
            base.copy(id="disabled",enabled=false,source=null,order=2)),listOf(builtin))
        val input=ClassificationInput(Direction.EXPENSE,"咖啡",captureSource=Source.IMAGE)
        val preview=ClassificationEngine.preview(snapshot,input)
        assertEquals(CategorySource.BUILTIN_RULE,preview.decision.source)
        assertFalse(preview.rules.first().sourceMatches)
        assertTrue(preview.rules.first().conditions.single().matched)
        assertFalse(preview.rules[1].directionMatches)
        assertFalse(preview.rules[2].matched)
        assertTrue(preview.rules.last().selected)
        val fallback=ClassificationEngine.preview(snapshot,input.copy(merchant=null))
        assertEquals("cat:expense:other",fallback.decision.categoryId)
        assertEquals("未命中规则，归入其他",fallback.decision.explanation)
        assertTrue(fallback.rules.none { it.selected || it.interceptedByRuleId!=null })
    }

    @Test fun hypotheticalInputKeepsStructuredFieldsSeparateAndOnlyUsesExplicitSourceChoices() {
        val input=ClassificationInput.hypothetical(Direction.EXPENSE," 微信支付合成平台 ",listOf("到店","午餐"),"IMAGE")
        assertEquals(Source.IMAGE,input.captureSource);assertNull(input.platform);assertNull(input.sourcePackage)
        assertEquals(listOf("到店","午餐"),input.orderContent)
        val image=ClassificationInput.hypothetical(Direction.EXPENSE,null,emptyList(),"IMAGE:wechat")
        assertEquals("wechat",image.platform)
        assertNull(image.sourcePackage)
        val live=ClassificationInput.hypothetical(Direction.INCOME,null,emptyList(),"LIVE:com.tencent.mm")
        assertEquals(Source.OCR,live.captureSource);assertEquals("com.tencent.mm",live.sourcePackage);assertNull(live.platform)
        assertNull(ClassificationInput.hypothetical(Direction.INCOME,null,emptyList(),null).captureSource)
        for(invalid in listOf(
            { ClassificationInput.hypothetical(Direction.TRANSFER,null,emptyList(),null) },
            { ClassificationInput.hypothetical(Direction.EXPENSE,"x".repeat(2001),emptyList(),null) },
            { ClassificationInput.hypothetical(Direction.EXPENSE,null,List(4) { "订单" },null) },
            { ClassificationInput.hypothetical(Direction.EXPENSE,null,listOf("x".repeat(2001)),null) },
            { ClassificationInput.hypothetical(Direction.EXPENSE,null,emptyList(),"IMAGE:com.tencent.mm") })) {
            assertTrue(runCatching(invalid).isFailure)
        }
    }

    @Test fun previewExplainsEarlierCustomInterceptionAndStillEvaluatesLaterConditions() {
        val first=ClassificationRule("first","合成优先餐饮",true,Direction.EXPENSE,"cat:expense:dining",1,
            listOf(ClassificationCondition(value="合成平台")),source="IMAGE:wechat")
        val later=first.copy(id="later",name="合成订单分类",categoryId="cat:expense:shopping",order=2,
            conditions=listOf(ClassificationCondition(value="合成平台"),
                ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.EQUALS,"Ａ＋Ｂ 午餐")))
        val disabled=first.copy(id="disabled",enabled=false,order=0)
        val builtin=first.copy(id="builtin",order=0)
        val snapshot=ClassificationSnapshot("preview-v1",listOf(later,disabled,first),listOf(builtin))
        val input=ClassificationInput(Direction.EXPENSE,"合成平台",listOf("a + b\t午餐","到店"),Source.IMAGE,platform="wechat")
        val result=ClassificationEngine.preview(snapshot,input)
        assertEquals("first",result.decision.ruleId)
        assertEquals("命中规则：合成优先餐饮",result.decision.explanation)
        assertEquals(result.decision,ClassificationEngine.classify(snapshot,input))
        assertEquals(listOf("disabled","first","later","builtin"),result.rules.map { it.rule.id })
        assertFalse(result.rules.first().matched)
        assertTrue(result.rules[1].selected)
        val intercepted=result.rules[2]
        assertTrue(intercepted.matched);assertFalse(intercepted.selected)
        assertEquals("first",intercepted.interceptedByRuleId)
        assertEquals(listOf(true,true),intercepted.conditions.map { it.matched })
        assertEquals(listOf("a + b\t午餐","到店"),result.input.orderContent)
        assertEquals("first",result.rules.last().interceptedByRuleId)
        assertEquals(CategorySource.BUILTIN_RULE,result.rules.last().source)
    }
}
