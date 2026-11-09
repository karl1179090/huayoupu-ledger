package net.ankio.ledger.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.R
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent S7 adversarial tests. Host observations are not claimed as launcher screenshots. */
@RunWith(AndroidJUnit4::class)
class ExpenseWidgetAcceptanceTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun await(message:String,timeout:Long=8000,predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) {assertTrue(message,SystemClock.elapsedRealtime()<deadline);SystemClock.sleep(20)}
    }
    private data class Bound(val host:AppWidgetHost,val id:Int)
    private fun withWidget(action:(Bound)->Unit) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val host=AppWidgetHost(app,8807);val id=host.allocateAppWidgetId()
        try {
            assertTrue(AppWidgetManager.getInstance(app).bindAppWidgetIdIfAllowed(id,ComponentName(app,ExpenseWidgetProvider::class.java)))
            host.startListening()
            action(Bound(host,id))
        } finally {
            host.stopListening();host.deleteAppWidgetId(id)
            ExpenseWidgetConfigStore(app).remove(id);ExpenseWidgetSnapshotCache.remove(app,id);ExpenseWidgetUpdater.remove(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
    private fun text(bound:Bound,viewId:Int):String {
        var result=""
        instrumentation.runOnMainSync {
            val manager=AppWidgetManager.getInstance(app)
            val view=bound.host.createView(app,bound.id,manager.getAppWidgetInfo(bound.id))
            result=view.findViewById<TextView>(viewId)?.text?.toString().orEmpty()
        }
        return result
    }
    private fun shell(command:String):String=instrumentation.uiAutomation.executeShellCommand(command).use { descriptor->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {it.readText()}
    }
    private fun rendered(bound:Bound,snapshot:LedgerStatistics.Snapshot,now:Instant,widthDp:Int=277,heightDp:Int=334,
        failed:Boolean=false,action:(android.view.ViewGroup)->Unit) {
        AppWidgetManager.getInstance(app).updateAppWidgetOptions(bound.id,Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,widthDp);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,heightDp);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,heightDp)
        })
        instrumentation.runOnMainSync {
            val method=ExpenseWidgetUpdater::class.java.getDeclaredMethod("render",android.content.Context::class.java,
                Int::class.javaPrimitiveType,ExpensePeriod::class.java,LedgerStatistics.Snapshot::class.java,
                Boolean::class.javaPrimitiveType,Instant::class.java).apply {isAccessible=true}
            val value=method.invoke(ExpenseWidgetUpdater,app,bound.id,snapshot.range!!.period,snapshot,failed,now)
            fun field(name:String)=value.javaClass.getDeclaredMethod(name).apply {isAccessible=true}.invoke(value)
            @Suppress("UNCHECKED_CAST") val bitmaps=field("getBitmaps") as List<android.graphics.Bitmap>
            try {
                val views=field("getViews") as android.widget.RemoteViews
                val root=views.apply(app,android.widget.FrameLayout(app)) as android.view.ViewGroup
                val density=app.resources.displayMetrics.density
                val width=(widthDp*density).toInt();val height=(heightDp*density).toInt()
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                root.layout(0,0,width,height)
                // Detached TextViews retain their own observer; complete the normal pre-draw
                // scroll correction before inspecting or drawing centered single-line text.
                fun preDraw(view:android.view.View) {
                    view.viewTreeObserver.dispatchOnPreDraw()
                    if(view is android.view.ViewGroup)for(index in 0 until view.childCount)preDraw(view.getChildAt(index))
                }
                preDraw(root)
                action(root)
            } finally {bitmaps.forEach {it.recycle()}}
        }
    }
    private fun bounds(root:android.view.ViewGroup,view:android.view.View)=android.graphics.Rect().also {
        view.getDrawingRect(it);root.offsetDescendantRectToMyCoords(view,it)
    }
    private fun saveHost(root:android.view.ViewGroup,filename:String) {
        val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
        val file=java.io.File(requireNotNull(app.getExternalFilesDir(null)),filename)
        try {
            root.draw(android.graphics.Canvas(bitmap));file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            shell("cp ${file.absolutePath} /data/local/tmp/$filename")
        } finally {bitmap.recycle();file.delete()}
    }
    private fun displayedCents(text:String):Long {
        val number=text.filter {it in '0'..'9' || it=='.' || it=='-'}
        return java.math.BigDecimal(number).movePointRight(2).longValueExact()
    }

    @Test fun immutableActionsCannotRetargetAnotherInstanceOrWriteFinancialFacts()=withWidget {a->withWidget {b->
        val config=ExpenseWidgetConfigStore(app)
        ExpenseWidgetUpdater.select(app,a.id,ExpensePeriod.YEAR)
        ExpenseWidgetUpdater.select(app,b.id,ExpensePeriod.MONTH)
        val records=app.store.all();val wallets=app.store.walletSnapshot();val accounts=app.store.accounts()
        for(action in listOf(ExpenseWidgetProvider.SWITCH,ExpenseWidgetProvider.REFRESH))for(period in ExpensePeriod.entries) {
            val valid=ExpenseWidgetProvider.actionIntent(app,a.id,action,period)
            assertTrue(ExpenseWidgetProvider.validAction(app,valid,a.id))
            for(invalid in listOf(Intent(valid).putExtra(ExpenseWidgetProvider.PERIOD,"javascript:alert(1)"),
                Intent(valid).setData(Uri.parse("ledger-widget://${b.id}/switch/${period.name}")),
                Intent(valid).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,b.id),
                Intent(valid).setAction("net.ankio.ledger.WRITE"))) {
                val id=invalid.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,0)
                assertFalse(ExpenseWidgetProvider.validAction(app,invalid,id))
                // Invalid onReceive must return before goAsync or configuration mutation.
                ExpenseWidgetProvider().onReceive(app,invalid)
            }
        }
        val intended=ExpenseWidgetProvider.button(app,a.id,ExpenseWidgetProvider.SWITCH,ExpensePeriod.WEEK)
        assertNotEquals(intended,ExpenseWidgetProvider.button(app,b.id,ExpenseWidgetProvider.SWITCH,ExpensePeriod.WEEK))
        assertNotEquals(intended,ExpenseWidgetProvider.button(app,a.id,ExpenseWidgetProvider.REFRESH,ExpensePeriod.WEEK))
        assertNotEquals(ExpenseWidgetProvider.open(app,a.id,ExpensePeriod.WEEK),ExpenseWidgetProvider.open(app,b.id,ExpensePeriod.WEEK))
        if(android.os.Build.VERSION.SDK_INT>=31)assertTrue(intended.isImmutable)
        intended.send(app,0,Intent().setData(Uri.parse("ledger-widget://${b.id}/switch/YEAR"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,b.id).putExtra(ExpenseWidgetProvider.PERIOD,"YEAR"))
        await("immutable intent must retain original instance and period") {config.get(a.id).period==ExpensePeriod.WEEK}
        assertEquals(ExpensePeriod.MONTH,config.get(b.id).period)
        assertEquals(records,app.store.all());assertEquals(wallets,app.store.walletSnapshot());assertEquals(accounts,app.store.accounts())
    }}

    @Test fun failedQueryRetainsDatedNonzeroSnapshotAndCommittedRecords()=withWidget {bound->
        val now=System.currentTimeMillis();val uid=UUID.randomUUID().toString();val invalid=UUID.randomUUID().toString()
        val record=LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv","合成失败保留",now),
            bill=ParsedBill(12345,Direction.EXPENSE,"合成失败保留","购物",now,null,true,"",true),state=RecordState.POSTED)
        try {
            app.store.importTransactions(listOf(record));ExpenseWidgetUpdater.select(app,bound.id,ExpensePeriod.WEEK)
            app.changed(true)
            await("initial nonzero snapshot") {ExpenseWidgetUpdater.measurement(bound.id)?.let {it.revision==app.widgetRevision.get() && it.period==ExpensePeriod.WEEK}==true}
            val beforeTotal=text(bound,R.id.widget_total);val beforeDates=text(bound,R.id.widget_dates)
            assertTrue(beforeTotal.isNotBlank());assertFalse(beforeTotal=="¥0.00")
            // Inject malformed persisted data to force the real query failure path, not a mocked renderer.
            synchronized(app.store) {app.store.writableDatabase.insertOrThrow("records",null,ContentValues().apply {
                put("uid",invalid);put("source","MANUAL");put("origin","synthetic");put("raw_text","");put("received_at",now)
                put("amount",1);put("direction","UNKNOWN");put("merchant","合成非法查询");put("category","购物");put("occurred_at",now)
                put("reliable",1);put("time_explicit",1);put("state","POSTED");put("reason","");put("note","")
            })}
            app.changed(true)
            await("failed refresh must publish explanatory fallback") {ExpenseWidgetUpdater.measurement(bound.id)?.revision==app.widgetRevision.get()}
            assertEquals(beforeTotal,text(bound,R.id.widget_total));assertEquals(beforeDates,text(bound,R.id.widget_dates))
            assertTrue(text(bound,R.id.widget_status).contains("失败"))
            assertTrue(app.store.readableDatabase.rawQuery("SELECT 1 FROM records WHERE uid IN (?,?)",arrayOf(uid,invalid)).use {it.count==2})
            assertEquals(12345L,app.store.readableDatabase.rawQuery("SELECT amount FROM records WHERE uid=?",arrayOf(uid)).use {it.moveToFirst();it.getLong(0)})
        } finally {
            synchronized(app.store) {app.store.writableDatabase.delete("records","uid=?",arrayOf(invalid))}
            app.store.all().firstOrNull {it.uid==uid}?.let {app.store.delete(it.id)};app.changed(true)
        }
    }

    @Test fun blockedOldQueryCannotPublishAcrossThemeFontAndSizeChange()=withWidget {bound->
        val initial=Configuration(app.resources.configuration)
        val originalFontSetting=shell("settings get system font_scale").trim()
        val now=System.currentTimeMillis();val lateUid=UUID.randomUUID().toString()
        val late=LedgerRecord(uid=lateUid,capture=Capture(Source.MANUAL,"csv","合成并发组件",now),
            bill=ParsedBill(1,Direction.EXPENSE,"合成并发组件","购物",now,null,true,"",true),state=RecordState.POSTED)
        val expected=Math.addExact(LedgerStatistics(app.store).query(ExpensePeriod.YEAR).totals.expense,1)
        val acquired=CountDownLatch(1);val release=CountDownLatch(1)
        val workerError=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val blocker=Thread {try {synchronized(app.store){
            acquired.countDown();check(release.await(12,TimeUnit.SECONDS))
            app.store.importTransactions(listOf(late));app.changed(true)
        }} catch(error:Throwable) {workerError.set(error)}}
        blocker.start();assertTrue(acquired.await(3,TimeUnit.SECONDS))
        try {
            ExpenseWidgetUpdater.select(app,bound.id,ExpensePeriod.WEEK)
            ExpenseWidgetUpdater.request(app,intArrayOf(bound.id))
            await("old query is actually blocked") {Thread.getAllStackTraces().keys.any {it.name=="ledger-widget" && it.state==Thread.State.BLOCKED}}
            shell("settings put system font_scale 1.3")
            val dark=initial.uiMode and Configuration.UI_MODE_NIGHT_MASK!=Configuration.UI_MODE_NIGHT_YES
            shell("cmd uimode night ${if(dark)"yes" else "no"}")
            await("application receives current device configuration") {app.resources.configuration.fontScale==1.3f &&
                (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)==dark}
            AppWidgetManager.getInstance(app).updateAppWidgetOptions(bound.id,Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,210);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,210)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,350);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,350)
            })
            ExpenseWidgetUpdater.select(app,bound.id,ExpensePeriod.YEAR);app.changed(true)
            val afterChange=SystemClock.elapsedRealtime()
            release.countDown();blocker.join(3000)
            assertFalse("writer must finish independently",blocker.isAlive);workerError.get()?.let {throw AssertionError(it)}
            val latestRevision=app.widgetRevision.get()
            await("latest configuration is published") {ExpenseWidgetUpdater.measurement(bound.id)?.let {
                it.period==ExpensePeriod.YEAR && it.revision==latestRevision && it.dark==dark && it.fontScale==1.3f && it.publishedAt>=afterChange
            }==true}
            SystemClock.sleep(150)
            val latest=ExpenseWidgetUpdater.measurement(bound.id)!!
            assertEquals(ExpensePeriod.YEAR,latest.period);assertEquals(latestRevision,latest.revision)
            assertEquals(dark,latest.dark);assertEquals(1.3f,latest.fontScale,0.001f)
            assertTrue(latest.bitmapBytes<=ExpenseChartRenderer.MAX_BITMAP_BYTES)
            assertTrue(text(bound,R.id.widget_title).contains("本年"))
            assertEquals(Money.format(expected),text(bound,R.id.widget_total).filter {it in '0'..'9' || it=='.' || it=='-'})
            println("S7_INDEPENDENT_LATEST $latest options=${AppWidgetManager.getInstance(app).getAppWidgetOptions(bound.id)}")
        } finally {
            release.countDown();blocker.join(3000)
            app.store.all().firstOrNull {it.uid==lateUid}?.let {app.store.delete(it.id)};app.changed(true)
            if(originalFontSetting=="null")shell("settings delete system font_scale")
            else shell("settings put system font_scale $originalFontSetting")
            shell("cmd uimode night ${if(initial.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)"yes" else "no"}")
        }
    }

    @Test fun sharedProjectionKeepsOriginalRefundDayAndExactlyOneTransferFee() {
        val name="widget-independent-${UUID.randomUUID()}.db";val store=LedgerStore(app,name)
        val zone=ZoneId.of("Asia/Shanghai");val now=Instant.parse("2026-11-06T04:00:00Z")
        val monday=Instant.parse("2026-11-02T04:00:00Z").toEpochMilli()
        fun row(amount:Long,direction:Direction,offset:Int,refund:RefundInfo?=null,fee:Long?=null):LedgerRecord {
            val uid=UUID.randomUUID().toString();val at=monday+offset*86_400_000L
            return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv","合成统计",now.toEpochMilli()),
                bill=ParsedBill(amount,direction,"合成统计","购物",at,null,true,"",true,refund=refund),state=RecordState.POSTED,
                transfer=fee?.let {RecordTransferDetails(uid,feeCents=it,feeMode=FeeMode.EXTRA,
                    fromReference="{\"version\":1,\"name\":\"合成转出\"}",toReference="{\"version\":1,\"name\":\"合成转入\"}")})
        }
        try {
            store.importTransactions(listOf(row(7000,Direction.EXPENSE,0,RefundInfo("wechat",10000,3000)),
                row(10000,Direction.TRANSFER,2,fee=100),row(2000,Direction.EXPENSE,4),row(777,Direction.INCOME,3),row(9999,Direction.TRANSFER,3,fee=0)))
            val projection=LedgerStatistics(store).query(ExpensePeriod.WEEK,now,zone)
            val minimal=LedgerStatistics(store).query(ExpensePeriod.WEEK,now,zone,includeBreakdown=false)
            assertEquals(9100L,projection.totals.expense);assertEquals(listOf(7000L,0L,100L,0L,2000L,0L,0L),projection.bucketAmounts)
            assertEquals(projection.totals,minimal.totals);assertEquals(projection.bucketAmounts,minimal.bucketAmounts)
            assertEquals(1,projection.categories.count {it.key==LedgerStatistics.TRANSFER_FEE_KEY})
            assertEquals(100L,projection.categories.single {it.key==LedgerStatistics.TRANSFER_FEE_KEY}.amountCents)
            assertEquals("9100",projection.json().getString("totalExpenseCents"))
            assertEquals("2026-11-02",projection.days.single {it.amountCents==7000L}.date)
            val chart=ExpenseChartRenderer.render(minimal,800,150,true)
            try {
                for(value in listOf("70","1","20"))assertTrue(Regex("$value(?:\\.00)?元").containsMatchIn(chart.description))
            }
            finally {chart.bitmap.recycle()}
            assertTrue(store.all().all {it.wallet?.eligibility==WalletEligibility.IMPORT_EXCLUDED && it.walletEffects.isEmpty()})
        } finally {store.close();app.deleteDatabase(name)}
    }

    @Test fun missingInstanceAndCorruptFallbackCannotCreateWorkOrFakeZeroData() {
        val absent=Int.MAX_VALUE
        assertFalse(absent in ExpenseWidgetProvider.ids(app))
        val preferences=app.getSharedPreferences("expense_widget_last_success",android.content.Context.MODE_PRIVATE)
        preferences.edit().putString(absent.toString(),"{\"period\":\"YEAR\",\"total\":\"999\",\"amounts\":[]}").commit()
        try {
            assertNull(ExpenseWidgetSnapshotCache.get(app,absent))
            ExpenseWidgetUpdater.request(app,intArrayOf(absent))
            SystemClock.sleep(100)
            assertNull(ExpenseWidgetUpdater.measurement(absent))
            val generationField=ExpenseWidgetUpdater::class.java.getDeclaredField("generations").apply {isAccessible=true}
            assertFalse((generationField.get(ExpenseWidgetUpdater) as Map<*,*>).containsKey(absent))
        } finally {preferences.edit().remove(absent.toString()).commit()}
    }


    @Test fun monthlyCalendarHasFortyTwoMondayFirstCellsAndEveryRealDateExactlyOnce() {
        data class Fixture(val month:String,val first:Int,val days:Int)
        val zone=ZoneId.of("Asia/Shanghai")
        for(f in listOf(Fixture("2027-02",0,28),Fixture("2028-02",1,29),Fixture("2026-11",6,30),Fixture("2026-05",4,31))) {
            val date=java.time.LocalDate.parse(f.month+"-15")
            val now=date.atTime(12,0).atZone(zone).toInstant()
            val range=ExpensePeriodBuckets.create(ExpensePeriod.MONTH,now,zone)
            val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,zone.id,LedgerStatistics.Totals(),List(range.buckets.size){0L},emptyList(),emptyList())
            val month=ExpenseWidgetCalendar.create(snapshot)
            assertEquals(42,month.cells.size)
            assertEquals(date.withDayOfMonth(1),month.start)
            assertEquals(date.withDayOfMonth(1).plusMonths(1),month.end)
            assertEquals(f.days,month.cells.count {it.date!=null})
            for(index in month.cells.indices) {
                val cell=month.cells[index]
                if(index in f.first until f.first+f.days) {
                    assertEquals(date.withDayOfMonth(index-f.first+1),cell.date)
                    assertEquals(0L,cell.expenseCents)
                } else {assertNull(cell.date);assertNull(cell.expenseCents)}
            }
            assertEquals(f.days,month.cells.mapNotNull {it.date}.distinct().size)
        }
    }

    @Test fun actualDailyExpenseFactsKeepRefundOriginalDateFeeOnceExcludedAndFuturePosted() {
        val name="widget-daily-${UUID.randomUUID()}.db";val store=LedgerStore(app,name)
        val zone=ZoneId.of("Asia/Shanghai");val now=Instant.parse("2026-11-06T04:00:00Z")
        fun row(day:String,amount:Long,direction:Direction=Direction.EXPENSE,fee:Long?=null,refund:RefundInfo?=null,state:RecordState=RecordState.POSTED):LedgerRecord {
            val uid=UUID.randomUUID().toString();val at=java.time.LocalDate.parse(day).atTime(12,0).atZone(zone).toInstant().toEpochMilli()
            return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv","合成日历明细",now.toEpochMilli()),
                bill=ParsedBill(amount,direction,"合成日历明细","购物",at,null,true,"",true,refund=refund),state=state,
                transfer=fee?.let {RecordTransferDetails(uid,feeCents=it,feeMode=FeeMode.EXTRA,
                    fromReference="{\"version\":1,\"name\":\"合成转出\"}",toReference="{\"version\":1,\"name\":\"合成转入\"}")})
        }
        try {
            val rows=store.importTransactions(listOf(row("2026-10-31",7000,refund=RefundInfo("wechat",10000,3000)),row("2026-11-01",2000),
                row("2026-11-03",10000,Direction.TRANSFER,fee=100),row("2026-11-30",1234),row("2026-11-04",5000,Direction.INCOME),
                row("2026-11-06",9999,state=RecordState.REVIEW),row("2026-11-07",10000,Direction.TRANSFER,fee=0),row("2026-11-01",9000,Direction.INCOME),row("2026-11-05",500)))
            assertTrue(store.softDelete(rows.last().id))
            val snapshot=LedgerStatistics(store).query(ExpensePeriod.MONTH,now,zone,includeBreakdown=false,includeDays=true)
            assertTrue(snapshot.categories.isEmpty());assertEquals(3334L,snapshot.totals.expense)
            assertEquals(3334L,snapshot.days.filter {it.direction==Direction.EXPENSE}.fold(0L){n,d->Math.addExact(n,d.amountCents)})
            val month=ExpenseWidgetCalendar.create(snapshot)
            val amounts=month.cells.filter {it.date!=null}.associate {it.date.toString() to it.expenseCents}
            assertEquals(2000L,amounts["2026-11-01"]);assertEquals(100L,amounts["2026-11-03"])
            assertEquals(1234L,amounts["2026-11-30"])
            for(day in listOf("2026-11-04","2026-11-05","2026-11-06","2026-11-07"))assertEquals(0L,amounts[day])
            assertEquals(snapshot.totals.expense,month.cells.mapNotNull {it.expenseCents}.fold(0L,Math::addExact))
            val cacheId=99170002
            try {
                ExpenseWidgetSnapshotCache.put(app,cacheId,snapshot,now)
                val cached=requireNotNull(ExpenseWidgetSnapshotCache.get(app,cacheId))
                assertTrue(cached.days.all {it.direction==Direction.EXPENSE})
                val cachedMonth=ExpenseWidgetCalendar.create(cached)
                assertEquals(2000L,cachedMonth.cells.single {it.date.toString()=="2026-11-01"}.expenseCents)
                assertEquals(0L,cachedMonth.cells.single {it.date.toString()=="2026-11-04"}.expenseCents)
            } finally {ExpenseWidgetSnapshotCache.remove(app,cacheId)}
            val previous=LedgerStatistics(store).query(ExpensePeriod.MONTH,Instant.parse("2026-10-31T04:00:00Z"),zone,includeBreakdown=false,includeDays=true)
            assertEquals(7000L,previous.totals.expense)
            assertEquals(7000L,ExpenseWidgetCalendar.create(previous).cells.single {it.date.toString()=="2026-10-31"}.expenseCents)
            assertTrue(store.all().all {it.wallet?.eligibility==WalletEligibility.IMPORT_EXCLUDED && it.walletEffects.isEmpty()})
        } finally {store.close();app.deleteDatabase(name)}
    }

    @Test fun monthlyCacheRequiresKnownDailyFactsAndCalendarCannotSilentlyLoseOutOfRangeOrDuplicateAmounts() {
        val id=99170001;val now=Instant.parse("2026-11-06T04:00:00Z");val zone=ZoneId.of("Asia/Shanghai")
        val range=ExpensePeriodBuckets.create(ExpensePeriod.MONTH,now,zone)
        val amounts=MutableList(range.buckets.size){0L}.apply {this[1]=100L}
        fun snapshot(days:List<LedgerStatistics.Day>)=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,zone.id,
            LedgerStatistics.Totals(expense=100,expenseCount=2),amounts,emptyList(),days)
        val preferences=app.getSharedPreferences("expense_widget_last_success",android.content.Context.MODE_PRIVATE)
        fun json(version:Int?=null)=org.json.JSONObject().put("period","MONTH").put("now",now.toEpochMilli()).put("zone",zone.id)
            .put("total","100").put("count",2).put("amounts",org.json.JSONArray(amounts.map(Long::toString))).apply {if(version!=null)put("version",version)}
        try {
            preferences.edit().putString(id.toString(),json().toString()).commit();assertNull(ExpenseWidgetSnapshotCache.get(app,id))
            preferences.edit().putString(id.toString(),json(2).toString()).commit();assertNull(ExpenseWidgetSnapshotCache.get(app,id))
            preferences.edit().putString(id.toString(),json(2).put("days",org.json.JSONArray()).toString()).commit();assertNull(ExpenseWidgetSnapshotCache.get(app,id))
            val one=org.json.JSONArray().put(org.json.JSONObject().put("date","2026-11-03").put("cents","99"))
            preferences.edit().putString(id.toString(),json(2).put("days",one).toString()).commit();assertNull(ExpenseWidgetSnapshotCache.get(app,id))
            val shortBuckets=json(2).put("days",org.json.JSONArray().put(org.json.JSONObject().put("date","2026-11-03").put("cents","100"))).put("amounts",org.json.JSONArray().put("100"))
            preferences.edit().putString(id.toString(),shortBuckets.toString()).commit();assertNull(ExpenseWidgetSnapshotCache.get(app,id))
            val zero=json(2).put("total","0").put("count",0).put("amounts",org.json.JSONArray(List(range.buckets.size){"0"})).put("days",org.json.JSONArray())
            preferences.edit().putString(id.toString(),zero.toString()).commit()
            val empty=requireNotNull(ExpenseWidgetSnapshotCache.get(app,id));assertEquals(0L,ExpenseWidgetCalendar.create(empty).cells.mapNotNull {it.expenseCents}.fold(0L,Math::addExact))
            val known=snapshot(listOf(LedgerStatistics.Day(3,Direction.EXPENSE,100,"2026-11-03")))
            ExpenseWidgetSnapshotCache.put(app,id,known,now)
            val read=requireNotNull(ExpenseWidgetSnapshotCache.get(app,id))
            assertEquals(100L,ExpenseWidgetCalendar.create(read).cells.single {it.date.toString()=="2026-11-03"}.expenseCents)
            val huge=9_007_199_254_740_993L
            val hugeBuckets=MutableList(range.buckets.size){0L}.apply {this[1]=huge}
            val precise=known.copy(totals=LedgerStatistics.Totals(expense=huge,expenseCount=100_000),bucketAmounts=hugeBuckets,
                days=listOf(LedgerStatistics.Day(3,Direction.EXPENSE,huge,"2026-11-03")))
            ExpenseWidgetSnapshotCache.put(app,id,precise,now)
            assertEquals(huge,ExpenseWidgetCalendar.create(requireNotNull(ExpenseWidgetSnapshotCache.get(app,id))).cells.single {it.date.toString()=="2026-11-03"}.expenseCents)
            val bad=snapshot(listOf(LedgerStatistics.Day(1,Direction.EXPENSE,100,"2026-12-01")))
            val rejected=runCatching {ExpenseWidgetCalendar.create(bad)}.exceptionOrNull()
            assertTrue("out-of-month expense must not become November 1 or disappear",rejected is IllegalArgumentException || rejected is IllegalStateException)
            val duplicates=snapshot(listOf(LedgerStatistics.Day(3,Direction.EXPENSE,40,"2026-11-03"),LedgerStatistics.Day(3,Direction.EXPENSE,60,"2026-11-03")))
            val result=runCatching {ExpenseWidgetCalendar.create(duplicates)}
            result.getOrNull()?.let {assertEquals(100L,it.cells.single {cell->cell.date.toString()=="2026-11-03"}.expenseCents)}
        } finally {ExpenseWidgetSnapshotCache.remove(app,id)}
    }

    @Test fun previousMonthDailyCacheRetainsItsOwnMonthAndZoneAfterCurrentMonthChanges() {
        val id=99170003;val zone=ZoneId.of("Pacific/Kiritimati");val oldNow=Instant.parse("2026-10-30T12:00:00Z")
        val range=ExpensePeriodBuckets.create(ExpensePeriod.MONTH,oldNow,zone)
        val day=java.time.LocalDate.parse("2026-10-31")
        val amounts=MutableList(range.buckets.size){0L}.apply {this[range.bucketIndex(day.atTime(12,0).atZone(zone).toInstant().toEpochMilli())]=7000L}
        val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,zone.id,LedgerStatistics.Totals(expense=7000,expenseCount=1),amounts,emptyList(),listOf(LedgerStatistics.Day(31,Direction.EXPENSE,7000,day.toString())))
        try {
            ExpenseWidgetSnapshotCache.put(app,id,snapshot,oldNow)
            val read=requireNotNull(ExpenseWidgetSnapshotCache.get(app,id))
            assertEquals("Pacific/Kiritimati",read.zoneId)
            val month=ExpenseWidgetCalendar.create(read)
            assertEquals(java.time.LocalDate.parse("2026-10-01"),month.start)
            assertEquals(java.time.LocalDate.parse("2026-11-01"),month.end)
            assertEquals(7000L,month.cells.single {it.date==day}.expenseCents)
            assertFalse(month.cells.mapNotNull {it.date}.any {it.monthValue==11})
            withWidget {bound->rendered(bound,read,Instant.parse("2026-11-06T04:00:00Z"),failed=true) {root->
                val dates=root.findViewById<TextView>(R.id.widget_dates).contentDescription.toString()
                assertTrue(dates.contains("2026-10-01"));assertTrue(dates.contains("2026-10-31"))
                val status=root.findViewById<TextView>(R.id.widget_status)
                assertEquals(android.view.View.VISIBLE,status.visibility);assertTrue(status.text.toString().contains("保留"))
                for(index in 0 until 42) {
                    val view=root.findViewById<android.view.View>(app.resources.getIdentifier("widget_day_cell_$index","id",app.packageName))
                    if(view.visibility==android.view.View.VISIBLE)assertTrue(view.contentDescription.toString().startsWith("2026-10-"))
                }
                val lastCell=root.findViewById<android.view.View>(app.resources.getIdentifier("widget_day_cell_33","id",app.packageName))
                assertTrue(lastCell.contentDescription.toString().contains("2026-10-31"))
                assertTrue(Regex("70(?:\\.00)?元").containsMatchIn(lastCell.contentDescription.toString()))
            }}
        } finally {ExpenseWidgetSnapshotCache.remove(app,id)}
    }

    @Test fun threeModesUseTheSameActualBodyBoundsAndCalendarTextsNeverOverlapAtNormalAndLargeFont()=withWidget {bound->
        val zone=ZoneId.of("Asia/Shanghai");val now=Instant.parse("2026-11-06T04:00:00Z")
        val original=shell("settings get system font_scale").trim()
        val info=AppWidgetManager.getInstance(app).getAppWidgetInfo(bound.id)
        val density=app.resources.displayMetrics.density
        val widthDp=maxOf(277,kotlin.math.ceil(info.minResizeWidth/density.toDouble()).toInt())
        val heightDp=maxOf(334,kotlin.math.ceil(info.minResizeHeight/density.toDouble()).toInt())
        try {
            for(font in listOf(1f,1.5f)) {
                shell("settings put system font_scale $font")
                await("layout font $font") {app.resources.configuration.fontScale==font}
                var expectedBody:android.graphics.Rect?=null
                for(period in ExpensePeriod.entries) {
                    val range=ExpensePeriodBuckets.create(period,now,zone)
                    val amount=12345L
                    val at=java.time.LocalDate.parse("2026-11-06").atTime(12,0).atZone(zone).toInstant().toEpochMilli()
                    val futureAt=java.time.LocalDate.parse("2026-11-30").atTime(12,0).atZone(zone).toInstant().toEpochMilli()
                    val amounts=MutableList(range.buckets.size){0L}.apply {
                        this[range.bucketIndex(at)]=amount
                        range.bucketIndex(futureAt).takeIf {it>=0}?.let {this[it]=Math.addExact(this[it],60000L)}
                    }
                    val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,zone.id,
                        LedgerStatistics.Totals(expense=amounts.fold(0L,Math::addExact),expenseCount=if(period==ExpensePeriod.WEEK)1 else 2),amounts,emptyList(),
                        if(period==ExpensePeriod.MONTH)listOf(LedgerStatistics.Day(6,Direction.EXPENSE,amount,"2026-11-06"),LedgerStatistics.Day(30,Direction.EXPENSE,60000,"2026-11-30")) else emptyList())
                    rendered(bound,snapshot,now,widthDp,heightDp) {root->
                        val body=root.findViewById<android.view.ViewGroup>(R.id.widget_chart_area)
                        val bodyRect=bounds(root,body)
                        println("S7_INDEPENDENT_BOUNDS period=$period font=$font root=${root.width}x${root.height} body=$bodyRect")
                        saveHost(root,"s7-independent-host-${period.name}-$font.png")
                        if(expectedBody==null)expectedBody=android.graphics.Rect(bodyRect) else assertEquals("mode switch body at font=$font",expectedBody,bodyRect)
                        assertEquals(android.view.View.GONE,root.findViewById<android.view.View>(R.id.widget_status).visibility)
                        val periodControls=listOf("widget_week","widget_month","widget_year").map {name->
                            val candidates=listOf(name,"${name}_selected").map {root.findViewById<android.view.View>(app.resources.getIdentifier(it,"id",app.packageName))}
                            val visible=candidates.filter {it.visibility==android.view.View.VISIBLE}
                            assertEquals("exactly one native period control is visible",1,visible.size)
                            assertTrue(visible.single() is android.widget.Button)
                            visible.single().id
                        }
                        for(id in listOf(R.id.widget_main_header,R.id.widget_chart_area)+periodControls) {
                            val view=root.findViewById<android.view.View>(id);val rect=bounds(root,view)
                            assertTrue("interactive $id outside card at $font",rect.left>=0 && rect.top>=0 && rect.right<=root.width && rect.bottom<=root.height)
                            if(view.isClickable)assertTrue("interactive $id <48dp at $font",view.width+1>=48*density && view.height+1>=48*density)
                        }
                        if(period==ExpensePeriod.MONTH) {
                            assertFalse(body.contentDescription.toString().contains("柱"))
                            val calendar=ExpenseWidgetCalendar.create(snapshot)
                            for(index in 0 until 42) {
                                fun view(name:String)=root.findViewById<android.view.View>(app.resources.getIdentifier("${name}_$index","id",app.packageName))
                                val cell=view("widget_day_cell");val day=calendar.cells[index].date
                                if(day==null) {assertEquals(android.view.View.INVISIBLE,cell.visibility);continue}
                                assertFalse(cell.isClickable)
                                val dates=listOf(view("widget_day_date"),view("widget_day_today")).filter {it.visibility==android.view.View.VISIBLE}
                                assertEquals("one date label for $day",1,dates.size)
                                val date=dates.single() as TextView;val money=view("widget_day_amount") as TextView
                                assertEquals(day.dayOfMonth.toString(),date.text.toString())
                                val dateRect=bounds(root,date);val moneyRect=bounds(root,money);val cellRect=bounds(root,cell)
                                assertTrue("date and amount overlap for $day at font=$font: $dateRect / $moneyRect",dateRect.bottom<=moneyRect.top)
                                assertTrue("day outside body $day",bodyRect.contains(cellRect))
                                val layout=money.layout
                                assertTrue("day amount clipped $day at font=$font",layout.getLineBottom(layout.lineCount-1)<=money.height-money.paddingTop-money.paddingBottom)
                                val amountExpected=calendar.cells[index].expenseCents!!
                                val label=money.text.toString()
                                val known=snapshot.days.any {it.direction==Direction.EXPENSE && it.date==day.toString()}
                                if(day>now.atZone(zone).toLocalDate() && !known)assertTrue("future day without an expense fact",label.trim() in listOf("","—"))
                                if(label.trim() in listOf("","—","–","-"))assertEquals("only zero contribution may use an absence mark",0L,amountExpected)
                                else assertEquals(amountExpected,displayedCents(label))
                                assertTrue(cell.contentDescription.toString().contains(day.toString()))
                            }
                        }
                        val segments=root.findViewById<android.view.View>(R.id.widget_segments)
                        assertTrue("bottom period controls",bounds(root,segments).top>=bodyRect.bottom)
                    }
                }
            }
        } finally {if(original=="null")shell("settings delete system font_scale") else shell("settings put system font_scale $original")}
    }

    @Test fun monthlyAmountsAreCloserToTheirOwnDateThanTheFollowingWeek()=withWidget {bound->
        data class Case(val name:String,val day:String,val width:Int,val height:Int,val font:Float,
            val todayCents:Long=12345L,val screenshot:Boolean=false)
        val cases=listOf(
            Case("four-weeks","2021-02-10",350,334,1f),
            Case("five-weeks-tall","2026-06-10",350,440,1f),
            Case("compact-boundary","2026-11-10",350,314,1f),
            Case("regular-boundary","2026-11-10",350,315,1f),
            Case("compact-319-boundary","2026-11-10",350,319,1f),
            Case("regular-320-boundary","2026-11-10",350,320,1f),
            Case("six-weeks-tall","2026-11-10",350,440,1.3f,screenshot=true),
            Case("today-zero","2026-11-10",280,280,1.3f,todayCents=0L),
            Case("six-weeks-minimum","2026-11-10",280,280,1.5f,screenshot=true),
            Case("long-amount","2026-11-10",350,334,1.5f,todayCents=1234567L,screenshot=true))
        val requested=InstrumentationRegistry.getArguments().getString("widgetSpacingCases")
            ?.split(',')?.filter {it.isNotBlank()}?.toSet().orEmpty()
        require(requested.all {name->cases.any {it.name==name}}) {"Unknown widget spacing case"}
        val selectedCases=cases.filter {requested.isEmpty() || it.name in requested}
        val zone=ZoneId.of("Asia/Shanghai")
        val original=shell("settings get system font_scale").trim()
        val density=app.resources.displayMetrics.density
        val report=org.json.JSONObject().put("api",android.os.Build.VERSION.SDK_INT)
            .put("densityDpi",app.resources.displayMetrics.densityDpi)
            .put("requestedCases",org.json.JSONArray(selectedCases.map {it.name}))
            .put("evidenceKind","Android production RemoteViews.apply; not a Launcher screenshot")
        val existing=org.json.JSONArray()
        val manager=AppWidgetManager.getInstance(app)
        ExpenseWidgetProvider.ids(app).filter {it!=bound.id}.forEach {id->
            existing.put(org.json.JSONObject().put("id",id).put("provider",manager.getAppWidgetInfo(id)?.provider.toString())
                .put("options",manager.getAppWidgetOptions(id).apply {keySet()}.toString()))
        }
        report.put("existingWidgets",existing)
        val observations=org.json.JSONArray();report.put("cases",observations)
        fun rect(value:android.graphics.Rect)=org.json.JSONArray(listOf(value.left,value.top,value.right,value.bottom).map {it/density})
        fun ink(root:android.view.ViewGroup,text:TextView):android.graphics.Rect {
            val view=bounds(root,text);val layout=requireNotNull(text.layout)
            val result=android.graphics.Rect()
            for(line in 0 until layout.lineCount) {
                val start=layout.getLineStart(line);val end=layout.getLineEnd(line)
                if(start==end)continue
                val paint=android.text.TextPaint(text.paint)
                (text.text as? android.text.Spanned)?.getSpans(start,end,android.text.style.MetricAffectingSpan::class.java)
                    ?.forEach {it.updateMeasureState(paint)}
                val glyphs=android.graphics.Rect();paint.getTextBounds(text.text.toString(),start,end,glyphs)
                val left=view.left+text.totalPaddingLeft-text.scrollX
                val baseline=view.top+text.totalPaddingTop-text.scrollY+layout.getLineBaseline(line)
                result.union(android.graphics.Rect(left+kotlin.math.floor(layout.getLineLeft(line).toDouble()).toInt(),
                    baseline+glyphs.top,left+kotlin.math.ceil(layout.getLineRight(line).toDouble()).toInt(),baseline+glyphs.bottom))
                assertEquals("amount must not be ellipsized",0,layout.getEllipsisCount(line))
                assertTrue("text exceeds its available line width: ${text.text}",
                    layout.getLineRight(line)-layout.getLineLeft(line)<=text.width-text.totalPaddingLeft-text.totalPaddingRight+1)
            }
            assertTrue("text ink outside TextView: ${text.text}: $result / $view",result.isEmpty || view.contains(result))
            return result
        }
        try {
            for(case in selectedCases) {
                shell("settings put system font_scale ${case.font}")
                await("spacing font ${case.font}") {app.resources.configuration.fontScale==case.font}
                val today=java.time.LocalDate.parse(case.day)
                val now=today.atTime(12,0).atZone(zone).toInstant()
                val range=ExpensePeriodBuckets.create(ExpensePeriod.MONTH,now,zone)
                val facts=(1..today.lengthOfMonth()).filter {it%5!=0 || it==today.dayOfMonth}.map {day->
                    val date=today.withDayOfMonth(day)
                    LedgerStatistics.Day(day,Direction.EXPENSE,if(date==today)case.todayCents else day*137L,date.toString())
                }
                val amounts=MutableList(range.buckets.size){0L}
                facts.forEach {fact->
                    val at=java.time.LocalDate.parse(fact.date).atTime(12,0).atZone(zone).toInstant().toEpochMilli()
                    val bucket=range.bucketIndex(at);amounts[bucket]=Math.addExact(amounts[bucket],fact.amountCents)
                }
                val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,zone.id,
                    LedgerStatistics.Totals(expense=facts.sumOf {it.amountCents},expenseCount=facts.count {it.amountCents>0}.toLong()),
                    amounts,emptyList(),facts)
                val calendar=ExpenseWidgetCalendar.create(snapshot)
                val observation=org.json.JSONObject().put("name",case.name).put("month",today.toString().take(7))
                    .put("optionsDp",org.json.JSONArray(listOf(case.width,case.height))).put("fontScale",case.font)
                val cells=org.json.JSONArray();observation.put("cells",cells);observations.put(observation)
                rendered(bound,snapshot,now,case.width,case.height) {root->
                    val body=bounds(root,root.findViewById(R.id.widget_chart_area))
                    observation.put("rootDp",org.json.JSONArray(listOf(root.width/density,root.height/density))).put("bodyDp",rect(body))
                    if(case.screenshot)saveHost(root,"widget-spacing-${case.name}-${app.resources.displayMetrics.densityDpi}.png")
                    try {
                        fun view(name:String,index:Int)=root.findViewById<android.view.View>(app.resources.getIdentifier("${name}_$index","id",app.packageName))
                        fun date(index:Int)=listOf(view("widget_day_date",index),view("widget_day_today",index))
                            .single {it.visibility==android.view.View.VISIBLE} as TextView
                        for((index,cell) in calendar.cells.withIndex()) {
                            val holder=view("widget_day_cell",index)
                            if(cell.date==null) {assertEquals(android.view.View.INVISIBLE,holder.visibility);continue}
                            val day=requireNotNull(cell.date);val isToday=day==today
                            val label=date(index);val money=view("widget_day_amount",index) as TextView
                            val cellBounds=bounds(root,holder)
                            val dateBounds=ink(root,label).apply {if(isToday)union(bounds(root,label))}
                            val amountBounds=ink(root,money).apply {if(isToday)union(bounds(root,money))}
                            val row=org.json.JSONObject().put("date",day.toString()).put("today",isToday)
                                .put("cellDp",rect(cellBounds)).put("dateDp",rect(dateBounds)).put("amountDp",rect(amountBounds))
                                .put("text",money.text.toString()).put("cents",cell.expenseCents.toString())
                            cells.put(row)
                            assertEquals(day.dayOfMonth.toString(),label.text.toString())
                            assertTrue("day outside calendar body: $day",body.contains(cellBounds))
                            assertTrue("date outside its day: $day",cellBounds.contains(dateBounds))
                            assertTrue("date description changed: $day",holder.contentDescription.toString().contains(day.toString()))
                            if(money.text.isEmpty()) {assertEquals(0L,cell.expenseCents);continue}
                            assertEquals(cell.expenseCents,displayedCents(money.text.toString()))
                            assertTrue("amount outside its day: $day",cellBounds.contains(amountBounds))
                            val ownGap=amountBounds.top-dateBounds.bottom
                            row.put("ownGapDp",ownGap/density)
                            assertTrue("date/amount overlap: ${case.name} $day $dateBounds / $amountBounds",ownGap>=0)
                            val next=index+7
                            if(next<calendar.cells.size && calendar.cells[next].date!=null) {
                                val nextLabel=date(next)
                                val nextDate=ink(root,nextLabel).apply {if(calendar.cells[next].date==today)union(bounds(root,nextLabel))}
                                val nextGap=nextDate.top-amountBounds.bottom
                                row.put("nextDateDp",rect(nextDate)).put("nextGapDp",nextGap/density)
                                assertTrue("amount must group with its own date: ${case.name} $day own=${ownGap/density}dp next=${nextGap/density}dp",
                                    ownGap<nextGap)
                            }
                        }
                        observation.put("passed",true)
                    } catch(failure:Throwable) {
                        observation.put("passed",false).put("failure",failure.toString())
                        saveHost(root,"widget-spacing-failed-${case.name}-${app.resources.displayMetrics.densityDpi}.png")
                        throw failure
                    }
                }
            }
        } finally {
            val file=java.io.File(requireNotNull(app.getExternalFilesDir(null)),"widget-spacing-geometry-${app.resources.displayMetrics.densityDpi}.json")
            file.writeText(report.toString(2))
            shell("cp ${file.absolutePath} /data/local/tmp/${file.name}");file.delete()
            if(original=="null")shell("settings delete system font_scale") else shell("settings put system font_scale $original")
        }
    }

    @Test fun headerRefreshHitAreaDoesNotCoverAmountInk()=withWidget {bound->
        val now=Instant.parse("2026-11-06T04:00:00Z");val range=ExpensePeriodBuckets.create(ExpensePeriod.WEEK,now,ZoneId.of("Asia/Shanghai"))
        val amounts=List(7){if(it==4)12345L else 0L}
        val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,range.zoneId,
            LedgerStatistics.Totals(expense=12345,expenseCount=1),amounts,emptyList(),emptyList())
        rendered(bound,snapshot,now,widthDp=350,heightDp=334) {root->
            saveHost(root,"s7-independent-host-header-hits.png")
            val refresh=bounds(root,root.findViewById(R.id.widget_refresh))
            val total=root.findViewById<TextView>(R.id.widget_total);val totalRect=bounds(root,total)
            val layout=total.layout
            for(line in 0 until layout.lineCount) {
                val ink=android.graphics.Rect(totalRect.left+total.totalPaddingLeft+layout.getLineLeft(line).toInt(),
                    totalRect.top+total.totalPaddingTop+layout.getLineTop(line),
                    totalRect.left+total.totalPaddingLeft+kotlin.math.ceil(layout.getLineRight(line).toDouble()).toInt(),
                    totalRect.top+total.totalPaddingTop+layout.getLineBottom(line))
                println("S7_INDEPENDENT_HEADER_HITS refresh=$refresh totalView=$totalRect amountLine=$ink")
                assertFalse("refresh intercepts amount text despite nominal 48dp targets: $refresh / $ink",android.graphics.Rect.intersects(refresh,ink))
            }
        }
    }

    @Test fun declaredMinimumHeightCanActuallyContainSixCalendarRows()=withWidget {bound->
        val manager=AppWidgetManager.getInstance(app);val info=manager.getAppWidgetInfo(bound.id)
        val density=app.resources.displayMetrics.density
        val minWidth=kotlin.math.ceil(info.minResizeWidth/density.toDouble()).toInt()
        val minHeight=kotlin.math.ceil(info.minResizeHeight/density.toDouble()).toInt()
        val now=Instant.parse("2026-11-06T04:00:00Z");val range=ExpensePeriodBuckets.create(ExpensePeriod.MONTH,now,ZoneId.of("Asia/Shanghai"))
        val amounts=MutableList(range.buckets.size){0L}.apply {this[1]=12345L}
        val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,range.zoneId,
            LedgerStatistics.Totals(expense=12345,expenseCount=1),amounts,emptyList(),listOf(LedgerStatistics.Day(6,Direction.EXPENSE,12345,"2026-11-06")))
        val calendar=ExpenseWidgetCalendar.create(snapshot)
        for(height in listOf(217,minHeight).filter {it>=minHeight}.distinct()) {
            rendered(bound,snapshot,now,widthDp=maxOf(350,minWidth),heightDp=height) {root->
                saveHost(root,"s7-independent-host-min-calendar-$height.png")
                val body=root.findViewById<android.view.ViewGroup>(R.id.widget_chart_area);val bodyRect=bounds(root,body)
                println("S7_INDEPENDENT_MIN_HEIGHT metadata=${minWidth}x$minHeight actualHost=${root.width}x${root.height} body=$bodyRect")
                for(index in 0 until 42) {
                    if(calendar.cells[index].date==null)continue
                    fun view(name:String)=root.findViewById<android.view.View>(app.resources.getIdentifier("${name}_$index","id",app.packageName))
                    val cell=view("widget_day_cell");val money=view("widget_day_amount") as TextView
                    val date=listOf(view("widget_day_date"),view("widget_day_today")).single {it.visibility==android.view.View.VISIBLE}
                    val cellRect=bounds(root,cell);val dateRect=bounds(root,date);val moneyRect=bounds(root,money)
                    assertTrue("six-row calendar outside declared legal height $height",bodyRect.contains(cellRect))
                    assertTrue("calendar date/amount overlap at legal height $height: $dateRect / $moneyRect",dateRect.bottom<=moneyRect.top)
                    assertTrue("calendar amount clipped at legal height $height",money.layout.getLineBottom(money.layout.lineCount-1)<=money.height-money.paddingTop-money.paddingBottom)
                }
                assertTrue("all six calendar rows must have real height",body.height>0)
            }
        }
    }
}
