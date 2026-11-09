package net.ankio.ledger.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Explicit host phases only: no activity/financial mutation is hidden in ordinary widget tests. */
@RunWith(AndroidJUnit4::class)
class ExpenseWidgetHostProbeTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun taskIds()=app.tasks.readableDatabase.rawQuery("SELECT id FROM tasks ORDER BY id",null).use { c ->
        buildList {while(c.moveToNext())add(c.getLong(0))}
    }
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun await(check:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+15000
        while(!check()) {assertTrue("Host probe timed out",SystemClock.elapsedRealtime()<until);SystemClock.sleep(80)}
    }
    @Test fun explicitlyRequestedHostPhase() {
        val phase=InstrumentationRegistry.getArguments().getString("widgetHostPhase")
        assumeTrue("Use scripts/verify-widget-host-lifecycle.py with an explicit phase",phase in setOf("setup","verify","cleanup","inspect","restore-initial"))
        assertTrue("Synthetic host probes only",android.os.Build.MODEL.contains("sdk_gphone"))
        val token=requireNotNull(InstrumentationRegistry.getArguments().getString("widgetHostToken"))
        UUID.fromString(token)
        val file=File(app.cacheDir,"widget-host-fixture.json")
        when(phase) {
            "inspect","restore-initial" -> {
                val id=requireNotNull(InstrumentationRegistry.getArguments().getString("widgetHostWidgetId")).toInt()
                val manager=AppWidgetManager.getInstance(app)
                val info=requireNotNull(manager.getAppWidgetInfo(id))
                assertEquals(ComponentName(app,ExpenseWidgetProvider::class.java),info.provider)
                fun options()=manager.getAppWidgetOptions(id).apply {keySet()}.toString()
                val before=options()
                // Finish any startup publication before restoring an explicitly captured
                // initial-layout host state. This does not create, resize or remove the pin.
                if(phase=="restore-initial") {
                    ExpenseWidgetUpdater.request(app,intArrayOf(id))
                    await {ExpenseWidgetUpdater.measurement(id)!=null}
                    manager.updateAppWidget(id,android.widget.RemoteViews(app.packageName,info.initialLayout))
                }
                assertEquals(before,options())
                println("WIDGET_HOST_PHASE phase=$phase provider=${info.provider} widgetId=$id options=$before")
            }
            "setup" -> {
                assertEquals("Use a fresh isolated synthetic ledger",0L,app.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records",null).use {it.moveToFirst();it.getLong(0)})
                assertTrue(app.store.accounts().isEmpty());assertTrue(taskIds().isEmpty())
                assertTrue("Use an isolated Launcher without existing expense instances",ExpenseWidgetProvider.ids(app).isEmpty())
                assertFalse("No capture consent is introduced by this probe",app.channels.captureEnabled())
                val zone=ZoneId.systemDefault()
                val fixtures=listOf("2026-12-31" to 12300L,"2027-01-01" to 45600L,"2027-01-03" to 700L,"2027-01-04" to 900L)
                val records=fixtures.map { (day,cents) ->
                    val at=LocalDate.parse(day).atTime(12,0).atZone(zone).toInstant().toEpochMilli()
                    LedgerRecord(capture=Capture(Source.MANUAL,"synthetic-host","合成组件生命周期",at),
                        bill=ParsedBill(cents,Direction.EXPENSE,"合成生命周期","购物",at,null,true,"",true),state=RecordState.POSTED)
                }
                val state=JSONObject().put("runToken",token).put("zone",zone.id).put("package",app.packageName).put("version",BuildConfig.VERSION_NAME)
                    .put("entryTaskIds",JSONArray(taskIds())).put("records",JSONArray().apply {records.forEach {
                        put(JSONObject().put("uid",it.uid).put("at",it.bill.occurredAt).put("cents",it.bill.amountCents.toString()))
                    }})
                // Write the ownership list before committing, so a failed pin can still clean its own rows.
                file.writeText(state.toString())
                app.store.importTransactions(records)
                val manager=AppWidgetManager.getInstance(app)
                assertTrue("System Launcher must support pin",manager.isRequestPinAppWidgetSupported)
                val activity=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                instrumentation.runOnMainSync {assertTrue(manager.requestPinAppWidget(ComponentName(app,ExpenseWidgetProvider::class.java),null,null))}
                val automation=instrumentation.uiAutomation
                await {
                    val add=nodes(automation.rootInActiveWindow).firstOrNull {it.isClickable && it.text?.toString()?.let {s ->
                        s.contains("ADD AUTOMATICALLY",true)||s.equals("Add",true)||s.equals("Add to home screen",true)||s.contains("自动添加")||s=="添加"||s=="添加到主屏幕"
                    }==true}
                    add?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ExpenseWidgetProvider.ids(app).size==1
                }
                val id=ExpenseWidgetProvider.ids(app).single()
                state.put("widgetId",id);file.writeText(state.toString())
                ExpenseWidgetUpdater.select(app,id,ExpensePeriod.MONTH)
                ExpenseWidgetUpdater.request(app,intArrayOf(id))
                await {ExpenseWidgetUpdater.measurement(id)?.period==ExpensePeriod.MONTH}
                instrumentation.runOnMainSync {activity.moveTaskToBack(true)}
            }
            "verify" -> {
                val state=JSONObject(file.readText());assertEquals(token,state.getString("runToken"));val rows=state.getJSONArray("records")
                val expected=(0 until rows.length()).associate { val row=rows.getJSONObject(it);row.getString("uid") to row }
                val actual=app.store.all()
                assertEquals(expected.keys,actual.map {it.uid}.toSet())
                actual.forEach {r ->
                    val row=expected.getValue(r.uid)
                    assertEquals(row.getString("cents").toLong(),r.bill.amountCents)
                    assertEquals(row.getLong("at"),r.bill.occurredAt)
                    assertEquals(Direction.EXPENSE,r.bill.direction);assertEquals(RecordState.POSTED,r.state)
                    assertEquals(WalletEligibility.IMPORT_EXCLUDED,r.wallet!!.eligibility);assertTrue(r.walletEffects.isEmpty())
                }
                assertTrue(app.store.accounts().isEmpty())
                val tasks=state.getJSONArray("entryTaskIds")
                assertEquals((0 until tasks.length()).map(tasks::getLong),taskIds())
                state.put("verifiedFinancialFactsUnchanged",true)
                state.put("actualProjection",JSONArray().apply {ExpensePeriod.entries.forEach {p ->
                    val s=LedgerStatistics(app.store).query(p)
                    put(JSONObject().put("period",p.name).put("start",s.start).put("end",s.end).put("cents",s.totals.expense.toString()))
                }})
                File(app.cacheDir,"widget-host-final-probe.json").writeText(state.toString())
            }
            "cleanup" -> {
                if(file.exists()) {
                    val state=JSONObject(file.readText())
                    if(state.optString("runToken")!=token)return
                    val rows=state.getJSONArray("records")
                    for(i in 0 until rows.length())app.store.all().firstOrNull {it.uid==rows.getJSONObject(i).getString("uid")}?.let {app.store.delete(it.id)}
                    app.changed(true)
                }
            }
        }
    }
}
