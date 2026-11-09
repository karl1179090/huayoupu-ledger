package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.Direction
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Management and editor requests cross the real WebView bridge and use the same store. */
@RunWith(AndroidJUnit4::class)
class CategoryManagementBridgeTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun manageDirectoryAndValidateEditorCategoryDirection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val bridge = LedgerWebBridge(activity)
            val name = "合成分类" + UUID.randomUUID().toString().take(8)
            val created = JSONObject(bridge.saveCategory(JSONObject().put("name", name).put("direction", "EXPENSE").put("icon", "heart").toString()))
            assertTrue(created.toString(), created.getBoolean("ok"))
            val category = created.getJSONObject("category")
            val id = category.getString("id")
            var billId = 0L
            try {
                fun input(direction: String) = JSONObject().put("id", 0).put("amount", "12.34").put("direction", direction)
                    .put("merchant", "合成分类商家").put("category", name).put("categoryId", id).put("note", "")
                    .put("occurredAt", System.currentTimeMillis()).put("submissionToken", UUID.randomUUID().toString())
                val wrongDirection = JSONObject(bridge.save(input("INCOME").toString()))
                assertFalse(wrongDirection.toString(), wrongDirection.getBoolean("ok"))
                assertEquals("CATEGORY_DIRECTION", wrongDirection.getString("code"))
                val missing = JSONObject(bridge.save(input("EXPENSE").put("categoryId", "missing-category").toString()))
                assertEquals("CATEGORY_NOT_FOUND", missing.getString("code"))
                val saved = JSONObject(bridge.save(input("EXPENSE").toString()))
                assertTrue(saved.toString(), saved.getBoolean("ok")); billId = saved.getLong("id")
                val blocked = JSONObject(bridge.removeCategory(id))
                assertEquals("CATEGORY_REFERENCED", blocked.getString("code")); assertEquals(1, blocked.getInt("referenceCount"))
                val renamed = JSONObject(bridge.saveCategory(category.put("name", name + "改").toString()))
                assertTrue(renamed.toString(), renamed.getBoolean("ok"))
                val details = JSONObject(bridge.recordDetails(billId)).getJSONObject("record")
                assertEquals(name + "改", details.getString("category")); assertEquals(id, details.getString("categoryId"))
                assertEquals("heart", details.getString("categoryIcon"))
                val directory = JSONObject(bridge.getCategoryDirectory()).getJSONObject("categoryDirectory").getJSONArray("expense")
                val ids = JSONArray((0 until directory.length()).map { directory.getJSONObject(it).getString("id") }.reversed())
                assertTrue(JSONObject(bridge.reorderCategories("EXPENSE", ids.toString())).getBoolean("ok"))
                val refreshed = JSONObject(bridge.snapshot()).getJSONObject("categoryDirectory").getJSONArray("expense")
                assertEquals(ids.toString(), JSONArray((0 until refreshed.length()).map { refreshed.getJSONObject(it).getString("id") }).toString())
                val duplicate = JSONObject(bridge.saveCategory(JSONObject().put("name", "  " + name.uppercase() + "改  ").put("direction", "expense").put("icon", "tag").toString()))
                assertEquals("CATEGORY_DUPLICATE", duplicate.getString("code"))
                for (invalid in listOf("", " ", "合成\n分类", "名".repeat(41))) {
                    val rejected = JSONObject(bridge.saveCategory(JSONObject().put("name", invalid).put("direction", "EXPENSE").put("icon", "tag").toString()))
                    assertFalse(rejected.toString(), rejected.getBoolean("ok"))
                }
            } finally {
                if (billId > 0) app.store.delete(billId)
                if (app.store.categoryDirectory(Direction.EXPENSE).any { it.id == id }) app.store.removeCategory(id)
            }
        } }
    }
}
