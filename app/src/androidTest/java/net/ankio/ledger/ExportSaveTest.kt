package net.ankio.ledger

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercise the Activity's real asynchronous document-result save path, not just the serializers. */
@RunWith(AndroidJUnit4::class)
class ExportSaveTest {
    @get:Rule val wallet = CaptureWalletFixture()
    @Test fun selectedDocumentReceivesBothCsvFormatsAndCompleteZip() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val at=System.currentTimeMillis()-70L*86400000
        val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"test","",at),
            bill=ParsedBill(12345,Direction.INCOME,"跨月导出验证","工资",at,null,true,"",true),state=RecordState.POSTED))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("window.__exportMessage='';window.__originalNotify=notify;notify=(message)=>{window.__exportMessage=message;window.__originalNotify(message)}")
                for(code in listOf(MainActivity.CSV_GENERAL_REQUEST,MainActivity.CSV_QIANJI_REQUEST,MainActivity.EXPORT_REQUEST)) {
                    val zip=code==MainActivity.EXPORT_REQUEST
                    val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME,"zhiyu-export-test-${UUID.randomUUID()}.${if(zip)"zip" else "csv"}")
                        put(MediaStore.MediaColumns.MIME_TYPE,if(zip)"application/zip" else "text/csv")
                    })!!
                    try {
                        web.run("window.__exportMessage=''")
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                                .apply { isAccessible=true }.invoke(activity,code,Activity.RESULT_OK,Intent().setData(uri))
                        }
                        web.await("window.__exportMessage.startsWith('${if(zip)"完整备份已导出" else "已导出 "}')")
                        val bytes=app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        if(zip) LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                            assertTrue(prepared.backup.records.any { it.uid==record.uid });assertTrue(prepared.recordCount>0)
                        } else {
                            val csv=bytes.toString(Charsets.UTF_8)
                            assertTrue(csv.startsWith("\uFEFF\"时间\""));assertTrue(csv.contains("123.45"));assertTrue(csv.contains("跨月导出验证"))
                            assertEquals(code==MainActivity.CSV_QIANJI_REQUEST,csv.lineSequence().first().contains("二级分类"))
                            assertTrue(csv.contains("收入"))
                        }
                        web.await("!document.querySelector('#sheet').open")
                        assertEquals("false",web.value("document.querySelector('#sheet').open"))
                    } finally { app.contentResolver.delete(uri,null,null) }
                }
            }
        } finally { app.store.delete(record.id);app.changed() }
    }
}
