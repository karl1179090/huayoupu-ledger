package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.core.*
import net.ankio.ledger.data.ClassificationCodec
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClassificationPreviewBridgeTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun bridgeTestsManualAndBillInputsAndRejectsInvalidRequestsWithoutMutatingAnyState()=runBlocking<Unit> {
        app.taskRecovery.await()
        val merchant="合成预览桥接${UUID.randomUUID()}"
        val rule=app.store.saveClassificationRule(ClassificationRule("",merchant,true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(operator=ClassificationOperator.EQUALS,value=merchant)),source="IMAGE"))
        val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.IMAGE,"com.tencent.mm","广告：微信支付"),
            bill=ParsedBill(100,Direction.EXPENSE,merchant,"购物",1_798_776_000_000,null,true,"",true),state=RecordState.POSTED,note="微信支付 午餐"))
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge=LedgerWebBridge(activity)
            val records=app.store.all();val accounts=app.store.accounts();val wallet=app.store.walletSnapshot()
            val categories=app.store.categoryDirectory();val config=app.store.classificationBackup();val revision=app.revision.value
            val draftResponse=JSONObject(bridge.classificationRuleDraftFromBill(record.id))
            assertTrue(draftResponse.toString(),draftResponse.getBoolean("ok"))
            val draft=draftResponse.getJSONObject("rule");assertTrue(draft.isNull("id"))
            assertEquals("cat:expense:shopping",draft.getString("categoryId"))
            val fromBill=JSONObject(bridge.previewClassification(JSONObject().put("recordId",record.id).put("rule",draft).toString()))
            assertTrue(fromBill.toString(),fromBill.getBoolean("ok"))
            val preview=fromBill.getJSONObject("preview")
            assertEquals(rule.id,preview.getJSONObject("decision").getString("ruleId"))
            assertEquals("餐饮",preview.getJSONObject("category").getString("name"))
            assertTrue(preview.getJSONObject("input").isNull("platform"))
            val traces=preview.getJSONArray("rules")
            val intercepted=(0 until traces.length()).map { traces.getJSONObject(it) }.single { it.getJSONObject("rule").getString("id")==preview.getString("draftRuleId") }
            assertTrue(intercepted.getBoolean("matched"));assertFalse(intercepted.getBoolean("selected"))
            assertEquals(rule.id,intercepted.getString("interceptedByRuleId"))
            val manual=JSONObject().put("direction","EXPENSE").put("merchant",merchant).put("source","IMAGE").put("orderContent",JSONArray().put("午餐"))
            val tested=JSONObject(bridge.previewClassification(JSONObject().put("input",manual).toString()))
            assertTrue(tested.toString(),tested.getBoolean("ok"))
            assertEquals(rule.id,tested.getJSONObject("preview").getJSONObject("decision").getString("ruleId"))
            for(invalid in listOf(JSONObject(),JSONObject().put("recordId",record.id).put("input",manual),
                JSONObject().put("recordId",record.id.toString()),JSONObject().put("recordId",record.id+0.5),JSONObject().put("recordId",0),
                JSONObject().put("input",JSONObject(manual.toString()).put("direction","TRANSFER")),
                JSONObject().put("input",JSONObject(manual.toString()).put("source","IMAGE:com.tencent.mm")),
                JSONObject().put("input",JSONObject(manual.toString()).put("merchant",true)),
                JSONObject().put("input",JSONObject(manual.toString()).put("orderContent",JSONArray().put("a").put("b").put("c").put("d"))),
                JSONObject().put("input",JSONObject(manual.toString()).put("orderContent",JSONArray().put(17))),
                JSONObject().put("input",JSONObject(manual.toString()).put("orderContent",JSONArray().put("x".repeat(2001)))))) {
                val response=JSONObject(bridge.previewClassification(invalid.toString()))
                assertFalse(response.toString(),response.getBoolean("ok"));assertEquals("RULE_INPUT",response.getString("code"))
            }
            val invalidDraft=ClassificationCodec.rule(rule).put("enabled","true")
            assertEquals("RULE_INVALID",JSONObject(bridge.previewClassification(JSONObject().put("input",manual).put("rule",invalidDraft).toString())).getString("code"))
            assertEquals("RULE_INPUT",JSONObject(bridge.previewClassification("{")).getString("code"))
            assertFalse(JSONObject(bridge.classificationRuleDraftFromBill(-1)).getBoolean("ok"))
            assertEquals(records,app.store.all());assertEquals(accounts,app.store.accounts());assertEquals(wallet,app.store.walletSnapshot())
            assertEquals(categories,app.store.categoryDirectory());assertEquals(config,app.store.classificationBackup())
            assertEquals(revision,app.revision.value)
        } } } finally { app.store.delete(record.id);app.store.deleteClassificationRule(rule.id) }
    }
}
