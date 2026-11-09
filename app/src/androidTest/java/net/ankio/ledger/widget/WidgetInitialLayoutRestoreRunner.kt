package net.ankio.ledger.widget

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit emulator restoration only. The ordinary AndroidJUnitRunner is unchanged. */
class WidgetInitialLayoutRestoreRunner : Instrumentation() {
    private val applicationAttached=CountDownLatch(1)
    private var applicationOnCreateSkipped=false
    private lateinit var restoreStep:String

    override fun onCreate(arguments:Bundle?) {
        require(arguments?.getString("restoreToken")=="widget-original-initial-2718b28e-fbe8-4286-8a25-15a140c80029")
        require(arguments.getString("restorePackage")=="net.ankio.ledger")
        require(arguments.getString("restoreWidgetId")=="2")
        restoreStep=requireNotNull(arguments.getString("restoreStep"))
        require(restoreStep in setOf("clear-host","initial"))
        require(Build.VERSION.SDK_INT==34 && Build.MODEL.contains("sdk_gphone"))
        super.onCreate(arguments)
        start()
    }

    override fun callApplicationOnCreate(app:Application) {
        check(app.packageName=="net.ankio.ledger")
        // Intentionally do not call LedgerApp.onCreate: it starts asynchronous widget
        // publications that can overwrite the preserved initial-layout host state.
        applicationOnCreateSkipped=true
        applicationAttached.countDown()
    }

    override fun onStart() {
        val result=Bundle()
        try {
            check(applicationAttached.await(10,TimeUnit.SECONDS) && applicationOnCreateSkipped)
            val context=targetContext
            check(context.packageName=="net.ankio.ledger")
            val digest=MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use {input->
                val buffer=ByteArray(8192)
                while(true) {val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
            }
            val apkHash=digest.digest().joinToString("") {"%02x".format(it)}
            check(apkHash=="240455881245eeb2c490031c2f2ea7979f5ebb784c2ea3e0af0c2ad6afc1111e")
            val manager=AppWidgetManager.getInstance(context)
            val provider=ComponentName("net.ankio.ledger","net.ankio.ledger.widget.ExpenseWidgetProvider")
            val info=requireNotNull(manager.getAppWidgetInfo(2))
            check(info.provider==provider && 2 in manager.getAppWidgetIds(provider))
            fun options():String {
                val bundle=manager.getAppWidgetOptions(2)
                return bundle.keySet().sorted().associateWith {bundle.get(it).toString()}.toString()
            }
            val before=options()
            // An action-free RemoteViews with the same layout ID can reapply onto old
            // text/children. The external driver must observe this different layout
            // before requesting the original initial layout in a second invocation.
            val views=if(restoreStep=="clear-host") {
                RemoteViews("android",android.R.layout.simple_list_item_1).apply {
                    setContentDescription(android.R.id.text1,"widget-restore-clear-host-2718b28e")
                }
            } else RemoteViews(context.packageName,info.initialLayout)
            manager.updateAppWidget(2,views)
            check(options()==before)
            result.putBoolean("applicationOnCreateSkipped",true)
            result.putString("provider",provider.flattenToShortString())
            result.putInt("widgetId",2)
            result.putString("options",before)
            result.putString("restoreStep",restoreStep)
            result.putString("stream","HOST_LAYOUT_UPDATED_WITHOUT_APPLICATION_ONCREATE\n")
            finish(Activity.RESULT_OK,result)
        } catch(failure:Throwable) {
            result.putString("stream",failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
