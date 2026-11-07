package net.ankio.ledger.widget

import android.content.Context
import net.ankio.ledger.core.ExpensePeriod

/** Desktop instance configuration is local UI state and never enters financial backups. */
class ExpenseWidgetConfigStore(context: Context) {
    private val preferences=context.getSharedPreferences("expense_widget_instances",Context.MODE_PRIVATE)
    data class Selection(val period:ExpensePeriod,val generation:Long,val offset:Int=0)
    fun get(id:Int):Selection=synchronized(lock) {
        require(id>0)
        val period=runCatching { ExpensePeriod.valueOf(preferences.getString("period:$id",null) ?: "WEEK") }.getOrDefault(ExpensePeriod.WEEK)
        Selection(period,preferences.getLong("generation:$id",0),preferences.getInt("offset:$id",0))
    }
    fun select(id:Int,period:ExpensePeriod):Selection=synchronized(lock) {
        val next=Selection(period,Math.addExact(get(id).generation,1),0)
        check(preferences.edit().putString("period:$id",period.name).putLong("generation:$id",next.generation).putInt("offset:$id",0).commit())
        next
    }
    fun step(id:Int,delta:Int):Selection=synchronized(lock) {
        val current=get(id)
        val nextOffset=(current.offset+delta).coerceIn(-120,120)
        val next=Selection(current.period,Math.addExact(current.generation,1),nextOffset)
        check(preferences.edit().putString("period:$id",next.period.name).putLong("generation:$id",next.generation).putInt("offset:$id",nextOffset).commit())
        next
    }
    fun remove(id:Int)=synchronized(lock) {check(preferences.edit().remove("period:$id").remove("generation:$id").remove("offset:$id").commit())}
    fun restore(oldIds:IntArray,newIds:IntArray)=synchronized(lock) {
        require(oldIds.size==newIds.size && oldIds.all {it>0} && newIds.all {it>0} && newIds.distinct().size==newIds.size)
        val previous=oldIds.map(::get)
        val edit=preferences.edit()
        oldIds.forEach {edit.remove("period:$it").remove("generation:$it").remove("offset:$it")}
        newIds.forEachIndexed {index,id->edit.putString("period:$id",previous[index].period.name).putLong("generation:$id",Math.addExact(previous[index].generation,1)).putInt("offset:$id",previous[index].offset)}
        check(edit.commit())
    }
    companion object {private val lock=Any()}
}
