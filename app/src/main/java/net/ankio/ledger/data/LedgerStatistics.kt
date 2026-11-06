package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Shared expense projection. Amounts are cents throughout; wallet eligibility does not filter statistics. */
class LedgerStatistics(private val store: LedgerStore) {
    data class Category(val key: String, val name: String, val direction: Direction, val amountCents: Long, val count: Long)
    data class Day(val day: Int, val direction: Direction, val amountCents: Long, val date: String)
    data class Totals(val expense: Long = 0, val income: Long = 0, val expenseCount: Long = 0,
        val incomeCount: Long = 0, val transferCount: Long = 0, val totalCount: Long = 0) {
        fun json() = JSONObject().put("expense", expense.toString()).put("income", income.toString())
            .put("expenseCount", expenseCount).put("incomeCount", incomeCount).put("transferCount", transferCount).put("totalCount", totalCount)
    }
    data class Snapshot(val range: ExpensePeriodRange?, val start: Long, val end: Long, val zoneId: String,
        val totals: Totals, val bucketAmounts: List<Long>, val categories: List<Category>, val days: List<Day>) {
        fun json() = JSONObject().put("period", range?.period?.name ?: "MONTH").put("rangeStart", start).put("rangeEnd", end)
            .put("zoneId", zoneId).put("totalExpenseCents", totals.expense.toString()).put("incomeCents", totals.income.toString())
            .put("contributionCount", totals.expenseCount).put("transferCount", totals.transferCount).put("totals", totals.json())
            .put("buckets", JSONArray().apply { range?.buckets?.forEachIndexed { index, bucket -> put(JSONObject()
                .put("start", bucket.startMillis).put("end", bucket.endMillis).put("label", bucket.label)
                .put("current", bucket.current).put("future", bucket.future).put("expenseCents", bucketAmounts[index].toString())) } })
            .put("categories", JSONArray().apply { categories.forEach { put(JSONObject().put("key", it.key).put("name", it.name)
                .put("direction", it.direction.name).put("amount", it.amountCents.toString()).put("count", it.count)) } })
            .put("days", JSONArray().apply { days.forEach { put(JSONObject().put("day", it.day).put("date",it.date).put("direction", it.direction.name).put("amount", it.amountCents.toString())) } })
    }

    fun query(period: ExpensePeriod, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Snapshot = synchronized(store) {
        val range = ExpensePeriodBuckets.create(period, now, zone)
        readSnapshot { aggregate(range.startMillis, range.endMillis, zone, range) }
    }

    /** The old month entry and the current-period service use this exact same projection. */
    fun month(month: String, zone: ZoneId = ZoneId.systemDefault()): JSONObject = synchronized(store) {
        val w = LedgerReadView(store).window(month,zone)
        readSnapshot {
            val current = aggregate(w.start, w.end, zone)
            current.json().put("month", month).put("previous", aggregate(w.previous, w.start, zone).totals.json())
                .put("postedCount", store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records r WHERE r.state='POSTED' AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)", null).use { it.moveToFirst(); it.getLong(0) })
        }
    }

    private fun <T> readSnapshot(action: () -> T): T {
        val db = store.readableDatabase
        db.beginTransactionNonExclusive()
        try { val result = action(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }

    private fun aggregate(start: Long, end: Long, zone: ZoneId, range: ExpensePeriodRange? = null): Snapshot {
        var expense = 0L; var income = 0L; var expenses = 0L; var incomes = 0L; var transfers = 0L; var count = 0L
        val amounts = LongArray(range?.buckets?.size ?: 0)
        val categories = linkedMapOf<Pair<Direction, String>, Pair<Long, Long>>()
        val days = linkedMapOf<Pair<java.time.LocalDate, Direction>, Long>()
        store.readableDatabase.rawQuery("""SELECT r.direction,r.amount,r.category,r.occurred_at,t.fee_cents FROM records r
            LEFT JOIN record_transfer_details t ON t.record_uid=r.uid WHERE r.state='POSTED' AND r.occurred_at>=? AND r.occurred_at<?
            AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)""", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) {
                count = add(count, 1)
                val direction = Direction.valueOf(c.getString(0))
                val original = c.getLong(1)
                require(!c.isNull(1) && original in 0..Money.MAX_CENTS) { "INVALID_AMOUNT" }
                val occurred = c.getLong(3)
                val cents = when (direction) {
                    Direction.EXPENSE -> original
                    Direction.INCOME -> original
                    Direction.TRANSFER -> {
                        transfers = add(transfers, 1)
                        require(!c.isNull(4) && c.getLong(4) in 0..Money.MAX_CENTS) { "TRANSFER_FEE_AMBIGUOUS" }
                        c.getLong(4)
                    }
                }
                if (direction == Direction.TRANSFER && cents == 0L) continue
                val contributionDirection = if (direction == Direction.INCOME) Direction.INCOME else Direction.EXPENSE
                if (contributionDirection == Direction.EXPENSE) {
                    expense = add(expense, cents); expenses = add(expenses, 1)
                    range?.bucketIndex(occurred)?.takeIf { it >= 0 }?.let { amounts[it] = add(amounts[it], cents) }
                } else { income = add(income, cents); incomes = add(incomes, 1) }
                val category = if (direction == Direction.TRANSFER) TRANSFER_FEE_KEY else "category:"+c.getString(2)
                val key = contributionDirection to category
                val prior = categories[key] ?: (0L to 0L)
                categories[key] = add(prior.first, cents) to add(prior.second, 1)
                val day = Instant.ofEpochMilli(occurred).atZone(zone).toLocalDate() to contributionDirection
                days[day] = add(days[day] ?: 0, cents)
            }
        }
        if (range != null) check(amounts.fold(0L, ::add) == expense)
        return Snapshot(range, start, end, zone.id, Totals(expense, income, expenses, incomes, transfers, count), amounts.toList(),
            categories.map { (key, value) -> Category(key.second, if (key.second == TRANSFER_FEE_KEY) "转账手续费" else key.second.removePrefix("category:").let { if(it=="转账手续费")"转账手续费（自定义）" else it }, key.first, value.first, value.second) }
                .sortedWith(compareByDescending<Category> { it.amountCents }.thenBy { it.key }),
            days.map { (key, value) -> Day(key.first.dayOfMonth, key.second, value,key.first.toString()) }.sortedBy { it.date })
    }

    companion object {
        const val TRANSFER_FEE_KEY = "system:transfer_fee"
        fun add(first: Long, second: Long): Long = try { Math.addExact(first, second) }
        catch (_: ArithmeticException) { throw IllegalArgumentException("STATISTICS_OVERFLOW") }
    }
}
