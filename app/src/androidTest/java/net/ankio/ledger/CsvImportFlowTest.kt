package net.ankio.ledger

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import net.ankio.ledger.ui.MintDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** A document result must show preview before writing, then confirm through the real native sheet. */
@RunWith(AndroidJUnit4::class)
class CsvImportFlowTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    @Test fun chosenCsvWaitsForConfirmationAndRepeatedImportShowsDuplicateCount() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val at=System.currentTimeMillis()/1000*1000
        val original=LedgerRecord(capture=Capture(Source.MANUAL,"test","",at),
            bill=ParsedBill(9876,Direction.INCOME,"CSV流程验证","工资",at,null,true,"",true),state=RecordState.POSTED)
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"zhiyu-import-test-${UUID.randomUUID()}.csv")
            put(MediaStore.MediaColumns.MIME_TYPE,"text/csv")
        })!!
        try {
            app.contentResolver.openOutputStream(uri)!!.use { LedgerCsvExport.write(listOf(original),LedgerCsvExport.Format.GENERAL,it) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("window.__importMessage='';window.__originalNotify=notify;notify=(message)=>{window.__importMessage=message;window.__originalNotify(message)}")
                fun select():MintDialog {
                    scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                        .apply { isAccessible=true }.invoke(activity,MainActivity.CSV_IMPORT_REQUEST,Activity.RESULT_OK,Intent().setData(uri)) }
                    var preview:MintDialog?=null
                    val deadline=SystemClock.elapsedRealtime()+10000
                    while(preview==null && SystemClock.elapsedRealtime()<deadline) {
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                            if(activity!=null) {
                                val imports=MainActivity::class.java.getDeclaredMethod("getFileImports").apply { isAccessible=true }.invoke(activity)
                                preview=(imports.javaClass.getDeclaredField("sheet").apply { isAccessible=true }.get(imports) as MintDialog?)?.takeIf {it.dialog.isShowing}
                            }
                        }
                        if(preview==null)SystemClock.sleep(30)
                    }
                    return requireNotNull(preview)
                }
                val balances=app.store.accounts().associate {it.uid to it.amountCents}
                val preview=select()
                assertFalse(app.store.all().any { it.uid==original.uid })
                scenario.onActivity {
                    assertTrue(views(preview.content).filterIsInstance<TextView>().any { it.text.toString()=="共 1 行 · 有效 1 · 重复 0 · 错误 0" })
                    views(preview.content).filterIsInstance<Button>().single { it.text.toString()=="确认导入以上 1 笔" }.performClick()
                }
                web.await("window.__importMessage === '已入账 1 笔，待确认 0 笔，重复 0，错误 0'")
                val saved=app.store.all().single { it.uid==original.uid }
                assertEquals(9876L,saved.bill.amountCents)
                assertEquals(WalletEligibility.IMPORT_EXCLUDED,saved.wallet!!.eligibility)
                assertEquals(balances,app.store.accounts().associate {it.uid to it.amountCents})
                val repeated=select()
                scenario.onActivity {
                    assertTrue(views(repeated.content).filterIsInstance<TextView>().any { it.text.toString()=="共 1 行 · 有效 0 · 重复 1 · 错误 0" })
                    assertFalse(views(repeated.content).filterIsInstance<Button>().any { it.text.startsWith("确认导入") })
                    repeated.dismiss()
                }
            }
        } finally {
            app.store.all().filter { it.uid==original.uid }.forEach { app.store.delete(it.id) };app.changed()
            app.contentResolver.delete(uri,null,null)
        }
    }
}
