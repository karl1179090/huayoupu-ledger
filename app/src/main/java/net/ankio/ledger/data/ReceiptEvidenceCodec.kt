package net.ankio.ledger.data

import net.ankio.ledger.core.ReceiptEvidence
import net.ankio.ledger.core.ReceiptField
import org.json.JSONObject

/** Shared bounded record/backup/detail representation. No raw page text is reconstructed on read. */
object ReceiptEvidenceCodec {
    const val MAX_BYTES = 32 * 1024
    private val fields = setOf("merchant", "productDescription", "transactionDescription", "orderRemark",
        "serviceProvider", "receiptIssuer", "sourceApp")

    fun json(receipt: ReceiptEvidence?): Any = receipt?.let { evidence ->
        fun field(value: ReceiptField?): Any = value?.let {
            JSONObject().put("value", it.value).put("rawText", it.rawText).put("source", it.source).put("blockId", it.blockId)
        } ?: JSONObject.NULL
        JSONObject().put("version", evidence.version).put("blockId", evidence.blockId)
            .put("merchant", field(evidence.merchant)).put("productDescription", field(evidence.productDescription))
            .put("transactionDescription", field(evidence.transactionDescription)).put("orderRemark", field(evidence.orderRemark))
            .put("serviceProvider", field(evidence.serviceProvider)).put("receiptIssuer", field(evidence.receiptIssuer))
            .put("sourceApp", field(evidence.sourceApp)).also { require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    } ?: JSONObject.NULL

    fun encode(receipt: ReceiptEvidence?): String? = receipt?.let { json(it).toString() }
    fun decode(text: String): ReceiptEvidence {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "凭证信息超过容量限制" }
        return decode(JSONObject(text))
    }

    fun decode(obj: JSONObject): ReceiptEvidence {
        require(obj.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(obj.keys().asSequence().toSet().all { it in fields || it in setOf("version", "blockId") }) { "凭证字段不支持" }
        require(obj.get("version") is Int && obj.getInt("version") == 1) { "凭证版本不支持" }
        fun text(value: JSONObject, key: String) = value.get(key) as? String ?: error("凭证字段类型错误：$key")
        fun field(key: String): ReceiptField? {
            if (obj.isNull(key)) return null
            val value = obj.getJSONObject(key)
            require(value.keys().asSequence().toSet() == setOf("value", "rawText", "source", "blockId"))
            return ReceiptField(text(value, "value"), text(value, "rawText"), text(value, "source"), text(value, "blockId"))
        }
        return ReceiptEvidence(version=obj.getInt("version"), blockId=text(obj, "blockId"),
            merchant=field("merchant"), productDescription=field("productDescription"),
            transactionDescription=field("transactionDescription"), orderRemark=field("orderRemark"),
            serviceProvider=field("serviceProvider"), receiptIssuer=field("receiptIssuer"), sourceApp=field("sourceApp"))
    }
}
