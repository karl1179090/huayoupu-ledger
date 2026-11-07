package net.ankio.ledger

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ocr.RecognitionSession
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.capture.ImageImporter
import net.ankio.ledger.data.*

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        net.ankio.ledger.capture.CaptureShortcuts.publish(this)
        getSystemService(android.app.NotificationManager::class.java).apply {
            // Remove the previous version's runtime/result channels during upgrade.
            listOf("shake_ledger","capture_repair","capture_feedback").forEach(::deleteNotificationChannel)
        }
        scope.launch {
            try {
                RecognitionCommitRecovery.recoverAll(tasks,store)
                tasks.recoverInterrupted(preserveCurrentSession=true)
                taskRecovery.complete(Unit)
            } catch (error: Throwable) {
                taskRecovery.completeExceptionally(error)
                throw error
            }
            store.readableDatabase
            store.purgeExpiredDeletes().forEach(tasks::deleteForRecord)
            store.pendingDeletion()?.let { deletion ->
                scope.launch {
                    delay((deletion.expiresAt-System.currentTimeMillis()).coerceAtLeast(0)+150)
                    store.purgeExpiredDeletes().forEach(tasks::deleteForRecord);changed(true)
                }
            }
            changed(true)
        }
    }
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        net.ankio.ledger.widget.ExpenseWidgetUpdater.request(this)
    }
    private val interactiveStarted=java.util.concurrent.atomic.AtomicBoolean()
    /** Widget-only process starts recover durable receipts, but do not load OCR or resume capture. */
    fun startInteractiveMaintenance() {
        if(!interactiveStarted.compareAndSet(false,true))return
        recognitionSession
        scope.launch {
            try {
                taskRecovery.await()
            for(record in store.merchantLabelRepairCandidates()) {
                val image=store.screenshot(record.id)?:continue
                runCatching {
                    val result=net.ankio.ledger.ocr.LocalRecognitionClient(this@LedgerApp).recognizePrepared(image,
                        receivedAt=record.capture.receivedAt,origin=record.capture.origin)
                    result.bill?.let { store.repairMerchantLabel(record.id,it,result.merchantIcon) }
                }
            }
            store.discardRejectedDrafts().forEach { (id,message) -> tasks.skipRefundReceipt(id,message) }
            store.discardStandaloneRefundReceipts().forEach { tasks.skipRefundReceipt(it) }
            store.reconcilePendingRefunds().forEach { (from,to) -> tasks.reassignRecord(from,to) }
            store.reconcilePendingDuplicates().forEach { (from,to) ->
                val cents=store.get(to)?.bill?.amountCents
                tasks.reassignRecord(from,to,if(cents==null)"已记账" else "已记账 ¥${net.ankio.ledger.core.Money.format(cents)}")
            }
            screenshots.cleanup();changed(true);captureQueue.resume()
            } catch(error:Throwable) {
                interactiveStarted.set(false)
                if(error is CancellationException)throw error
                android.util.Log.e("LedgerApp","Interactive maintenance failed",error)
            }
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val taskRecovery = CompletableDeferred<Unit>()
    val revision = MutableStateFlow(0L)
    val store by lazy { LedgerStore(this) }
    val tasks by lazy { RecognitionTasks(this) }
    val screenshots by lazy { ScreenshotStore.get(this) }
    val captureQueue by lazy { net.ankio.ledger.capture.PaymentRecognitionQueue(this) }
    val executionLog by lazy { ExecutionLog(this) }
    val channels by lazy { ChannelSettings(this) }
    val shakeRuntime by lazy { net.ankio.ledger.capture.ShakeRuntime(this) }
    val captureHealth by lazy { net.ankio.ledger.capture.CaptureHealth(this) }
    val recognitionSession by lazy { RecognitionSession(this) }
    val recognizer by lazy { OcrClient(this) }
    val importer by lazy { ImageImporter(this) }
    val coordinator by lazy { CaptureCoordinator(this) }
    val widgetRevision=java.util.concurrent.atomic.AtomicLong()
    fun changed(ledgerChanged:Boolean=false) {
        revision.value = System.nanoTime()
        if(ledgerChanged) {
            widgetRevision.incrementAndGet()
            net.ankio.ledger.widget.ExpenseWidgetUpdater.request(this)
        }
    }
}

val Context.ledger: LedgerApp get() = applicationContext as LedgerApp
