package net.ankio.ledger.ocr

import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecognitionPolicy
import net.ankio.ledger.core.CandidateId
import net.ankio.ledger.core.TransactionCandidate

data class RecognitionResult(val rawText:String,val bill:ParsedBill?,val screenshot:ByteArray?=null,
    val iconRegion:MerchantIconRegion?=null,val merchantIcon:ByteArray?=null,
    val iconStatus:String="not_bill",val iconDetails:String="",val skipReason:String?=null) {
    /** Adapt legacy single-result recognizers without a second, potentially inconsistent bill field. */
    val candidates:List<TransactionCandidate>
        get() = bill?.let { listOf(TransactionCandidate(CandidateId.SINGLE,it)) } ?: emptyList()
}

/** In-process seam for cancellation/queue tests; the app always uses the bundled implementation. */
fun interface RecognitionBackend {
    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult

    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,expenseOnly:Boolean,origin:String):RecognitionResult =
        recognize(image,onPrepared,stillAllowed,livePaymentAt,RecognitionPolicy.fromExpenseOnly(expenseOnly),origin)
}
