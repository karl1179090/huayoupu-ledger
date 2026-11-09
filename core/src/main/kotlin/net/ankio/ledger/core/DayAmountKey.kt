package net.ankio.ledger.core

import java.time.Instant
import java.time.ZoneId

/** The deliberately coarse multi-page business rule; direction and account are not part of this key. */
data class DayAmountKey(val localDate:String,val amountCents:Long) {
    companion object {
        fun from(bill:ParsedBill,zoneId:String):DayAmountKey? {
            val amount=bill.amountCents ?: return null
            val evidence=bill.receipt?.time
            val date=evidence?.takeIf { it.accountingAt==bill.occurredAt && it.precision=="DATE" &&
                it.source in setOf("EXPLICIT","YEAR_INFERRED") }?.localDate
                ?: Instant.ofEpochMilli(bill.occurredAt).atZone(ZoneId.of(zoneId)).toLocalDate().toString()
            return DayAmountKey(date,amount)
        }
    }
}
