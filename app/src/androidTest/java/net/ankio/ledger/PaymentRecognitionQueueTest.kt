package net.ankio.ledger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.SyntheticRecognitionBackend
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.PaymentRecognitionQueue
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic pictures, in-process fake model only. Never exercise payment/credentials on a phone. */
@RunWith(AndroidJUnit4::class)
class PaymentRecognitionQueueTest {
    @get:org.junit.Rule val wallet=CaptureWalletFixture()
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private lateinit var app:LedgerApp
    private lateinit var queue:PaymentRecognitionQueue
    private val permission=AtomicBoolean(true)
    private val tasks=mutableListOf<Long>()
    private val barriers=mutableListOf<CompletableDeferred<Unit>>()
    private val merchantPrefix="队列测试${UUID.randomUUID().toString().take(8)}"
    private var savedAi:Map<String,*> = emptyMap<String,Any>()
    private var selected=emptySet<String>()
    private var automatic=false
    private var shake=false
    private var initialized=false

    @Before fun setup() {
        assumeTrue("Only synthetic emulator tests",Build.FINGERPRINT.startsWith("google/sdk_gphone") || Build.MODEL.contains("sdk_gphone"))
        app=ApplicationProvider.getApplicationContext()
        runBlocking { withTimeout(10_000) { app.taskRecovery.await() } }
        // Do not take over somebody else's pending work, even on an emulator.
        assumeTrue("Existing queue must finish first",app.captureQueue.size()==0 && app.tasks.pendingQueued().isEmpty())
        savedAi=app.getSharedPreferences("remote_ai",Context.MODE_PRIVATE).all.toMap()
        selected=app.channels.selectedAutoPackages();automatic=app.channels.automaticEnabled()
        shake=app.channels.enabled(ChannelSettings.Channel.OCR)
        initialized=true
        app.channels.setAutomaticEnabled(false)
        PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,false) }
        app.channels.setAutoPackage(WECHAT,true);app.channels.setAutomaticEnabled(true)
        app.channels.set(ChannelSettings.Channel.OCR,true)

        queue=PaymentRecognitionQueue(app) { permission.get() }
    }

    @After fun cleanup()=runBlocking {
        if(!initialized)return@runBlocking
        // Stop automatic resume before releasing fake transports; no default network factory may run.
        try {
            permission.set(false)
            if(::queue.isInitialized)queue.cancelAll()
            barriers.forEach { it.complete(Unit) }
            if(::queue.isInitialized)await { queue.size()==0 }
        } finally {
            app.store.all().filter { it.bill.merchant.startsWith(merchantPrefix) }.forEach { app.store.delete(it.id) }
            tasks.forEach { id ->
                app.tasks.paymentIdentity(id)?.let { app.store.writableDatabase.delete("capture_events","event_key=?",arrayOf(it)) }
                app.tasks.delete(id)
            }
            app.channels.setAutomaticEnabled(false)
            PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in selected) }
            app.channels.setAutomaticEnabled(automatic);app.channels.set(ChannelSettings.Channel.OCR,shake)
            val edit=app.getSharedPreferences("remote_ai",Context.MODE_PRIVATE).edit().clear()
            for((key,value) in savedAi)when(value) {
                is String->edit.putString(key,value)
                is Boolean->edit.putBoolean(key,value)
                is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value)
                is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            assertTrue(edit.commit());app.changed()
        }
    }

    @Test fun secondCaptureQueuesDurablyWhileFirstModelResponseIsBlocked()=runBlocking {
        val fake=FakeModel()
        val first=newTask();assertTrue(queue.enqueue(first,WECHAT,true,fake.factory))
        withTimeout(10_000){fake.entered.await()}
        val second=newTask();assertTrue(queue.enqueue(second,WECHAT,true,fake.factory))
        assertEquals(2,queue.size());assertEquals(1,fake.calls.get())
        assertEquals("RECOGNIZING",app.tasks.status(first));assertEquals("QUEUED",app.tasks.status(second))
        assertNotNull(app.tasks.image(second))
        assertTrue(app.tasks.snapshot().let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }
            .any { it.getLong("id")==second && it.getBoolean("hasScreenshot") } })
        fake.release.complete(Unit)
        await { queue.size()==0 }
        assertEquals(2,fake.calls.get())
        for(id in listOf(first,second)) {
            assertEquals("SUCCEEDED",app.tasks.status(id))
            val row=task(id);val record=app.store.get(row.getLong("recordId"))!!
            assertEquals(RecordState.POSTED,record.state);assertNotNull(app.store.screenshot(record.id))
        }
    }

    @Test fun revokingApplicationCancelsInflightAndQueuedWithoutPostingALateResponse()=runBlocking {
        val fake=FakeModel(ignoreTransportCancellation=true)
        val first=newTask();assertTrue(queue.enqueue(first,WECHAT,true,fake.factory))
        withTimeout(10_000){fake.entered.await()}
        val second=newTask();assertTrue(queue.enqueue(second,WECHAT,true,fake.factory))
        app.channels.setAutoPackage(WECHAT,false);queue.cancelAutomatic(WECHAT)
        fake.release.complete(Unit)
        await { queue.size()==0 }
        assertEquals(1,fake.calls.get())
        assertEquals("CANCELLED",app.tasks.status(first));assertEquals("CANCELLED",app.tasks.status(second))
        assertTrue(ownRecords().isEmpty());assertNull(app.tasks.image(first));assertNull(app.tasks.image(second))
    }

    @Test fun disablingShakeLeavesAutomaticTransactionRunningAndOnlyCancelsShakeEntry()=runBlocking {
        val fake=FakeModel()
        val automaticTask=newTask();assertTrue(queue.enqueue(automaticTask,WECHAT,true,fake.factory))
        withTimeout(10_000){fake.entered.await()}
        val shakeTask=newTask(automatic=false);assertTrue(queue.enqueue(shakeTask,whitelist.packageName,false,fake.factory))
        app.channels.set(ChannelSettings.Channel.OCR,false);queue.cancelShake()
        await { app.tasks.status(shakeTask)=="CANCELLED" }
        assertEquals("RECOGNIZING",app.tasks.status(automaticTask))
        assertTrue(app.channels.autoAllowed(WECHAT))
        fake.release.complete(Unit);await { queue.size()==0 }
        assertEquals("SUCCEEDED",app.tasks.status(automaticTask));assertEquals(1,fake.calls.get())
        assertEquals(1,ownRecords().size)
    }

    @Test fun boundedQueueRejectsNinthAndDoesNotUploadRejectedCapture()=runBlocking {
        val fake=FakeModel()
        val first=newTask();assertTrue(queue.enqueue(first,WECHAT,true,fake.factory))
        withTimeout(10_000){fake.entered.await()}
        repeat(PaymentRecognitionQueue.MAX_PENDING-1) { assertTrue(queue.enqueue(newTask(),WECHAT,true,fake.factory)) }
        assertEquals(8,queue.size());assertFalse(queue.hasCapacity())
        val rejected=newTask()
        assertFalse(queue.enqueue(rejected,WECHAT,true,fake.factory))
        assertEquals(8,queue.size());assertEquals(1,fake.calls.get())
        // finally.resume scans durable entries. The caller must discard an entry rejected by the bound.
        app.tasks.delete(rejected);tasks.remove(rejected)
        app.channels.setAutoPackage(WECHAT,false);queue.cancelAutomatic(WECHAT)
        fake.release.complete(Unit);await { queue.size()==0 }
        assertEquals(1,fake.calls.get());assertTrue(ownRecords().isEmpty());assertTrue(queue.hasCapacity())
    }

    @Test fun missingCapturePermissionCancelsBeforeCallingModel()=runBlocking {
        permission.set(false)
        val fake=FakeModel();val id=newTask()
        assertTrue(queue.enqueue(id,WECHAT,true,fake.factory))
        await { queue.size()==0 }
        assertEquals(0,fake.calls.get());assertEquals("CANCELLED",app.tasks.status(id))
        assertTrue(ownRecords().isEmpty())
    }

    @Test fun startupRecoveryGatesResumeAndExplicitEnqueue()=runBlocking {
        val recovery=CompletableDeferred<Unit>()
        queue=PaymentRecognitionQueue(app,recovery) { permission.get() }
        val fake=FakeModel();fake.release.complete(Unit)
        val id=newTask()
        queue.resume(fake.factory)
        assertEquals(0,queue.size());assertEquals(0,fake.calls.get())
        assertTrue(queue.enqueue(id,WECHAT,true,fake.factory))
        delay(100)
        assertEquals("QUEUED",app.tasks.status(id));assertEquals(0,fake.calls.get())
        recovery.complete(Unit)
        await { queue.size()==0 }
        assertEquals(1,fake.calls.get());assertEquals("SUCCEEDED",app.tasks.status(id))
    }

    @Test fun recoveryFailureDoesNotLeaveAnEnqueuedWorkerWaitingForever()=runBlocking {
        val recovery=CompletableDeferred<Unit>()
        queue=PaymentRecognitionQueue(app,recovery) { permission.get() }
        val fake=FakeModel();val id=newTask()
        assertTrue(queue.enqueue(id,WECHAT,true,fake.factory))
        recovery.completeExceptionally(java.io.IOException("synthetic recovery failure"))
        await { queue.size()==0 }
        assertEquals(0,fake.calls.get());assertEquals("FAILED",app.tasks.status(id))
        queue.resume(fake.factory);assertEquals(0,queue.size())
    }

    @Test fun legacyPostedFrameInSameWindowSkipsModelButIdenticalFrameInNewWindowIsIndependent()=runBlocking {
        val merchant="$merchantPrefix-legacy"
        val saved=app.store.ingest(net.ankio.ledger.core.Capture(Source.OCR,WECHAT,"合成旧账单"),
            net.ankio.ledger.core.ParsedBill(1234,net.ankio.ledger.core.Direction.EXPENSE,merchant,"其他",System.currentTimeMillis(),null,true,"",false)) as net.ankio.ledger.data.IngestResult.Saved
        val old=newTask();app.tasks.diagnose(old,"window","$WECHAT; id=12345; automatic=true")
        app.tasks.update(old,"SUCCEEDED","已记账",saved.record.id)
        fun restoredTask(window:Int,hash:Char):Long {
            val id=newTask();app.tasks.diagnose(id,"window","$WECHAT; id=$window; automatic=true")
            // Simulate the native identity surviving an on-disk queued restart.
            val token=UUID.randomUUID().toString().replace("-","").padEnd(64,hash)
            val diagnostic=task(id).getJSONObject("diagnostic").put("payment_identity","payment_page:1:$token")
            app.tasks.writableDatabase.execSQL("UPDATE tasks SET diagnostic=? WHERE id=?",arrayOf(diagnostic.toString(),id))
            return id
        }
        val fake=FakeModel();fake.release.complete(Unit)
        val same=restoredTask(12345,'a');assertTrue(queue.enqueue(same,WECHAT,true,fake.factory))
        await { queue.size()==0 }
        assertEquals("A frame already posted by the old app must not be uploaded again",0,fake.calls.get())
        assertEquals("DUPLICATE",app.tasks.status(same));assertEquals(1,ownRecords().size)
        assertEquals("已记账 ¥12.34",app.tasks.message(same))
        assertEquals(saved.record.id,task(same).getLong("recordId"))
        // A worker removes itself before its finally.resume() has returned. Let that
        // recovery pass finish before publishing the next synthetic queued task.
        delay(200)
        val next=restoredTask(12346,'b');assertTrue(queue.enqueue(next,WECHAT,true,fake.factory))
        await { queue.size()==0 }
        assertEquals("New window task=${task(next)}",1,fake.calls.get());assertEquals("SUCCEEDED",app.tasks.status(next))
        assertEquals(2,ownRecords().size)
    }

    private fun newTask(automatic:Boolean=true):Long {
        val bitmap=Bitmap.createBitmap(80,160,Bitmap.Config.ARGB_8888)
        val image=try { bitmap.eraseColor(Color.WHITE);app.recognizer.prepareScreenshot(bitmap) } finally { bitmap.recycle() }
        val pkg=if(automatic)WECHAT else whitelist.packageName
        val authorization=if(automatic)null else app.channels.shakeAuthorization(pkg)!!
        val id=app.tasks.create(Source.OCR,"${if(automatic)"payment_auto" else "shake"}:$pkg",authorization)
        tasks.add(id);assertTrue(app.tasks.queued(id,image));return id
    }
    private fun task(id:Long):JSONObject = app.tasks.snapshot().let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.first { it.getLong("id")==id }
    }
    private fun ownRecords()=app.store.all().filter { it.bill.merchant.startsWith(merchantPrefix) }
    private suspend fun await(check:()->Boolean) { withTimeout(10_000) { while(!check())delay(25) } }

    private inner class FakeModel(private val ignoreTransportCancellation:Boolean=false) {
        val calls=AtomicInteger()
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>().also { barriers.add(it) }
        val factory:()->CaptureProcessor = { CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,payload,_ ->
            assertNotNull(payload);val index=calls.incrementAndGet();entered.complete(Unit)
            if(ignoreTransportCancellation)withContext(NonCancellable){release.await()} else release.await()
            val merchant="$merchantPrefix-$index"
            val time=LocalDateTime.now().withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
            val order="queue-${UUID.randomUUID()}"
            val bill=JSONObject().put("is_bill",true).put("amount","12.34").put("direction","expense")
                .put("merchant",merchant).put("category","购物").put("occurred_at",time).put("transaction_id",order)
                .put("reliable",true).put("merchant_icon",JSONObject.NULL)
                .put("raw_text","微信支付成功 $merchant 支付金额12.34元 支付时间$time 交易单号$order")
            JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
        })) }
    }
    companion object { private const val WECHAT="com.tencent.mm" }
}
