package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.core.*
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The settings bridge exposes actual migration effects through bill details and rule configuration. */
@RunWith(AndroidJUnit4::class)
class CategoryDeletionBridgeTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun previewAndConfirmMovesReferencesTogetherAndRequiresFreshImpactConfirmation():Unit=runBlocking {
        app.taskRecovery.await()
        val suffix=UUID.randomUUID().toString().take(8)
        val source=app.store.saveCategory(name="合成删除$suffix",direction=Direction.EXPENSE)
        val target=app.store.saveCategory(name="合成替代$suffix",direction=Direction.EXPENSE)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成删除规则$suffix",true,Direction.EXPENSE,source.id,0,listOf(ClassificationCondition(value=suffix))))
        val task=app.tasks.create(Source.IMAGE,"image")
        val ids=mutableListOf<Long>()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge=LedgerWebBridge(activity)
            fun save()=JSONObject(bridge.save(JSONObject().put("id",0).put("amount","12.34").put("direction","EXPENSE")
                .put("merchant","合成删除商家$suffix").put("categoryId",source.id).put("category",source.name).put("note","")
                .put("occurredAt",1_798_776_000_000).put("submissionToken",UUID.randomUUID().toString()).toString())).also { assertTrue(it.toString(),it.getBoolean("ok"));ids+=it.getLong("id") }
            save()
            val before=app.store.get(ids.first())!!
            var preview=JSONObject(bridge.previewCategoryDeletion(source.id)).getJSONObject("deletionPreview")
            assertEquals(1,preview.getInt("billCount"));assertEquals(1,preview.getInt("ruleCount"));assertEquals(1,preview.getInt("queuedConfigurationCount"))
            assertTrue(preview.getBoolean("requiresReplacement"))
            fun confirm(replacement:String?)=JSONObject(bridge.confirmCategoryDeletion(JSONObject().put("id",source.id)
                .put("replacementId",replacement ?: JSONObject.NULL).put("previewToken",preview.getString("previewToken")).toString()))
            assertEquals("CATEGORY_REPLACEMENT_REQUIRED",confirm(null).getString("code"))
            for(invalid in listOf(source.id,"cat:income:salary","missing"))assertEquals("CATEGORY_REPLACEMENT_INVALID",confirm(invalid).getString("code"))
            // Editing a referenced rule with the same count still invalidates the shown impact.
            app.store.saveClassificationRule(rule.copy(name="合成更新规则$suffix"))
            val sameCountStale=confirm(target.id)
            assertEquals("CATEGORY_PREVIEW_STALE",sameCountStale.getString("code"))
            preview=sameCountStale.getJSONObject("deletionPreview");assertEquals(1,preview.getInt("billCount"))
            save()
            val stale=confirm(target.id);assertEquals("CATEGORY_PREVIEW_STALE",stale.getString("code"))
            preview=stale.getJSONObject("deletionPreview");assertEquals(2,preview.getInt("billCount"))
            assertEquals(source.name,app.store.get(ids.first())!!.bill.category)
            assertTrue(confirm(target.id).getBoolean("ok"))
            val detail=JSONObject(bridge.recordDetails(ids.first())).getJSONObject("record")
            assertEquals(target.id,detail.getString("categoryId"));assertEquals(target.name,detail.getString("category"))
            val after=app.store.get(ids.first())!!
            assertEquals(before.bill.amountCents,after.bill.amountCents);assertEquals(before.bill.occurredAt,after.bill.occurredAt)
            assertEquals(before.bill.direction,after.bill.direction);assertEquals(before.wallet,after.wallet)
            assertEquals(before.bill.categoryDecision!!.source,after.bill.categoryDecision!!.source)
            assertTrue(after.bill.categoryDecision!!.manual)
            assertEquals(target.id,after.bill.categoryDecision!!.categoryId)
            assertEquals(target.id,app.store.classificationRules().customRules.single { it.id==rule.id }.categoryId)
            assertEquals(source.id,app.tasks.classificationSnapshot(task)!!.customRules.single { it.id==rule.id }.categoryId)
            assertFalse(app.store.categoryDirectory().any { it.id==source.id })
            assertEquals("CATEGORY_SYSTEM",JSONObject(bridge.previewCategoryDeletion("cat:expense:other")).getString("code"))
        } } } finally {
            ids.forEach { app.store.delete(it) };app.tasks.delete(task);app.store.deleteClassificationRule(rule.id)
            if(app.store.categoryDirectory().any { it.id==source.id })app.store.removeCategory(source.id)
            app.store.confirmCategoryDeletion(target.id,"cat:expense:other",app.store.previewCategoryDeletion(target.id).previewToken)
        }
    }
    @Test fun anUnusedCategoryCanBeConfirmedWithoutReplacementAndInvalidPayloadIsRejected() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge=LedgerWebBridge(activity)
            val unused=app.store.saveCategory(name="合成无引用${UUID.randomUUID().toString().take(8)}",direction=Direction.INCOME)
            val preview=JSONObject(bridge.previewCategoryDeletion(unused.id)).getJSONObject("deletionPreview")
            assertFalse(preview.getBoolean("requiresReplacement"))
            val response=JSONObject(bridge.confirmCategoryDeletion(JSONObject().put("id",unused.id).put("replacementId",JSONObject.NULL)
                .put("previewToken",preview.getString("previewToken")).toString()))
            assertTrue(response.toString(),response.getBoolean("ok"));assertFalse(app.store.categoryDirectory().any { it.id==unused.id })
            assertEquals("CATEGORY_INVALID",JSONObject(bridge.confirmCategoryDeletion("{broken")).getString("code"))
        } }
    }

}
