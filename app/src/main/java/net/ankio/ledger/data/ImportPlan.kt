package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONObject
import java.util.UUID

/** File references never masquerade as trusted local account UIDs. */
data class WalletFileReference(val uid:String?=null,val name:String="",val kind:String?=null,val bankCode:String?=null,val cardLast4:String?=null) {
    val key:String get()=uid?.let { "uid:$it" } ?: "name:$name"
    fun json()=JSONObject().put("version",1).put("uid",uid ?: JSONObject.NULL).put("name",name)
        .put("kind",kind ?: JSONObject.NULL).put("bankCode",bankCode ?: JSONObject.NULL).put("cardLast4",cardLast4 ?: JSONObject.NULL).toString()
    companion object {
        fun from(value:String?):WalletFileReference?=value?.let { text->val j=JSONObject(text)
            require(j.optInt("version",1)==1) { "钱包引用版本不支持" }
            fun optional(key:String)=j.optString(key).takeIf { !j.isNull(key) && it.isNotEmpty() }
            WalletFileReference(optional("uid"),j.optString("name"),optional("kind"),optional("bankCode"),optional("cardLast4")) }
        fun of(account:LedgerAccount)=WalletFileReference(account.uid,account.name,account.kind,account.bankCode,account.cardLast4)
    }
}
data class ImportMapping(val reference:WalletFileReference,val accountUid:String?,val suggestions:List<String>,val confirmed:Boolean)
data class BillImportOptions(val linkWalletBalances:Boolean=false,val useDefaultWallet:Boolean=false,val mappings:Map<String,String> = emptyMap()) {
    fun digest()=PortableCsv.digest(JSONObject().put("link",linkWalletBalances).put("default",useDefaultWallet)
        .put("mappings",JSONObject(mappings.toSortedMap())).toString().toByteArray())
}
/** A confirmed preview carries preallocated UIDs, deterministic facts and a stable retry token. */
data class ImportPlan(
    val kind:String,val schema:String,val fileSha256:String,val optionsDigest:String,val factsDigest:String,
    val submissionToken:String=UUID.randomUUID().toString(),val records:List<LedgerRecord> = emptyList(),
    val errors:List<LedgerCsvImport.RowError> = emptyList(),val duplicates:Int=0,val pending:Int=0,
    val impacts:Map<String,Long> = emptyMap(),val mappings:List<ImportMapping> = emptyList(),
)
