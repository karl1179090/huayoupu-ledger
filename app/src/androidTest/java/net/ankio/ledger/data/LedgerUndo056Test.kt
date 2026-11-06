package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerUndo056Test {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="undo056-${UUID.randomUUID()}.db"
    private val images=ScreenshotStore(context,"undo-images-${UUID.randomUUID()}")
    private var store=LedgerStore(context,name,images).prepareSyntheticDefaultWallet()
    private val now=System.currentTimeMillis()
    private val capture=Capture(Source.OCR,"com.eg.android.AlipayGphone","交易号 UNDO-ORDER-0001",now)
    private val bill=ParsedBill(1000,Direction.EXPENSE,"测试店铺","购物",now,"UNDO-ORDER-0001",true,"",true)
    private val image=byteArrayOf(1,2,3)
    private fun record()=(store.ingest(capture,bill,screenshot=image) as IngestResult.Saved).record
    @After fun cleanup() { store.purgeExpiredDeletes(Long.MAX_VALUE);store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name);images.close() }

    @Test fun deletedBillImmediatelyLeavesTotalsAndReturnsWithSameEvidenceOnUndo() {
        val record=record()
        assertTrue(store.softDelete(record.id,now));assertNull(store.get(record.id));assertTrue(store.all().isEmpty())
        assertTrue(store.posted(now-1,now+1).isEmpty())
        assertArrayEquals(image,store.screenshot(record.id));assertEquals(1,store.captureHistory(record.id).size)
        images.cleanup(maxBytes=0);assertArrayEquals(image,store.screenshot(record.id))
        assertTrue(store.undoDelete(record.id,now+1000));assertEquals(record.uid,store.get(record.id)!!.uid)
        assertArrayEquals(image,store.historyScreenshot(record.id,1));assertEquals(1,store.all().size)
        assertFalse(store.undoDelete(record.id,now+2000))
    }

    @Test fun restartRetainsDeadlineAndExpiryPurgesAttachments() {
        val id=record().id;store.softDelete(id,now);store.close();store=LedgerStore(context,name,images)
        assertEquals(DeletedRecord(id,now+6000),store.pendingDeletion(now+1000));assertNull(store.get(id))
        assertFalse(store.undoDelete(id,now+6000));assertNull(store.pendingDeletion(now+6000))
        assertEquals(listOf(id),store.purgeExpiredDeletes(now+6000))
        assertTrue(store.captureHistory(id).isEmpty());assertNull(store.screenshot(id));assertTrue(store.purgeExpiredDeletes(now+7000).isEmpty())
    }

    @Test fun editingStaleRecordCannotResurrectDeletionOrChangeItsEvidence() {
        val record=record()
        assertTrue(store.softDelete(record.id,now))
        try { store.saveEdited(record.copy(note="过期编辑"));fail("deleted record edited") }
        catch(_:IllegalStateException) { }
        assertNull(store.get(record.id))
        assertArrayEquals(image,store.historyScreenshot(record.id,1))
        assertTrue(store.undoDelete(record.id,now+1))
        val restored=store.get(record.id)!!
        assertEquals(record.copy(wallet=restored.wallet),restored)
        assertEquals(record.wallet!!.appliedCents,restored.wallet!!.appliedCents)
        assertTrue(restored.wallet.revision>record.wallet.revision)
    }

    @Test fun recognitionDuringUndoWindowCannotCreateCompetingBill() {
        val id=record().id;store.softDelete(id,now)
        assertTrue(store.ingest(capture.copy(text="详情 交易号 UNDO-ORDER-0001",receivedAt=now+1000),bill,screenshot=byteArrayOf(4)) is IngestResult.Skipped)
        assertTrue(store.all().isEmpty());assertTrue(store.undoDelete(id,now+1000));assertEquals(1,store.all().size)
    }

    @Test fun undoWindowAllowsDifferentRealOrderAndEachDeletionHasIndependentDeadline() {
        val first=record().id;store.softDelete(first,now)
        val other=(store.ingest(capture.copy(text="交易号 UNDO-ORDER-0002"),bill.copy(transactionId="UNDO-ORDER-0002")) as IngestResult.Saved).record
        assertEquals(1,store.all().size);assertTrue(store.softDelete(other.id,now+1000))
        assertEquals(other.id,store.pendingDeletion(now+1000)!!.id)
        assertEquals(listOf(first),store.purgeExpiredDeletes(now+6000));assertTrue(store.undoDelete(other.id,now+6000))
        assertEquals(other.id,store.all().single().id)
    }
}
