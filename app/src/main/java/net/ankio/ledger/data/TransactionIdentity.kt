package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject

/** The namespace belongs to the issuer of an identifier, never to the merchant name. */
data class TransactionIdentity(val scope: String, val value: String)

internal object TransactionIdentities {
    private val reservedLabels=Regex("(?:商户\\s*(?:交易号|交易单号|订单号|订单编号|转账单号|充值单号|提现单号)|原\\s*(?:交易号|交易单号|订单号|订单编号|转账单号|充值单号|提现单号))\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{3,159})")
    private val packageName=Regex("[A-Za-z0-9_.]+")
    private val paymentScopes=setOf("alipay","wechat")
    private val issuerPrefixes=listOf("商户","原","支付宝","微信","微信支付","淘宝")
    private val explicitLabels=listOf(
        "alipay" to Regex("(?:支付宝\\s*(?:交易号|交易单号|转账单号|转账号|充值单号|提现单号))\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{3,159})"),
        "wechat" to Regex("(?:微信(?:支付)?\\s*(?:支付单号|交易号|交易单号|转账单号|转账号|充值单号|提现单号))\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{3,159})"),
        "app:com.taobao.taobao" to Regex("(?:淘宝\\s*订单号|淘宝\\s*订单编号)\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{3,159})"),
    )
    private val paymentLabel=Regex("(?:(?<![\\p{L}\\p{N}_])(?:交易号|交易单号|转账单号|转账号|充值单号|提现单号))\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{3,159})")
    private val orderLabel=Regex("(?:(?<![\\p{L}\\p{N}_])(?:订单编号|订单号码|订单号))\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{3,159})")
    fun reservedPrimary(capture:Capture,value:String)=reservedLabels.findAll(capture.text).any { it.groupValues[1]==value }
    private fun validScope(scope:String)=scope in paymentScopes ||
        scope.startsWith("app:") && scope.length<=200 && packageName.matches(scope.removePrefix("app:")) && CapturePrivacy.manualPackageAllowed(scope.removePrefix("app:"))
    fun valid(id:TransactionIdentity)=validScope(id.scope) && id.value.length in 4..160 && id.value.all { it.isLetterOrDigit() || it in "-_" }
    fun encode(ids:List<TransactionIdentity>):String=JSONArray().apply {
        ids.distinct().forEach { require(valid(it));put(JSONObject().put("scope",it.scope).put("value",it.value)) }
    }.toString()
    fun decode(text:String):List<TransactionIdentity> {
        val array=JSONArray(text);require(array.length()<=16)
        return (0 until array.length()).map { val id=array.getJSONObject(it)
            TransactionIdentity(id.getString("scope"),id.getString("value")).also { require(valid(it)) }
        }.distinct().also { require(it.groupBy { it.scope }.all { (_,values)->values.size==1 }) }
    }
    fun from(capture:Capture,bill:ParsedBill):List<TransactionIdentity> {
        val result=mutableListOf<TransactionIdentity>()
        val reserved=mutableSetOf<String>()
        fun labelled(pattern:Regex,scope:String,generic:Boolean=false) {
            pattern.findAll(capture.text).forEach {
                // A separated issuer prefix ("支付宝 交易号") is still an explicit
                // issuer label, not a generic field owned by the foreground app.
                if(generic && issuerPrefixBefore(capture.text,it.range.first))return@forEach
                result.add(TransactionIdentity(scope,it.groupValues[1]))
            }
        }
        explicitLabels.forEach { (scope,pattern)->labelled(pattern,scope) }
        reservedLabels.findAll(capture.text).forEach { reserved.add(it.groupValues[1]) }
        val scope=DuplicateDetector.identityScope(capture)
        if(scope!=null && validScope(scope)) {
            labelled(if(scope in paymentScopes)paymentLabel else orderLabel,scope,generic=true)
            bill.transactionId?.takeIf { value->value !in reserved && result.none { it.value==value } }?.let { result.add(TransactionIdentity(scope,it)) }
        }
        // Ambiguous OCR never establishes a namespace link.
        return result.filter(::valid).distinct().groupBy { it.scope }.values.filter { it.size==1 }.flatten()
    }

    private fun issuerPrefixBefore(text:String,offset:Int):Boolean {
        var end=offset
        while(end>0 && text[end-1].isWhitespace())end--
        return issuerPrefixes.any { prefix->end>=prefix.length && text.regionMatches(end-prefix.length,prefix,0,prefix.length) }
    }
}
