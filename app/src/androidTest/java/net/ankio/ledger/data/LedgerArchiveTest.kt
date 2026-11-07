package net.ankio.ledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LedgerArchiveTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val stores=mutableListOf<Pair<String,LedgerStore>>()
    private val cache=File(context.cacheDir,"archive-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val at=1_790_000_000_000L
    private fun store():LedgerStore {
        val name="archive-test-${UUID.randomUUID()}.db"
        return LedgerStore(context,name).also { stores.add(name to it) }
    }
    private fun record(merchant:String="合成归档商户")=LedgerRecord(capture=Capture(Source.OCR,"com.taobao.taobao","合成实付款22.50",at),
        bill=ParsedBill(2250,Direction.EXPENSE,merchant,"购物",at,"synthetic-${UUID.randomUUID()}",true,"",true),state=RecordState.POSTED)
    private fun image(seed:Int=1,width:Int=24,height:Int=24):ByteArray {
        val random=java.util.Random(seed.toLong())
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width*height) { Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) },0,width,0,0,width,height)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle() }.toByteArray()
    }
    private fun archive(source:LedgerStore):ByteArray=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
    private fun entries(bytes:ByteArray):LinkedHashMap<String,ByteArray> {
        val result=linkedMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while(true) {
            val entry=zip.nextEntry ?: break
            result[entry.name]=zip.readBytes()
        } }
        return result
    }
    private fun zip(entries:Map<String,ByteArray>)=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
    } }.toByteArray()
    private fun rejected(bytes:ByteArray) {
        try { LedgerArchive.read(ByteArrayInputStream(bytes),cache).use { };fail("invalid archive accepted") }
        catch(_:Exception) { }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @After fun close() {
        stores.forEach { (name,store) -> store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name) }
        cache.listFiles()?.forEach { file -> file.listFiles()?.forEach { it.delete() };file.delete() };cache.delete()
    }

    @Test fun zipDeduplicatesImagesAndRestoresUidIconHistoryWithoutOverwritingEditsOrPermissions() {
        val source=store();val target=store();val shared=image();val icon=image(8)
        val original=record();val other=record("另一个归档商户")
        val account=LedgerAccount(name="归档银行卡",amountCents=123456,kind="bank_icbc",bankCode="icbc")
        val category=CustomCategory("归档自定义分类",Direction.EXPENSE)
        source.merge(listOf(original,other),listOf(account),listOf(category));val saved=source.all().associateBy { it.uid }
        listOf(original,other).forEach { r -> source.restoreHistory(saved.getValue(r.uid).id,listOf(
            RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",2250,0,"原始截图",true),shared))) }
        source.restoreMerchantIcon(saved.getValue(original.uid).id,icon)
        val channels=ChannelSettings(context);val enabled=channels.enabled(ChannelSettings.Channel.OCR)
        val automatic=channels.automaticEnabled();val applications=channels.selectedAutoPackages()
        val encoded=archive(source)
        assertEquals(2,entries(encoded).keys.count { it.startsWith("images/") })
        LedgerArchive.read(ByteArrayInputStream(encoded),cache).use { prepared ->
            assertEquals(2,prepared.recordCount);assertFalse(prepared.legacy)
            assertEquals(2,LedgerArchive.restore(target,prepared))
            val restored=target.all().associateBy { it.uid }
            assertEquals(setOf(original.uid,other.uid),restored.keys)
            assertEquals(account,target.accounts().single().copy(id=0))
            assertEquals(listOf(category),target.customCategories())
            assertArrayEquals(shared,target.historyScreenshot(restored.getValue(original.uid).id,1))
            assertArrayEquals(icon,target.merchantIcon(restored.getValue(original.uid).id))
            target.saveEdited(restored.getValue(original.uid).copy(note="本机修改"))
            assertEquals(0,LedgerArchive.restore(target,prepared))
            assertEquals("本机修改",target.get(restored.getValue(original.uid).id)!!.note)
        }
        assertEquals(enabled,channels.enabled(ChannelSettings.Channel.OCR))
        assertEquals(automatic,channels.automaticEnabled());assertEquals(applications,channels.selectedAutoPackages())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun attachmentFailureRollsBackImportedRecordsAndPreservesExistingData() {
        val source=store();val target=store();source.merge(listOf(record()));target.merge(listOf(record("保留账单")))
        val saved=source.all().single()
        source.restoreHistory(saved.id,listOf(RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",2250,0,"截图",true),image())))
        LedgerArchive.read(ByteArrayInputStream(archive(source)),cache).use { prepared ->
            prepared.files.values.first().writeBytes(byteArrayOf(1,2,3))
            try { LedgerArchive.restore(target,prepared);fail("damaged attachment accepted") }
            catch(_:IllegalArgumentException) { }
            assertEquals("保留账单",target.all().single().bill.merchant)
            assertTrue(target.all().none { it.uid==saved.uid })
        }
    }

    @Test fun moreThanLegacySixteenMiBRestoresFromDiskWithoutAggregatingAttachments() {
        val source=store();val target=store();val original=record();source.merge(listOf(original));val saved=source.all().single()
        // One bill may retain many captures: exercise the provider overload, not a per-bill image list.
        val history=(1..8).map { sequence -> RestoredCaptureHistory(RecordCaptureHistory(sequence,at+sequence,
            if(sequence==1)"payment" else "payment_supplement",2250,0,"截图$sequence",false)) }
        source.restoreHistory(saved.id,history) { image(it.entry.sequence,960,960) }
        val encoded=archive(source)
        assertTrue(encoded.size>LedgerBackup.MAX_BYTES)
        LedgerArchive.read(ByteArrayInputStream(encoded),cache).use { prepared ->
            assertTrue(prepared.backup.histories.values.flatten().all { it.image==null })
            assertEquals(1,LedgerArchive.restore(target,prepared))
            assertEquals(8,target.captureHistory(target.all().single().id).size)
            assertNotNull(target.historyScreenshot(target.all().single().id,8))
        }
    }

    @Test fun oldJsonIsAcceptedAndDoesNotChangeCaptureConfiguration() {
        val source=store();val target=store();source.merge(listOf(record()))
        val channels=ChannelSettings(context);val before=channels.enabled(ChannelSettings.Channel.OCR)
        val json=LedgerBackup.encode(source,channels).toByteArray()
        LedgerArchive.read(ByteArrayInputStream(json),cache).use { prepared ->
            assertTrue(prepared.legacy);assertEquals(1,LedgerArchive.restore(target,prepared))
        }
        assertEquals(before,channels.enabled(ChannelSettings.Channel.OCR))
    }

    @Test fun damagedMissingAndOrphanImagesAndTruncatedOrTraversalArchivesAreRejectedBeforeRestore() {
        val source=store();source.merge(listOf(record()));val saved=source.all().single()
        source.restoreHistory(saved.id,listOf(RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",2250,0,"截图",true),image())))
        val good=archive(source);val original=entries(good);val key=original.keys.first { it.startsWith("images/") }
        rejected(zip(LinkedHashMap(original).apply { this[key]=byteArrayOf(9,8,7) }))
        rejected(zip(LinkedHashMap(original).apply { remove(key) }))
        rejected(zip(LinkedHashMap(original).apply { this["../escape"]=byteArrayOf(1) }))
        rejected(good.copyOf(good.size-22))
        rejected(zip(LinkedHashMap(original).apply {
            val bytes=image(999);val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            this["images/$hash.bin"]=bytes
        }))
        rejected(zip(LinkedHashMap(original).apply { this[key]=ByteArray(LedgerArchive.MAX_IMAGE_BYTES+1) }))
        assertEquals(1,source.all().size)
    }

    @Test fun inconsistentRefundHistoryIsRejectedDuringPreparationAndPreservesTarget() {
        val source=store();val target=store();source.merge(listOf(record()));target.merge(listOf(record("保留账单")))
        val saved=source.all().single()
        source.restoreHistory(saved.id,listOf(RestoredCaptureHistory(RecordCaptureHistory(1,at,"payment",2250,0,"无图",false))))
        val entries=entries(archive(source));val manifest=JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONObject("ledger").getJSONObject("histories").getJSONArray(saved.uid).getJSONObject(0).put("sequence",2)
        entries["manifest.json"]=manifest.toString().toByteArray()
        rejected(zip(entries))
        assertEquals("保留账单",target.all().single().bill.merchant)
    }
}
