package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import net.ankio.ledger.core.ReceiptTimeEvidence
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.CalendarDayIntervals
import java.time.LocalDate
import java.time.ZoneId

/** Indexed calendar override for proven DATE receipts; all other records keep their instant semantics. */
internal object AccountingDates {
    fun dateOnly(bill:ParsedBill):String? = dateOnly(bill.receipt?.time,bill.occurredAt)
    private fun dateOnly(time:ReceiptTimeEvidence?,occurredAt:Long):String? = time?.takeIf {
        it.precision=="DATE" && it.source in setOf("EXPLICIT","YEAR_INFERRED") && it.accountingAt==occurredAt
    }?.localDate

    fun create(db:SQLiteDatabase) {
        val present=db.rawQuery("PRAGMA table_info(records)",null).use { c ->
            var found=false;while(c.moveToNext())if(c.getString(1)=="accounting_date")found=true;found
        }
        if(!present)db.execSQL("ALTER TABLE records ADD COLUMN accounting_date TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_accounting_date_time ON records(accounting_date DESC,occurred_at DESC,id DESC)")
    }

    /** One upgrade pass over bounded receipt metadata, never a read-time scan or a replay of financial effects. */
    fun migrate(db:SQLiteDatabase) {
        create(db)
        db.execSQL("UPDATE records SET accounting_date=NULL WHERE accounting_date IS NOT NULL")
        db.query("records",arrayOf("id","occurred_at","receipt_evidence"),"receipt_evidence IS NOT NULL",null,null,null,"id").use { c ->
            while(c.moveToNext()) {
                val date=runCatching { dateOnly(ReceiptEvidenceCodec.decode(c.getString(2)).time,c.getLong(1)) }.getOrNull()
                if(date!=null)db.update("records",ContentValues().apply { put("accounting_date",date) },"id=?",arrayOf(c.getLong(0).toString()))
            }
        }
    }

    data class Filter(val sql:String,val args:List<String>)
    data class Range(val ordinary:Filter,val dates:Filter) {
        val sql get()="((${ordinary.sql}) OR (${dates.sql}))"
        val args get()=ordinary.args+dates.args
    }
    fun all()=Range(Filter("r.accounting_date IS NULL",emptyList()),Filter("r.accounting_date IS NOT NULL",emptyList()))
    fun calendar(start:LocalDate,end:LocalDate,zone:ZoneId):Range {
        val times=instants(CalendarDayIntervals.between(start,end,zone))
        return Range(Filter("r.accounting_date IS NULL AND (${times.sql})",times.args),
            Filter("r.accounting_date>=? AND r.accounting_date<?",listOf(start.toString(),end.toString())))
    }
    fun instants(intervals:List<CalendarDayIntervals.Interval>):Filter {
        if(intervals.isEmpty())return Filter("0",emptyList())
        val parts=intervals.map { "(r.occurred_at>=? AND r.occurred_at<?)" }
        val args=intervals.flatMap { listOf(it.startMillis.toString(),it.endMillis.toString()) }
        if(intervals.size==1)return Filter(parts.single(),args)
        // The outer bounds retain an ordinary timestamp index seek when a date has a gap.
        return Filter("r.occurred_at>=? AND r.occurred_at<? AND (${parts.joinToString(" OR ")})",
            listOf(intervals.first().startMillis.toString(),intervals.last().endMillis.toString())+args)
    }
    fun within(start:Long,end:Long):Range {
        // Explicit instant bounds apply to the saved final timestamp for every precision.
        val values=listOf(start.toString(),end.toString())
        return Range(Filter("r.accounting_date IS NULL AND r.occurred_at>=? AND r.occurred_at<?",values),
            Filter("r.accounting_date IS NOT NULL AND r.occurred_at>=? AND r.occurred_at<?",values))
    }
}
