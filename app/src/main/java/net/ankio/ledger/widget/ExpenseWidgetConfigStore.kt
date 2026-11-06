package net.ankio.ledger.widget

import android.content.Context
import net.ankio.ledger.core.ExpensePeriod

/** Desktop instance configuration is local UI state and never enters financial backups. */
class ExpenseWidgetConfigStore(context: Context) {
    private val preferences=context.getSharedPreferences("expense_widget_instances",Context.MODE_PRIVATE)
    data class Selection(val period:ExpensePeriod,val generation:Long)
    fun get(id:Int):Selection=synchronized(lock) {
        require(id>0)
        val period=runCatching { ExpensePeriod.valueOf(preferences.getString("period:$id",null) ?: "WEEK") }.getOrDefault(ExpensePeriod.WEEK)
        Selection(period,preferences.getLong("generation:$id",0))
    }
    fun select(id:Int,period:ExpensePeriod):Selection=synchronized(lock) {
        val next=Selection(period,Math.addExact(get(id).generation,1))
        check(preferences.edit().putString("period:$id",period.name).putLong("generation:$id",next.generation).commit())
        next
    }
    fun remove(id:Int)=synchronized(lock) {check(preferences.edit().remove("period:$id").remove("generation:$id").commit())}
    fun restore(oldIds:IntArray,newIds:IntArray)=synchronized(lock) {
        require(oldIds.size==newIds.size && oldIds.all {it>0} && newIds.all {it>0} && newIds.distinct().size==newIds.size)
        val previous=oldIds.map(::get)
        val edit=preferences.edit()
        oldIds.forEach {edit.remove("period:$it").remove("generation:$it")}
        newIds.forEachIndexed {index,id->edit.putString("period:$id",previous[index].period.name).putLong("generation:$id",Math.addExact(previous[index].generation,1))}
        check(edit.commit())
    }
    companion object {private val lock=Any()}
}
