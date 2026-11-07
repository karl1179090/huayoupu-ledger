package net.ankio.ledger.ocr

import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecognitionPolicy

data class RecognitionResult(val rawText:String,val bill:ParsedBill?,val screenshot:ByteArray?=null,
    val iconRegion:MerchantIconRegion?=null,val merchantIcon:ByteArray?=null,
    val iconStatus:String="not_bill",val iconDetails:String="",val skipReason:String?=null)

/** In-process seam for cancellation/queue tests; the app always uses the bundled implementation. */
fun interface RecognitionBackend {
    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult

    suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,expenseOnly:Boolean,origin:String):RecognitionResult =
        recognize(image,onPrepared,stillAllowed,livePaymentAt,RecognitionPolicy.fromExpenseOnly(expenseOnly),origin)
}
