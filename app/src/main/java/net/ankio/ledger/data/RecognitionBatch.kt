package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Financially committed outcomes. recordId is only a local navigation projection of recordUID. */
data class BatchItemResult(val candidateId:CandidateId,val operationKey:String,val status:String,val reason:String,
    val amountCents:Long?,val direction:Direction?,val merchant:String,val accountingDate:String?,
    val recordUID:String?=null,val recordId:Long?=null)
data class BatchCounts(val added:Int,val skipped:Int,val review:Int,val failed:Int)
data class RecognitionBatchResult(val operationUID:String,val items:List<BatchItemResult>) {
    val complete get()=items.none { it.status in setOf("PENDING","FAILED") }
    val counts get()=BatchCounts(items.count { it.status=="ADDED" },items.count { it.status=="SKIPPED" },
        items.count { it.status=="REVIEW" },items.count { it.status=="FAILED" })
    val message get()="新增 ${counts.added} · 跳过 ${counts.skipped} · 待确认 ${counts.review} · 失败 ${counts.failed}"
    val taskStatus get()=when { !complete->"FAILED";counts.review>0->"REVIEW";counts.added>0->"SUCCEEDED";else->"SKIPPED" }
    fun json()=JSONObject().put("operationUID",operationUID).put("complete",complete)
        .put("counts",JSONObject().put("added",counts.added).put("skipped",counts.skipped).put("review",counts.review).put("failed",counts.failed))
        .put("items",JSONArray(items.map { item -> JSONObject().put("candidateId",item.candidateId.value)
            .put("operationKey",item.operationKey).put("status",item.status).put("reason",item.reason)
            .put("amountCents",item.amountCents ?: JSONObject.NULL).put("direction",item.direction?.name ?: JSONObject.NULL)
            .put("merchant",item.merchant).put("accountingDate",item.accountingDate ?: JSONObject.NULL)
            .put("recordUID",item.recordUID ?: JSONObject.NULL).put("recordId",item.recordId ?: JSONObject.NULL) }))
}

internal data class FrozenBatchItem(val candidate:TransactionCandidate,val operationKey:String,val historyRecordUID:String?,
    val status:String="PENDING",val reason:String="",val recordUID:String?=null)
internal data class FrozenRecognitionBatch(val operationKey:String,val capture:Capture,val zoneId:String,val items:List<FrozenBatchItem>)

