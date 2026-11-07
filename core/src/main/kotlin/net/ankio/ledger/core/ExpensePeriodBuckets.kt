package net.ankio.ledger.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ExpensePeriod { WEEK, MONTH, YEAR }

data class ExpenseBucket(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startMillis: Long,
    val endMillis: Long,
    val label: String,
    val current: Boolean,
    val future: Boolean,
) {
    fun contains(occurredAt: Long): Boolean = occurredAt >= startMillis && occurredAt < endMillis
}

data class ExpensePeriodRange(
    val period: ExpensePeriod,
    val zoneId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startMillis: Long,
    val endMillis: Long,
    val buckets: List<ExpenseBucket>,
) {
    fun bucketIndex(occurredAt: Long): Int = buckets.indexOfFirst { it.contains(occurredAt) }
}

data class ExpenseContribution(val occurredAt: Long, val expenseCents: Long)
data class ExpenseBucketTotals(val amounts: List<Long>, val totalCents: Long)

/** Calendar boundaries are converted separately, including short/long days around DST. */
object ExpensePeriodBuckets {
    operator fun invoke(period: ExpensePeriod, now: Instant, zone: ZoneId): ExpensePeriodRange = create(period, now, zone, 0)

    fun create(period: ExpensePeriod, now: Long, zone: ZoneId): ExpensePeriodRange =
        create(period, Instant.ofEpochMilli(now), zone, 0)

    fun create(period: ExpensePeriod, now: Instant, zone: ZoneId, offset: Int = 0): ExpensePeriodRange {
        val today = now.atZone(zone).toLocalDate()
        val anchor = when (period) {
            ExpensePeriod.WEEK -> today.plusWeeks(offset.toLong())
            ExpensePeriod.MONTH -> today.plusMonths(offset.toLong())
            ExpensePeriod.YEAR -> today.plusYears(offset.toLong())
        }
        val start = when (period) {
            ExpensePeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ExpensePeriod.MONTH -> anchor.withDayOfMonth(1)
            ExpensePeriod.YEAR -> anchor.withDayOfYear(1)
        }
        val end = when (period) {
            ExpensePeriod.WEEK -> start.plusWeeks(1)
            ExpensePeriod.MONTH -> start.plusMonths(1)
            ExpensePeriod.YEAR -> start.plusYears(1)
        }
        val buckets = buildList {
            var cursor = start
            while (cursor < end) {
                val next = when (period) {
                    ExpensePeriod.WEEK -> cursor.plusDays(1)
                    ExpensePeriod.MONTH -> minOf(cursor.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), end)
                    ExpensePeriod.YEAR -> cursor.plusMonths(1)
                }
                val label = when (period) {
                    ExpensePeriod.WEEK -> listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[cursor.dayOfWeek.value - 1]
                    ExpensePeriod.MONTH -> if (cursor.plusDays(1) == next) "${cursor.dayOfMonth}日"
                        else "${cursor.dayOfMonth}–${next.minusDays(1).dayOfMonth}日"
                    ExpensePeriod.YEAR -> "${cursor.monthValue}月"
                }
                add(ExpenseBucket(cursor, next, midnight(cursor, zone), midnight(next, zone), label,
                    current = today >= cursor && today < next, future = today < cursor))
                cursor = next
            }
        }
        return ExpensePeriodRange(period, zone.id, start, end, midnight(start, zone), midnight(end, zone), buckets)
    }

    /** Record contributions are already projected by transaction type and original expense date. */
    fun sum(range: ExpensePeriodRange, contributions: Iterable<ExpenseContribution>): ExpenseBucketTotals {
        val amounts = LongArray(range.buckets.size)
        for (contribution in contributions) {
            require(contribution.expenseCents >= 0) { "INVALID_AMOUNT" }
            val index = range.bucketIndex(contribution.occurredAt)
            if (index >= 0) amounts[index] = checkedAdd(amounts[index], contribution.expenseCents)
        }
        val total = amounts.fold(0L, ::checkedAdd)
        return ExpenseBucketTotals(amounts.toList(), total)
    }

    private fun midnight(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun checkedAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("STATISTICS_OVERFLOW")
    }
}
