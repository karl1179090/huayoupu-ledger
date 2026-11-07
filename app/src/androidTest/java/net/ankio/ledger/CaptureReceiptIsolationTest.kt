package net.ankio.ledger

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.Source
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaptureReceiptIsolationTest {
    @Test fun cleanupKeepsEntryAndConcurrentForeignReceiptsEvenWhenANewTaskReusesAnEntryEvent() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val suffix=UUID.randomUUID().toString()
        val event="payment_page:$suffix:"+"1".repeat(64)
        val eventToken="capture-event:$event"
        val entryToken="synthetic-receipt-entry:$suffix"
        val laterToken="synthetic-receipt-later:$suffix"
        val taskIds=mutableListOf<Long>()
        val ownTokens=mutableListOf(eventToken,entryToken,laterToken)
        fun insert(token:String,kind:String) = synchronized(app.store) {
            app.store.writableDatabase.insertOrThrow("wallet_submissions",null,ContentValues().apply {
                put("token",token);put("kind",kind);put("result_uid","synthetic:$suffix");put("created_at",1L)
            })
        }
        fun exists(token:String) = synchronized(app.store) {
            app.store.readableDatabase.rawQuery("SELECT 1 FROM wallet_submissions WHERE token=?",arrayOf(token)).use { it.moveToFirst() }
        }
        try {
            insert(eventToken,"CAPTURE_EVENT_RESULT")
            insert(entryToken,"MANUAL")
            // The UI task snapshot is capped at 200; cleanup ownership must use all entry tasks.
            repeat(201) { app.tasks.create(Source.OCR,"synthetic:entry").also(taskIds::add) }
            val isolation=CaptureReceiptIsolation(app)
            assertEquals(true,isolation.entryTaskIds.containsAll(taskIds))
            val task=app.tasks.create(Source.OCR,"payment_auto:com.tencent.mm").also(taskIds::add)
            app.tasks.diagnose(task,"payment_identity",event)
            val operation=requireNotNull(app.tasks.operationKey(task)).also(ownTokens::add)
            insert(operation,"CAPTURE_RESULT")
            insert(laterToken,"BILL_IMPORT")
            assertEquals(listOf(task),isolation.createdTaskIds())
            isolation.clean(isolation.createdTaskIds())
            assertEquals(false,exists(operation))
            listOf(eventToken,entryToken,laterToken).forEach { assertEquals("Preserve unrelated receipt $it",true,exists(it)) }
        } finally {
            taskIds.forEach(app.tasks::delete)
            synchronized(app.store) {
                ownTokens.forEach { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) }
            }
        }
    }
}
