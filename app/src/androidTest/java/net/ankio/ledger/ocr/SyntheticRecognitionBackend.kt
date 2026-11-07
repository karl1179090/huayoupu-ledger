package net.ankio.ledger.ocr

import net.ankio.ledger.ocr.SyntheticRecognition
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import net.ankio.ledger.core.RecognitionPolicy

/** Adapts existing deterministic JSON fixtures; no connection, credential or HTTP implementation. */
class SyntheticRecognitionBackend(private val fixture:suspend (Unit,String,JSONObject?,()->Boolean)->JSONObject):RecognitionBackend {
    override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
        livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult {
        suspend fun check() { currentCoroutineContext().ensureActive();if(!stillAllowed())throw CancellationException("识别已停止") }
        check();onPrepared(image);check()
        // The envelope exists only so older persistence fixtures keep their
        // deterministic screenshot assertions. None of it enters the app APK.
        val envelope=JSONObject().put("enable_thinking",false).put("max_tokens",2048)
            .put("messages",JSONArray().put(JSONObject().put("content",JSONArray()
                .put(JSONObject().put("type","text").put("text","synthetic fixture"))
                .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,"+Base64.encodeToString(image,Base64.NO_WRAP)))))))
        val value=fixture(Unit,"chat/completions",envelope,stillAllowed);check()
        val content=value.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(image,0,image.size,bounds)
        val result=SyntheticRecognition.parseRecognition(content,imageWidth=bounds.outWidth,imageHeight=bounds.outHeight,
            livePaymentAt=livePaymentAt,expenseOnly=policy==RecognitionPolicy.AUTOMATIC_PAYMENT)
        val crop=if(result.bill!=null&&result.iconRegion!=null)MerchantIconCropper.evaluate(image,result.iconRegion) else null
        check()
        return result.copy(screenshot=image,merchantIcon=crop?.bytes,
            iconStatus=if(crop?.bytes!=null)"saved" else if(crop!=null)"crop_rejected" else result.iconStatus,iconDetails=crop?.details.orEmpty())
    }
}
