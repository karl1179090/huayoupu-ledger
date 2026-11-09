package net.ankio.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarDayIntervalsTest {
    private fun contains(values:List<CalendarDayIntervals.Interval>,at:Long)=values.any { at>=it.startMillis && at<it.endMillis }

    @Test fun exactDateInverseMatchesZoneRulesAcrossMidnightRollbacksDstAndSkippedDates() {
        for((name,dayText) in listOf("Antarctica/Casey" to "2010-03-04","Antarctica/Casey" to "2010-03-05",
            "America/St_Johns" to "2009-10-31","America/St_Johns" to "2009-11-01",
            "America/New_York" to "2026-03-08","America/New_York" to "2026-11-01",
            "Europe/Berlin" to "2026-10-25","Pacific/Apia" to "2011-12-30","Pacific/Kiritimati" to "1994-12-31")) {
            val zone=ZoneId.of(name);val day=LocalDate.parse(dayText)
            val values=CalendarDayIntervals.between(day,day.plusDays(1),zone)
            val before=CalendarDayIntervals.before(day,zone)
            val low=day.minusDays(2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val high=day.plusDays(3).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            for(at in low until high step 60_000) {
                val actual=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                assertEquals("$name $day $at",actual==day,contains(values,at))
                assertEquals("before $name $day $at",actual<day,contains(before,at))
            }
            assertTrue(values.zipWithNext().all { (a,b)->a.endMillis<b.startMillis })
        }
    }

    @Test fun repeatedDayHasTwoDisjointIntervalsAndSkippedDayHasNone() {
        val casey=CalendarDayIntervals.between(LocalDate.parse("2010-03-04"),LocalDate.parse("2010-03-05"),ZoneId.of("Antarctica/Casey"))
        assertEquals(2,casey.size)
        assertTrue(contains(casey,Instant.parse("2010-03-04T15:30:00Z").toEpochMilli()))
        assertFalse(contains(casey,Instant.parse("2010-03-04T14:30:00Z").toEpochMilli()))
        assertTrue(CalendarDayIntervals.between(LocalDate.parse("2011-12-30"),LocalDate.parse("2011-12-31"),ZoneId.of("Pacific/Apia")).isEmpty())
    }

    @Test fun monthIntervalsKeepTheRepeatedOctoberSegmentWithoutAdmittingTheFirstNovemberMinute() {
        val zone=ZoneId.of("America/St_Johns")
        val october=CalendarDayIntervals.between(LocalDate.parse("2009-10-01"),LocalDate.parse("2009-11-01"),zone)
        val november=CalendarDayIntervals.between(LocalDate.parse("2009-11-01"),LocalDate.parse("2009-12-01"),zone)
        for((text,inOctober) in listOf("2009-11-01T02:30:30Z" to false,"2009-11-01T03:00:00Z" to true)) {
            val at=Instant.parse(text).toEpochMilli()
            assertEquals(inOctober,contains(october,at));assertEquals(!inOctober,contains(november,at))
        }
    }
}
