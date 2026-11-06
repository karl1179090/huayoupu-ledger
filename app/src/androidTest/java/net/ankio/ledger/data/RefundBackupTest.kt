package net.ankio.ledger.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RefundBackupTest {
    @Test fun restoreOnlyVisitsNewActiveRecordsAndKeepsHiddenRecordsDeleted() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="restore-active-${UUID.randomUUID()}.db";val store=LedgerStore(context,name)
        val now=System.currentTimeMillis()
        fun record()=LedgerRecord(capture=Capture(Source.MANUAL,"manual","",now),
            bill=ParsedBill(1200,Direction.EXPENSE,"合成商户","其他",now,null,true,"",true),state=RecordState.POSTED)
        store.prepareSyntheticDefaultWallet()
        val active=store.saveEdited(record().copy(note="本地修改"))
        val hidden=store.saveEdited(record())
        val fresh=record()
        try {
            assertTrue(store.softDelete(hidden.id,now))
            assertEquals(mapOf(active.uid to active.id),store.activeRecordIdsByUid())
            val visited=mutableListOf<LedgerRecord>()
            val backup=ValidatedBackup(listOf(active.copy(note="旧备注"),hidden,fresh),emptyMap())
            assertEquals(1,LedgerBackup.restore(store,backup) { visited.add(it) })
            assertEquals(listOf(fresh.uid),visited.map { it.uid })
            assertEquals("本地修改",store.get(active.id)!!.note)
            assertNull(store.get(hidden.id))
            assertEquals(0,LedgerBackup.restore(store,backup) { fail("existing record visited") })
            assertTrue(store.undoDelete(hidden.id,now+1))
            assertEquals(hidden.copy(wallet=store.get(hidden.id)!!.wallet),store.get(hidden.id))
            assertTrue(store.get(hidden.id)!!.wallet!!.revision>hidden.wallet!!.revision)
        } finally { store.purgeExpiredDeletes(Long.MAX_VALUE);store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name) }
    }

    @Test fun laterInvalidHistoryRollsBackEarlierImportedImageReferences() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="refund-batch-${UUID.randomUUID()}.db";val store=LedgerStore(context,name)
        val images=ScreenshotStore.get(context);val before=images.stats().getInt("count")
        val at=1_792_302_196_000L
        fun record()=LedgerRecord(capture=Capture(Source.OCR,"com.tencent.mm","synthetic",at),
            bill=ParsedBill(10000,Direction.EXPENSE,"合成商户","其他",at,null,true,"",true),state=RecordState.POSTED)
        val first=record();val second=record()
        val valid=RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",10000,0,"原付款",true),byteArrayOf(8,7,6,5))
        val invalid=RestoredCaptureHistory(valid.entry.copy(sequence=2),byteArrayOf(5,6,7,8))
        try {
            val backup=ValidatedBackup(listOf(first,second),emptyMap(),histories=mapOf(first.uid to listOf(valid),second.uid to listOf(invalid)))
            try { LedgerBackup.restore(store,backup);fail("invalid batch accepted") } catch(_:IllegalArgumentException) { }
            assertTrue(store.all().isEmpty());assertEquals(before,images.stats().getInt("count"))
        } finally { store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name) }
    }

    @Test fun backupRestoresNetAmountBothImagesAndPreservesLaterLocalEdit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val names=listOf("refund-backup-${UUID.randomUUID()}.db","refund-restore-${UUID.randomUUID()}.db")
        val first=LedgerStore(context,names[0]).prepareSyntheticDefaultWallet();val second=LedgerStore(context,names[1])
        val at=1_792_302_196_000L
        val original=ParsedBill(10000,Direction.EXPENSE,"合成退款商户","交通",at,"synthetic-original",true,"",true)
        val image1=byteArrayOf(1,2,3);val image2=byteArrayOf(4,5,6)
        try {
            val saved=first.ingest(Capture(Source.OCR,"com.tencent.mm","原付100.00",at),original,screenshot=image1) as IngestResult.Saved
            first.ingest(Capture(Source.OCR,"com.tencent.mm","已退款63.46",at+1000),original.copy(amountCents=3654,
                refund=RefundInfo("wechat",10000,6346)),screenshot=image2)
            val encoded=LedgerBackup.encode(first,ChannelSettings(context))
            val decoded=LedgerBackup.decode(ByteArrayInputStream(encoded.toByteArray()))
            assertEquals(1,LedgerBackup.restore(second,decoded))
            val restored=second.all().single()
            assertEquals(saved.record.uid,restored.uid);assertEquals(3654L,restored.bill.amountCents)
            assertEquals(listOf(1,2),second.captureHistory(restored.id).map { it.sequence })
            assertArrayEquals(image1,second.historyScreenshot(restored.id,1));assertArrayEquals(image2,second.historyScreenshot(restored.id,2))
            second.saveEdited(restored.copy(note="备份后的本地备注"))
            assertEquals(0,LedgerBackup.restore(second,decoded));assertEquals("备份后的本地备注",second.get(restored.id)!!.note)
            assertEquals(2,second.captureHistory(restored.id).size)
            val tampered=org.json.JSONObject(encoded)
            tampered.getJSONObject("histories").getJSONArray(saved.record.uid).getJSONObject(1).put("refundedCents",9999)
            // A history that contradicts the financial summary must be rejected before writing.
            val invalid=LedgerBackup.decode(ByteArrayInputStream(tampered.toString().toByteArray()))
            val thirdName="refund-invalid-${UUID.randomUUID()}.db";val third=LedgerStore(context,thirdName)
            try { try { LedgerBackup.restore(third,invalid);fail("inconsistent history accepted") } catch(_:IllegalArgumentException) { }
                assertTrue(third.all().isEmpty())
            } finally { third.all().forEach { third.delete(it.id) };third.close();context.deleteDatabase(thirdName) }
        } finally {
            for(store in listOf(first,second)) { store.all().forEach { store.delete(it.id) };store.close() }
            names.forEach(context::deleteDatabase)
        }
    }
}
