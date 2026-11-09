package net.ankio.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Exact inverse of local calendar dates, including skipped dates and rollbacks across midnight. */
object CalendarDayIntervals {
    data class Interval(val startMillis:Long,val endMillis:Long) {
        init { require(startMillis<endMillis) }
    }

    fun between(start:LocalDate,end:LocalDate,zone:ZoneId):List<Interval> {
        require(start<=end)
        if(start==end)return emptyList()
        val localStart=start.atStartOfDay().toInstant(ZoneOffset.UTC)
        val localEnd=end.atStartOfDay().toInstant(ZoneOffset.UTC)
        return merge(buildList {
            segments(localStart.minusSeconds(OFFSET_BOUND),localEnd.plusSeconds(OFFSET_BOUND),zone) { low,high,offset ->
                val from=maxOf(low,localStart.minusSeconds(offset.totalSeconds.toLong()))
                val until=minOf(high,localEnd.minusSeconds(offset.totalSeconds.toLong()))
                if(from<until)add(Interval(from.toEpochMilli(),until.toEpochMilli()))
            }
        })
    }

    /** All instants whose local calendar date is strictly before the supplied date. */
    fun before(day:LocalDate,zone:ZoneId):List<Interval> {
        val local=day.atStartOfDay().toInstant(ZoneOffset.UTC)
        val low=local.minusSeconds(OFFSET_BOUND);val high=local.plusSeconds(OFFSET_BOUND)
        return merge(buildList {
            add(Interval(Long.MIN_VALUE,low.toEpochMilli()))
            segments(low,high,zone) { from,until,offset ->
                val end=minOf(until,local.minusSeconds(offset.totalSeconds.toLong()))
                if(from<end)add(Interval(from.toEpochMilli(),end.toEpochMilli()))
            }
        })
    }

    private inline fun segments(start:Instant,end:Instant,zone:ZoneId,action:(Instant,Instant,ZoneOffset)->Unit) {
        var cursor=start;val rules=zone.rules
        while(cursor<end) {
            val next=rules.nextTransition(cursor)?.instant?.let { minOf(it,end) } ?: end
            action(cursor,next,rules.getOffset(cursor));cursor=next
        }
    }
    private fun merge(values:List<Interval>):List<Interval> = buildList {
        for(value in values) {
            val previous=lastOrNull()
            if(previous!=null && previous.endMillis>=value.startMillis) {
                removeAt(lastIndex);add(Interval(previous.startMillis,maxOf(previous.endMillis,value.endMillis)))
            } else add(value)
        }
    }
    private val OFFSET_BOUND=ZoneOffset.MAX.totalSeconds.toLong()
}
