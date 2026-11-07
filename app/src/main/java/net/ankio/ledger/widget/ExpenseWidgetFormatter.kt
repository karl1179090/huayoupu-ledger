package net.ankio.ledger.widget

import net.ankio.ledger.core.Money

internal object ExpenseWidgetFormatter {
    /** Visible CNY thousands commas; invisible break opportunities only follow whole groups. */
    fun compact(cents:Long):String = money(cents).trimEnd('0').trimEnd('.')
    fun money(cents:Long):String {
        require(cents>=0)
        val (whole,fraction)=Money.format(cents).split('.')
        return whole.reversed().chunked(3).joinToString(",").reversed().replace(",",",\u200b")+"."+fraction
    }
}
