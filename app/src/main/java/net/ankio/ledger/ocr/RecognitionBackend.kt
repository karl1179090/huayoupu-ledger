package net.ankio.ledger.ocr

import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.ParsedTransactionPage
import net.ankio.ledger.core.RecognitionPolicy
import net.ankio.ledger.core.CandidateId
import net.ankio.ledger.core.TransactionCandidate
import net.ankio.ledger.core.TransactionPageParser

data class RecognitionResult(val rawText:String,val parsedPage:ParsedTransactionPage,val screenshot:ByteArray?=null,
    val iconRegion:MerchantIconRegion?=null,val merchantIcon:ByteArray?=null,
    val iconStatus:String="not_bill",val iconDetails:String="",
    val phaseTimingsMs:Map<String,Long> = emptyMap()) {
    constructor(rawText:String,candidates:List<TransactionCandidate>,screenshot:ByteArray?=null,iconRegion:MerchantIconRegion?=null,
        merchantIcon:ByteArray?=null,iconStatus:String="not_bill",iconDetails:String="",skipReason:String?=null,
        phaseTimingsMs:Map<String,Long> = emptyMap()):this(rawText,TransactionPageParser.fromCandidates(rawText,candidates,skipReason),
        screenshot,iconRegion,merchantIcon,iconStatus,iconDetails,phaseTimingsMs)
    constructor(rawText:String,bill:ParsedBill?,screenshot:ByteArray?=null,iconRegion:MerchantIconRegion?=null,
        merchantIcon:ByteArray?=null,iconStatus:String="not_bill",iconDetails:String="",skipReason:String?=null,
        phaseTimingsMs:Map<String,Long> = emptyMap()):this(rawText,
        bill?.let { listOf(TransactionCandidate(CandidateId.SINGLE,it,rawText)) }.orEmpty(),screenshot,iconRegion,merchantIcon,
        iconStatus,iconDetails,skipReason,phaseTimingsMs)
    val candidates: List<TransactionCandidate> get() = parsedPage.candidates
    val bill:ParsedBill? get()=candidates.singleOrNull()?.bill
    val skipReason: String? get() = parsedPage.skipReason
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
