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

    @Test fun editingOnlyTheNotePreservesTheRuleDecisionAndFunds() {
        val suffix=UUID.randomUUID().toString().take(8);val merchant="合成备注来源$suffix"
        val previousDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(net.ankio.ledger.data.LedgerAccount(name="合成备注钱包",amountCents=10_000))
        app.store.setDefaultWallet(wallet.uid)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成备注规则$suffix",true,Direction.EXPENSE,"cat:expense:dining",0,listOf(ClassificationCondition(value=merchant))))
        var recordId=0L
        try {
            val bill=app.store.classify(ParsedBill(100,Direction.EXPENSE,merchant,"其他",1_798_776_000_000,"SYNTHETIC-NOTE-$suffix",true,"",true),app.store.classificationRules())
            val original=(app.store.ingest(Capture(Source.IMAGE,"image","合成付款备注$suffix"),bill) as net.ankio.ledger.data.IngestResult.Saved).record
            recordId=original.id
            assertEquals(CategorySource.CUSTOM_RULE,original.bill.categoryDecision!!.source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val bridge=LedgerWebBridge(activity)
                val response=JSONObject(bridge.save(JSONObject().put("id",original.id).put("amount","1.00").put("direction","EXPENSE")
                    .put("merchant",merchant).put("category","餐饮").put("categoryId","cat:expense:dining").put("note","仅修改合成备注")
                    .put("occurredAt",original.bill.occurredAt).put("expectedRevision",original.wallet!!.revision)
                    .put("submissionToken",UUID.randomUUID().toString()).toString()))
                assertTrue(response.toString(),response.getBoolean("ok"))
                val edited=app.store.get(original.id)!!
                assertEquals("仅修改合成备注",edited.note)
                assertEquals(original.bill.categoryDecision,edited.bill.categoryDecision)
                assertEquals(9900L,app.store.getAccount(wallet.id)!!.amountCents)
                val current=app.store.get(original.id)!!
                val explicit=JSONObject().put("id",current.id).put("amount","1.00").put("direction","EXPENSE").put("merchant",merchant)
                    .put("category","餐饮").put("categoryId","cat:expense:dining").put("note","明确重选同一分类")
                    .put("occurredAt",current.bill.occurredAt).put("expectedRevision",current.wallet!!.revision)
                    .put("categoryEdited",true).put("submissionToken",UUID.randomUUID().toString())
                assertTrue(JSONObject(bridge.save(explicit.toString())).getBoolean("ok"))
                val selected=app.store.get(current.id)!!
                assertEquals(CategorySource.MANUAL,selected.bill.categoryDecision!!.source);assertTrue(selected.bill.categoryDecision!!.manual)
                val forged=JSONObject(explicit.toString()).put("expectedRevision",selected.wallet!!.revision)
                    .put("submissionToken",UUID.randomUUID().toString()).put("categoryDecision",JSONObject().put("source","CUSTOM_RULE"))
                assertFalse(JSONObject(bridge.save(forged.toString())).getBoolean("ok"))
                val invalid=JSONObject(explicit.toString()).put("categoryEdited","true").put("expectedRevision",selected.wallet.revision)
                    .put("submissionToken",UUID.randomUUID().toString())
                assertFalse(JSONObject(bridge.save(invalid.toString())).getBoolean("ok"))
                assertEquals(9900L,app.store.getAccount(wallet.id)!!.amountCents)
            } }
        } finally {
            if(recordId>0)app.store.delete(recordId)
            app.store.deleteClassificationRule(rule.id);app.store.setDefaultWallet(previousDefault)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

}
