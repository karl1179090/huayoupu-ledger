package net.ankio.ledger.data

import net.ankio.ledger.core.FeeMode
import org.json.JSONObject
import java.util.UUID

internal object BackupValues {
    fun uid(value:String)=value.also { require(runCatching { UUID.fromString(it).toString() }.getOrNull()==it) { "备份标识须为规范 UUID" } }
    fun number(j:JSONObject,key:String):Long { val v=j.get(key);require(v is Int || v is Long) { "备份字段必须为整数：$key" };return (v as Number).toLong() }
    fun text(j:JSONObject,key:String,max:Int=160):String?=if(j.isNull(key))null else (j.get(key) as? String ?: error("备份字段必须为文本：$key")).also { require(it.length<=max) { "备份字段过长：$key" } }
    fun bool(j:JSONObject,key:String)=j.get(key) as? Boolean ?: error("备份字段必须为布尔值：$key")
    fun keys(j:JSONObject,allowed:Set<String>) { require(j.keys().asSequence().all { it in allowed }) { "备份包含未知字段" } }
    val sources=setOf("MANUAL","PAYMENT_METHOD","APP","DEFAULT","IMPORT")
    val reviews=setOf("BANK_DIRECTION_REQUIRED","WALLET_REQUIRED","BALANCE_LIMIT","PAYMENT_EVIDENCE_CONFLICT","PAYMENT_METHOD_AMBIGUOUS","PAYMENT_METHOD_UNSUPPORTED",
        "PAYMENT_METHOD_UNMATCHED","MISSING_WALLET_LINK","INVALID_WALLET","INVALID_AMOUNT","TRANSFER_SAME_WALLET","TRANSFER_WALLET_REQUIRED",
        "TRANSFER_ACCOUNT_UNSUPPORTED","TRANSFER_FEE_AMBIGUOUS","TRANSFER_AMOUNT_CONFLICT","TRANSFER_IDENTITY_CONFLICT",
        "TRANSFER_STATUS_REQUIRED","TRANSFER_PAYMENT_EVIDENCE_CONFLICT","TRANSFER_REFUND_CONFLICT","TRANSFER_REVIEW_REQUIRED")
    val kinds=setOf("POST","EDIT","REFUND","DELETE","UNDO_DELETE","TRANSFER","CALIBRATION","RESTORE_SNAPSHOT","BILL_IMPORT","WALLET_IMPORT_SNAPSHOT")
    fun source(value:String?)=value.also { require(it==null || it in sources) { "钱包选择来源无效" } }
    fun review(value:String?)=value.also { require(it==null || it in reviews) { "钱包核对原因无效" } }
    fun fingerprint(value:String?)=value.also { require(it==null || it.matches(Regex("[0-9a-f]{64}"))) { "证据指纹无效" } }
    fun reference(json:JSONObject):String {
        keys(json,setOf("version","uid","name","kind","bankCode","cardLast4"));require(number(json,"version")==1L) { "钱包引用版本无效" }
        val id=text(json,"uid",36)?.let(::uid);val name=text(json,"name",40) ?: error("钱包引用缺少名称字段")
        require(id!=null || name.isNotBlank()) { "钱包历史引用为空" }
        val kind=text(json,"kind",32);val bank=text(json,"bankCode",32);val last=text(json,"cardLast4",4)
        require(bank==null || bank.matches(Regex("[a-z][a-z0-9_]{1,31}")))
        require(last==null || last.matches(Regex("[0-9]{4}")))
        return WalletFileReference(id,name,kind,bank,last).json()
    }
}

internal object TransferBackupCodec {
    private val fields=setOf("recordUid","fromAccountUid","toAccountUid","feeCents","feeMode","fromSelectionSource","toSelectionSource",
        "fromManualLocked","toManualLocked","feeManualLocked","fromEvidence","toEvidence","fromEvidenceFingerprint","toEvidenceFingerprint",
        "fromReference","toReference","evidenceVersion","reviewCode","recognitionEvidence")
    fun encode(d:RecordTransferDetails)=JSONObject().apply {
        put("recordUid",d.recordUid);put("fromAccountUid",d.fromAccountUid ?: JSONObject.NULL);put("toAccountUid",d.toAccountUid ?: JSONObject.NULL)
        put("feeCents",d.feeCents ?: JSONObject.NULL);put("feeMode",if(d.feeCents==0L)FeeMode.EXTRA.name else d.feeMode?.name ?: JSONObject.NULL)
        put("fromSelectionSource",d.fromSelectionSource ?: JSONObject.NULL);put("toSelectionSource",d.toSelectionSource ?: JSONObject.NULL)
        put("fromManualLocked",d.fromManualLocked);put("toManualLocked",d.toManualLocked);put("feeManualLocked",d.feeManualLocked)
        put("fromEvidence",d.fromEvidence?.let(::JSONObject) ?: JSONObject.NULL);put("toEvidence",d.toEvidence?.let(::JSONObject) ?: JSONObject.NULL)
        put("fromEvidenceFingerprint",d.fromEvidenceFingerprint ?: JSONObject.NULL);put("toEvidenceFingerprint",d.toEvidenceFingerprint ?: JSONObject.NULL)
        put("fromReference",d.fromReference?.let(::JSONObject) ?: JSONObject.NULL);put("toReference",d.toReference?.let(::JSONObject) ?: JSONObject.NULL)
        put("evidenceVersion",d.evidenceVersion);put("reviewCode",d.reviewCode ?: JSONObject.NULL)
        put("recognitionEvidence",d.recognitionEvidence?.let(::JSONObject) ?: JSONObject.NULL)
    }
    fun decode(j:JSONObject):RecordTransferDetails {
        BackupValues.keys(j,fields)
        fun optional(key:String,max:Int=160)=BackupValues.text(j,key,max)
        fun evidence(key:String):String?=if(j.isNull(key))null else j.getJSONObject(key).also { WalletBackupCodec.evidence(it) }.toString()
        fun reference(key:String):String?=if(j.isNull(key))null else BackupValues.reference(j.getJSONObject(key))
        val recognition=if(j.isNull("recognitionEvidence"))null else j.getJSONObject("recognitionEvidence").toString().also { TransferEvidenceCodec.decode(it) }
        return RecordTransferDetails(BackupValues.uid(requireNotNull(optional("recordUid",36))),optional("fromAccountUid",36)?.let(BackupValues::uid),
            optional("toAccountUid",36)?.let(BackupValues::uid),if(j.isNull("feeCents"))null else BackupValues.number(j,"feeCents"),
            optional("feeMode",16)?.let(FeeMode::valueOf),BackupValues.source(optional("fromSelectionSource",32)),BackupValues.source(optional("toSelectionSource",32)),
            BackupValues.bool(j,"fromManualLocked"),BackupValues.bool(j,"toManualLocked"),evidence("fromEvidence"),evidence("toEvidence"),
            BackupValues.fingerprint(optional("fromEvidenceFingerprint",64)),BackupValues.fingerprint(optional("toEvidenceFingerprint",64)),
            reference("fromReference"),reference("toReference"),BackupValues.number(j,"evidenceVersion").also { require(it==1L) }.toInt(),
            BackupValues.review(optional("reviewCode",64)),BackupValues.bool(j,"feeManualLocked"),recognition).let { if(it.feeCents==0L)it.copy(feeMode=FeeMode.EXTRA) else it }
    }
}
