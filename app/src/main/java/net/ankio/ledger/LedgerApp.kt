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
        val capturePausePending=recoverPendingBackupCapturePause()
        net.ankio.ledger.capture.CaptureShortcuts.publish(this)
        getSystemService(android.app.NotificationManager::class.java).apply {
            // Remove the previous version's runtime/result channels during upgrade.
            listOf("shake_ledger","capture_repair","capture_feedback").forEach(::deleteNotificationChannel)
        }
        recognitionWork.launch {
            try {
                RecognitionCommitRecovery.recoverAll(tasks,store)
                tasks.recoverInterrupted(preserveCurrentSession=true,capturePausedForRestore=capturePausePending)
                if(capturePausePending)clearBackupCapturePause()
                taskRecovery.complete(Unit)
            } catch (error: Throwable) {
                taskRecovery.completeExceptionally(error)
                throw error
            }
            store.readableDatabase
            store.purgeExpiredDeletes().forEach(tasks::deleteForRecord)
            store.pendingDeletion()?.let { deletion ->
                recognitionWork.launch {
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
        if(!recognitionWork.accepting || !interactiveStarted.compareAndSet(false,true))return
        recognitionSession
        recognitionWork.launch {
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
            store.reconcilePendingRefunds().forEach { (from,to) -> RecognitionCommitRecovery.reassignRefund(tasks,store,from,to) }
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
    val recognitionWork=net.ankio.ledger.capture.RecognitionWork(scope)
    private val backupMutex=kotlinx.coroutines.sync.Mutex()
    private val backupCaptureState get()=getSharedPreferences("backup_capture_state",MODE_PRIVATE)

    internal suspend fun restoreCompleteBackup(prepare:()->LedgerArchive.PreparedBackup,onCommitted:(RestoreSummary)->Unit={}):RestoreSummary {
        backupMutex.lock()
        try {
            val pending=recognitionWork.pauseAdmission()
            var committed=false
            try {
                withContext(Dispatchers.Main) { net.ankio.ledger.capture.ScreenCaptureService.current?.pausePaymentChecks() }
                taskRecovery.await()
                return withContext(Dispatchers.IO) {
                    AtomicBackupTransaction.checkpoint("opening")
                    prepare().use { prepared ->
                        LedgerArchive.checkClassificationMerge(store,prepared)
                        AtomicBackupTransaction.checkpoint("classificationPreflight")
                        pending.values.awaitAll()
                        val queuedImages=try {
                            tasks.writableDatabase;screenshots.writableDatabase
                            tasks.pendingQueued().map { it.first }.filter(tasks::isImageImport)
                        } catch(error:Exception) { throw RestorePreparationException(error) }

                        // From here the committed ledger decides whether the persisted capture grants must be revoked.
                        withContext(NonCancellable) {
                            check(backupCaptureState.edit().putBoolean("check_commit",true).commit())
                            val summary=try { LedgerArchive.restoreDetailed(store,prepared,pauseCaptureOnCommit=true) }
                            catch(error:RestoreOutcomeUncertainException) {
                                runCatching { pauseCaptureAfterBackup() }.onFailure(error::addSuppressed)
                                throw error
                            } catch(error:Exception) {
                                if(runCatching { !AtomicBackupTransaction.capturePausePending(store) }.getOrDefault(false))clearBackupCaptureCheck()
                                throw error
                            }
                            committed=true
                            try { onCommitted(summary) } finally { pauseCaptureAfterBackup() }
                            AtomicBackupTransaction.checkpoint("applicationCleanup")
                            runCatching {
                                RecognitionCommitRecovery.recoverAll(tasks,store)
                                queuedImages.forEach { tasks.update(it,"CANCELLED","识别已停止，可以重新导入截图") }
                                tasks.recoverInterrupted(capturePausedForRestore=true)
                                clearBackupCapturePause()
                            }.onFailure { android.util.Log.e("LedgerBackup","Restore committed; task feedback will recover at next startup",it) }
                            summary
                        }
                    }
                }
            } catch(error:Exception) {
                if(!committed && error !is ClassificationCapacityException && error !is RestoreOutcomeUncertainException && error !is CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { pauseCaptureAfterBackup(clearSelections=false,cancelQueued=true) }.onFailure(error::addSuppressed)
                    }
                }
                throw error
            } finally {
                recognitionWork.reopen(requireDrained=false)
                withContext(NonCancellable + Dispatchers.Main) {
                    runCatching { net.ankio.ledger.capture.ScreenCaptureService.current?.checkPaymentPages();captureQueue.resume() }
                        .onFailure { android.util.Log.e("LedgerBackup","Queue will resume at next startup",it) }
                }
            }
        } finally { backupMutex.unlock() }
    }

    private suspend fun pauseCaptureAfterBackup(clearSelections:Boolean=true,cancelQueued:Boolean=false) {
        channels.pauseForRestore(clearSelections)
        recognitionSession.invalidate()
        withContext(Dispatchers.Main) {
            try {
                if(cancelQueued) {
                    val service=net.ankio.ledger.capture.ScreenCaptureService.current
                    if(service==null)captureQueue.cancelAll() else service.cancelRecognition()
                }
            } finally { net.ankio.ledger.capture.CaptureRuntime.stopSafely() }
        }
    }
    private fun recoverPendingBackupCapturePause():Boolean {
        if(!backupCaptureState.getBoolean("check_commit",false))return false
        val pending=runCatching { AtomicBackupTransaction.capturePausePending(store) }.getOrElse { true }
        if(pending)channels.pauseForRestore() else clearBackupCaptureCheck()
        return pending
    }
    private fun clearBackupCapturePause() {
        AtomicBackupTransaction.clearCapturePause(store)
        clearBackupCaptureCheck()
    }
    private fun clearBackupCaptureCheck() {
        if(!backupCaptureState.edit().remove("check_commit").commit())
            android.util.Log.e("LedgerBackup","Could not clear the pending capture pause check")
    }

    suspend fun <T> restoreQuiescent(action:()->T):T {
        backupMutex.lock()
        try {
            taskRecovery.await()
            var closed=false
            try {
                withContext(NonCancellable) { recognitionWork.closeAndDrain();closed=true }
                channels.pauseForRestore()
                recognitionSession.invalidate()
                withContext(Dispatchers.Main) { net.ankio.ledger.capture.ScreenCaptureService.current?.cancelRecognition();net.ankio.ledger.capture.CaptureRuntime.stopSafely() }
                // All tracked completion callbacks (including queue cleanup/resume) have drained.
                return withContext(Dispatchers.IO) {
                    action().also {
                        AtomicBackupTransaction.checkpoint("applicationCleanup")
                        runCatching { RecognitionCommitRecovery.recoverAll(tasks,store);tasks.recoverInterrupted() }
                            .onFailure { android.util.Log.e("LedgerBackup","Restore committed; task feedback will recover at next startup",it) }
                    }
                }
            } finally { if(closed)recognitionWork.reopen();runCatching { captureQueue.resume() }.onFailure { android.util.Log.e("LedgerBackup","Queue will resume at next startup",it) } }
        } finally { backupMutex.unlock() }
    }
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
