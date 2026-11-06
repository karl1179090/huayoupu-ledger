package net.ankio.ledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native output picker callbacks write real synthetic templates/error files; no document-provider data is read. */
@RunWith(AndroidJUnit4::class)
class NativeFileExportAcceptanceTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun deliver(activity:MainActivity,request:Int,file:File) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            .apply {isAccessible=true}.invoke(activity,request,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(60)}
        assertTrue("Native export did not finish",check())
    }
    @Test fun pickerCallbacksProduceReadableTemplatesAndOnlyRowAndReasonInErrorExports() {
        val suffix=UUID.randomUUID().toString();val files=(0..3).map {File(app.cacheDir,"synthetic-independent-export-$suffix-$it.csv")}
        val before=app.store.all().map {it.uid}.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready()
                scenario.onActivity {deliver(it,19,files[0])}
                waitFor {files[0].length()>0};Thread.sleep(100)
                val bills=files[0].inputStream().use(LedgerCsvImport::read)
                assertEquals("ledger-v2",bills.schema);assertTrue(bills.errors.isEmpty());assertEquals(Direction.TRANSFER,bills.records.single().bill.direction)
                scenario.onActivity {deliver(it,20,files[1])}
                waitFor {files[1].length()>0};Thread.sleep(100)
                val wallets=files[1].inputStream().use(WalletCsvImport::read)
                assertTrue(wallets.errors.isEmpty());assertTrue(wallets.rows.isNotEmpty())
                val now=System.currentTimeMillis();val record=LedgerRecord(capture=Capture(Source.MANUAL,"synthetic","",now),
                    bill=ParsedBill(10000,Direction.EXPENSE,"SYNTHETIC-PRIVATE-MERCHANT","其他",now,null,true,"",true),state=RecordState.POSTED)
                val text=java.io.ByteArrayOutputStream().also {LedgerCsvExport.write(listOf(record),LedgerCsvExport.Format.GENERAL,it)}.toString(Charsets.UTF_8.name())
                files[2].writeText(text.replaceFirst("\"100.00\"","\"100.001\""))
                scenario.onActivity {deliver(it,MainActivity.CSV_IMPORT_REQUEST,files[2])}
                waitFor {instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("导出错误清单")?.isNotEmpty()==true}
                scenario.onActivity {deliver(it,21,files[3])}
                waitFor {files[3].length()>0};Thread.sleep(100)
                val errorRows=files[3].inputStream().use {PortableCsv.read(it).first}
                assertEquals(listOf("行号","原因"),errorRows.first());assertEquals(2,errorRows.size)
                assertEquals("2",errorRows[1][0]);assertEquals(2,errorRows[1].size)
                val exported=files[3].readText();assertFalse(exported.contains(record.uid));assertFalse(exported.contains("PRIVATE-MERCHANT"))
            }
            assertEquals(before,app.store.all().map {it.uid}.toSet())
        } finally {files.forEach(File::delete)}
    }
}
