package net.ankio.ledger

import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.InstalledAppsRepository
import net.ankio.ledger.capture.PaymentRecognitionQueue
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.core.Source
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.SyntheticRecognitionBackend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Model replies contain no bill. These tests verify cancellation without changing any balance. */
@RunWith(AndroidJUnit4::class)
class ShakeWhitelistQueueTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private lateinit var queue:PaymentRecognitionQueue
    private val permission=AtomicBoolean(true)
    private val ids=mutableListOf<Long>()
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    private var oldShake=false
    private var oldAutomatic=false
    private var oldShakePackages=emptySet<String>()
    private var oldAutoPackages=emptySet<String>()
    private var initialized=false
    private lateinit var firstPackage:String
    private lateinit var secondPackage:String

    @Before fun setup()=runBlocking {
        assumeTrue("Dedicated synthetic emulator only",Build.MODEL.contains("sdk_gphone"))
        withTimeout(10_000) { app.taskRecovery.await() }
        assumeTrue("Do not adopt existing work",app.captureQueue.size()==0 && app.tasks.pendingQueued().isEmpty())
        val rows=InstalledAppsRepository.get(app).page("",0,200,true).getJSONArray("applications")
        val selectable=(0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getBoolean("selectable") }.map { it.getString("packageName") }
        assertTrue("The emulator must provide two selectable installed applications",selectable.size>=2)
        firstPackage=selectable[0];secondPackage=selectable[1]
        oldShake=app.channels.enabled(ChannelSettings.Channel.OCR)
        oldAutomatic=app.channels.automaticEnabled();oldAutoPackages=app.channels.selectedAutoPackages()
        oldShakePackages=app.channels.selectedShakePackages();initialized=true
        oldShakePackages.forEach { app.channels.setShakePackage(it,false) }
        app.channels.setShakePackage(firstPackage,true);app.channels.setShakePackage(secondPackage,true)
        app.channels.set(ChannelSettings.Channel.OCR,true)
        app.channels.setAutoPackage(PaymentApps.WECHAT,true);app.channels.setAutomaticEnabled(true)
        queue=PaymentRecognitionQueue(app) { permission.get() }
    }

    @After fun cleanup()=runBlocking {
        if(!initialized)return@runBlocking
        permission.set(false)
        if(::queue.isInitialized)queue.cancelAll()
        releases.forEach { it.complete(Unit) }
        if(::queue.isInitialized)await { queue.size()==0 }
        ids.forEach(app.tasks::delete)
        app.channels.selectedShakePackages().forEach { app.channels.setShakePackage(it,false) }
        oldShakePackages.forEach { app.channels.setShakePackage(it,true) }
        app.channels.set(ChannelSettings.Channel.OCR,oldShake)
        PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in oldAutoPackages) }
        app.channels.setAutomaticEnabled(oldAutomatic);app.changed()
    }

    @Test fun removingOnePackageCancelsItsNativeAndQueuedShakeButPreservesOtherWork()=runBlocking {
        val model=HeldModel()
        val first=task(firstPackage);assertTrue(queue.enqueue(first,firstPackage,false,model.factory))
        withTimeout(10_000) { model.entered.await() }
        val same=task(firstPackage);assertTrue(queue.enqueue(same,firstPackage,false,model.factory))
        val other=task(secondPackage);assertTrue(queue.enqueue(other,secondPackage,false,model.factory))
        val automatic=task(PaymentApps.WECHAT,true);assertTrue(queue.enqueue(automatic,PaymentApps.WECHAT,true,model.factory))
        app.channels.setShakePackage(firstPackage,false);queue.cancelShake(firstPackage)
        model.release.complete(Unit)
        await { queue.size()==0 }
        assertEquals("CANCELLED",app.tasks.status(first));assertEquals("CANCELLED",app.tasks.status(same))
        assertEquals("NO_BILL",app.tasks.status(other));assertEquals("NO_BILL",app.tasks.status(automatic))
        assertEquals(3,model.calls.get())
        assertNull(app.tasks.recordId(first));assertNull(app.tasks.recordId(same))
        assertTrue(app.channels.shakeAllowed(secondPackage));assertTrue(app.channels.autoAllowed(PaymentApps.WECHAT))
    }

    @Test fun aRevokedTaskCannotUseFreshConsentWhenResumedFromDisk()=runBlocking {
        val previous=task(firstPackage)
        app.channels.setShakePackage(firstPackage,false);app.channels.setShakePackage(firstPackage,true)
        val model=HeldModel().also { it.release.complete(Unit) }
        queue.resume(model.factory)
        await { queue.size()==0 && app.tasks.status(previous)=="CANCELLED" }
        assertEquals(0,model.calls.get());assertNull(app.tasks.recordId(previous))
        assertTrue(app.channels.shakeAllowed(firstPackage))
    }

    @Test fun legacyShakeWithoutStoredContextIsCancelledWhileAutomaticWorkResumes()=runBlocking {
        val legacy=task(firstPackage,legacy=true)
        val automatic=task(PaymentApps.WECHAT,true)
        val model=HeldModel().also { it.release.complete(Unit) }
        queue.resume(model.factory)
        await { queue.size()==0 && app.tasks.status(automatic)=="NO_BILL" }
        assertEquals("CANCELLED",app.tasks.status(legacy));assertEquals(1,model.calls.get())
    }

    @Test fun masterSwitchRoundTripInvalidatesThePersistedQueueAuthorization()=runBlocking {
        val previous=task(firstPackage)
        app.channels.set(ChannelSettings.Channel.OCR,false);app.channels.set(ChannelSettings.Channel.OCR,true)
        val model=HeldModel().also { it.release.complete(Unit) }
        queue.resume(model.factory)
        await { queue.size()==0 && app.tasks.status(previous)=="CANCELLED" }
        assertEquals(0,model.calls.get())
    }

    private fun task(pkg:String,automatic:Boolean=false,legacy:Boolean=false):Long {
        val bitmap=Bitmap.createBitmap(16,32,Bitmap.Config.ARGB_8888)
        val image=try { app.recognizer.prepareScreenshot(bitmap) } finally { bitmap.recycle() }
        val authorization=if(automatic || legacy)null else app.channels.shakeAuthorization(pkg)!!
        val id=app.tasks.create(Source.OCR,"${if(automatic)"payment_auto" else "shake"}:$pkg",authorization)
        ids+=id;assertTrue(app.tasks.queued(id,image));return id
    }
    private suspend fun await(check:()->Boolean)=withTimeout(10_000) { while(!check())delay(20) }

    private inner class HeldModel {
        val calls=AtomicInteger()
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>().also { releases+=it }
        val factory:()->CaptureProcessor={CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ ->
            calls.incrementAndGet();entered.complete(Unit)
            // Native OCR is allowed to finish after Job.cancel. Its late result must remain unusable.
            withContext(NonCancellable) { release.await() }
            JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content","{\"is_bill\":false,\"raw_text\":\"合成非账单\"}"))))
        }))}
    }
}
