package net.ankio.ledger.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExpenseWidgetTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun <T> withInstance(action:(Int)->T):T {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val host=AppWidgetHost(app,7901);val id=host.allocateAppWidgetId()
        try {
            assertTrue(AppWidgetManager.getInstance(app).bindAppWidgetIdIfAllowed(id,ComponentName(app,ExpenseWidgetProvider::class.java)))
            return action(id)
        } finally {host.deleteAppWidgetId(id);ExpenseWidgetConfigStore(app).remove(id);ExpenseWidgetUpdater.remove(id);instrumentation.uiAutomation.dropShellPermissionIdentity()}
    }
    private fun await(timeout:Long=8000,predicate:()->Boolean){val until=SystemClock.elapsedRealtime()+timeout;while(!predicate()){check(SystemClock.elapsedRealtime()<until){"Widget update timed out"};SystemClock.sleep(30)}}
    @Test fun configurationDefaultsRestoresAndDeletesArePerInstance() {
        val config=ExpenseWidgetConfigStore(app);val a=991001;val b=991002;val c=991003
        try {
            config.remove(a);config.remove(b);config.remove(c)
            assertEquals(ExpensePeriod.WEEK,config.get(a).period)
            config.select(a,ExpensePeriod.YEAR);config.select(b,ExpensePeriod.MONTH)
            assertEquals(ExpensePeriod.YEAR,ExpenseWidgetConfigStore(app).get(a).period)
            assertEquals(ExpensePeriod.MONTH,config.get(b).period)
            config.restore(intArrayOf(a),intArrayOf(c))
            assertEquals(ExpensePeriod.WEEK,config.get(a).period);assertEquals(ExpensePeriod.YEAR,config.get(c).period)
            assertEquals(2,config.get(c).generation.toInt());config.remove(c);assertEquals(ExpensePeriod.WEEK,config.get(c).period)
        } finally {listOf(a,b,c).forEach(config::remove)}
    }
    @Test fun actionsRequireOwnedIdExactIdentityAndKnownPeriod()=withInstance {id->
        val valid=ExpenseWidgetProvider.actionIntent(app,id,ExpenseWidgetProvider.SWITCH,ExpensePeriod.YEAR)
        assertTrue(ExpenseWidgetProvider.validAction(app,valid,id))
        assertFalse(ExpenseWidgetProvider.validAction(app,android.content.Intent(valid).putExtra(ExpenseWidgetProvider.PERIOD,"ALL"),id))
        assertFalse(ExpenseWidgetProvider.validAction(app,android.content.Intent(valid).setData(android.net.Uri.parse("ledger-widget://$id/refresh/YEAR")),id))
        assertFalse(ExpenseWidgetProvider.validAction(app,valid,Int.MAX_VALUE))
        val week=ExpenseWidgetProvider.button(app,id,ExpenseWidgetProvider.SWITCH,ExpensePeriod.WEEK)
        val year=ExpenseWidgetProvider.button(app,id,ExpenseWidgetProvider.SWITCH,ExpensePeriod.YEAR)
        assertNotEquals(week,year);assertNotEquals(ExpenseWidgetProvider.open(app,id,ExpensePeriod.WEEK),ExpenseWidgetProvider.open(app,id,ExpensePeriod.YEAR))
    }
    @Test fun rendererKeepsAllTwelveExactAmountsWithinControlledBitmapBudget() {
        val name="widget-render-${UUID.randomUUID()}.db";val store=LedgerStore(app,name)
        try {
            val range=ExpensePeriodBuckets.create(ExpensePeriod.YEAR,Instant.parse("2026-11-06T04:00:00Z"),ZoneId.of("Asia/Shanghai"))
            val amounts=List(12){if(it==10)9_999_999_999_900_000L else 0L}
            val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,range.zoneId,LedgerStatistics.Totals(expense=amounts[10]),amounts,emptyList(),emptyList())
            for(dark in listOf(false,true)) {
                val chart=ExpenseChartRenderer.render(snapshot,10_000,10_000,dark)
                assertTrue(chart.bitmap.allocationByteCount<=ExpenseChartRenderer.MAX_BITMAP_BYTES)
                assertTrue(chart.bitmap.hasAlpha());assertTrue(chart.description.contains("99999999999000.00"));assertTrue(chart.description.contains("12月"))
                chart.bitmap.recycle()
            }
            ExpenseWidgetSnapshotCache.put(app,9988,snapshot,Instant.parse("2026-11-06T04:00:00Z"))
            assertEquals(snapshot,ExpenseWidgetSnapshotCache.get(app,9988));ExpenseWidgetSnapshotCache.remove(app,9988)
        } finally {store.close();app.deleteDatabase(name)}
    }
    @Test fun latestSelectionAndLedgerRevisionWinAndTaskProgressDoesNotRedraw()=withInstance {id->
        ExpenseWidgetUpdater.request(app,intArrayOf(id));await {ExpenseWidgetUpdater.measurement(id)!=null}
        val first=ExpenseWidgetUpdater.measurement(id)!!
        app.changed();SystemClock.sleep(100);assertEquals(first,ExpenseWidgetUpdater.measurement(id))
        ExpensePeriod.entries.forEach {period->ExpenseWidgetUpdater.select(app,id,period);ExpenseWidgetUpdater.request(app,intArrayOf(id))}
        app.changed(true)
        await {ExpenseWidgetUpdater.measurement(id)?.let {it.period==ExpensePeriod.YEAR && it.revision==app.widgetRevision.get()}==true}
        val latest=ExpenseWidgetUpdater.measurement(id)!!
        assertTrue(latest.bitmapBytes<=ExpenseChartRenderer.MAX_BITMAP_BYTES)
        assertTrue(latest.publishedAt>=first.publishedAt)
        println("S7_REFRESH latest=$latest")
    }
    @Test fun nativeProjectionFiftyThousandRowsIsExactAndBounded() {
        val name="widget-50k-${UUID.randomUUID()}.db";val store=LedgerStore(app,name)
        try {
            val now=Instant.now();val db=store.writableDatabase
            db.beginTransaction()
            try {db.compileStatement("INSERT INTO records(uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,reliable,time_explicit,state,reason,note) VALUES(?,'MANUAL','synthetic','',?,?,'EXPENSE','合成组件','合成组件',?,1,1,'POSTED','','')").use {statement->
                statement.bindLong(2,now.toEpochMilli());statement.bindLong(3,Money.MAX_CENTS);statement.bindLong(4,now.toEpochMilli())
                repeat(50_000){statement.bindString(1,"SYNTHETIC-WIDGET-$it");statement.executeInsert()}
            };db.setTransactionSuccessful()} finally {db.endTransaction()}
            val started=System.nanoTime();val snapshot=LedgerStatistics(store).query(ExpensePeriod.YEAR,now,ZoneId.systemDefault(),includeBreakdown=false)
            val query=System.nanoTime()-started
            assertEquals(50_000*Money.MAX_CENTS,snapshot.totals.expense);assertEquals(12,snapshot.bucketAmounts.size)
            assertTrue(snapshot.categories.isEmpty());assertTrue(snapshot.days.isEmpty())
            val chart=ExpenseChartRenderer.render(snapshot,1000,200,false)
            println("S7_50K queryNs=$query drawNs=${chart.renderNanos} bitmapBytes=${chart.bitmap.allocationByteCount}")
            assertTrue(query<5_000_000_000L);chart.bitmap.recycle()
        } finally {store.close();app.deleteDatabase(name)}
    }
    @Test fun realFileImportAndCompleteRestoreActivelyPublishWithinTwoSeconds()=withInstance {id->
        val suffix=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        val record=LedgerRecord(uid=UUID.randomUUID().toString(),capture=Capture(Source.MANUAL,"csv","合成组件导入",now),bill=ParsedBill(12345,Direction.EXPENSE,"合成组件导入","购物",now,null,true,"",true),state=RecordState.POSTED)
        val file=java.io.File(app.cacheDir,"synthetic-widget-$suffix.csv")
        file.outputStream().use {LedgerCsvExport.write(listOf(record),LedgerCsvExport.Format.GENERAL,it)}
        val sourceName="widget-restore-$suffix.db";val source=LedgerStore(app,sourceName)
        val restored=record.copy(uid=UUID.randomUUID().toString(),bill=record.bill.copy(amountCents=6789,merchant="合成组件恢复"))
        source.importTransactions(listOf(restored))
        val backup=java.io.File(app.cacheDir,"synthetic-widget-$suffix.json").apply {writeText(LedgerBackup.encode(source,net.ankio.ledger.capture.ChannelSettings(app)))}
        fun deliver(activity:net.ankio.ledger.ui.MainActivity,code:Int,input:java.io.File) {
            net.ankio.ledger.ui.MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,android.content.Intent::class.java).apply {isAccessible=true}
                .invoke(activity,code,android.app.Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.fromFile(input)))
        }
        fun click(text:String):Long {
            var node:android.view.accessibility.AccessibilityNodeInfo?=null
            await {node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.let {list->list.firstOrNull {it.text?.toString()==text}?:list.firstOrNull()};node!=null}
            while(node!=null && !node!!.isClickable)node=node!!.parent
            val clicked=SystemClock.elapsedRealtime()
            assertTrue(node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)
            return clicked
        }
        try {
            androidx.test.core.app.ActivityScenario.launch(net.ankio.ledger.ui.MainActivity::class.java).use {scenario->
                ExpenseWidgetUpdater.request(app,intArrayOf(id));await {ExpenseWidgetUpdater.measurement(id)!=null}
                scenario.onActivity {deliver(it,net.ankio.ledger.ui.MainActivity.CSV_IMPORT_REQUEST,file)}
                val before=app.widgetRevision.get();val importClicked=click("确认导入以上 1 笔")
                await {app.store.all().any {it.uid==record.uid} && app.widgetRevision.get()>before}
                
                await {ExpenseWidgetUpdater.measurement(id)?.revision==app.widgetRevision.get()}
                val importLatency=ExpenseWidgetUpdater.measurement(id)!!.publishedAt-importClicked
                assertTrue("File import refresh $importLatency ms",importLatency<2000)
                scenario.onActivity {deliver(it,net.ankio.ledger.ui.MainActivity.RESTORE_REQUEST,backup)}
                val restoreBefore=app.widgetRevision.get();val restoreClicked=click("恢复")
                await {app.store.all().any {it.uid==restored.uid} && app.widgetRevision.get()>restoreBefore}
                
                await {ExpenseWidgetUpdater.measurement(id)?.revision==app.widgetRevision.get()}
                val restoreLatency=ExpenseWidgetUpdater.measurement(id)!!.publishedAt-restoreClicked
                assertTrue("Restore refresh $restoreLatency ms",restoreLatency<2000)
                println("S7_W12 importMs=$importLatency restoreMs=$restoreLatency")
            }
        } finally {source.close();app.deleteDatabase(sourceName);file.delete();backup.delete();app.store.all().filter {it.uid in setOf(record.uid,restored.uid)}.forEach {app.store.delete(it.id)};app.changed(true)}
    }

    @Test fun deliberatelyBlockedOldQueryCannotPublishOverNewPeriod()=withInstance {id->
        ExpenseWidgetUpdater.select(app,id,ExpensePeriod.WEEK)
        val acquired=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val blocker=Thread {synchronized(app.store){acquired.countDown();release.await()}}.apply {start()}
        assertTrue(acquired.await(3,java.util.concurrent.TimeUnit.SECONDS))
        try {
            ExpenseWidgetUpdater.request(app,intArrayOf(id))
            await {Thread.getAllStackTraces().keys.any {it.name=="ledger-widget" && it.state==Thread.State.BLOCKED}}
            ExpenseWidgetUpdater.select(app,id,ExpensePeriod.YEAR);ExpenseWidgetUpdater.request(app,intArrayOf(id))
        } finally {release.countDown();blocker.join(3000)}
        await {ExpenseWidgetUpdater.measurement(id)?.period==ExpensePeriod.YEAR}
        assertEquals(ExpensePeriod.YEAR,ExpenseWidgetConfigStore(app).get(id).period)
    }
    @Test fun multipleSamePeriodInstancesReuseOneSnapshotWithoutMixingConfiguration()=withInstance {a->withInstance {b->withInstance {c->
        await {listOf(a,b,c).all {ExpenseWidgetUpdater.measurement(it)!=null}}
        SystemClock.sleep(150)
        listOf(a,b,c).forEach {ExpenseWidgetUpdater.select(app,it,ExpensePeriod.YEAR)}
        ExpenseWidgetUpdater.request(app,intArrayOf(a,b,c))
        await {listOf(a,b,c).all {ExpenseWidgetUpdater.measurement(it)?.period==ExpensePeriod.YEAR} && listOf(a,b,c).map {ExpenseWidgetUpdater.measurement(it)!!.queryNanos}.distinct().size==1}
        assertEquals(1,listOf(a,b,c).map {ExpenseWidgetUpdater.measurement(it)!!.queryNanos}.distinct().size)
        ExpenseWidgetUpdater.select(app,b,ExpensePeriod.MONTH);ExpenseWidgetUpdater.request(app,intArrayOf(b))
        await {ExpenseWidgetUpdater.measurement(b)?.period==ExpensePeriod.MONTH}
        assertEquals(ExpensePeriod.YEAR,ExpenseWidgetUpdater.measurement(a)!!.period)
        assertEquals(ExpensePeriod.YEAR,ExpenseWidgetUpdater.measurement(c)!!.period)
    }}}

}
