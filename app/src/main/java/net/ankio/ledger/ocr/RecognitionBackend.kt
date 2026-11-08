package net.ankio.ledger.ocr

import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecognitionPolicy
import net.ankio.ledger.core.CandidateId
import net.ankio.ledger.core.TransactionCandidate

data class RecognitionResult(val rawText:String,val candidates:List<TransactionCandidate>,val screenshot:ByteArray?=null,
    val iconRegion:MerchantIconRegion?=null,val merchantIcon:ByteArray?=null,
    val iconStatus:String="not_bill",val iconDetails:String="",val skipReason:String?=null,
    val phaseTimingsMs:Map<String,Long> = emptyMap()) {
    /** Legacy fixtures and callers construct a single candidate; there is one source of truth. */
    constructor(rawText:String,bill:ParsedBill?,screenshot:ByteArray?=null,iconRegion:MerchantIconRegion?=null,
        merchantIcon:ByteArray?=null,iconStatus:String="not_bill",iconDetails:String="",skipReason:String?=null,
        phaseTimingsMs:Map<String,Long> = emptyMap()):this(rawText,
        bill?.let { listOf(TransactionCandidate(CandidateId.SINGLE,it,rawText)) }.orEmpty(),screenshot,iconRegion,merchantIcon,
        iconStatus,iconDetails,skipReason,phaseTimingsMs)
    val bill:ParsedBill? get()=candidates.singleOrNull()?.bill
    init { require(candidates.map { it.id }.distinct().size==candidates.size) }
}

/** In-process seam for cancellation/queue tests; the app always uses the bundled implementation. */
fun interface RecognitionBackend {
    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult

    /** Legacy injectable backends begin immediately; the bundled backend freezes after its inference gate. */
    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,
        recognitionTime:()->net.ankio.ledger.core.RecognitionTimeContext):RecognitionResult {
        recognitionTime()
        return recognize(image,onPrepared,stillAllowed,livePaymentAt,policy,origin)
    }

    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,expenseOnly:Boolean,origin:String):RecognitionResult =
        recognize(image,onPrepared,stillAllowed,livePaymentAt,RecognitionPolicy.fromExpenseOnly(expenseOnly),origin)
}
