package net.ankio.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

class ExpensePeriodBucketsTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-06T04:00:00Z")

    @Test fun november2026HasSevenDaysSixNaturalWeekSegmentsAndTwelveMonths() {
        val week = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, now, shanghai)
        assertEquals(LocalDate.parse("2026-11-02"), week.startDate)
        assertEquals(LocalDate.parse("2026-11-09"), week.endDate)
        assertEquals(7, week.buckets.size)
        assertEquals(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"), week.buckets.map { it.label })
        val month = ExpensePeriodBuckets.create(ExpensePeriod.MONTH, now, shanghai)
        assertEquals(listOf("1日", "2–8日", "9–15日", "16–22日", "23–29日", "30日"), month.buckets.map { it.label })
        assertEquals(LocalDate.parse("2026-11-01"), month.startDate)
        assertEquals(LocalDate.parse("2026-12-01"), month.endDate)
        val year = ExpensePeriodBuckets.create(ExpensePeriod.YEAR, now, shanghai)
        assertEquals(12, year.buckets.size)
        assertEquals((1..12).map { "${it}月" }, year.buckets.map { it.label })
        assertEquals(LocalDate.parse("2027-01-01"), year.endDate)
    }

    @Test fun februaryHasFourNaturalWeeksIn2027AndIncludesLeapDayIn2028() {
        val four = ExpensePeriodBuckets.create(ExpensePeriod.MONTH, Instant.parse("2027-02-18T00:00:00Z"), shanghai)
        assertEquals(4, four.buckets.size)
        assertEquals(listOf("1–7日", "8–14日", "15–21日", "22–28日"), four.buckets.map { it.label })
        val leap = ExpensePeriodBuckets.create(ExpensePeriod.MONTH, Instant.parse("2028-02-29T00:00:00Z"), shanghai)
        assertEquals(5, leap.buckets.size)
        assertEquals("28–29日", leap.buckets.last().label)
        assertTrue(leap.buckets.last().contains(Instant.parse("2028-02-29T12:00:00Z").toEpochMilli()))
    }

    @Test fun aCrossYearWeekRemainsACompleteMondayToSundayWeek() {
        val week = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, Instant.parse("2027-01-01T12:00:00Z"), shanghai)
        assertEquals(LocalDate.parse("2026-12-28"), week.startDate)
        assertEquals(LocalDate.parse("2027-01-04"), week.endDate)
        assertEquals(7, week.buckets.size)
        val previousYear = ExpensePeriodBuckets.create(ExpensePeriod.YEAR, Instant.parse("2026-12-31T15:59:59Z"), shanghai)
        val nextYear = ExpensePeriodBuckets.create(ExpensePeriod.YEAR, Instant.parse("2026-12-31T16:00:00Z"), shanghai)
        assertEquals(previousYear.endMillis, nextYear.startMillis)
    }

    @Test fun bucketBoundariesAreHalfOpenAndBelongToExactlyOneBucket() {
        for (period in ExpensePeriod.entries) {
            val range = ExpensePeriodBuckets.create(period, now, shanghai)
            for ((index, bucket) in range.buckets.withIndex()) {
                assertEquals(index, range.bucketIndex(bucket.startMillis))
                assertEquals(index, range.bucketIndex(bucket.endMillis - 1))
                assertEquals(if (index == range.buckets.lastIndex) -1 else index + 1,
                    range.bucketIndex(bucket.endMillis))
                assertEquals(1, range.buckets.count { it.contains(bucket.startMillis) })
            }
            assertEquals(-1, range.bucketIndex(range.startMillis - 1))
            assertEquals(-1, range.bucketIndex(range.endMillis))
        }
    }

    @Test fun monthSegmentsAreContiguousAndNeverLeakOutsideTheMonth() {
        for (year in 2020..2030) for (month in 1..12) {
            val date = LocalDate.of(year, month, 15)
            val range = ExpensePeriodBuckets.create(ExpensePeriod.MONTH, date.atStartOfDay(shanghai).toInstant(), shanghai)
            assertTrue(range.buckets.size in 4..6)
            assertEquals(range.startMillis, range.buckets.first().startMillis)
            assertEquals(range.endMillis, range.buckets.last().endMillis)
            assertEquals(date.lengthOfMonth().toLong(), range.buckets.sumOf { ChronoUnit.DAYS.between(it.startDate, it.endDate) })
            for ((left, right) in range.buckets.zipWithNext()) {
                assertEquals(left.endDate, right.startDate)
                assertEquals(left.endMillis, right.startMillis)
                assertEquals(1, right.startDate.dayOfWeek.value)
            }
        }
    }

    @Test fun daylightSavingChangesCreateShortAndLongDaysWithoutBoundaryDrift() {
        val newYork = ZoneId.of("America/New_York")
        val spring = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, Instant.parse("2026-03-08T12:00:00Z"), newYork)
        val autumn = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, Instant.parse("2026-11-01T12:00:00Z"), newYork)
        assertEquals(23 * 60 * 60 * 1000L, spring.buckets.last().endMillis - spring.buckets.last().startMillis)
        assertEquals(25 * 60 * 60 * 1000L, autumn.buckets.last().endMillis - autumn.buckets.last().startMillis)
        assertEquals(LocalDate.parse("2026-03-09"), Instant.ofEpochMilli(spring.endMillis).atZone(newYork).toLocalDate())
        assertEquals(LocalDate.parse("2026-11-02"), Instant.ofEpochMilli(autumn.endMillis).atZone(newYork).toLocalDate())
    }

    @Test fun zoneChangesRecomputeTheCalendarDayFromTheSameInstant() {
        val instant = Instant.parse("2026-11-01T20:00:00Z")
        val east = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, instant, shanghai)
        val west = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, instant, ZoneId.of("America/New_York"))
        assertEquals(LocalDate.parse("2026-11-02"), east.startDate)
        assertEquals(LocalDate.parse("2026-10-26"), west.startDate)
        assertEquals("Asia/Shanghai", east.zoneId)
    }

    @Test fun exactlyOneBucketIsCurrentAndLaterBucketsAreMarkedFuture() {
        for (period in ExpensePeriod.entries) {
            val range = ExpensePeriodBuckets.create(period, now, shanghai)
            assertEquals(1, range.buckets.count { it.current })
            val index = range.buckets.indexOfFirst { it.current }
            assertTrue(range.buckets.take(index + 1).none { it.future })
            assertTrue(range.buckets.drop(index + 1).all { it.future })
        }
    }

    @Test fun emptyBucketsStayZeroAndFuturePostedContributionsStillCount() {
        val range = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, now, shanghai)
        assertEquals(List(7) { 0L }, ExpensePeriodBuckets.sum(range, emptyList()).amounts)
        val totals = ExpensePeriodBuckets.sum(range, listOf(ExpenseContribution(range.buckets.last().startMillis, 123)))
        assertEquals(123L, totals.amounts.last())
        assertEquals(123L, totals.totalCents)
    }

    @Test fun originalNetExpenseTransferFeeAndExcludedImportAreAddedWithoutPrincipal() {
        val range = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, now, shanghai)
        val totals = ExpensePeriodBuckets.sum(range, listOf(
            ExpenseContribution(range.buckets[0].startMillis, 7_000),
            ExpenseContribution(range.buckets[2].startMillis, 100),
            ExpenseContribution(range.buckets[4].startMillis, 2_000),
            ExpenseContribution(range.startMillis - 1, 90_000),
            ExpenseContribution(range.endMillis, 90_000),
        ))
        assertEquals(listOf(7_000L, 0L, 100L, 0L, 2_000L, 0L, 0L), totals.amounts)
        assertEquals(9_100L, totals.totalCents)
        assertEquals(totals.amounts.sum(), totals.totalCents)
    }

    @Test fun statisticalTotalsKeepLongPrecisionAboveJavascriptSafeInteger() {
        val range = ExpensePeriodBuckets.create(ExpensePeriod.YEAR, now, shanghai)
        val contributions = List(100_000) { ExpenseContribution(range.startMillis, Money.MAX_CENTS) }
        val totals = ExpensePeriodBuckets.sum(range, contributions)
        assertEquals(9_999_999_999_900_000L, totals.totalCents)
        assertEquals("99999999999000.00", Money.format(totals.totalCents))
    }

    @Test fun statisticalOverflowRaisesAnErrorRatherThanWrappingOrReturningZero() {
        val range = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, now, shanghai)
        val contributions = listOf(ExpenseContribution(range.startMillis, Long.MAX_VALUE), ExpenseContribution(range.startMillis, 1))
        try { ExpensePeriodBuckets.sum(range, contributions); fail("Expected overflow") }
        catch (error: IllegalArgumentException) { assertEquals("STATISTICS_OVERFLOW", error.message) }
        val crossBucket = listOf(ExpenseContribution(range.buckets[0].startMillis, Long.MAX_VALUE),
            ExpenseContribution(range.buckets[1].startMillis, 1))
        try { ExpensePeriodBuckets.sum(range, crossBucket); fail("Expected total overflow") }
        catch (error: IllegalArgumentException) { assertEquals("STATISTICS_OVERFLOW", error.message) }
    }
}
