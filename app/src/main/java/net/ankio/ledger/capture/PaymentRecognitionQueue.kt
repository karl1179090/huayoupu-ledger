package net.ankio.ledger.capture

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.ocr.RecognitionException
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult

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
        factory:()->CaptureProcessor={CaptureProcessor(app)},feedback:(String,Boolean)->Unit={message,loading->deliver(id,message,loading)}):Boolean {
        if(jobs.containsKey(id))return true
        if(!hasCapacity())return false
        val imported=!automatic && pkg=="image" && app.tasks.isImageImport(id)
        val epoch=app.channels.automaticGeneration(pkg)
        val shake=if(automatic)null else app.tasks.shakeAuthorization(id)?.takeIf { it.packageName==pkg }
        val recognitionEpoch=app.recognitionSession.generation
        fun allowed()=app.tasks.exists(id) && app.recognitionSession.generation==recognitionEpoch &&
            (if(imported)true else hasCapturePermission() &&
                if(automatic)app.channels.autoAllowed(pkg) && app.channels.automaticGeneration(pkg)==epoch
                else shake!=null && app.channels.shakeAuthorized(shake))
        val work=app.scope.launch(start=CoroutineStart.LAZY) {
            try {
                taskRecovery.await()
                modelPermit.withPermit {
                    if(!allowed())throw CancellationException("识别授权已关闭")
                    val image=app.tasks.image(id)?:throw RecognitionException("本次截图已失效，请重新摇一摇补录")
                    feedback("正在识别…",true)
                    val result=factory().processPrepared(image,if(imported)Source.IMAGE else Source.OCR,pkg,id,::allowed)
                    if(allowed()) {
                        var message=completedMessage(id,result)
                        if(!imported && (message.startsWith("已记账 ¥") || message=="退款已更新") && app.tasks.recordId(id)?.let { app.captureHealth.successful(it,pkg) }!=true)
                            message="账单已移除，未新增记账"
                        feedback(message,false)
                    }
                }
            } catch(cancelled:CancellationException) {
                app.tasks.update(id,"CANCELLED",stoppedMessage(id,"识别已停止，未新增账单"))
            } catch(error:Exception) {
                val message=(error as? RecognitionException)?.userMessage?:"识别失败，请重新补录"
                app.tasks.update(id,"FAILED",message)
                if(allowed())feedback(message,false)
            }
        }
        jobs[id]=Entry(automatic,pkg,work,shake?.shortcut==true,imported)
        // Cancellation can win before a lazy coroutine enters its body, so a
        // body-level finally cannot be the owner of queue removal.
        work.invokeOnCompletion {
            synchronized(this@PaymentRecognitionQueue) { jobs.remove(id) }
            app.changed()
            runCatching { app.screenshots.cleanup() }
            resume()
        }
        work.start();return true
    }

    /** Resume only durable unfinished children; recognition input and decisions already belong to ledger.db. */
    @Synchronized fun retryBatch(id:Long):Boolean {
        val key=app.tasks.operationKey(id) ?: return false
        val batch=app.store.recognitionBatch(key) ?: return false
        if(batch.complete || app.tasks.status(id) !in setOf("FAILED","CANCELLED") || !hasCapacity() || app.tasks.image(id)==null)return false
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
        if(!app.tasks.update(id,"QUEUED","正在重试未完成项"))return false
        app.changed();resume();return true
    }
    private fun stoppedMessage(id:Long,fallback:String):String = app.tasks.operationKey(id)?.let(app.store::recognitionBatch)
        ?.stoppedMessage(fallback) ?: fallback

    /** A model response and an old duplicate message are not proof of a live bill. */
    internal fun completedMessage(id:Long,result:IngestResult):String = when(result) {
        is IngestResult.Saved,IngestResult.DuplicateSkipped -> {
            val linked=app.tasks.recordId(id)
            val record=linked?.let(app.store::get)
            when {
                record==null || result is IngestResult.Saved && result.record.id!=linked -> "账单已移除，未新增记账"
                record.state==net.ankio.ledger.core.RecordState.POSTED && record.wallet?.reviewCode?.startsWith("TRANSFER_")==true -> net.ankio.ledger.data.TransferRecognitionEnricher.message(record.wallet.reviewCode)
                record.state==net.ankio.ledger.core.RecordState.REVIEW -> if(record.wallet?.reviewCode=="WALLET_REQUIRED")"请选择钱包后入账" else record.reason.ifBlank { "金额待核对，请打开账单" }
                record.state!=net.ankio.ledger.core.RecordState.POSTED || record.bill.amountCents==null -> "账单未入账，请重试"
                result is IngestResult.Saved && result.updated && record.bill.refund!=null || app.tasks.message(id)=="退款已更新" -> "退款已更新"
                else -> "已记账 ¥${net.ankio.ledger.core.Money.format(record.bill.amountCents!!)}${if(record.bill.direction==net.ankio.ledger.core.Direction.TRANSFER)"（钱包转账）" else ""}"
            }
        }
        IngestResult.Ignored -> "未识别到金额，请重试"
        is IngestResult.Skipped -> result.message
        is IngestResult.Batch -> result.result.message
    }
    private fun deliver(id:Long,message:String,loading:Boolean) {
        val linked=if(!loading && (message.startsWith("已记账 ¥") || message=="退款已更新" || app.tasks.status(id)=="REVIEW"))app.tasks.recordId(id)?.takeIf { app.store.get(it)!=null } else null
        val display=if(!loading && (message.startsWith("已记账 ¥") || message=="退款已更新") && linked==null)"账单已移除，未新增记账" else message
        val service=ScreenCaptureService.current
        if(service!=null)service.showQueuedFeedback(display,loading,linked)
        else { app.channels.status(display);app.changed() }
    }

    /** Called after service connection as well as application start, never while permission is still binding. */
    @Synchronized fun resume(factory:()->CaptureProcessor={CaptureProcessor(app)},feedback:((Long,String,Boolean)->Unit)?=null) {
        if(!taskRecovery.isCompleted || taskRecovery.isCancelled)return
        for((id,origin) in app.tasks.pendingQueued()) {
            if(jobs.containsKey(id))continue
            val imported=origin=="image" && app.tasks.isImageImport(id)
            if(!imported && !hasCapturePermission())continue
            val automatic=origin.startsWith("payment_auto:")
            val pkg=if(imported)"image" else origin.substringAfter(':',"")
            val allowed=if(imported)true else if(automatic)app.channels.autoAllowed(pkg)
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
    }
    @Synchronized fun cancelShortcut(pkg:String?=null) {
        jobs.values.filter { it.shortcut && (pkg==null || it.pkg==pkg) }.forEach { it.job.cancel() }
        app.tasks.pendingShortcut(pkg).forEach { app.tasks.update(it,"CANCELLED",stoppedMessage(it,"快捷方式识别授权已关闭，未新增账单")) }
    }
    @Synchronized fun cancelAll() {
        cancelAutomatic();cancelShake();cancelShortcut()
        jobs.values.filter { it.imageImport }.forEach { it.job.cancel() }
        app.tasks.pendingQueued().filter { app.tasks.isImageImport(it.first) }
            .forEach { app.tasks.update(it.first,"CANCELLED",stoppedMessage(it.first,"识别已停止，可以重新导入截图")) }
    }
    companion object { const val MAX_PENDING=8 }
}
