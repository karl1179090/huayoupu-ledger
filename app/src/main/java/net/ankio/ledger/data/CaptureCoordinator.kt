package net.ankio.ledger.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ankio.ledger.core.*
import net.ankio.ledger.ledger

class CaptureCoordinator(context: Context) {
    private val app = context.ledger
    private val mutex = Mutex()

    suspend fun acceptRecognition(capture: Capture, bill: ParsedBill?, screenshot: ByteArray? = null, merchantIcon: ByteArray? = null, eventKey:String?=null, operationKey:String?=null,
        policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED, allowed: () -> Boolean): IngestResult =
        acceptCandidate(capture,bill?.let { TransactionCandidate(CandidateId.SINGLE,it) },screenshot,merchantIcon,eventKey,operationKey,policy,allowed=allowed).outcome

    suspend fun acceptCandidate(capture:Capture,candidate:TransactionCandidate?,screenshot:ByteArray?=null,merchantIcon:ByteArray?=null,
        eventKey:String?=null,operationKey:String?=null,policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED,
        classificationSnapshot:ClassificationSnapshot?=null,allowed:()->Boolean):CandidateSubmission = mutex.withLock {
        val candidateId=candidate?.id ?: CandidateId.SINGLE
        if (!allowed() || candidate == null) return@withLock CandidateSubmission(candidateId,IngestResult.Ignored)
        if(!policy.allows(candidate.bill,capture.origin))return@withLock CandidateSubmission(candidateId,IngestResult.Skipped("该交易需通过摇一摇或截图导入核对"))
        synchronized(app.channels) {
            if (!allowed()) CandidateSubmission(candidateId,IngestResult.Ignored)
            else synchronized(app.store) {
                val bill=app.store.classify(candidate.bill,classificationSnapshot ?: app.store.classificationRules(),capture)
                val classified=candidate.copy(bill=bill)
                (if(operationKey==null)CandidateSubmission(candidateId,app.store.ingest(capture.copy(text = capture.text.take(32_768)), bill, eventKey=eventKey, screenshot = screenshot, merchantIcon = merchantIcon))
                else app.store.ingestCandidate(capture.copy(text=capture.text.take(32_768)),classified,operationKey,eventKey,screenshot,merchantIcon)).also {
                if (it.outcome is IngestResult.Saved || it.outcome == IngestResult.DuplicateSkipped && merchantIcon!=null) app.changed(true)
                }
            }
        }
    }

    /** Freeze every history decision before committing the first child. Failed children remain retryable. */
    suspend fun acceptBatch(capture:Capture,candidates:List<TransactionCandidate>,operationKey:String,zoneId:String,
        screenshot:ByteArray?=null,classificationSnapshot:ClassificationSnapshot?=null,
        policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED,allowed:()->Boolean):RecognitionBatchResult = mutex.withLock {
        synchronized(app.channels) {
            if(!allowed())throw kotlinx.coroutines.CancellationException("识别已停止")
            val initial=app.store.freezeRecognitionBatch(capture,candidates,operationKey,zoneId,
                classificationSnapshot ?: app.store.classificationRules(),policy)
            continueBatchLocked(operationKey,initial,screenshot,allowed)
        }
    }
    suspend fun resumeBatch(operationKey:String,screenshot:ByteArray?=null,allowed:()->Boolean):RecognitionBatchResult?=mutex.withLock {
        synchronized(app.channels) {
            val initial=app.store.recognitionBatch(operationKey) ?: return@withLock null
            continueBatchLocked(operationKey,initial,screenshot,allowed)
        }
    }
    private fun continueBatchLocked(operationKey:String,initial:RecognitionBatchResult,screenshot:ByteArray?,allowed:()->Boolean):RecognitionBatchResult {
        for(item in initial.items.filter { it.status in setOf("PENDING","FAILED") }) {
            if(!allowed())throw kotlinx.coroutines.CancellationException("识别已停止")
            try { app.store.submitBatchCandidate(operationKey,item.candidateId,screenshot) }
            catch(error:kotlinx.coroutines.CancellationException) { throw error }
            catch(error:Exception) { app.store.failBatchCandidate(operationKey,item.candidateId) }
        }
        app.changed(true)
        return requireNotNull(app.store.recognitionBatch(operationKey))
    }


}
