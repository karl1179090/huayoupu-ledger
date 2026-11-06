package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned financial metadata is validated before the ledger transaction starts. */
internal object WalletBackupCodec {
    private val sources=setOf("MANUAL","PAYMENT_METHOD","APP","DEFAULT")
    private val reviews=setOf("WALLET_REQUIRED","BALANCE_LIMIT","PAYMENT_EVIDENCE_CONFLICT","PAYMENT_METHOD_AMBIGUOUS",
        "PAYMENT_METHOD_UNSUPPORTED","PAYMENT_METHOD_UNMATCHED","MISSING_WALLET_LINK")
    private val kinds=setOf("POST","EDIT","REFUND","DELETE","UNDO_DELETE","TRANSFER","CALIBRATION","RESTORE_SNAPSHOT")
    private fun uid(value:String)=value.also { require(UUID.fromString(it).toString()==it) { "钱包标识无效" } }
    private fun number(json:JSONObject,key:String):Long {
        val value=json.get(key);require(value is Int || value is Long) { "钱包字段必须为整数：$key" };return (value as Number).toLong()
    }
    private fun optional(json:JSONObject,key:String,max:Int=160):String? = if(json.isNull(key))null else
        (json.get(key) as? String ?: error("钱包字段必须为文本：$key")).also { require(it.length<=max) }
    private fun bool(json:JSONObject,key:String)=json.get(key) as? Boolean ?: error("钱包字段必须为布尔值：$key")
    fun evidenceJson(e:PaymentMethodEvidence):JSONObject=JSONObject().put("version",1).put("medium",e.medium).put("role",e.role)
        .put("bankCode",e.bankCode?:JSONObject.NULL).put("cardLast4",e.cardLast4?:JSONObject.NULL)
        .put("platform",e.platform?:JSONObject.NULL).put("rawLabel",e.rawLabel).put("rawValue",e.rawValue)
        .put("ambiguous",e.ambiguous).put("reason",e.reason?:JSONObject.NULL)
    fun evidence(json:JSONObject):PaymentMethodEvidence {
        require(number(json,"version")==1L)
        val medium=optional(json,"medium",32)!!;require(medium in setOf("BANK_CARD","WECHAT_BALANCE","ALIPAY_BALANCE","UNKNOWN","UNSUPPORTED"))
        val role=optional(json,"role",16)!!;require(role in setOf("DEBIT","CREDIT"))
        val bank=optional(json,"bankCode",32);require(bank==null || bank.matches(Regex("[a-z][a-z0-9_]{1,31}")))
        val last=optional(json,"cardLast4",4);require(last==null || last.matches(Regex("[0-9]{4}")))
        val platform=optional(json,"platform",16);require(platform==null || platform in setOf("wechat","alipay"))
        return PaymentMethodEvidence(medium,role,bank,last,platform,optional(json,"rawLabel",80)!!,
            optional(json,"rawValue",160)!!,bool(json,"ambiguous"),optional(json,"reason",160))
    }
    fun standaloneJson(e:StandaloneRefundEvidence)=JSONObject().put("version",1).put("provider",e.provider)
        .put("refundedCents",e.refundedCents).put("refundId",e.refundId?:JSONObject.NULL)
        .put("originalTransactionId",e.originalTransactionId?:JSONObject.NULL)
    fun standalone(json:JSONObject):StandaloneRefundEvidence {
        require(number(json,"version")==1L)
        val provider=optional(json,"provider",16)!!;require(provider in setOf("wechat","alipay"))
        val amount=number(json,"refundedCents");require(amount in 1..Money.MAX_CENTS)
        return StandaloneRefundEvidence(provider,amount,optional(json,"refundId")?.also { require(it.isNotBlank()) },
            optional(json,"originalTransactionId")?.also { require(it.isNotBlank()) })
    }
    fun encode(snapshot:WalletSnapshot):JSONObject {
        val settings=JSONObject().put("defaultAccountUid",snapshot.settings.defaultAccountUid?:JSONObject.NULL)
            .put("revision",snapshot.settings.revision).put("appBindings",JSONObject(snapshot.settings.appBindings))
        val links=JSONArray()
        snapshot.links.forEach { link->links.put(JSONObject().put("recordUid",link.recordUid).put("eligibility",link.eligibility.name)
            .put("accountUid",link.accountUid?:JSONObject.NULL).put("selectionSource",link.selectionSource?:JSONObject.NULL)
            .put("manualLocked",link.manualLocked).put("paymentEvidence",link.paymentEvidence?.let(::JSONObject)?:JSONObject.NULL)
            .put("evidenceFingerprint",link.evidenceFingerprint?:JSONObject.NULL).put("appliedAccountUid",link.appliedAccountUid?:JSONObject.NULL)
            .put("appliedCents",link.appliedCents).put("reviewCode",link.reviewCode?:JSONObject.NULL).put("revision",link.revision)) }
        val movements=JSONArray()
        snapshot.movements.forEach { m->movements.put(JSONObject().put("movementUid",m.movementUid).put("operationUid",m.operationUid)
            .put("accountUid",m.accountUid).put("recordUid",m.recordUid?:JSONObject.NULL).put("kind",m.kind).put("deltaCents",m.deltaCents)
            .put("balanceBefore",m.balanceBefore).put("balanceAfter",m.balanceAfter).put("createdAt",m.createdAt).put("summary",m.summary)) }
        return JSONObject().put("settings",settings).put("links",links).put("movements",movements)
    }
    fun decode(json:JSONObject):WalletSnapshot {
        val settings=json.getJSONObject("settings")
        val default=optional(settings,"defaultAccountUid",36)?.let(::uid)
        val revision=number(settings,"revision").also { require(it>=0) }
        val apps=settings.getJSONObject("appBindings");require(apps.length()<=10000)
        val bindings=apps.keys().asSequence().associateWith { key->
            require(key.length<=200 && key.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
            uid(apps.get(key) as? String ?: error("应用钱包引用错误"))
        }
        val links=json.getJSONArray("links");require(links.length()<=100000)
        val decodedLinks=(0 until links.length()).map { index->
            val link=links.getJSONObject(index)
            RecordWalletLink(uid(link.getString("recordUid")),WalletEligibility.valueOf(link.getString("eligibility")),
                optional(link,"accountUid",36)?.let(::uid),optional(link,"selectionSource",32)?.also { require(it in sources) },
                bool(link,"manualLocked"),if(link.isNull("paymentEvidence"))null else evidenceJson(evidence(link.getJSONObject("paymentEvidence"))).toString(),
                optional(link,"evidenceFingerprint",64)?.also { require(it.matches(Regex("[0-9a-f]{64}"))) },
                optional(link,"appliedAccountUid",36)?.let(::uid),number(link,"appliedCents"),
                optional(link,"reviewCode",48)?.also { require(it in reviews) },number(link,"revision").also { require(it>=0) })
        }
        val movements=json.getJSONArray("movements");require(movements.length()<=500000)
        val decodedMovements=(0 until movements.length()).map { index->
            val m=movements.getJSONObject(index)
            WalletMovement(uid(m.getString("movementUid")),uid(m.getString("operationUid")),uid(m.getString("accountUid")),
                optional(m,"recordUid",36)?.let(::uid),optional(m,"kind",32)!!.also { require(it in kinds) },number(m,"deltaCents"),
                number(m,"balanceBefore"),number(m,"balanceAfter"),number(m,"createdAt"),optional(m,"summary",80)!!)
        }
        return WalletSnapshot(WalletSettings(default,bindings,revision),decodedLinks,decodedMovements)
    }
    fun validate(records:List<LedgerRecord>,accounts:List<LedgerAccount>,snapshot:WalletSnapshot) {
        val byAccount=accounts.associateBy { it.uid };require(byAccount.size==accounts.size)
        accounts.forEach(LedgerAccount::validate)
        fun reference(uid:String?,usable:Boolean=false) {
            if(uid==null)return
            val account=requireNotNull(byAccount[uid]) { "钱包引用缺少账户" }
            require(account.type==AccountType.ASSET && (!usable || !account.archived)) { "钱包引用了无效账户" }
        }
        reference(snapshot.settings.defaultAccountUid,true);snapshot.settings.appBindings.values.forEach { reference(it,true) }
        val byRecord=records.associateBy { it.uid };require(byRecord.size==records.size)
        val byLink=snapshot.links.associateBy { it.recordUid }
        require(byLink.size==snapshot.links.size && byLink.keys==byRecord.keys) { "账单钱包关联缺失或重复" }
        snapshot.links.forEach { link->
            val record=byRecord.getValue(link.recordUid)
            require(link.revision>=0 && link.appliedCents in -Money.MAX_CENTS..Money.MAX_CENTS)
            reference(link.accountUid);reference(link.appliedAccountUid)
            require(link.accountUid!=null || link.selectionSource==null)
            require(link.selectionSource==null || link.selectionSource in sources)
            require(!link.manualLocked || link.selectionSource=="MANUAL")
            require(link.selectionSource!="MANUAL" || link.manualLocked)
            require(link.reviewCode==null || link.reviewCode in reviews)
            val evidence=link.paymentEvidence?.let { evidence(JSONObject(it)) }
            require(record.bill.paymentMethod==evidence) { "付款方式与钱包证据不一致" }
            if(link.evidenceFingerprint!=null)require(evidence!=null && link.evidenceFingerprint==WalletResolver.fingerprint(evidence))
            val target=WalletEffectCalculator.target(record.bill.amountCents,record.bill.direction,record.state==RecordState.POSTED,
                eligibilityActive=link.eligibility==WalletEligibility.ACTIVE)
            require(target==link.appliedCents) { "账单生效余额与净额不一致" }
            if(link.eligibility==WalletEligibility.ACTIVE && record.state==RecordState.POSTED) {
                require(link.accountUid!=null && link.appliedAccountUid==link.accountUid) { "已入账账单缺少生效钱包" }
            }
            if(link.appliedCents!=0L)require(link.appliedAccountUid!=null)
            if(record.bill.standaloneRefund!=null)require(record.state==RecordState.REVIEW && record.bill.amountCents==null && link.appliedCents==0L)
        }
        val movements=mutableSetOf<String>()
        snapshot.movements.forEach { m->
            require(movements.add(m.movementUid));uid(m.movementUid);uid(m.operationUid)
            require(m.accountUid in byAccount && m.kind in kinds && m.summary.length<=80)
            require(m.deltaCents in -2*Money.MAX_CENTS..2*Money.MAX_CENTS && m.balanceBefore in -Money.MAX_CENTS..Money.MAX_CENTS &&
                m.balanceAfter in -Money.MAX_CENTS..Money.MAX_CENTS && Math.addExact(m.balanceBefore,m.deltaCents)==m.balanceAfter)
            require(m.createdAt in 1..4_102_444_800_000L)
            // An unlinked asset may later become a liability; its old calibration/snapshot remains signed history.
            if(byAccount.getValue(m.accountUid).type==AccountType.LIABILITY && m.kind !in setOf("CALIBRATION","RESTORE_SNAPSHOT"))
                require(m.balanceBefore>=0 && m.balanceAfter>=0)
            m.recordUid?.let(::uid) // Deleted records intentionally remain in the audit trail.
        }
    }
}
