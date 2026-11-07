package net.ankio.ledger.widget

import android.content.Context
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ExpensePeriod
import net.ankio.ledger.core.ExpensePeriodBuckets
import net.ankio.ledger.data.LedgerStatistics
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** UI-only dated projection. A month without complete day facts can never become a zero calendar. */
internal object ExpenseWidgetSnapshotCache {
    private fun preferences(context:Context)=context.getSharedPreferences("expense_widget_last_success",Context.MODE_PRIVATE)
    fun put(context:Context,id:Int,snapshot:LedgerStatistics.Snapshot,now:Instant) {
        if(snapshot.range!!.period==ExpensePeriod.MONTH)ExpenseWidgetCalendar.create(snapshot)
        val value=JSONObject().put("version",2).put("period",snapshot.range.period.name).put("now",now.toEpochMilli())
            .put("zone",snapshot.zoneId).put("total",snapshot.totals.expense.toString()).put("count",snapshot.totals.expenseCount)
            .put("amounts",JSONArray(snapshot.bucketAmounts.map(Long::toString)))
            .put("days",JSONArray().apply {snapshot.days.filter {it.direction==Direction.EXPENSE}.forEach {put(JSONObject().put("date",it.date).put("cents",it.amountCents.toString()))}})
        preferences(context).edit().putString(id.toString(),value.toString()).apply()
    }
    fun get(context:Context,id:Int):LedgerStatistics.Snapshot?=runCatching {
        val text=preferences(context).getString(id.toString(),null)?:return null
        require(text.length<12_288)
        val value=JSONObject(text);val period=ExpensePeriod.valueOf(value.getString("period"))
        if(period==ExpensePeriod.MONTH)require(value.optInt("version",1)==2 && value.optJSONArray("days")!=null)
        val range=ExpensePeriodBuckets.create(period,Instant.ofEpochMilli(value.getLong("now")),ZoneId.of(value.getString("zone")))
        val array=value.getJSONArray("amounts");require(array.length()==range.buckets.size)
        val amounts=(0 until array.length()).map {array.getString(it).toLong().also {n->require(n>=0)}}
        val total=value.getString("total").toLong();require(amounts.fold(0L,Math::addExact)==total)
        val days=value.optJSONArray("days")?.let {list->require(list.length()<=31);(0 until list.length()).map {i->
            val day=list.getJSONObject(i);val date=LocalDate.parse(day.getString("date"));val cents=day.getString("cents").toLong()
            require(cents>=0);LedgerStatistics.Day(date.dayOfMonth,Direction.EXPENSE,cents,date.toString())
        }}?:emptyList()
        val count=value.getLong("count");require(count>=0)
        val snapshot=LedgerStatistics.Snapshot(range,range.startMillis,range.endMillis,range.zoneId,LedgerStatistics.Totals(expense=total,expenseCount=count),amounts,emptyList(),days)
        if(period==ExpensePeriod.MONTH)ExpenseWidgetCalendar.create(snapshot)
        snapshot
    }.getOrNull()
    fun remove(context:Context,id:Int){preferences(context).edit().remove(id.toString()).apply()}
}
