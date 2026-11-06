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
        policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED, allowed: () -> Boolean): IngestResult = mutex.withLock {
        if (!allowed() || bill == null) return@withLock IngestResult.Ignored
        if(!policy.allows(bill,capture.origin))return@withLock IngestResult.Skipped("该交易需通过摇一摇或截图导入核对")
        synchronized(app.channels) {
            if (!allowed()) IngestResult.Ignored
            else (if(operationKey==null)app.store.ingest(capture.copy(text = capture.text.take(32_768)), bill, eventKey=eventKey, screenshot = screenshot, merchantIcon = merchantIcon)
                else app.store.ingestRecognition(capture.copy(text=capture.text.take(32_768)),bill,operationKey,eventKey,screenshot,merchantIcon)).also {
                if (it is IngestResult.Saved || it == IngestResult.DuplicateSkipped && merchantIcon!=null) app.changed()
            }
        }
    }


}
