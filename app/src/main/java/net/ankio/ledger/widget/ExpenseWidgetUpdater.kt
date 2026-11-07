package net.ankio.ledger.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import net.ankio.ledger.R
import net.ankio.ledger.core.ExpensePeriod
import net.ankio.ledger.core.Money
import net.ankio.ledger.data.LedgerStatistics
import net.ankio.ledger.ledger
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded drain coalesces changes; only the newest configuration/data generation can publish. */
object ExpenseWidgetUpdater {
    private val lock=Any()
    private var executor:java.util.concurrent.ScheduledExecutorService?=null
    private var draining=false
    private var receiverContext:Context?=null
    private val dateReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:android.content.Intent){
        if(intent.action==android.content.Intent.ACTION_DATE_CHANGED)request(context,pending=goAsync())
    }}
    private val queued=linkedSetOf<Int>()
    private val generations=mutableMapOf<Int,Long>()
    private val lastGood=mutableMapOf<Int,LedgerStatistics.Snapshot>()
    private val tickets=mutableListOf<Ticket>()
    private val main=Handler(Looper.getMainLooper())
    private val measurements=mutableMapOf<Int,Measurement>()
    data class Measurement(val queryNanos:Long,val drawNanos:Long,val bitmapBytes:Int,val publishedAt:Long,val revision:Long,val period:ExpensePeriod,val dark:Boolean,val fontScale:Float)
    private class Ticket(val result:BroadcastReceiver.PendingResult,val ids:MutableSet<Int>) {
        private val finished=AtomicBoolean()
        fun finish(){if(finished.compareAndSet(false,true))result.finish()}
    }
    fun measurement(id:Int):Measurement?=synchronized(lock){measurements[id]}
    fun select(context:Context,id:Int,period:ExpensePeriod) = synchronized(lock) { ExpenseWidgetConfigStore(context).select(id,period) }
    fun step(context:Context,id:Int,delta:Int) = synchronized(lock) { ExpenseWidgetConfigStore(context).step(id,delta) }
    fun request(context:Context,ids:IntArray=ExpenseWidgetProvider.ids(context),pending:BroadcastReceiver.PendingResult?=null) {
        val app=context.applicationContext
        synchronized(lock) {
            val installed=ExpenseWidgetProvider.ids(app).toSet()
            val valid=ids.filter {it>0 && it in installed}.toSet()
            if(valid.isEmpty()){pending?.finish();return}
            if(receiverContext==null) {
                val filter=android.content.IntentFilter(android.content.Intent.ACTION_DATE_CHANGED)
                if(android.os.Build.VERSION.SDK_INT>=33)app.registerReceiver(dateReceiver,filter,Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(dateReceiver,filter)
                receiverContext=app
            }
            valid.forEach { generations[it]=(generations[it]?:0)+1;queued.add(it) }
            pending?.let {
                val ticket=Ticket(it,valid.toMutableSet());tickets.add(ticket)
                // Broadcast deadlines must not depend on a slow/locked database or renderer.
                main.postDelayed({synchronized(lock){ticket.finish();tickets.remove(ticket)}},8_000)
                if(tickets.size>32)tickets.removeAt(0).finish()
            }
            if(!draining) {
                draining=true
                val worker=executor?.takeUnless {it.isShutdown} ?: Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable,"ledger-widget").apply {isDaemon=true} }.also {executor=it}
                worker.schedule({drain(app)},40,java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }
    private data class Work(val id:Int,val generation:Long,val config:ExpenseWidgetConfigStore.Selection,val deviceConfig:Configuration)
    private fun drain(context:Context) {
        try {
            while(true) {
                val batch=synchronized(lock) {
                    if(queued.isEmpty()){return}
                    queued.map {Work(it,generations[it]?:0,ExpenseWidgetConfigStore(context).get(it),Configuration(context.resources.configuration))}.also {queued.clear()}
                }
                val revision=context.ledger.widgetRevision.get()
                val now=Instant.now();val zone=ZoneId.systemDefault()
                val snapshots=mutableMapOf<Pair<ExpensePeriod,Int>,Result<Pair<LedgerStatistics.Snapshot,Long>>>()
                for(work in batch) {
                    val result=snapshots.getOrPut(work.config.period to work.config.offset) {runCatching {
                        val started=System.nanoTime()
                        val snapshot=LedgerStatistics(context.ledger.store).query(work.config.period,now,zone,includeBreakdown=false,includeDays=work.config.period==ExpensePeriod.MONTH,offset=work.config.offset)
                        // Retain only the small widget projection; category/day growth is irrelevant here.
                        snapshot.copy(categories=emptyList(),days=if(work.config.period==ExpensePeriod.MONTH)snapshot.days else emptyList()) to (System.nanoTime()-started)
                    }}
                    try {
                        val snapshot=result.getOrNull()?.first
                        val previous=synchronized(lock){lastGood[work.id]}?:ExpenseWidgetSnapshotCache.get(context,work.id)
                        val display=snapshot?:previous
                        val rendered=render(context,work.id,work.config.period,display,result.isFailure,now)
                        try {synchronized(lock) {
                            if(isCurrent(context,work,revision)) {
                                AppWidgetManager.getInstance(context).updateAppWidget(work.id,rendered.views)
                                if(snapshot!=null && work.config.offset==0){lastGood[work.id]=snapshot;ExpenseWidgetSnapshotCache.put(context,work.id,snapshot,now)}
                                measurements[work.id]=Measurement(result.getOrNull()?.second?:0,rendered.drawNanos,rendered.bytes,
                                    SystemClock.elapsedRealtime(),revision,work.config.period,rendered.dark,rendered.fontScale)
                                finishTickets(work.id)
                            }
                        }} finally {rendered.bitmaps.forEach {it.recycle()}}
                    } catch (_:Exception) {
                        synchronized(lock){finishTickets(work.id)}
                    }
                }
            }
        } finally {
            synchronized(lock) {
                // Any unexpected failure releases broadcast handles; later system/ledger events retry.
                tickets.forEach {it.finish()};tickets.clear()
                draining=false
                if(queued.isNotEmpty()) {
                    val retry=queued.toIntArray();queued.clear();request(context,retry)
                } else if(ExpenseWidgetProvider.ids(context).isEmpty())stopWhenIdle()
            }
        }
    }
    private fun isCurrent(context:Context,work:Work,revision:Long):Boolean =
        generations[work.id]==work.generation && ExpenseWidgetConfigStore(context).get(work.id)==work.config &&
            context.ledger.widgetRevision.get()==revision && work.deviceConfig==context.resources.configuration && work.id in ExpenseWidgetProvider.ids(context)
    private fun finishTickets(id:Int) {
        val iterator=tickets.iterator()
        while(iterator.hasNext()) {val ticket=iterator.next();ticket.ids.remove(id);if(ticket.ids.isEmpty()){ticket.finish();iterator.remove()}}
    }
    fun remove(id:Int)=synchronized(lock) {queued.remove(id);generations.remove(id);lastGood.remove(id);measurements.remove(id);finishTickets(id)}
    fun stopWhenIdle()=synchronized(lock) {if(!draining && queued.isEmpty()){executor?.shutdown();executor=null;receiverContext?.let {runCatching {it.unregisterReceiver(dateReceiver)}};receiverContext=null}}
    private data class Rendered(val views:RemoteViews,val drawNanos:Long,val bytes:Int,val bitmaps:List<android.graphics.Bitmap>,val dark:Boolean,val fontScale:Float)
    private val bucketIds=intArrayOf(R.id.widget_bucket_0,R.id.widget_bucket_1,R.id.widget_bucket_2,R.id.widget_bucket_3,
        R.id.widget_bucket_4,R.id.widget_bucket_5,R.id.widget_bucket_6,R.id.widget_bucket_7,R.id.widget_bucket_8,R.id.widget_bucket_9,R.id.widget_bucket_10,R.id.widget_bucket_11)
    private val axisIds=intArrayOf(R.id.widget_axis_0,R.id.widget_axis_1,R.id.widget_axis_2,R.id.widget_axis_3,R.id.widget_axis_4,R.id.widget_axis_5,R.id.widget_axis_6,R.id.widget_axis_7,R.id.widget_axis_8,R.id.widget_axis_9,R.id.widget_axis_10,R.id.widget_axis_11)
    private val weekRowIds=intArrayOf(R.id.widget_week_row_0,R.id.widget_week_row_1,R.id.widget_week_row_2,R.id.widget_week_row_3,R.id.widget_week_row_4,R.id.widget_week_row_5,R.id.widget_week_row_6)
    private val weekDayIds=intArrayOf(R.id.widget_week_day_0,R.id.widget_week_day_1,R.id.widget_week_day_2,R.id.widget_week_day_3,R.id.widget_week_day_4,R.id.widget_week_day_5,R.id.widget_week_day_6)
    private val weekTodayTagIds=intArrayOf(R.id.widget_week_today_tag_0,R.id.widget_week_today_tag_1,R.id.widget_week_today_tag_2,R.id.widget_week_today_tag_3,R.id.widget_week_today_tag_4,R.id.widget_week_today_tag_5,R.id.widget_week_today_tag_6)
    private val yearCardIds=intArrayOf(R.id.widget_year_card_0,R.id.widget_year_card_1,R.id.widget_year_card_2,R.id.widget_year_card_3,R.id.widget_year_card_4,R.id.widget_year_card_5,R.id.widget_year_card_6,R.id.widget_year_card_7,R.id.widget_year_card_8,R.id.widget_year_card_9,R.id.widget_year_card_10,R.id.widget_year_card_11)
    private val yearTagIds=intArrayOf(R.id.widget_year_tag_0,R.id.widget_year_tag_1,R.id.widget_year_tag_2,R.id.widget_year_tag_3,R.id.widget_year_tag_4,R.id.widget_year_tag_5,R.id.widget_year_tag_6,R.id.widget_year_tag_7,R.id.widget_year_tag_8,R.id.widget_year_tag_9,R.id.widget_year_tag_10,R.id.widget_year_tag_11)
    private val calendarCellIds=intArrayOf(R.id.widget_day_cell_0,R.id.widget_day_cell_1,R.id.widget_day_cell_2,R.id.widget_day_cell_3,R.id.widget_day_cell_4,R.id.widget_day_cell_5,R.id.widget_day_cell_6,R.id.widget_day_cell_7,R.id.widget_day_cell_8,R.id.widget_day_cell_9,R.id.widget_day_cell_10,R.id.widget_day_cell_11,R.id.widget_day_cell_12,R.id.widget_day_cell_13,R.id.widget_day_cell_14,R.id.widget_day_cell_15,R.id.widget_day_cell_16,R.id.widget_day_cell_17,R.id.widget_day_cell_18,R.id.widget_day_cell_19,R.id.widget_day_cell_20,R.id.widget_day_cell_21,R.id.widget_day_cell_22,R.id.widget_day_cell_23,R.id.widget_day_cell_24,R.id.widget_day_cell_25,R.id.widget_day_cell_26,R.id.widget_day_cell_27,R.id.widget_day_cell_28,R.id.widget_day_cell_29,R.id.widget_day_cell_30,R.id.widget_day_cell_31,R.id.widget_day_cell_32,R.id.widget_day_cell_33,R.id.widget_day_cell_34,R.id.widget_day_cell_35,R.id.widget_day_cell_36,R.id.widget_day_cell_37,R.id.widget_day_cell_38,R.id.widget_day_cell_39,R.id.widget_day_cell_40,R.id.widget_day_cell_41)
    private val calendarDateIds=intArrayOf(R.id.widget_day_date_0,R.id.widget_day_date_1,R.id.widget_day_date_2,R.id.widget_day_date_3,R.id.widget_day_date_4,R.id.widget_day_date_5,R.id.widget_day_date_6,R.id.widget_day_date_7,R.id.widget_day_date_8,R.id.widget_day_date_9,R.id.widget_day_date_10,R.id.widget_day_date_11,R.id.widget_day_date_12,R.id.widget_day_date_13,R.id.widget_day_date_14,R.id.widget_day_date_15,R.id.widget_day_date_16,R.id.widget_day_date_17,R.id.widget_day_date_18,R.id.widget_day_date_19,R.id.widget_day_date_20,R.id.widget_day_date_21,R.id.widget_day_date_22,R.id.widget_day_date_23,R.id.widget_day_date_24,R.id.widget_day_date_25,R.id.widget_day_date_26,R.id.widget_day_date_27,R.id.widget_day_date_28,R.id.widget_day_date_29,R.id.widget_day_date_30,R.id.widget_day_date_31,R.id.widget_day_date_32,R.id.widget_day_date_33,R.id.widget_day_date_34,R.id.widget_day_date_35,R.id.widget_day_date_36,R.id.widget_day_date_37,R.id.widget_day_date_38,R.id.widget_day_date_39,R.id.widget_day_date_40,R.id.widget_day_cell_41.let { R.id.widget_day_date_41 })
    private val calendarAmountIds=intArrayOf(R.id.widget_day_amount_0,R.id.widget_day_amount_1,R.id.widget_day_amount_2,R.id.widget_day_amount_3,R.id.widget_day_amount_4,R.id.widget_day_amount_5,R.id.widget_day_amount_6,R.id.widget_day_amount_7,R.id.widget_day_amount_8,R.id.widget_day_amount_9,R.id.widget_day_amount_10,R.id.widget_day_amount_11,R.id.widget_day_amount_12,R.id.widget_day_amount_13,R.id.widget_day_amount_14,R.id.widget_day_amount_15,R.id.widget_day_amount_16,R.id.widget_day_amount_17,R.id.widget_day_amount_18,R.id.widget_day_amount_19,R.id.widget_day_amount_20,R.id.widget_day_amount_21,R.id.widget_day_amount_22,R.id.widget_day_amount_23,R.id.widget_day_amount_24,R.id.widget_day_amount_25,R.id.widget_day_amount_26,R.id.widget_day_amount_27,R.id.widget_day_amount_28,R.id.widget_day_amount_29,R.id.widget_day_amount_30,R.id.widget_day_amount_31,R.id.widget_day_amount_32,R.id.widget_day_amount_33,R.id.widget_day_amount_34,R.id.widget_day_amount_35,R.id.widget_day_amount_36,R.id.widget_day_amount_37,R.id.widget_day_amount_38,R.id.widget_day_amount_39,R.id.widget_day_amount_40,R.id.widget_day_amount_41)
    private val calendarTodayIds=intArrayOf(R.id.widget_day_today_0,R.id.widget_day_today_1,R.id.widget_day_today_2,R.id.widget_day_today_3,R.id.widget_day_today_4,R.id.widget_day_today_5,R.id.widget_day_today_6,R.id.widget_day_today_7,R.id.widget_day_today_8,R.id.widget_day_today_9,R.id.widget_day_today_10,R.id.widget_day_today_11,R.id.widget_day_today_12,R.id.widget_day_today_13,R.id.widget_day_today_14,R.id.widget_day_today_15,R.id.widget_day_today_16,R.id.widget_day_today_17,R.id.widget_day_today_18,R.id.widget_day_today_19,R.id.widget_day_today_20,R.id.widget_day_today_21,R.id.widget_day_today_22,R.id.widget_day_today_23,R.id.widget_day_today_24,R.id.widget_day_today_25,R.id.widget_day_today_26,R.id.widget_day_today_27,R.id.widget_day_today_28,R.id.widget_day_today_29,R.id.widget_day_today_30,R.id.widget_day_today_31,R.id.widget_day_today_32,R.id.widget_day_today_33,R.id.widget_day_today_34,R.id.widget_day_today_35,R.id.widget_day_today_36,R.id.widget_day_today_37,R.id.widget_day_today_38,R.id.widget_day_today_39,R.id.widget_day_today_40,R.id.widget_day_today_41)

    private fun render(context:Context,id:Int,selected:ExpensePeriod,snapshot:LedgerStatistics.Snapshot?,failed:Boolean,now:Instant):Rendered {
        val config=Configuration(context.resources.configuration)
        val dark=config.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val options=AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val bitmaps=mutableListOf<android.graphics.Bitmap>();val drawNanos=0L
        val range=snapshot?.range;val period=range?.period?:selected
        val title=when(period){ExpensePeriod.WEEK->"本周花销";ExpensePeriod.MONTH->"本月花销";ExpensePeriod.YEAR->"本年花销"}
        val zone=ZoneId.of(snapshot?.zoneId?:ZoneId.systemDefault().id)
        val start=snapshot?.let {Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()}
        val last=snapshot?.let {Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate().minusDays(1)}
        val shortDate=if(start==null)"等待数据" else when(period) {
            ExpensePeriod.YEAR->"${start.year}年"
            ExpensePeriod.MONTH->"${start.year}年${start.monthValue}月"
            ExpensePeriod.WEEK->if(start.monthValue==last!!.monthValue)"${start.monthValue}月${start.dayOfMonth}日—${last.dayOfMonth}日"
                else "${start.monthValue}月${start.dayOfMonth}日—${last.monthValue}月${last.dayOfMonth}日"
        }
        val subtitle=if(start!=null && period==ExpensePeriod.WEEK)"${start.year}年${start.monthValue}月" else ""
        val fullDate=if(start==null)"等待账本数据" else "$start 至 $last"
        val amount=snapshot?.let {"¥${ExpenseWidgetFormatter.money(it.totals.expense)}"}?:"—"
        val open=ExpenseWidgetProvider.open(context,id,period)
        val portraitWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,280).coerceIn(180,1000)
        val portraitHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,260).coerceIn(160,1000)
        val landscapeWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,portraitWidth).coerceIn(180,1000)
        val landscapeHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,portraitHeight).coerceIn(160,1000)
        val twoSizes=portraitWidth!=landscapeWidth || portraitHeight!=landscapeHeight

        val colorText=context.getColor(R.color.widget_text)
        val colorMuted=context.getColor(R.color.widget_muted)
        val colorAccent=context.getColor(R.color.widget_accent)
        val colorOnAccent=context.getColor(R.color.widget_on_accent)
        val colorTotal=context.getColor(if(period==ExpensePeriod.MONTH)R.color.widget_total else R.color.widget_text)
        val colorWeekdayPast=context.getColor(R.color.widget_weekday_past)

        fun create(width:Int,height:Int):RemoteViews {
            val views=RemoteViews(context.packageName,R.layout.expense_widget)
            views.setTextViewText(R.id.widget_title,title)
            views.setTextViewText(R.id.widget_dates,shortDate)
            views.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_DIP,11f)
            views.setTextViewTextSize(R.id.widget_dates,TypedValue.COMPLEX_UNIT_DIP,17f)
            views.setContentDescription(R.id.widget_dates,"统计日期 $fullDate")
            views.setViewVisibility(R.id.widget_subtitle,if(subtitle.isNotEmpty())View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_subtitle,subtitle)
            views.setTextViewTextSize(R.id.widget_subtitle,TypedValue.COMPLEX_UNIT_DIP,11f)
            views.setTextViewText(R.id.widget_total,amount)
            views.setTextColor(R.id.widget_total,colorTotal)
            val heroWidth=(width-32-130).coerceAtLeast(60).toFloat()
            var hero=if(amount.length>16)15f else 22f
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            while(hero>14f) {paint.textSize=hero;if(amount.split('\u200b').all {paint.measureText(it)<=heroWidth})break;hero-=0.5f}
            views.setTextViewTextSize(R.id.widget_total,TypedValue.COMPLEX_UNIT_DIP,hero)
            views.setContentDescription(R.id.widget_total,"$title，${snapshot?.let {Money.format(it.totals.expense)+"元"}?:"尚未更新"}")
            listOf(R.id.widget_main_header,R.id.widget_header,R.id.widget_chart_area).forEach {views.setOnClickPendingIntent(it,open)}
            views.setOnClickPendingIntent(R.id.widget_prev,ExpenseWidgetProvider.button(context,id,ExpenseWidgetProvider.PREV,selected))
            views.setOnClickPendingIntent(R.id.widget_next,ExpenseWidgetProvider.button(context,id,ExpenseWidgetProvider.NEXT,selected))
            views.setOnClickPendingIntent(R.id.widget_refresh,ExpenseWidgetProvider.button(context,id,ExpenseWidgetProvider.REFRESH,selected))
            views.setContentDescription(R.id.widget_chart_area,when(period){
                ExpensePeriod.MONTH->"查看${title}统计，$fullDate。每日花费按日期顺序朗读，周一开始。"
                ExpensePeriod.WEEK->"查看${title}统计，$fullDate。每日花费按周一至周日顺序排列。"
                ExpensePeriod.YEAR->"查看${title}统计，$fullDate。各月花费按1月至12月顺序排列。"
            })
            listOf(Triple(R.id.widget_week,R.id.widget_week_selected,ExpensePeriod.WEEK),Triple(R.id.widget_month,R.id.widget_month_selected,ExpensePeriod.MONTH),Triple(R.id.widget_year,R.id.widget_year_selected,ExpensePeriod.YEAR)).forEach {(normal,chosen,p)->
                val intent=ExpenseWidgetProvider.button(context,id,ExpenseWidgetProvider.SWITCH,p)
                views.setViewVisibility(normal,if(p==selected)View.GONE else View.VISIBLE)
                views.setViewVisibility(chosen,if(p==selected)View.VISIBLE else View.GONE)
                for(view in listOf(normal,chosen)) {
                    views.setOnClickPendingIntent(view,intent)
                    views.setContentDescription(view,"切换${when(p){ExpensePeriod.WEEK->"本周";ExpensePeriod.MONTH->"本月";ExpensePeriod.YEAR->"本年"}}${if(p==selected)"，已选择" else ""}")
                }
            }
            views.setViewVisibility(R.id.widget_status,if(failed || snapshot==null)View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_status,if(failed)"更新失败 · ${if(snapshot==null)"点按刷新重试" else "保留上述日期数据"}" else "正在更新…")
            views.removeAllViews(R.id.widget_chart_area)
            if(snapshot!=null && range!=null && period==ExpensePeriod.MONTH) {
                val calendar=runCatching {ExpenseWidgetCalendar.create(snapshot)}.getOrNull()
                if(calendar==null) {
                    views.setViewVisibility(R.id.widget_status,View.VISIBLE)
                    views.setTextViewText(R.id.widget_status,"暂无每日明细，请刷新 · $fullDate")
                } else {
                    val body=RemoteViews(context.packageName,R.layout.widget_calendar)
                    val today=now.atZone(zone).toLocalDate()
                    val compactHeight=height<=282
                    val dateFont=if(compactHeight)9.5f else 13f
                    val amountFont=if(compactHeight)6.5f else 8.5f
                    val cellBottomPad=if(compactHeight)0 else (2.5f*context.resources.displayMetrics.density).toInt()
                    calendar.cells.forEachIndexed {index,cell->
                        val date=cell.date
                        body.setViewVisibility(calendarCellIds[index],if(date==null)View.INVISIBLE else View.VISIBLE)
                        body.setViewPadding(calendarCellIds[index],0,0,0,cellBottomPad)
                        val dayText=date?.dayOfMonth?.toString()?:""
                        val isToday=date==today
                        val isFuture=date!=null && date>today
                        body.setTextViewText(calendarDateIds[index],dayText)
                        body.setTextViewTextSize(calendarDateIds[index],TypedValue.COMPLEX_UNIT_DIP,dateFont)
                        body.setTextColor(calendarDateIds[index],if(isFuture)colorMuted else colorText)
                        body.setTextViewText(calendarTodayIds[index],dayText)
                        body.setTextViewTextSize(calendarTodayIds[index],TypedValue.COMPLEX_UNIT_DIP,dateFont)
                        body.setViewVisibility(calendarDateIds[index],if(isToday)View.INVISIBLE else View.VISIBLE)
                        body.setViewVisibility(calendarTodayIds[index],if(isToday)View.VISIBLE else View.INVISIBLE)
                        body.setTextViewTextSize(calendarAmountIds[index],TypedValue.COMPLEX_UNIT_DIP,amountFont)
                        val cents=cell.expenseCents?:0L
                        val rawAmount=if(date==null)"" else if(isToday)"¥${ExpenseWidgetFormatter.money(cents)}" else if(cents>0L)"¥${ExpenseWidgetFormatter.compact(cents)}" else ""
                        val label=android.text.SpannableString(rawAmount)
                        if(isToday && label.isNotEmpty())label.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,label.length,0)
                        body.setTextViewText(calendarAmountIds[index],label)
                        body.setTextColor(calendarAmountIds[index],if(isToday)colorAccent else colorMuted)
                        body.setInt(calendarAmountIds[index],"setBackgroundResource",if(isToday)R.drawable.widget_today_amount_pill else android.R.color.transparent)
                        body.setContentDescription(calendarCellIds[index],if(date==null)"" else "$date${if(isToday)"，今天" else ""}，${Money.format(cents)}元")
                        body.setInt(calendarCellIds[index],"setBackgroundResource",android.R.color.transparent)
                    }
                    views.addView(R.id.widget_chart_area,body)
                }
            } else if(snapshot!=null && range!=null && period==ExpensePeriod.WEEK) {
                val body=RemoteViews(context.packageName,R.layout.widget_week_list)
                for(index in 0 until minOf(7,range.buckets.size)) {
                    val bucket=range.buckets[index]
                    val cents=snapshot.bucketAmounts[index]
                    val isToday=bucket.current
                    val isFuture=bucket.future
                    body.setInt(weekRowIds[index],"setBackgroundResource",if(isToday)R.drawable.widget_week_today_row else android.R.color.transparent)
                    body.setTextViewText(weekDayIds[index],bucket.startDate.dayOfMonth.toString())
                    body.setTextViewTextSize(weekDayIds[index],TypedValue.COMPLEX_UNIT_DIP,14f)
                    body.setTextColor(weekDayIds[index],if(isToday)colorOnAccent else if(isFuture)colorMuted else colorText)
                    body.setInt(weekDayIds[index],"setBackgroundResource",if(isToday)R.drawable.widget_today_badge else android.R.color.transparent)

                    val weekdayLabel=android.text.SpannableString(bucket.label)
                    if(isToday)weekdayLabel.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,weekdayLabel.length,0)
                    body.setTextViewText(axisIds[index],weekdayLabel)
                    body.setTextViewTextSize(axisIds[index],TypedValue.COMPLEX_UNIT_DIP,12.5f)
                    body.setTextColor(axisIds[index],if(isToday)colorText else if(isFuture)colorMuted else colorWeekdayPast)

                    body.setViewVisibility(weekTodayTagIds[index],if(isToday)View.VISIBLE else View.GONE)
                    body.setTextViewTextSize(weekTodayTagIds[index],TypedValue.COMPLEX_UNIT_DIP,11f)

                    val amountString=if(isFuture && cents==0L)"" else "¥${ExpenseWidgetFormatter.money(cents)}"
                    val amountSpan=android.text.SpannableString(amountString)
                    if(isToday && amountSpan.isNotEmpty())amountSpan.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,amountSpan.length,0)
                    body.setTextViewText(bucketIds[index],amountSpan)
                    body.setTextViewTextSize(bucketIds[index],TypedValue.COMPLEX_UNIT_DIP,13f)
                    body.setTextColor(bucketIds[index],if(isToday)colorAccent else colorMuted)
                    body.setContentDescription(bucketIds[index],"${bucket.startDate} 至 ${bucket.endDate.minusDays(1)}${if(bucket.current)"，当前" else if(bucket.future)"，后续日期" else ""}，${Money.format(cents)}元")
                }
                views.addView(R.id.widget_chart_area,body)
            } else if(snapshot!=null && range!=null && period==ExpensePeriod.YEAR) {
                val body=RemoteViews(context.packageName,R.layout.widget_year_grid)
                for(index in 0 until minOf(12,range.buckets.size)) {
                    val bucket=range.buckets[index]
                    val cents=snapshot.bucketAmounts[index]
                    val isCurrent=bucket.current
                    val isFuture=bucket.future
                    body.setInt(yearCardIds[index],"setBackgroundResource",if(isCurrent)R.drawable.widget_year_card_current else R.drawable.widget_year_card_normal)

                    val monthTitle=android.text.SpannableString("${index+1}月")
                    monthTitle.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,monthTitle.length,0)
                    body.setTextViewText(axisIds[index],monthTitle)
                    body.setTextViewTextSize(axisIds[index],TypedValue.COMPLEX_UNIT_DIP,12.5f)
                    body.setTextColor(axisIds[index],if(isCurrent)colorAccent else if(isFuture)colorMuted else colorText)

                    body.setViewVisibility(yearTagIds[index],if(isCurrent)View.VISIBLE else View.GONE)
                    body.setTextViewTextSize(yearTagIds[index],TypedValue.COMPLEX_UNIT_DIP,9.5f)

                    val amountString=if(isFuture && cents==0L)"" else "¥${ExpenseWidgetFormatter.money(cents)}"
                    val amountSpan=android.text.SpannableString(amountString)
                    if(isCurrent && amountSpan.isNotEmpty())amountSpan.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,amountSpan.length,0)
                    body.setTextViewText(bucketIds[index],amountSpan)
                    body.setTextViewTextSize(bucketIds[index],TypedValue.COMPLEX_UNIT_DIP,11.5f)
                    body.setTextColor(bucketIds[index],if(isCurrent)colorAccent else colorText)
                    body.setContentDescription(bucketIds[index],"${bucket.startDate} 至 ${bucket.endDate.minusDays(1)}${if(bucket.current)"，当前" else if(bucket.future)"，后续日期" else ""}，${Money.format(cents)}元")
                }
                views.addView(R.id.widget_chart_area,body)
            }
            return views
        }
        try {
            val portrait=create(portraitWidth,portraitHeight)
            val remote=if(!twoSizes)portrait else {
                val landscape=create(landscapeWidth,landscapeHeight)
                if(android.os.Build.VERSION.SDK_INT>=31)RemoteViews(mapOf(android.util.SizeF(portraitWidth.toFloat(),portraitHeight.toFloat()) to portrait,android.util.SizeF(landscapeWidth.toFloat(),landscapeHeight.toFloat()) to landscape))
                else RemoteViews(landscape,portrait)
            }
            val bytes=bitmaps.sumOf {it.allocationByteCount};check(bytes<=ExpenseChartRenderer.MAX_BITMAP_BYTES)
            return Rendered(remote,drawNanos,bytes,bitmaps,dark,config.fontScale)
        } catch(error:Throwable) {
            bitmaps.forEach {if(!it.isRecycled)it.recycle()}
            throw error
        }
    }
}
