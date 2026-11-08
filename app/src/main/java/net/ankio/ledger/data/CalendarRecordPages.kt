package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Merge two indexed streams. No receipt decoding, SQLite localtime, or unbounded row materialization. */
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
        if(offset==0)return merge(stream(false,limit+1,cursor),stream(true,limit+1,cursor)).take(limit+1)

        // Legacy offset callers skip whole days with SQL counts, then seek only the requested page.
        // Memory remains bounded by page size even for a large offset or a sparse history.
        var skip=offset;var olderThan:LocalDate?=null
        val result=mutableListOf<Key>()
        while(result.size<limit+1) {
            val day=merge(stream(false,1,olderThan=olderThan),stream(true,1,olderThan=olderThan)).firstOrNull()?.day ?: break
            val conditions=listOf(dayFilter(false,day),dayFilter(true,day))
            val count=conditions.sumOf { filter -> db.rawQuery("SELECT COUNT(*) $JOINS WHERE $where AND ${filter.sql}",
                (args+filter.args).toTypedArray()).use { c -> c.moveToFirst();c.getLong(0) } }
            if(skip.toLong()>=count)skip-=count.toInt()
            else {
                val queries=conditions.map { "SELECT r.id id,r.occurred_at occurred_at,r.accounting_date accounting_date $JOINS WHERE $where AND ${it.sql}" }
                val queryArgs=conditions.flatMap { args+it.args }
                result+=read("${queries[0]} UNION ALL ${queries[1]} ORDER BY occurred_at DESC,id DESC LIMIT ${limit+1-result.size} OFFSET $skip",queryArgs)
                skip=0
            }
            olderThan=day
        }
        return result
    }

    private fun stream(dates:Boolean,limit:Int,cursor:Key?=null,olderThan:LocalDate?=null):List<Key> {
        val base=if(dates)range.dates else range.ordinary
        val sql=StringBuilder("${base.sql}");val values=base.args.toMutableList()
        if(cursor!=null) {
            if(dates) {
                sql.append(" AND (r.accounting_date<? OR (r.accounting_date=? AND (r.occurred_at<? OR (r.occurred_at=? AND r.id<?))))")
                values+=listOf(cursor.day.toString(),cursor.day.toString(),cursor.at.toString(),cursor.at.toString(),cursor.id.toString())
            } else {
                sql.append(" AND r.occurred_at<? AND (r.occurred_at<? OR r.occurred_at<? OR (r.occurred_at=? AND r.id<?))")
                values+=listOf(start(cursor.day.plusDays(1)),start(cursor.day),cursor.at.toString(),cursor.at.toString(),cursor.id.toString())
            }
        }
        if(olderThan!=null) {
            sql.append(if(dates)" AND r.accounting_date<?" else " AND r.occurred_at<?")
            values+=if(dates)olderThan.toString() else start(olderThan)
        }
        val order=if(dates)"r.accounting_date DESC,r.occurred_at DESC,r.id DESC" else "r.occurred_at DESC,r.id DESC"
        return read("SELECT r.id,r.occurred_at,r.accounting_date $JOINS WHERE $where AND $sql ORDER BY $order LIMIT $limit",args+values)
    }

    private fun dayFilter(dates:Boolean,day:LocalDate):AccountingDates.Filter {
        val base=if(dates)range.dates else range.ordinary
        return if(dates)AccountingDates.Filter("${base.sql} AND r.accounting_date=?",base.args+day.toString())
        else AccountingDates.Filter("${base.sql} AND r.occurred_at>=? AND r.occurred_at<?",base.args+listOf(start(day),start(day.plusDays(1))))
    }
    private fun start(day:LocalDate)=day.atStartOfDay(zone).toInstant().toEpochMilli().toString()
    private fun key(id:Long,at:Long,date:String?)=Key(id,at,date?.let(LocalDate::parse) ?: Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
    private fun read(sql:String,values:List<String>):List<Key> = db.rawQuery(sql,values.toTypedArray()).use { c ->
        buildList { while(c.moveToNext())add(key(c.getLong(0),c.getLong(1),if(c.isNull(2))null else c.getString(2))) }
    }
    private fun merge(first:List<Key>,second:List<Key>)=(first+second).sortedWith(compareByDescending<Key> { it.day }.thenByDescending { it.at }.thenByDescending { it.id })

    companion object {
        private const val JOINS="""FROM records r LEFT JOIN record_wallet_links w ON w.record_uid=r.uid
            LEFT JOIN record_transfer_details t ON t.record_uid=r.uid LEFT JOIN accounts fa ON fa.uid=t.from_account_uid
            LEFT JOIN accounts ta ON ta.uid=t.to_account_uid"""
    }
}
