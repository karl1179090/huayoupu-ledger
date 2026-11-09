package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.CalendarDayIntervals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Indexed calendar-day seeks and bounded page reads, independent of instant/day monotonicity. */
internal class CalendarRecordPages(private val db:SQLiteDatabase,private val zone:ZoneId,
    private val where:String,private val args:List<String>,private val range:AccountingDates.Range) {
    data class Key(val id:Long,val at:Long,val day:LocalDate)
    class StaleCursor:IllegalArgumentException("STALE_CALENDAR_CURSOR")

    fun select(limit:Int,offset:Int,beforeAt:Long?,beforeId:Long?):List<Key> {
        val cursor=if(beforeAt==null)null else db.rawQuery("SELECT r.id,r.occurred_at,r.accounting_date $JOINS WHERE $where AND ${range.sql} AND r.id=? AND r.occurred_at=?",
            (args+range.args+listOf(beforeId.toString(),beforeAt.toString())).toTypedArray()).use { c ->
            if(!c.moveToFirst())throw StaleCursor()
            key(c.getLong(0),c.getLong(1),if(c.isNull(2))null else c.getString(2))
        }
        val ordinary=if(cursor==null)range.ordinary else and(range.ordinary,continuation(cursor))
        val dates=if(cursor==null)range.dates else and(range.dates,AccountingDates.Filter(
            "(r.accounting_date<? OR (r.accounting_date=? AND (r.occurred_at<? OR (r.occurred_at=? AND r.id<?))))",
            listOf(cursor.day.toString(),cursor.day.toString(),cursor.at.toString(),cursor.at.toString(),cursor.id.toString())))
        var skip=offset;var older:LocalDate?=null
        val result=mutableListOf<Key>()
        while(result.size<limit+1) {
            val activeOrdinary=if(older==null)ordinary else and(ordinary,AccountingDates.instants(CalendarDayIntervals.before(older,zone)))
            val activeDates=if(older==null)dates else and(dates,AccountingDates.Filter("r.accounting_date<?",listOf(older.toString())))
            val ordinaryDay=latestOrdinaryDay(activeOrdinary,older)
            val dateDay=read(activeDates,"r.accounting_date DESC,r.occurred_at DESC,r.id DESC",1).firstOrNull()?.day
            val day=listOfNotNull(ordinaryDay,dateDay).maxOrNull() ?: break
            val conditions=listOf(and(activeOrdinary,dayFilter(day)),and(activeDates,AccountingDates.Filter("r.accounting_date=?",listOf(day.toString()))))
            val count=if(skip==0)0L else conditions.sumOf { filter -> db.rawQuery("SELECT COUNT(*) $JOINS WHERE $where AND (${filter.sql})",
                (args+filter.args).toTypedArray()).use { c -> c.moveToFirst();c.getLong(0) } }
            if(skip>0 && skip.toLong()>=count)skip-=count.toInt()
            else {
                val queries=conditions.map { "SELECT r.id id,r.occurred_at occurred_at,r.accounting_date accounting_date $JOINS WHERE $where AND (${it.sql})" }
                val queryArgs=conditions.flatMap { args+it.args }
                result+=readKeys("${queries[0]} UNION ALL ${queries[1]} ORDER BY occurred_at DESC,id DESC LIMIT ${limit+1-result.size} OFFSET $skip",queryArgs)
                skip=0
            }
            older=day
        }
        return result
    }

    private fun continuation(cursor:Key):AccountingDates.Filter {
        val before=AccountingDates.instants(CalendarDayIntervals.before(cursor.day,zone))
        val same=dayFilter(cursor.day)
        return AccountingDates.Filter("((${before.sql}) OR ((${same.sql}) AND (r.occurred_at<? OR (r.occurred_at=? AND r.id<?))))",
            before.args+same.args+listOf(cursor.at.toString(),cursor.at.toString(),cursor.id.toString()))
    }

    private fun latestOrdinaryDay(filter:AccountingDates.Filter,older:LocalDate?):LocalDate? {
        val latest=read(filter,"r.occurred_at DESC,r.id DESC",1).firstOrNull() ?: return null
        // All earlier instants have local dates <= latestInstant at the largest legal UTC offset.
        // The real offset is within +/-18 hours, so at most three calendar dates need probing.
        var candidate=Instant.ofEpochMilli(latest.at).atOffset(ZoneOffset.MAX).toLocalDate()
        if(older!=null)candidate=minOf(candidate,older.minusDays(1))
        while(candidate>=latest.day) {
            if(read(and(filter,dayFilter(candidate)),"r.occurred_at DESC,r.id DESC",1).isNotEmpty())return candidate
            candidate=candidate.minusDays(1)
        }
        error("Calendar index seek lost its own boundary row")
    }
    private fun dayFilter(day:LocalDate)=AccountingDates.instants(CalendarDayIntervals.between(day,day.plusDays(1),zone))
    private fun and(first:AccountingDates.Filter,second:AccountingDates.Filter)=AccountingDates.Filter("(${first.sql}) AND (${second.sql})",first.args+second.args)
    private fun key(id:Long,at:Long,date:String?)=Key(id,at,date?.let(LocalDate::parse) ?: Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
    private fun read(filter:AccountingDates.Filter,order:String,limit:Int)=readKeys("SELECT r.id,r.occurred_at,r.accounting_date $JOINS WHERE $where AND (${filter.sql}) ORDER BY $order LIMIT $limit",args+filter.args)
    private fun readKeys(sql:String,values:List<String>):List<Key> = db.rawQuery(sql,values.toTypedArray()).use { c ->
        buildList { while(c.moveToNext())add(key(c.getLong(0),c.getLong(1),if(c.isNull(2))null else c.getString(2))) }
    }
    companion object {
        private const val JOINS="""FROM records r LEFT JOIN record_wallet_links w ON w.record_uid=r.uid
            LEFT JOIN record_transfer_details t ON t.record_uid=r.uid LEFT JOIN accounts fa ON fa.uid=t.from_account_uid
            LEFT JOIN accounts ta ON ta.uid=t.to_account_uid"""
    }
}
