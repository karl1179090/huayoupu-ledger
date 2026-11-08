package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import net.ankio.ledger.core.ReceiptTimeEvidence
import net.ankio.ledger.core.ParsedBill
import java.time.Instant
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
    fun within(start:Long,end:Long,zone:ZoneId):Range {
        // DATE represents a local calendar start, including a 23/25-hour DST day. No fixed-day arithmetic.
        fun ceiling(at:Long):LocalDate {
            val day=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            return if(day.atStartOfDay(zone).toInstant().toEpochMilli()<at)day.plusDays(1) else day
        }
        return Range(Filter("r.accounting_date IS NULL AND r.occurred_at>=? AND r.occurred_at<?",listOf(start.toString(),end.toString())),
            Filter("r.accounting_date>=? AND r.accounting_date<?",listOf(ceiling(start).toString(),ceiling(end).toString())))
    }
}