/** Portable frozen input and receipts, independent of local task/record row IDs. */
internal object RecognitionBatchCodec {
    const val MAX_BYTES=512*1024
    private fun text(j:JSONObject,key:String)=j.get(key) as? String ?: error("批次字段必须为文字：$key")
    private fun number(j:JSONObject,key:String):Long {
        val value=j.get(key);require(value is Int || value is Long) { "批次字段必须为整数：$key" };return (value as Number).toLong()
    }
    private fun bool(j:JSONObject,key:String)=j.get(key) as? Boolean ?: error("批次字段必须为布尔值：$key")
    private fun optional(j:JSONObject,key:String)=if(j.isNull(key))null else text(j,key)
    fun bill(b:ParsedBill)=JSONObject().put("amount",b.amountCents ?: JSONObject.NULL).put("direction",b.direction?.name ?: JSONObject.NULL)
        .put("merchant",b.merchant).put("category",b.category).put("occurredAt",b.occurredAt)
        .put("transactionId",b.transactionId ?: JSONObject.NULL).put("reliable",b.reliable).put("reason",b.reason).put("timeExplicit",b.timeExplicit)
        .put("paymentMethod",b.paymentMethod?.let(WalletBackupCodec::evidenceJson) ?: JSONObject.NULL)
        .put("receipt",ReceiptEvidenceCodec.json(b.receipt)).put("categoryDecision",b.categoryDecision?.let(ClassificationCodec::decision) ?: JSONObject.NULL)
        .put("transfer",b.transfer?.let { JSONObject(TransferEvidenceCodec.encode(it)) } ?: JSONObject.NULL)
        .put("standaloneRefund",b.standaloneRefund?.let(WalletBackupCodec::standaloneJson) ?: JSONObject.NULL)
        .put("refund",b.refund?.let { JSONObject().put("provider",it.provider).put("originalAmountCents",it.originalAmountCents)
            .put("refundedCents",it.refundedCents).put("mode",it.mode).put("refundId",it.refundId ?: JSONObject.NULL) } ?: JSONObject.NULL)
    fun readBill(j:JSONObject)=ParsedBill(if(j.isNull("amount"))null else number(j,"amount"),optional(j,"direction")?.let(Direction::valueOf),
        text(j,"merchant"),text(j,"category"),number(j,"occurredAt"),optional(j,"transactionId"),bool(j,"reliable"),
        text(j,"reason"),bool(j,"timeExplicit"),j.optJSONObject("refund")?.let {
            RefundInfo(text(it,"provider"),number(it,"originalAmountCents"),number(it,"refundedCents"),text(it,"mode"),optional(it,"refundId")) },
        j.optJSONObject("paymentMethod")?.let(WalletBackupCodec::evidence),j.optJSONObject("standaloneRefund")?.let(WalletBackupCodec::standalone),
        j.optJSONObject("transfer")?.let { TransferEvidenceCodec.decode(it.toString()) },j.optJSONObject("receipt")?.let(ReceiptEvidenceCodec::decode),
        ClassificationCodec.readDecision(j.optJSONObject("categoryDecision")))
    fun encode(b:FrozenRecognitionBatch):String=JSONObject().put("version",1).put("operationKey",b.operationKey).put("operationUID",b.operationKey.removePrefix("recognition:"))
        .put("source",b.capture.source.name).put("origin",b.capture.origin).put("rawText",b.capture.text).put("capturedAt",b.capture.receivedAt)
        .put("zoneId",b.zoneId).put("items",JSONArray(b.items.map { item -> JSONObject().put("candidateId",item.candidate.id.value)
            .put("rawText",item.candidate.rawText ?: JSONObject.NULL).put("bill",bill(item.candidate.bill)).put("operationKey",item.operationKey)
            .put("historyRecordUID",item.historyRecordUID ?: JSONObject.NULL).put("status",item.status).put("reason",item.reason)
            .put("recordUID",item.recordUID ?: JSONObject.NULL) })).toString().also { decode(it) }
    fun validate(batches:JSONArray) {
        require(batches.length()<=100000)
        val keys=HashSet<String>()
        for(index in 0 until batches.length())require(keys.add(decode(batches.getJSONObject(index).toString()).operationKey)) { "重复的识别批次" }
    }
    fun decode(text:String):FrozenRecognitionBatch {
        require(text.toByteArray().size<=MAX_BYTES)
        val j=JSONObject(text);require(j.get("version") is Int && j.getInt("version")==1)
        val operation=text(j,"operationKey");require(operation.isNotBlank() && operation.length<=160)
        require(text(j,"operationUID")==operation.removePrefix("recognition:"))
        val zone=text(j,"zoneId");java.time.ZoneId.of(zone)
        val array=j.getJSONArray("items");require(array.length() in 1..32)
        val items=(0 until array.length()).map { index -> val item=array.getJSONObject(index)
            val id=CandidateId(text(item,"candidateId"));val key=text(item,"operationKey")
            require(key.isNotBlank() && key.length<=160 && id!=CandidateId.SINGLE && key==candidateOperationKey(operation,id))
            val status=text(item,"status");require(status in setOf("PENDING","ADDED","SKIPPED","REVIEW","FAILED"))
            val bill=readBill(item.getJSONObject("bill"))
            require(bill.amountCents==null || bill.amountCents in 0..Money.MAX_CENTS)
            require(bill.occurredAt>0 && bill.merchant.length<=512 && bill.reason.length<=2048)
            val raw=optional(item,"rawText");require(raw==null || raw.length<=32768)
            require(text(item,"reason").length<=2048)
            require(status !in setOf("ADDED","REVIEW") || optional(item,"recordUID")!=null)
            require(status !in setOf("PENDING","FAILED") || optional(item,"recordUID")==null)
            require(optional(item,"historyRecordUID")==null || status=="SKIPPED")
            listOfNotNull(optional(item,"historyRecordUID"),optional(item,"recordUID")).forEach { require(java.util.UUID.fromString(it).toString()==it) }
            FrozenBatchItem(TransactionCandidate(id,bill,raw),key,optional(item,"historyRecordUID"),status,text(item,"reason"),optional(item,"recordUID"))
        }
        require(items.map { it.candidate.id }.distinct().size==items.size && items.map { it.operationKey }.distinct().size==items.size)
        val raw=text(j,"rawText");require(raw.length<=32768 && text(j,"origin").length<=512 && number(j,"capturedAt")>0)
        return FrozenRecognitionBatch(operation,Capture(Source.valueOf(text(j,"source")),text(j,"origin"),raw,number(j,"capturedAt")),zone,items)
    }
}
