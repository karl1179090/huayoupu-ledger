package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CompoundClassificationMatrixTest(private val field:ClassificationField,private val operator:ClassificationOperator,
    private val keyword:String,private val values:List<String>,private val expectedId:String?) {
    @Test fun comparesLiteralBusinessFieldsWithoutGuessingOrCombiningThem() {
        val rule=ClassificationRule("matched","合成字面条件",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(field,operator,keyword)))
        val input=if(field==ClassificationField.MERCHANT)ClassificationInput(Direction.EXPENSE,values.singleOrNull())
            else ClassificationInput(Direction.EXPENSE,null,values)
        val result=ClassificationEngine.classify(ClassificationSnapshot("matrix",listOf(rule),emptyList()),input)
        assertEquals(expectedId,result.ruleId)
        val preview=ClassificationEngine.preview(ClassificationSnapshot("matrix",listOf(rule),emptyList()),input)
        assertEquals(expectedId,preview.decision.ruleId)
        assertEquals(expectedId!=null,preview.rules.single().conditions.single().matched)
        assertEquals(if(field==ClassificationField.MERCHANT)values.singleOrNull() else null,input.merchant)
        assertEquals(if(field==ClassificationField.ORDER_CONTENT)values else emptyList<String>(),input.orderContent)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0} {1} {2} against {3}") fun examples():List<Array<Any?>> = buildList {
            for(field in ClassificationField.entries)for(operator in ClassificationOperator.entries) {
                add(arrayOf(field,operator,"午餐",emptyList<String>(),null))
                add(arrayOf(field,operator,"午餐",listOf("　 \t"),null))
            }
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.CONTAINS,"a+b",listOf("合成 Ａ＋Ｂ 店"),"matched"))
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.EQUALS,"Ａ＋Ｂ",listOf("a \t+ b"),"matched"))
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.EQUALS,"a+b",listOf("A-B"),null))
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.CONTAINS,"🧋",listOf("合成☕店"),null))
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.CONTAINS,"1008",listOf("合成l008店"),null))
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.NOT_CONTAINS,"打车",listOf("午餐"),"matched"))
            add(arrayOf(ClassificationField.MERCHANT,ClassificationOperator.NOT_CONTAINS,"打车",listOf("合成打车平台"),null))
            add(arrayOf(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"店午",listOf("到店","午餐"),null))
            add(arrayOf(ClassificationField.ORDER_CONTENT,ClassificationOperator.EQUALS,"Ａ＋Ｂ 午餐",listOf("到店","a+b\t午餐","少糖"),"matched"))
            add(arrayOf(ClassificationField.ORDER_CONTENT,ClassificationOperator.NOT_CONTAINS,"打车",listOf("午餐","打车"),null))
            add(arrayOf(ClassificationField.ORDER_CONTENT,ClassificationOperator.NOT_CONTAINS,"打车",listOf("午餐","少糖"),"matched"))
        }
    }
}
