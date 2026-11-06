package net.ankio.ledger.widget

import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ExpensePeriod
import net.ankio.ledger.data.LedgerStatistics
import java.time.LocalDate

/** Calendar facts are the same expense projection, never a division of natural-week totals. */
object ExpenseWidgetCalendar {
    data class Cell(val date:LocalDate?,val expenseCents:Long?)
    data class Month(val start:LocalDate,val end:LocalDate,val cells:List<Cell>)
    fun create(snapshot:LedgerStatistics.Snapshot):Month {
        val range=requireNotNull(snapshot.range){"CALENDAR_DAY_FACTS_REQUIRED"}
        require(range.period==ExpensePeriod.MONTH){"CALENDAR_DAY_FACTS_REQUIRED"}
        val start=range.startDate;val end=range.endDate
        val facts=linkedMapOf<LocalDate,Long>()
        snapshot.days.filter {it.direction==Direction.EXPENSE}.forEach {day->
            val date=LocalDate.parse(day.date)
            require(date>=start && date<end && day.amountCents>=0 && date !in facts){"CALENDAR_DAY_FACTS_REQUIRED"}
            facts[date]=day.amountCents
        }
        require(facts.values.fold(0L,Math::addExact)==snapshot.totals.expense){"CALENDAR_DAY_FACTS_REQUIRED"}
        val offset=start.dayOfWeek.value-1
        val cells=List(42) {index->
            val date=start.plusDays((index-offset).toLong())
            if(date<start || date>=end)Cell(null,null) else Cell(date,facts[date]?:0L)
        }
        return Month(start,end,cells)
    }
}
