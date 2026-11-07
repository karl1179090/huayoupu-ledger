package net.ankio.ledger.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real system Launcher accepts the pin request. No custom AppWidgetHost substitutes for this evidence. */
@RunWith(AndroidJUnit4::class)
class ExpenseWidgetLauncherTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private val windowRoot:AccessibilityNodeInfo? get() {
        // System bars can become the active accessibility window during font/theme changes.
        val applications=automation.windows.filter {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION}
        return (applications.firstOrNull {it.isFocused} ?: applications.firstOrNull {it.isActive}
            ?: applications.firstOrNull())?.root ?: automation.rootInActiveWindow
    }
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
        if(node==null)return emptyList()
        return (if(node.isVisibleToUser)listOf(node) else emptyList())+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    }
    private fun waitFor(timeout:Long=12_000,predicate:()->Boolean){val until=SystemClock.elapsedRealtime()+timeout;while(!predicate()){check(SystemClock.elapsedRealtime()<until){"Launcher condition timed out; tree="+nodes(windowRoot).map {it.text?:it.contentDescription}.joinToString()};SystemClock.sleep(100)}}
    private fun clickPeriod(label:String) = waitFor {
        nodes(windowRoot).firstOrNull {
            it.contentDescription?.toString()?.startsWith("切换本$label")==true || it.text?.toString()==label
        }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
    }
    private fun onResumedActivity(excluding:MainActivity?=null,action:(MainActivity)->Unit) = waitFor {
        var applied=false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull {it!==excluding}?.let {
                    action(it);applied=true
                }
        }
        applied
    }
    private fun shell(command:String){automation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    private fun capture(name:String) {
        SystemClock.sleep(250)
        shell("screencap -p /data/local/tmp/huayoupu-s7-api${android.os.Build.VERSION.SDK_INT}-$name.png")
        val writer=java.io.StringWriter()
        val xml=android.util.Xml.newSerializer().apply {setOutput(writer);startDocument("UTF-8",true);startTag(null,"hierarchy")}
        fun write(node:AccessibilityNodeInfo?) {
            if(node==null)return
            xml.startTag(null,"node")
            val rect=android.graphics.Rect();node.getBoundsInScreen(rect)
            for((key,value) in listOf("text" to node.text,"description" to node.contentDescription,"id" to node.viewIdResourceName,"class" to node.className,"bounds" to rect.toShortString(),"clickable" to node.isClickable.toString(),"visible" to node.isVisibleToUser.toString()))xml.attribute(null,key,value?.toString()?:"")
            for(i in 0 until node.childCount)write(node.getChild(i))
            xml.endTag(null,"node")
        }
        write(windowRoot);xml.endTag(null,"hierarchy");xml.endDocument()
        val encoded=android.util.Base64.encodeToString(writer.toString().toByteArray(),android.util.Base64.NO_WRAP)
        shell("echo $encoded | base64 -d > /data/local/tmp/huayoupu-s7-api${android.os.Build.VERSION.SDK_INT}-$name.xml")
    }
    @Test fun realLauncherPinShowsCurrentPeriodAndNativeAmountsAndDirectActivityRoutes() {
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val manager=AppWidgetManager.getInstance(app)
        assertTrue("Isolated system launcher must support pin widgets",manager.isRequestPinAppWidgetSupported)
        val before=ExpenseWidgetProvider.ids(app).toSet()
        assertTrue("Use a clean isolated launcher (no pre-existing expense widgets)",before.isEmpty())
        val visibleImportUid=java.util.UUID.randomUUID().toString()
        val prefix="SYNTHETIC-LAUNCHER-${java.util.UUID.randomUUID()}-"
        val baseline=net.ankio.ledger.data.LedgerStatistics(app.store).query(net.ankio.ledger.core.ExpensePeriod.YEAR).totals.expense
        val weekBaseline=net.ankio.ledger.data.LedgerStatistics(app.store).query(net.ankio.ledger.core.ExpensePeriod.WEEK).totals.expense
        val monday=java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        fun fixture(uid:String,cents:Long,direction:net.ankio.ledger.core.Direction,day:Int):net.ankio.ledger.data.LedgerRecord {
            val at=monday.plusDays(day.toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            return net.ankio.ledger.data.LedgerRecord(uid=prefix+uid,capture=net.ankio.ledger.core.Capture(net.ankio.ledger.core.Source.MANUAL,"csv","合成组件W05",at),
                bill=net.ankio.ledger.core.ParsedBill(cents,direction,"合成组件W05",if(direction==net.ankio.ledger.core.Direction.TRANSFER)"转账" else "餐饮",at,null,true,"",true,
                    refund=if(uid=="w05-refund")net.ankio.ledger.core.RefundInfo("wechat",10000,3000) else null),state=net.ankio.ledger.core.RecordState.POSTED,
                transfer=if(direction==net.ankio.ledger.core.Direction.TRANSFER)net.ankio.ledger.data.RecordTransferDetails(prefix+uid,feeCents=100,feeMode=net.ankio.ledger.core.FeeMode.EXTRA,fromReference="{\"version\":1,\"name\":\"合成转出\"}",toReference="{\"version\":1,\"name\":\"合成转入\"}") else null)
        }
        app.store.importTransactions(listOf(fixture("w05-refund",7000,net.ankio.ledger.core.Direction.EXPENSE,0),fixture("w05-transfer",10000,net.ankio.ledger.core.Direction.TRANSFER,2),fixture("w05-history",2000,net.ankio.ledger.core.Direction.EXPENSE,4)))
        val db=app.store.writableDatabase
        fun addPrecisionRows() {
        synchronized(app.store) {db.beginTransaction();try {
            db.compileStatement("INSERT INTO records(uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,reliable,time_explicit,state,reason,note) VALUES(?,'MANUAL','synthetic','',?,?,'EXPENSE','合成组件精度','合成组件精度',?,1,1,'POSTED','','')").use {statement->
                statement.bindLong(2,System.currentTimeMillis());statement.bindLong(3,net.ankio.ledger.core.Money.MAX_CENTS)
                repeat(100_000) {index->statement.bindString(1,prefix+index);statement.bindLong(4,java.time.LocalDate.now().withDayOfYear(15).plusMonths((index%12).toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());statement.executeInsert()}
            };db.setTransactionSuccessful()
        } finally {db.endTransaction()}}
        }
        val ordinaryExtra=83_750L
        synchronized(app.store) {db.beginTransaction();try {
            db.compileStatement("INSERT INTO records(uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,reliable,time_explicit,state,reason,note) VALUES(?,'MANUAL','synthetic','',?,?,'EXPENSE','合成普通年','合成普通年',?,1,1,'POSTED','','')").use {statement->
                statement.bindLong(2,System.currentTimeMillis())
                repeat(12) {index->if(index!=10) {statement.bindString(1,prefix+"ordinary-$index");statement.bindLong(3,(index+1)*1250L);statement.bindLong(4,java.time.LocalDate.now().withDayOfYear(15).plusMonths(index.toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());statement.executeInsert()}}
            };db.setTransactionSuccessful()
        } finally {db.endTransaction()}}
        app.changed(true)
        val expected=net.ankio.ledger.core.Money.format(Math.addExact(Math.addExact(Math.addExact(baseline,ordinaryExtra),9100),100_000*net.ankio.ledger.core.Money.MAX_CENTS))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            SystemClock.sleep(600)
            instrumentation.runOnMainSync {assertTrue(manager.requestPinAppWidget(ComponentName(app,ExpenseWidgetProvider::class.java),null,null))}
            waitFor {
                val candidates=nodes(windowRoot)
                val button=candidates.firstOrNull {node->node.isClickable && node.text?.toString()?.let {it.contains("ADD AUTOMATICALLY",true)||it=="Add"||it.equals("Add to home screen",true)||it.contains("自动添加")||it=="添加到主屏幕"||it=="添加"}==true}
                if(button!=null){button.performAction(AccessibilityNodeInfo.ACTION_CLICK);true} else ExpenseWidgetProvider.ids(app).any {it !in before}
            }
            waitFor {ExpenseWidgetProvider.ids(app).any {it !in before}}
            val id=ExpenseWidgetProvider.ids(app).first {it !in before}
            waitFor {ExpenseWidgetUpdater.measurement(id)!=null}
            shell("input keyevent KEYCODE_HOME")
            waitFor {nodes(windowRoot).any {it.viewIdResourceName?.endsWith("/workspace")==true} && nodes(windowRoot).any {it.text?.toString()=="本周花销"}}
            waitFor {nodes(windowRoot).any {it.text?.toString()?.replace(" ","")?.replace(",","")?.replace("\u200b","")=="¥${net.ankio.ledger.core.Money.format(weekBaseline+9100)}"}}
            val launcher=requireNotNull(windowRoot).packageName.toString()
            assertFalse(launcher==app.packageName)
            val version=app.packageManager.getPackageInfo(launcher,0).versionName
            capture("launcher-week")
            clickPeriod("月")
            waitFor {nodes(windowRoot).any {it.text?.toString()=="本月花销"}}
            capture("launcher-month")
            clickPeriod("年")
            waitFor {nodes(windowRoot).any {it.text?.toString()=="本年花销"}}
            assertEquals(net.ankio.ledger.core.ExpensePeriod.YEAR,ExpenseWidgetConfigStore(app).get(id).period)
            val ordinaryTotal=net.ankio.ledger.core.Money.format(baseline+ordinaryExtra+9100)
            waitFor {nodes(windowRoot).any {it.text?.toString()?.replace(" ","")?.replace(",","")?.replace("\u200b","")=="¥$ordinaryTotal"}}
            capture("launcher-year-normal")
            println("S7_NORMAL options=${manager.getAppWidgetOptions(id).keySet().associateWith {manager.getAppWidgetOptions(id).get(it)}}")
            if(InstrumentationRegistry.getArguments().getString("normalOnly")=="true")return
            addPrecisionRows();app.changed(true)
            waitFor {ExpenseWidgetUpdater.measurement(id)?.revision==app.widgetRevision.get()}
            waitFor {nodes(windowRoot).any {it.text?.toString()?.replace(" ","")?.replace(",","")?.replace("\u200b","")=="¥$expected"}}
            waitFor {nodes(windowRoot).any {it.contentDescription?.toString()?.startsWith(java.time.LocalDate.now().withMonth(12).withDayOfMonth(1).toString())==true}}
            capture("launcher-year")
            // Observe actual launcher text after a real native CSV confirmation, in addition to publish metrics.
            val lateAt=System.currentTimeMillis()
            val late=net.ankio.ledger.data.LedgerRecord(uid=visibleImportUid,capture=net.ankio.ledger.core.Capture(net.ankio.ledger.core.Source.MANUAL,"csv","合成桌面可见刷新",lateAt),bill=net.ankio.ledger.core.ParsedBill(1,net.ankio.ledger.core.Direction.EXPENSE,"合成桌面可见刷新","购物",lateAt,null,true,"",true),state=net.ankio.ledger.core.RecordState.POSTED)
            val lateFile=java.io.File(app.cacheDir,"synthetic-widget-visible.csv").apply {outputStream().use {net.ankio.ledger.data.LedgerCsvExport.write(listOf(late),net.ankio.ledger.data.LedgerCsvExport.Format.GENERAL,it)}}
            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitFor {windowRoot?.packageName==app.packageName}
            // Configuration changes may replace the activity while the launcher is foreground.
            var replaced:MainActivity?=null
            onResumedActivity {activity->replaced=activity;activity.recreate()}
            onResumedActivity(excluding=replaced) { activity ->
                MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply {isAccessible=true}.invoke(activity,MainActivity.CSV_IMPORT_REQUEST,android.app.Activity.RESULT_OK,Intent().setData(android.net.Uri.fromFile(lateFile)))
            }
            waitFor {nodes(windowRoot).any {it.text?.toString()?.startsWith("确认导入以上 1 笔")==true}}
            val confirm=nodes(windowRoot).first {it.text?.toString()?.startsWith("确认导入以上 1 笔")==true}
            val beforeImportRevision=app.widgetRevision.get()
            val clicked=SystemClock.elapsedRealtime()
            assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor {app.store.readableDatabase.rawQuery("SELECT id FROM records WHERE uid=?",arrayOf(visibleImportUid)).use {it.moveToFirst()}}
            waitFor {app.widgetRevision.get()>beforeImportRevision && ExpenseWidgetUpdater.measurement(id)?.revision==app.widgetRevision.get()}
            val publishMs=requireNotNull(ExpenseWidgetUpdater.measurement(id)).publishedAt-clicked
            ExpenseWidgetTiming.assertPublication("Widget publication after native import",publishMs)
            shell("input keyevent KEYCODE_HOME")
            val expectedAfter=net.ankio.ledger.core.Money.format(Math.addExact(Math.addExact(Math.addExact(Math.addExact(baseline,ordinaryExtra),9100),100_000*net.ankio.ledger.core.Money.MAX_CENTS),1))
            waitFor {nodes(windowRoot).any {it.text?.toString()?.replace(" ","")?.replace(",","")?.replace("\u200b","")=="¥$expectedAfter"}}
            val visibleMs=SystemClock.elapsedRealtime()-clicked
            // The host launcher transition and accessibility polling are outside the app's publication deadline.
            // Still require the exact updated amount to become visible within waitFor's bounded timeout.
            println("S7_VISIBLE nativeImportConfirmToLauncherTextMs=$visibleMs nativePublishMs=$publishMs")
            // Evidence collection includes sleeps, screenshots and tree export; exclude it from refresh latency.
            capture("launcher-import-visible")
            lateFile.delete()
            shell("settings put system font_scale 1.5");shell("cmd uimode night yes")
            waitFor {app.resources.configuration.fontScale==1.5f && app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES}
            val themeAt=SystemClock.elapsedRealtime()
            ExpenseWidgetUpdater.request(app,intArrayOf(id))
            waitFor {ExpenseWidgetUpdater.measurement(id)?.let {it.dark && it.fontScale==1.5f && it.publishedAt>=themeAt}==true}
            waitFor {nodes(windowRoot).any {it.contentDescription?.toString()?.startsWith(java.time.LocalDate.now().withMonth(12).withDayOfMonth(1).toString())==true}}
            capture("launcher-year-dark-large")
            shell("settings put system font_scale 2.0")
            waitFor {app.resources.configuration.fontScale==2f}
            ExpenseWidgetUpdater.request(app,intArrayOf(id))
            waitFor {ExpenseWidgetUpdater.measurement(id)?.fontScale==2f}
            capture("launcher-year-dark-maxfont")
            clickPeriod("月")
            waitFor {nodes(windowRoot).any {it.text?.toString()=="本月花销"}}
            capture("launcher-month-extreme-maxfont")
            clickPeriod("年")
            waitFor {nodes(windowRoot).any {it.text?.toString()=="本年花销"}}
            println("S7_THEME font=${app.resources.configuration.fontScale} night=${app.resources.configuration.uiMode} options=${manager.getAppWidgetOptions(id).keySet().associateWith {manager.getAppWidgetOptions(id).get(it)}}")
            val hold=InstrumentationRegistry.getArguments().getString("holdWidgetMillis")?.toLongOrNull()?.coerceIn(0,60_000)?:0L
            if(hold>0) {println("S7_RESIZE_READY widgetId=$id");SystemClock.sleep(hold)}
            shell("settings delete system font_scale");shell("cmd uimode night no")
            waitFor {ExpenseWidgetUpdater.measurement(id)?.let {!it.dark && it.fontScale==1f}==true}
            val metric=ExpenseWidgetUpdater.measurement(id)!!
            println("S7_LAUNCHER package=$launcher version=$version options=${manager.getAppWidgetOptions(id).keySet().associateWith {manager.getAppWidgetOptions(id).get(it)}} font=${app.resources.configuration.fontScale} night=${app.resources.configuration.uiMode} metric=$metric")
            waitFor {nodes(windowRoot).any {it.text?.toString()=="本年花销"}}
            var title:AccessibilityNodeInfo?=nodes(windowRoot).first {it.text?.toString()=="本年花销"}
            while(title!=null && !title.isClickable)title=title.parent
            assertTrue(title?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
            waitFor {windowRoot?.packageName==app.packageName}
            waitFor {nodes(windowRoot).any {it.text?.toString()?.contains("本年花销")==true || it.text?.toString()=="本年"}}
            capture("statistics-route")
            shell("input keyevent KEYCODE_HOME")
        } finally {
            shell("settings delete system font_scale");shell("cmd uimode night no")
            synchronized(app.store){app.store.readableDatabase.rawQuery("SELECT id FROM records WHERE uid=?",arrayOf(visibleImportUid)).use {if(it.moveToFirst())app.store.delete(it.getLong(0))};app.store.readableDatabase.rawQuery("SELECT id FROM records WHERE uid LIKE ?",arrayOf(prefix+"w05-%")).use {cursor->val ids=mutableListOf<Long>();while(cursor.moveToNext())ids.add(cursor.getLong(0));ids.forEach(app.store::delete)};app.store.writableDatabase.delete("records","uid LIKE ?",arrayOf(prefix+"%"))};app.changed(true)
        }
    }
}
