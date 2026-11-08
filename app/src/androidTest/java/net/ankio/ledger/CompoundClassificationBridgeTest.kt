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

@RunWith(AndroidJUnit4::class)
class CompoundClassificationBridgeTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun bridgeEditsCompoundRulesAndBuiltinStateWithoutSavingCopiedDrafts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge=LedgerWebBridge(activity);val suffix=UUID.randomUUID().toString()
            val original=app.store.classificationRules();val builtin=original.builtinRules.first()
            val created=mutableListOf<String>()
            try {
                val toggle=JSONObject().put("id",builtin.id).put("enabled",false)
                assertTrue(JSONObject(bridge.setBuiltinClassificationRuleEnabled(toggle.toString())).getBoolean("ok"))
                assertFalse(app.store.classificationRules().builtinRules.single { it.id==builtin.id }.enabled)
                val version=app.store.classificationRules().version
                val copy=JSONObject(bridge.copyBuiltinClassificationRule(builtin.id)).getJSONObject("rule")
                assertTrue(copy.isNull("id"));assertTrue(copy.getBoolean("enabled"))
                assertEquals(builtin.conditions.single().value,copy.getJSONArray("conditions").getJSONObject(0).getString("value"))
                assertEquals(version,app.store.classificationRules().version)
                copy.put("name","合成组合桥接$suffix").put("source","IMAGE:wechat").put("matchMode","ANY")
                    .put("conditions",JSONArray().put(JSONObject().put("field","MERCHANT").put("operator","EQUALS").put("value","Ａ＋Ｂ"))
                        .put(JSONObject().put("field","ORDER_CONTENT").put("operator","NOT_CONTAINS").put("value","打车")))
                val saved=JSONObject(bridge.saveClassificationRule(copy.toString()));assertTrue(saved.toString(),saved.getBoolean("ok"))
                val rule=app.store.classificationRules().customRules.single { it.name=="合成组合桥接$suffix" };created+=rule.id
                assertEquals(ClassificationMatchMode.ANY,rule.matchMode);assertEquals("IMAGE:wechat",rule.source)
                assertEquals("Ａ＋Ｂ",rule.conditions.first().value)
                for(invalid in listOf(JSONObject(copy.toString()).put("source","微信广告"),
                    JSONObject(copy.toString()).put("source","IMAGE:com.tencent.mm"),
                    JSONObject(copy.toString()).put("matchMode","NESTED"),JSONObject(copy.toString()).put("conditions",JSONArray()),
                    JSONObject(copy.toString()).put("categoryId","cat:income:salary"),
                    JSONObject(copy.toString()).put("conditions",JSONArray().put(JSONObject().put("field","NOTE").put("operator","CONTAINS").put("value","打车"))),
                    JSONObject(copy.toString()).put("enabled","true"))) {
                    val result=JSONObject(bridge.saveClassificationRule(invalid.toString()))
                    assertFalse(result.toString(),result.getBoolean("ok"));assertTrue(result.getString("code").startsWith("RULE_"))
                }
                assertFalse(JSONObject(bridge.setBuiltinClassificationRuleEnabled(toggle.put("enabled","false").toString())).getBoolean("ok"))
                assertFalse(JSONObject(bridge.setBuiltinClassificationRuleEnabled(toggle.put("id",rule.id).put("enabled",true).toString())).getBoolean("ok"))
                assertFalse(JSONObject(bridge.copyBuiltinClassificationRule(rule.id)).getBoolean("ok"))
                assertEquals(1,app.store.classificationRules().customRules.count { it.id in created })
                assertEquals(original.customRules.size+1,app.store.classificationRules().customRules.size)
            } finally {
                created.forEach { app.store.deleteClassificationRule(it) }
                app.store.setBuiltinClassificationRuleEnabled(builtin.id,builtin.enabled)
            }
        } }
    }
}
