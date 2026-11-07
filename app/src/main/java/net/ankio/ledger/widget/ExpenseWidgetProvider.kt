package net.ankio.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import net.ankio.ledger.core.ExpensePeriod
import net.ankio.ledger.ui.MainActivity

class ExpenseWidgetProvider:AppWidgetProvider() {
    override fun onReceive(context:Context,intent:Intent) {
        when(intent.action) {
            SWITCH,REFRESH,PREV,NEXT->{
                val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,0)
                if(!validAction(context,intent,id))return
                when(intent.action) {
                    SWITCH->ExpenseWidgetUpdater.select(context,id,ExpensePeriod.valueOf(intent.getStringExtra(PERIOD)!!))
                    PREV->ExpenseWidgetUpdater.step(context,id,-1)
                    NEXT->ExpenseWidgetUpdater.step(context,id,1)
                }
                ExpenseWidgetUpdater.request(context,intArrayOf(id),goAsync());return
            }
            Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,Intent.ACTION_LOCALE_CHANGED->{
                if(intent.action==Intent.ACTION_TIMEZONE_CHANGED)java.util.TimeZone.setDefault(null)
                val ids=ids(context);if(ids.isNotEmpty())ExpenseWidgetUpdater.request(context,ids,goAsync());return
            }
        }
        super.onReceive(context,intent)
    }
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        val valid=ids.filter {it in ExpenseWidgetProvider.ids(context).toSet()}.toIntArray()
        if(valid.isNotEmpty())ExpenseWidgetUpdater.request(context,valid,goAsync())
    }
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle) {
        if(id in ids(context))ExpenseWidgetUpdater.request(context,intArrayOf(id),goAsync())
    }
    override fun onDeleted(context:Context,ids:IntArray) {
        val config=ExpenseWidgetConfigStore(context);ids.forEach {config.remove(it);ExpenseWidgetSnapshotCache.remove(context,it);ExpenseWidgetUpdater.remove(it)}
        if(ExpenseWidgetProvider.ids(context).isEmpty())ExpenseWidgetUpdater.stopWhenIdle()
    }
    override fun onRestored(context:Context,oldWidgetIds:IntArray,newWidgetIds:IntArray) {
        if(oldWidgetIds.size!=newWidgetIds.size || (oldWidgetIds+newWidgetIds).any {it<=0})return
        ExpenseWidgetConfigStore(context).restore(oldWidgetIds,newWidgetIds)
        oldWidgetIds.filter {it !in newWidgetIds}.forEach {ExpenseWidgetSnapshotCache.remove(context,it);ExpenseWidgetUpdater.remove(it)}
        ExpenseWidgetUpdater.request(context,newWidgetIds,goAsync())
    }
    override fun onDisabled(context:Context) {ExpenseWidgetUpdater.stopWhenIdle()}

    companion object {
        const val SWITCH="net.ankio.ledger.widget.SWITCH"
        const val REFRESH="net.ankio.ledger.widget.REFRESH"
        const val PREV="net.ankio.ledger.widget.PREV"
        const val NEXT="net.ankio.ledger.widget.NEXT"
        const val PERIOD="expensePeriod"
        fun ids(context:Context):IntArray=AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ExpenseWidgetProvider::class.java))
        private fun actionSlug(action:String)=when(action){
            SWITCH->"switch"
            PREV->"prev"
            NEXT->"next"
            else->"refresh"
        }
        fun actionIntent(context:Context,id:Int,action:String,period:ExpensePeriod):Intent=Intent(context,ExpenseWidgetProvider::class.java)
            .setAction(action).setData(Uri.parse("ledger-widget://$id/${actionSlug(action)}/${period.name}"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra(PERIOD,period.name)
        fun validAction(context:Context,intent:Intent,id:Int):Boolean {
            if(id<=0 || id !in ids(context) || intent.action !in setOf(SWITCH,REFRESH,PREV,NEXT))return false
            val period=runCatching {ExpensePeriod.valueOf(intent.getStringExtra(PERIOD) ?: "")}.getOrNull() ?: return false
            return intent.data==actionIntent(context,id,intent.action!!,period).data
        }
        fun button(context:Context,id:Int,action:String,period:ExpensePeriod):PendingIntent=PendingIntent.getBroadcast(context,id,
            actionIntent(context,id,action,period),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun open(context:Context,id:Int,period:ExpensePeriod):PendingIntent=PendingIntent.getActivity(context,id,
            Intent(context,MainActivity::class.java).setAction(MainActivity.OPEN_STATISTICS)
                .setData(Uri.parse("ledger-statistics://widget/$id/${period.name}")).putExtra(PERIOD,period.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
