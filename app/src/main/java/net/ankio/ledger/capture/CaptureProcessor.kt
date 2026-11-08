package net.ankio.ledger.capture

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Source
import net.ankio.ledger.core.RecognitionPolicy
import net.ankio.ledger.data.IngestResult

/** Shared local persistence pipeline; the bundled recognizer reads the exact stored image. */
class CaptureProcessor(private val app: LedgerApp, private val client: OcrClient = app.recognizer,
    private val recognitionClock:()->net.ankio.ledger.core.RecognitionTimeContext={net.ankio.ledger.core.RecognitionTimeContext.now()}) {
    suspend fun process(bitmap: Bitmap, source: Source, origin: String, taskId:Long?=null, beforeAccept:suspend ()->Unit={}, allowed: () -> Boolean): IngestResult {
        return processRecognition(source,origin,taskId,beforeAccept,allowed) { guard, time ->
            recognize(client.prepareScreenshot(bitmap), taskId, origin, guard, time)
        }
    }
    suspend fun processPrepared(image:ByteArray,source:Source,origin:String,taskId:Long,allowed:()->Boolean):IngestResult =
        processRecognition(source,origin,taskId,{},allowed) { guard, time -> recognize(image, taskId, origin, guard, time) }

    private suspend fun recognize(image:ByteArray,taskId:Long?,origin:String,guard:()->Boolean,time:()->net.ankio.ledger.core.RecognitionTimeContext):net.ankio.ledger.ocr.RecognitionResult {
        val prepared:(ByteArray)->Unit={ bytes -> taskId?.let { app.tasks.prepared(it,bytes);app.changed() } }
        val liveAt=taskId?.let(app.tasks::livePaymentAt)
        return client.recognizePrepared(image,onPrepared=prepared,stillAllowed=guard,livePaymentAt=liveAt,policy=policy(taskId),origin=origin,recognitionTime=time)
    }

    private fun policy(taskId:Long?) = if(taskId?.let(app.tasks::automatic)==true)
        RecognitionPolicy.AUTOMATIC_PAYMENT else RecognitionPolicy.USER_INITIATED

    private fun recordedMessage(cents:Long?,transfer:Boolean=false)=if(cents==null)"已记账" else
        "已记账 ¥${net.ankio.ledger.core.Money.format(cents)}${if(transfer)"（钱包转账）" else ""}"

    private suspend fun processRecognition(source:Source,origin:String,taskId:Long?,beforeAccept:suspend ()->Unit,allowed:()->Boolean,
        recognize:suspend (()->Boolean,()->net.ankio.ledger.core.RecognitionTimeContext)->net.ankio.ledger.ocr.RecognitionResult):IngestResult {
        require(source == Source.OCR || source == Source.IMAGE)
        val processingContext=currentCoroutineContext()
        val guard={ processingContext.isActive && allowed() && (taskId==null || app.tasks.exists(taskId)) }
        try {
            val operationKey=taskId?.let(app.tasks::operationKey)
            if(operationKey!=null) {
                val batch=app.coordinator.resumeBatch(operationKey,taskId?.let(app.tasks::image),guard)
                if(batch!=null) {
                    app.tasks.update(requireNotNull(taskId),batch.taskStatus,batch.message)
                    app.changed();return IngestResult.Batch(batch)
                }
            }
            if(taskId!=null && net.ankio.ledger.data.RecognitionCommitRecovery.recover(app.tasks,app.store,taskId)) {
                app.changed();return IngestResult.DuplicateSkipped
            }
            val identity=taskId?.let(app.tasks::paymentIdentity)
            if(identity!=null && (app.store.hasCaptureEvent(identity) || app.tasks.legacyFrameRecords(taskId).any { app.store.get(it)!=null })) {
                if(!guard())throw CancellationException("识别已停止")
                app.store.rememberCaptureEvent(identity)
                val record=(app.store.committedPaymentEvent(identity)?.record
                    ?:app.tasks.recordForPaymentIdentity(identity)?.let(app.store::get)
                    ?:app.tasks.legacyFrameRecords(taskId).firstNotNullOfOrNull(app.store::get))
                app.tasks.update(taskId,"DUPLICATE",recordedMessage(record?.bill?.amountCents,record?.bill?.direction==net.ankio.ledger.core.Direction.TRANSFER),record?.id)
                app.tasks.diagnose(taskId,"stage","duplicate_before_model")
                app.changed();return IngestResult.DuplicateSkipped
            }
            taskId?.let { app.tasks.diagnose(it,"stage","local_recognition");app.tasks.diagnose(it,"engine","local") }
            val classification=taskId?.let(app.tasks::classificationSnapshot) ?: app.store.classificationRules()
            var frozenTime:net.ankio.ledger.core.RecognitionTimeContext?=null
            val recognitionTime = { frozenTime ?: (taskId?.let { app.tasks.beginRecognition(it,recognitionClock) }
                ?: recognitionClock()).also { frozenTime=it } }
            val recognized = recognize(guard,recognitionTime)
            currentCoroutineContext().ensureActive()
            beforeAccept()
            if(!guard())throw CancellationException("识别已停止")
            val multi=recognized.candidates.any { it.id!=net.ankio.ledger.core.CandidateId.SINGLE }
            if(multi) {
                val time=recognitionTime()
                val key=operationKey ?: "recognition:${java.util.UUID.randomUUID()}"
                val batch=app.coordinator.acceptBatch(Capture(source,origin,recognized.rawText,time.recognitionAt),
                    recognized.candidates,key,time.zoneId,recognized.screenshot,classification,policy(taskId),guard)
                taskId?.let { app.tasks.update(it,batch.taskStatus,batch.message);app.tasks.diagnose(it,"stage","finished") }
                app.changed();return IngestResult.Batch(batch)
            }
            val skip=recognized.skipReason ?: net.ankio.ledger.core.TransactionRejection.reason(recognized.rawText)
            skip?.let { message ->
                taskId?.let { app.tasks.update(it,"SKIPPED",message);app.tasks.diagnose(it,"stage","skipped_transaction") }
                app.changed();return IngestResult.Skipped(message)
            }
            taskId?.let {
                app.tasks.diagnose(it,"icon",recognized.iconStatus)
                if(recognized.iconDetails.isNotBlank())app.tasks.diagnose(it,"icon_details",recognized.iconDetails)
                app.tasks.diagnose(it,"stage","save_result")
            }
            // The acquisition clock may corroborate the same live payment later; it never supplies missing accounting time.
            val capturedAt=taskId?.let(app.tasks::livePaymentAt) ?: recognitionTime().recognitionAt
            val result=app.coordinator.acceptCandidate(Capture(source, origin, recognized.rawText, capturedAt), recognized.candidates.singleOrNull(), recognized.screenshot, recognized.merchantIcon, eventKey=identity, operationKey=operationKey, policy=policy(taskId), classificationSnapshot=classification, allowed=guard).outcome
            if(result==IngestResult.Ignored&&!guard())throw CancellationException("识别已停止")
            taskId?.let { id ->
                // Explain REVIEW without exporting the model response or financial fields.
                app.tasks.diagnose(id,"decision",org.json.JSONObject().apply {
                    put("validated",recognized.bill?.reliable==true)
                    put("hasAmount",recognized.bill?.amountCents!=null);put("hasDirection",recognized.bill?.direction!=null)
                    put("timeExplicit",recognized.bill?.timeExplicit==true)
                    if(result is IngestResult.Saved) {
                        put("state",result.record.state.name);put("possibleDuplicate",result.record.duplicateId!=null)
                        if(result.record.state==net.ankio.ledger.core.RecordState.REVIEW)put("reviewReason",result.record.reason.take(220))
                    } else put("outcome",if(result==IngestResult.DuplicateSkipped)"duplicate_skipped" else "no_bill")
                }.toString())
                when(result) {
                    is IngestResult.Saved -> {
                        val conflict=result.record.state==net.ankio.ledger.core.RecordState.POSTED && result.record.wallet?.reviewCode?.startsWith("TRANSFER_")==true
                        val review=result.record.state==net.ankio.ledger.core.RecordState.REVIEW || conflict
                        val message=if(conflict)net.ankio.ledger.data.TransferRecognitionEnricher.message(result.record.wallet?.reviewCode)
                            else if(review)result.record.reason else if(recognized.bill?.refund!=null || recognized.bill?.standaloneRefund!=null) "退款已更新"
                            else recordedMessage(result.record.bill.amountCents,result.record.bill.direction==net.ankio.ledger.core.Direction.TRANSFER)
                        app.tasks.update(id,if(review)"REVIEW" else "SUCCEEDED",message,result.record.id)
                    }
                    IngestResult.DuplicateSkipped -> {
                        val capture=Capture(source,origin,recognized.rawText,capturedAt)
                        // The primary legacy field may still be a Taobao order ID;
                        // resolve an Alipay/WeChat alias through the scoped index first.
                        val original=operationKey?.let(app.store::committedCapture)?.record
                            ?:identity?.let(app.store::committedPaymentEvent)?.record
                            ?:recognized.bill?.let { app.store.matchingIdentity(capture,it) } ?: run {
                            val exact=recognized.bill?.let { bill -> net.ankio.ledger.core.DuplicateDetector.find(capture,bill,app.store.all().map { it.existing() }) } as? net.ankio.ledger.core.Duplicate.Exact
                            exact?.id?.let(app.store::get)
                        }
                        app.tasks.update(id,"DUPLICATE",recordedMessage(original?.bill?.amountCents ?: recognized.bill?.amountCents,
                            original?.bill?.direction==net.ankio.ledger.core.Direction.TRANSFER),original?.id)
                    }
                    IngestResult.Ignored -> app.tasks.update(id,"NO_BILL","未识别到单笔账单，可换到账单详情页重试")
                    is IngestResult.Skipped -> app.tasks.update(id,"SKIPPED",result.message)
                    is IngestResult.Batch -> app.tasks.update(id,result.result.taskStatus,result.result.message)
                }
                app.tasks.diagnose(id,"stage","finished")
                app.changed()
            }
            return result
        } catch(error:CancellationException) {
            taskId?.let { id ->
                val batch=app.tasks.operationKey(id)?.let(app.store::recognitionBatch)
                app.tasks.update(id,"CANCELLED",batch?.let { it.message+" · 未完成项已停止" } ?: "识别已停止，未新增账单");app.changed()
            };throw error
        } catch(error:Exception) {
            app.executionLog.event("model","local","failed",org.json.JSONObject().put("errorType",error.javaClass.simpleName)
                .put("message",(error as? net.ankio.ledger.ocr.RecognitionException)?.userMessage?:"识别失败"))
            taskId?.let { app.tasks.update(it,"FAILED",(error as? net.ankio.ledger.ocr.RecognitionException)?.userMessage?:"识别失败，请换一张清晰截图重试");app.changed() };throw error
        }
    }
}
