package net.ankio.ledger.capture

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.ocr.RecognitionException
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.RecognitionCommitRecovery
import net.ankio.ledger.data.RecognitionFeedback

/** Disk-backed images, bounded pending count, one model call at a time. Acquisition has its own lock. */
class PaymentRecognitionQueue(private val app:LedgerApp,
    private val taskRecovery:Deferred<Unit> = app.taskRecovery,
    private val hasCapturePermission:()->Boolean={app.channels.permitted(ChannelSettings.Channel.OCR)}) {
    private data class Entry(val automatic:Boolean,val pkg:String,val job:Job,val shortcut:Boolean=false,val imageImport:Boolean=false)
    private val jobs=linkedMapOf<Long,Entry>()
    private val modelPermit=Semaphore(1)
    @Synchronized fun hasCapacity()=jobs.size<MAX_PENDING
    @Synchronized fun size()=jobs.size

    @Synchronized fun enqueue(id:Long,pkg:String,automatic:Boolean,
        factory:()->CaptureProcessor={CaptureProcessor(app)},feedback:((String,Boolean)->Unit)?=null):Boolean {
        if(!app.recognitionWork.accepting)return false
        if(jobs.containsKey(id))return true
        if(!hasCapacity())return false
        val imported=!automatic && pkg=="image" && app.tasks.isImageImport(id)
        val epoch=app.channels.automaticGeneration(pkg)
        val automaticGrant=!automatic || app.tasks.automaticQueueGrantValid(id)
        val shake=if(automatic)null else app.tasks.shakeAuthorization(id)?.takeIf { it.packageName==pkg }
        val recognitionEpoch=app.recognitionSession.generation
        fun allowed()=app.tasks.exists(id) && app.recognitionSession.generation==recognitionEpoch &&
            (if(imported)true else hasCapturePermission() &&
                if(automatic)automaticGrant && app.channels.autoAllowed(pkg) && app.channels.automaticGeneration(pkg)==epoch
                else shake!=null && app.channels.shakeAuthorized(shake))
        val work=app.recognitionWork.launch(start=CoroutineStart.LAZY,onCompletion={
            synchronized(this@PaymentRecognitionQueue) { jobs.remove(id) }
            app.changed()
            runCatching { app.screenshots.cleanup() }
            resume()
        }) {
            try {
                taskRecovery.await()
                modelPermit.withPermit {
                    if(!allowed())throw CancellationException("识别授权已关闭")
                    val image=app.tasks.image(id)
                    if(image==null && !app.tasks.hasMissingNodeEvidence(id))throw RecognitionException("本次截图已失效，请重新摇一摇补录")
                    deliver(RecognitionFeedback("正在识别…"), true, feedback)
                    val processor=factory()
                    val result=if(image==null)processor.processWithoutScreenshot(Source.OCR,pkg,id,::allowed)
                        else processor.processPrepared(image,if(imported)Source.IMAGE else Source.OCR,pkg,id,::allowed)
                    if (allowed()) {
                        var completion = completedFeedback(id, result)
                        if (!imported && completion?.posted == true &&
                            completion.recordId?.let { app.captureHealth.successful(it, pkg) } != true)
                            completion = completedFeedback(id, result)
                        deliver(completion, false, feedback)
                    }
                }
            } catch(cancelled:CancellationException) {
                val message=stoppedMessage(id,"识别已停止，未新增账单")
                app.tasks.update(id,"CANCELLED",message)
                if(shake?.shortcut==true && app.tasks.exists(id)) deliver(RecognitionFeedback(message), false, feedback)
            } catch(error:Exception) {
                val message=(error as? RecognitionException)?.userMessage?:"识别失败，请重新补录"
                app.tasks.update(id,"FAILED",message)
                if (allowed()) {
                    val committed = RecognitionCommitRecovery.receipt(app.tasks, app.store, id)
                    if (committed == null) deliver(RecognitionFeedback(message), false, feedback)
                    else {
                        runCatching { RecognitionCommitRecovery.recover(app.tasks, app.store, id) }
                        deliver(completedFeedback(id, IngestResult.DuplicateSkipped), false, feedback)
                    }
                }
            }
        }
        jobs[id]=Entry(automatic,pkg,work,shake?.shortcut==true,imported)
        if(work.isCancelled) { jobs.remove(id);return false }
        work.start();return true
    }

    /** Resume only durable unfinished children; recognition input and decisions already belong to ledger.db. */
    @Synchronized fun retryBatch(id:Long):Boolean {
        if(!app.recognitionWork.accepting)return false
        if(!hasCapacity())return false
        val origin=app.tasks.origin(id) ?: return false
        val imported=app.tasks.isImageImport(id)
        if(!imported) {
            if(!hasCapturePermission())return false
            val pkg=origin.substringAfter(':',"")
            val authorized=if(origin.startsWith("payment_auto:"))app.channels.autoAllowed(pkg)
                else (origin.startsWith("shake:") || origin.startsWith("shortcut:")) &&
                    app.tasks.shakeAuthorization(id)?.let { it.packageName==pkg && app.channels.shakeAuthorized(it) }==true
            if(!authorized)return false
        }
        if(!app.tasks.queueBatchRetry(id))return false
        app.changed();resume();return true
    }
    private fun stoppedMessage(id:Long,fallback:String):String = app.tasks.operationKey(id)?.let(app.store::recognitionBatch)
        ?.stoppedMessage(fallback) ?: fallback

    internal fun completedFeedback(id: Long, result: IngestResult): RecognitionFeedback? {
        RecognitionCommitRecovery.immediate(app.tasks, app.store, id, result)?.let { return it }
        if (app.tasks.origin(id)?.startsWith("shortcut:") != true) return null
        val duplicate = when (result) {
            IngestResult.DuplicateSkipped -> true
            is IngestResult.Saved -> !result.effect.needsFeedback
            is IngestResult.Batch -> result.result.complete && result.result.items.all { it.status == "SKIPPED" && it.recordUID != null }
            else -> false
        }
        if (!duplicate) return null
        val linkedId = (result as? IngestResult.Saved)?.record?.id ?: app.tasks.recordId(id)
        val current = linkedId?.let(app.store::get)?.let { RecognitionCommitRecovery.project(null, it) }
        return if (current?.status == "REVIEW") current.feedback else RecognitionFeedback("这笔已记过")
    }

    private fun deliver(result: RecognitionFeedback?, loading: Boolean, feedback: ((String, Boolean) -> Unit)?) {
        if (feedback != null) {
            if (result != null) feedback(result.message, loading)
            return
        }
        val service = ScreenCaptureService.current
        if (result == null) {
            service?.dismissQueuedFeedback()
            if (app.channels.lastStatus() == "正在识别…") app.channels.status("")
            app.changed()
        } else if (service != null) service.showQueuedFeedback(result.message, loading, result.recordId, result.editInPlace)
        else { app.channels.status(result.message); app.changed() }
    }

    /** Called after service connection as well as application start, never while permission is still binding. */
    @Synchronized fun resume(factory:()->CaptureProcessor={CaptureProcessor(app)},feedback:((Long,String,Boolean)->Unit)?=null) {
        if(!app.recognitionWork.accepting)return
        if(!taskRecovery.isCompleted || taskRecovery.isCancelled)return
        for((id,origin) in app.tasks.pendingQueued()) {
            if(jobs.containsKey(id))continue
            val imported=origin=="image" && app.tasks.isImageImport(id)
            if(!imported && !hasCapturePermission())continue
            val automatic=origin.startsWith("payment_auto:")
            val pkg=if(imported)"image" else origin.substringAfter(':',"")
            val allowed=if(imported)true else if(automatic)app.channels.autoAllowed(pkg) && app.tasks.automaticQueueGrantValid(id)
                else (origin.startsWith("shake:") || origin.startsWith("shortcut:")) && app.tasks.shakeAuthorization(id)?.let { it.packageName==pkg && app.channels.shakeAuthorized(it) }==true
            if(!allowed) { app.tasks.update(id,"CANCELLED",stoppedMessage(id,"识别授权已关闭，未新增账单"));continue }
            if(!hasCapacity())break
            if(feedback==null)enqueue(id,pkg,automatic,factory)
            else enqueue(id,pkg,automatic,factory) { message,loading->feedback(id,message,loading) }
        }
    }
    @Synchronized fun cancelAutomatic(pkg:String?=null) {
        jobs.values.filter { it.automatic && (pkg==null || it.pkg==pkg) }.forEach { it.job.cancel() }
        app.tasks.pendingQueued().filter { (_,origin)->origin.startsWith("payment_auto:") && (pkg==null || origin=="payment_auto:$pkg") }
            .forEach { app.tasks.update(it.first,"CANCELLED",stoppedMessage(it.first,"自动识别已关闭，未新增账单")) }
    }
    @Synchronized fun cancelShake(pkg:String?=null) {
        jobs.values.filter { !it.automatic && !it.shortcut && !it.imageImport && (pkg==null || it.pkg==pkg) }.forEach { it.job.cancel() }
        app.tasks.pendingShake(pkg).forEach { app.tasks.update(it,"CANCELLED",stoppedMessage(it,"摇一摇授权已关闭，未新增账单")) }
        app.tasks.reconcileRevokedBatchReservations("shake:",pkg)
    }
    @Synchronized fun cancelShortcut(pkg:String?=null) {
        jobs.values.filter { it.shortcut && (pkg==null || it.pkg==pkg) }.forEach { it.job.cancel() }
        app.tasks.pendingShortcut(pkg).forEach { app.tasks.update(it,"CANCELLED",stoppedMessage(it,"快捷方式识别授权已关闭，未新增账单")) }
        app.tasks.reconcileRevokedBatchReservations("shortcut:",pkg)
    }
    @Synchronized fun cancelAll() {
        cancelAutomatic();cancelShake();cancelShortcut()
        jobs.values.filter { it.imageImport }.forEach { it.job.cancel() }
        app.tasks.pendingQueued().filter { app.tasks.isImageImport(it.first) }
            .forEach { app.tasks.update(it.first,"CANCELLED",stoppedMessage(it.first,"识别已停止，可以重新导入截图")) }
    }
    companion object { const val MAX_PENDING=8 }
}
