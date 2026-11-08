package net.ankio.ledger.ocr

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import net.ankio.ledger.core.RecognitionPolicy

/** One bundled recognizer for capture, import and automatic bookkeeping. */
class OcrClient(context: Context, private val backend: RecognitionBackend = object : RecognitionBackend {
    private val local = LocalRecognitionClient(context)
    override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult =
        local.recognizePrepared(image,onPrepared=onPrepared,stillAllowed=stillAllowed,livePaymentAt=livePaymentAt,policy=policy,origin=origin)
    override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->net.ankio.ledger.core.RecognitionTimeContext):RecognitionResult =
        local.recognizePrepared(image,onPrepared=onPrepared,stillAllowed=stillAllowed,livePaymentAt=livePaymentAt,policy=policy,origin=origin,
            recognitionTime=recognitionTime)
}) {
    suspend fun recognize(bitmap: Bitmap,onPrepared:(ByteArray)->Unit={},stillAllowed:()->Boolean={true}):RecognitionResult =
        recognizePrepared(prepareScreenshot(bitmap),onPrepared,stillAllowed)
    suspend fun recognizePrepared(image:ByteArray,onPrepared:(ByteArray)->Unit={},stillAllowed:()->Boolean={true},
        livePaymentAt:Long?=null,expenseOnly:Boolean=false,origin:String="",
        policy:RecognitionPolicy=RecognitionPolicy.fromExpenseOnly(expenseOnly),
        recognitionTime:()->net.ankio.ledger.core.RecognitionTimeContext={net.ankio.ledger.core.RecognitionTimeContext.now()}):RecognitionResult =
        backend.recognize(image,onPrepared,stillAllowed,livePaymentAt,policy,origin,recognitionTime)
    fun prepareScreenshot(bitmap:Bitmap):ByteArray {
        if (bitmap.isRecycled || bitmap.width < 1 || bitmap.height < 1) throw RecognitionException("截图已失效，请重新截图")
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > 1800) Bitmap.createScaledBitmap(bitmap,
            maxOf(1, bitmap.width * 1800 / longest), maxOf(1, bitmap.height * 1800 / longest), true) else bitmap
        return try {
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw RecognitionException("截图处理失败，请重试")
                stream.toByteArray()
            }
            if (bytes.size > 5 * 1024 * 1024) throw RecognitionException("图片过大，请裁剪后重试")
            bytes
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

}
