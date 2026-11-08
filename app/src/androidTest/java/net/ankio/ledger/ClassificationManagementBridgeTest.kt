package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.ClassificationCodec
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Calls native bridge methods directly; browser interactions belong to the separate UI acceptance. */
@RunWith(AndroidJUnit4::class)
class ClassificationManagementBridgeTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun bridgeSavesValidRulesRejectsInvalidTargetsAndExposesManualReason() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge=LedgerWebBridge(activity);val suffix=UUID.randomUUID().toString().take(8)
            val originalOrder=app.store.classificationRules().customRules.filter { it.direction==Direction.EXPENSE }.sortedBy { it.order }.map { it.id }
            val created=mutableListOf<String>();var recordId=0L
            try {
                fun input(name:String)=ClassificationCodec.rule(ClassificationRule("",name,true,Direction.EXPENSE,"cat:expense:dining",0,listOf(ClassificationCondition(value="合成咖啡$suffix"))))
                val payload=input("合成桥接$suffix")
                val valid=JSONObject(bridge.saveClassificationRule(payload.toString()));assertTrue(valid.toString(),valid.getBoolean("ok"))
                val rules=valid.getJSONObject("classificationRules").getJSONArray("customRules")
                val rule=(0 until rules.length()).map { rules.getJSONObject(it) }.single { it.getString("name")=="合成桥接$suffix" }
                created+=rule.getString("id")
                val second=JSONObject(bridge.saveClassificationRule(input("合成第二$suffix").toString()))
                val secondRules=second.getJSONObject("classificationRules").getJSONArray("customRules")
                created+=(0 until secondRules.length()).map { secondRules.getJSONObject(it) }.single { it.getString("name")=="合成第二$suffix" }.getString("id")
                val order=originalOrder+created.reversed()
                assertTrue(JSONObject(bridge.reorderClassificationRules("EXPENSE",JSONArray(order).toString())).getBoolean("ok"))
                assertEquals(order,app.store.classificationRules().customRules.filter { it.direction==Direction.EXPENSE }.sortedBy { it.order }.map { it.id })
                val disabled=JSONObject(bridge.saveClassificationRule(rule.put("enabled",false).toString()))
                assertTrue(disabled.getBoolean("ok"));assertFalse(app.store.classificationRules().customRules.single { it.id==created.first() }.enabled)
                for(invalid in listOf(input("合成无效").put("direction","TRANSFER"),input("合成无效").put("categoryId","cat:income:salary"),
                    input("合成无效").put("categoryId","missing"),input("合成无效").put("conditions",JSONArray().put(JSONObject().put("field","MERCHANT").put("operator","CONTAINS").put("value","　 "))))) {
                    val failure=JSONObject(bridge.saveClassificationRule(invalid.toString()));assertFalse(failure.toString(),failure.getBoolean("ok"));assertTrue(failure.getString("code").startsWith("RULE_"))
                }
                val saved=JSONObject(bridge.save(JSONObject().put("id",0).put("amount","1.00").put("direction","EXPENSE")
                    .put("merchant","合成桥接商家$suffix").put("category","餐饮").put("categoryId","cat:expense:dining").put("note","")
                    .put("occurredAt",System.currentTimeMillis()).put("submissionToken",UUID.randomUUID().toString()).toString()))
                assertTrue(saved.toString(),saved.getBoolean("ok"));recordId=saved.getLong("id")
                val detail=JSONObject(bridge.recordDetails(recordId)).getJSONObject("record").getJSONObject("categoryDecision")
                assertEquals("MANUAL",detail.getString("source"));assertEquals("人工选择分类",detail.getString("explanation"));assertTrue(detail.getBoolean("manual"))
                assertTrue(JSONObject(bridge.snapshot()).has("classificationRules"))
            } finally {
                if(recordId>0)app.store.delete(recordId)
                created.forEach { app.store.deleteClassificationRule(it) }
                app.store.reorderClassificationRules(Direction.EXPENSE,originalOrder)
            }
        } }
    }
    @Test fun symbolsAndFormattingWhitespaceAreValidKeywordsWithoutChangingTheirRawValue() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge=LedgerWebBridge(activity);val suffix=UUID.randomUUID().toString().take(8);val ids=mutableListOf<String>()
            try {
                for((index,keyword) in listOf("＋","🧋","合成$suffix\tＡ＋Ｂ").withIndex()) {
                    val name="合成字面关键词$suffix-$index"
                    val payload=ClassificationCodec.rule(ClassificationRule("",name,true,Direction.EXPENSE,"cat:expense:dining",0,
                        listOf(ClassificationCondition(operator=ClassificationOperator.EQUALS,value=keyword))))
                    val result=JSONObject(bridge.saveClassificationRule(payload.toString()))
                    assertTrue(result.toString(),result.getBoolean("ok"))
                    val saved=app.store.classificationRules().customRules.single { it.name==name };ids+=saved.id
                    assertEquals(keyword,saved.conditions.single().value)
                    val bill=ParsedBill(100,Direction.EXPENSE,if(index==2)"合成$suffix a+b" else keyword,"其他",1_798_776_000_000,null,true,"",true)
                    assertEquals(saved.id,app.store.classify(bill,app.store.classificationRules()).categoryDecision!!.ruleId)
                }
            } finally { ids.forEach { app.store.deleteClassificationRule(it) } }
        } }
    }

}
