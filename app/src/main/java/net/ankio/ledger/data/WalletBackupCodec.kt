package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned financial metadata is validated before the ledger transaction starts. */
internal object WalletBackupCodec {
    private val sources=BackupValues.sources
    private val reviews=BackupValues.reviews
    private val kinds=BackupValues.kinds
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
        .put("ambiguous",e.ambiguous).put("reason",e.reason?:JSONObject.NULL).also { if(e.executed)it.put("executed",true) }
    fun evidence(json:JSONObject):PaymentMethodEvidence {
        BackupValues.keys(json,setOf("version","medium","role","bankCode","cardLast4","platform","rawLabel","rawValue","ambiguous","reason","executed"))
        require(number(json,"version")==1L)
        val medium=optional(json,"medium",32)!!;require(medium in setOf("BANK_CARD","WECHAT_BALANCE","ALIPAY_BALANCE","UNKNOWN","UNSUPPORTED","LIABILITY"))
        val role=optional(json,"role",16)!!;require(role in setOf("DEBIT","CREDIT","UNKNOWN"))
        val bank=optional(json,"bankCode",32);require(bank==null || bank.matches(Regex("[a-z][a-z0-9_]{1,31}")))
        val last=optional(json,"cardLast4",4);require(last==null || last.matches(Regex("[0-9]{4}")))
        val platform=optional(json,"platform",16);require(platform==null || platform in setOf("wechat","alipay"))
        return PaymentMethodEvidence(medium,role,bank,last,platform,optional(json,"rawLabel",80)!!,
            optional(json,"rawValue",160)!!,bool(json,"ambiguous"),optional(json,"reason",160),if(json.has("executed"))bool(json,"executed") else false)
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
        val settings=JSONObject().put("defaultAccountUid",snapshot.settings.defaultAccountUid ?: JSONObject.NULL)
            .put("revision",snapshot.settings.revision).put("appBindings",JSONObject(snapshot.settings.appBindings))
        val links=JSONArray(snapshot.links.map { link->JSONObject().put("recordUid",link.recordUid).put("eligibility",link.eligibility.name)
            .put("accountUid",link.accountUid ?: JSONObject.NULL).put("selectionSource",link.selectionSource ?: JSONObject.NULL)
            .put("manualLocked",link.manualLocked).put("paymentEvidence",link.paymentEvidence?.let(::JSONObject) ?: JSONObject.NULL)
            .put("evidenceFingerprint",link.evidenceFingerprint ?: JSONObject.NULL).put("reviewCode",link.reviewCode ?: JSONObject.NULL)
            .put("revision",link.revision).put("accountReference",link.accountReference?.let(::JSONObject) ?: JSONObject.NULL) })
        val effects=JSONArray();snapshot.effects.forEach { (recordUid,values)->values.forEach { (accountUid,amount)->
            effects.put(JSONObject().put("recordUid",recordUid).put("accountUid",accountUid).put("appliedCents",amount))
        } }
        val movements=JSONArray(snapshot.movements.map { m->JSONObject().put("movementUid",m.movementUid).put("operationUid",m.operationUid)
            .put("accountUid",m.accountUid).put("recordUid",m.recordUid ?: JSONObject.NULL).put("kind",m.kind).put("deltaCents",m.deltaCents)
            .put("balanceBefore",m.balanceBefore).put("balanceAfter",m.balanceAfter).put("createdAt",m.createdAt).put("summary",m.summary) })
        val aliases=JSONArray(snapshot.aliases.toSortedMap().map { (source,target)->JSONObject().put("sourceUid",source).put("localAccountUid",target) })
        val audits=JSONArray(snapshot.audits.map { a->JSONObject().put("batchUid",a.batchUid).put("kind",a.kind).put("fileSha256",a.fileSha256)
            .put("optionsDigest",a.optionsDigest).put("summary",JSONObject(a.summary)).put("createdAt",a.createdAt).put("recordUids",JSONArray(a.recordUids)) })
        return JSONObject().put("version",1).put("settings",settings).put("links",links).put("effects",effects)
            .put("transfers",JSONArray(snapshot.transfers.map(TransferBackupCodec::encode))).put("movements",movements).put("aliases",aliases).put("audits",audits)
    }
    fun decode(json:JSONObject,metadataVersion:Int=5):WalletSnapshot {
        val modern=metadataVersion>=5
        if(modern) { BackupValues.keys(json,setOf("version","settings","links","effects","transfers","movements","aliases","audits"));require(number(json,"version")==1L) }
        val settings=json.getJSONObject("settings");val default=optional(settings,"defaultAccountUid",36)?.let(::uid)
        val revision=number(settings,"revision").also { require(it>=0) };val apps=settings.getJSONObject("appBindings");require(apps.length()<=10000)
        val bindings=apps.keys().asSequence().associateWith { key->require(key.length<=200 && key.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
            uid(apps.get(key) as? String ?: error("应用钱包引用错误")) }
        val array=json.getJSONArray("links");require(array.length()<=100000)
        val links=(0 until array.length()).map { i->val link=array.getJSONObject(i)
            if(modern)BackupValues.keys(link,setOf("recordUid","eligibility","accountUid","selectionSource","manualLocked","paymentEvidence","evidenceFingerprint","reviewCode","revision","accountReference"))
            val eligibility=WalletEligibility.valueOf(requireNotNull(optional(link,"eligibility",32)))
            require(modern || eligibility!=WalletEligibility.IMPORT_EXCLUDED)
            RecordWalletLink(uid(requireNotNull(optional(link,"recordUid",36))),eligibility,optional(link,"accountUid",36)?.let(::uid),
                optional(link,"selectionSource",32)?.also { require(it in if(modern)sources else sources-"IMPORT") },bool(link,"manualLocked"),
                if(link.isNull("paymentEvidence"))null else link.getJSONObject("paymentEvidence").also { evidence(it) }.toString(),
                BackupValues.fingerprint(optional(link,"evidenceFingerprint",64)),if(modern)null else optional(link,"appliedAccountUid",36)?.let(::uid),
                if(modern)0L else number(link,"appliedCents"),BackupValues.review(optional(link,"reviewCode",64)),number(link,"revision").also { require(it>=0) },
                if(modern && !link.isNull("accountReference"))BackupValues.reference(link.getJSONObject("accountReference")) else null)
        }
        val effects=links.associate { it.recordUid to linkedMapOf<String,Long>() }.toMutableMap()
        if(modern) {
            val values=json.getJSONArray("effects");require(values.length()<=200000);val pairs=mutableSetOf<Pair<String,String>>()
            for(i in 0 until values.length()) { val e=values.getJSONObject(i);BackupValues.keys(e,setOf("recordUid","accountUid","appliedCents"))
                val record=uid(requireNotNull(optional(e,"recordUid",36)));val account=uid(requireNotNull(optional(e,"accountUid",36)));val amount=number(e,"appliedCents")
                require(pairs.add(record to account)) { "账单钱包影响重复" };require(amount!=0L && amount in -Money.MAX_CENTS..Money.MAX_CENTS)
                effects.getOrPut(record) { linkedMapOf() }[account]=amount
            }
        } else links.filter { it.appliedCents!=0L }.forEach { link->effects.getValue(link.recordUid)[requireNotNull(link.appliedAccountUid)]=link.appliedCents }
        val transfers=if(modern)json.getJSONArray("transfers").let { a->require(a.length()<=100000);(0 until a.length()).map { TransferBackupCodec.decode(a.getJSONObject(it)) } } else emptyList()
        val movementArray=json.getJSONArray("movements");require(movementArray.length()<=500000)
        val movements=(0 until movementArray.length()).map { i->val m=movementArray.getJSONObject(i)
            WalletMovement(uid(requireNotNull(optional(m,"movementUid",36))),uid(requireNotNull(optional(m,"operationUid",36))),uid(requireNotNull(optional(m,"accountUid",36))),
                optional(m,"recordUid",36)?.let(::uid),requireNotNull(optional(m,"kind",32)).also { require(it in kinds) },number(m,"deltaCents"),number(m,"balanceBefore"),
                number(m,"balanceAfter"),number(m,"createdAt"),requireNotNull(optional(m,"summary",80)))
        }
        val aliases=linkedMapOf<String,String>()
        if(modern)json.getJSONArray("aliases").let { a->require(a.length()<=100000);for(i in 0 until a.length()) {
            val entry=a.getJSONObject(i);BackupValues.keys(entry,setOf("sourceUid","localAccountUid"))
            val source=uid(requireNotNull(optional(entry,"sourceUid",36)));val target=uid(requireNotNull(optional(entry,"localAccountUid",36)))
            require(aliases.put(source,target)==null) { "钱包别名重复" }
        } }
        val audits=if(modern)json.getJSONArray("audits").let { a->require(a.length()<=100000);(0 until a.length()).map { i->val entry=a.getJSONObject(i)
            BackupValues.keys(entry,setOf("batchUid","kind","fileSha256","optionsDigest","summary","createdAt","recordUids"))
            val records=entry.getJSONArray("recordUids");require(records.length()<=50000)
            ImportAudit(uid(requireNotNull(optional(entry,"batchUid",36))),requireNotNull(optional(entry,"kind",16)),requireNotNull(optional(entry,"fileSha256",64)),
                requireNotNull(optional(entry,"optionsDigest",64)),entry.getJSONObject("summary").toString(),number(entry,"createdAt"),
                (0 until records.length()).map { uid(records.get(it) as? String ?: error("导入审计账单标识无效")) })
        } } else emptyList()
        return WalletSnapshot(WalletSettings(default,bindings,revision),links.map { WalletAccounting.project(it,effects[it.recordUid].orEmpty()) },movements,transfers,effects,aliases,audits)
    }
    fun validateRecord(record:LedgerRecord) {
        uid(record.uid);val bill=record.bill;val amount=bill.amountCents;val refund=bill.refund;val standaloneRefund=bill.standaloneRefund
        if(record.state==RecordState.POSTED)require(amount!=null && bill.direction!=null &&
            (amount in 1..Money.MAX_CENTS || amount==0L && validRefundEvidence(bill))) { "正式账单金额或交易类型无效" }
        else require(amount==null || amount in 0..Money.MAX_CENTS) { "待确认账单金额超出范围" }
        if(refund!=null) {
            require(bill.direction!=Direction.TRANSFER && standaloneRefund==null && refund.provider in setOf("wechat","alipay") &&
                refund.originalAmountCents in 0..Money.MAX_CENTS && refund.refundedCents in 0..Money.MAX_CENTS &&
                refund.mode in setOf("single","cumulative") && (refund.refundId?.length ?: 0)<=160) { "退款字段无效" }
            if(record.state==RecordState.POSTED)require(validRefundEvidence(bill)) { "正式退款净额无效" }
        }
        if(standaloneRefund!=null) {
            require(record.state==RecordState.REVIEW && amount==null && refund==null && bill.direction!=Direction.TRANSFER)
            standalone(standaloneJson(standaloneRefund))
        }
    }
    fun validate(records:List<LedgerRecord>,accounts:List<LedgerAccount>,snapshot:WalletSnapshot) {
        val byAccount=accounts.associateBy { it.uid };require(byAccount.size==accounts.size);accounts.forEach(LedgerAccount::validate)
        fun reference(id:String?,available:Boolean=false) {
            if(id==null)return;uid(id);val account=requireNotNull(byAccount[id]) { "钱包引用缺少账户" }
            require(account.type==AccountType.ASSET && (!available || !account.archived)) { "钱包引用了无效账户" }
        }
        reference(snapshot.settings.defaultAccountUid,true);snapshot.settings.appBindings.values.forEach { reference(it,true) }
        require(snapshot.settings.revision>=0)
        val byRecord=records.associateBy { it.uid };require(byRecord.size==records.size);records.forEach(::validateRecord)
        val links=snapshot.links.associateBy { it.recordUid };require(links.size==snapshot.links.size && links.keys==byRecord.keys) { "账单钱包关联缺失或重复" }
        val transfers=snapshot.transfers.associateBy { it.recordUid };require(transfers.size==snapshot.transfers.size && transfers.keys.all { it in byRecord }) { "转账资料缺失关联或重复" }
        require(snapshot.effects.keys.all { it in byRecord }) { "钱包影响没有关联账单" }
        snapshot.effects.forEach { (_,values)->values.forEach { (id,amount)->reference(id);require(amount!=0L && amount in -Money.MAX_CENTS..Money.MAX_CENTS) } }
        snapshot.links.forEach { link->
            val record=byRecord.getValue(link.recordUid);val details=transfers[link.recordUid];reference(link.accountUid)
            require(link.revision>=0 && link.selectionSource in sources+null && link.reviewCode in reviews+null)
            val payment=link.paymentEvidence?.let { evidence(JSONObject(it)) }
            val pendingPaymentAccount=record.state==RecordState.REVIEW && link.eligibility==WalletEligibility.ACTIVE &&
                link.selectionSource=="PAYMENT_METHOD" && !link.manualLocked && snapshot.effects[record.uid].orEmpty().isEmpty() &&
                (payment?.medium=="BANK_CARD" && record.bill.receipt?.bank!=null &&
                    link.reviewCode in setOf("BANK_DIRECTION_REQUIRED","PAYMENT_METHOD_UNMATCHED","PAYMENT_METHOD_AMBIGUOUS","PAYMENT_EVIDENCE_CONFLICT") ||
                    payment?.medium=="UNKNOWN" && payment.ambiguous && payment.reason=="ACTUAL_ACCOUNT_REQUIRED" && link.reviewCode=="ACTUAL_ACCOUNT_REQUIRED" ||
                    payment?.executed==true && payment.role=="DEBIT" && link.reviewCode=="ACTUAL_ACCOUNT_REQUIRED")
            require(link.accountUid!=null || link.selectionSource in setOf(null,"IMPORT") ||
                link.eligibility!=WalletEligibility.ACTIVE && link.accountReference!=null || pendingPaymentAccount)
            require((link.selectionSource=="MANUAL")==link.manualLocked)
            link.accountReference?.let { BackupValues.reference(JSONObject(it)) }
            require(record.bill.paymentMethod==payment) { "付款方式与钱包证据不一致" }
            if(link.evidenceFingerprint!=null)require(payment!=null && link.evidenceFingerprint==WalletResolver.fingerprint(payment))
            val isTransfer=record.bill.direction==Direction.TRANSFER
            val ambiguous=record.bill.direction==null && record.state==RecordState.REVIEW && details!=null
            if(isTransfer || ambiguous) {
                require(details!=null && link.accountUid==null && link.selectionSource==null && !link.manualLocked && payment==null)
                require(record.bill.refund==null && record.bill.standaloneRefund==null) { "转账不能包含消费退款" }
                require(details.feeCents==null || details.feeCents in 0..Money.MAX_CENTS)
                require(details.evidenceVersion==1 && details.reviewCode in reviews+null)
                listOf(details.fromSelectionSource to details.fromManualLocked,details.toSelectionSource to details.toManualLocked).forEach { (source,locked)->
                    require(source in sources+null && (source=="MANUAL")==locked)
                }
                reference(details.fromAccountUid);reference(details.toAccountUid)
                require(record.state!=RecordState.POSTED || details.fromAccountUid==null || details.fromAccountUid!=details.toAccountUid) { "转账钱包相同" }
                details.fromReference?.let { BackupValues.reference(JSONObject(it)) };details.toReference?.let { BackupValues.reference(JSONObject(it)) }
                listOf(details.fromEvidence to details.fromEvidenceFingerprint,details.toEvidence to details.toEvidenceFingerprint).forEach { (raw,fingerprint)->
                    val value=raw?.let { evidence(JSONObject(it)) }
                    if(fingerprint!=null)require(value!=null && fingerprint==WalletResolver.fingerprint(value))
                }
                val recognition=details.recognitionEvidence?.let(TransferEvidenceCodec::decode)
                require(record.bill.transfer==recognition) { "转账原始证据与扩展信息不一致" }
                if(ambiguous)require(details.fromAccountUid==null && details.toAccountUid==null)
                if(record.state==RecordState.POSTED) {
                    TransferCalculator.calculate(requireNotNull(record.bill.amountCents),requireNotNull(details.feeCents),requireNotNull(details.feeMode))
                    require(details.fromAccountUid!=null || details.fromReference!=null);require(details.toAccountUid!=null || details.toReference!=null)
                }
            } else require(details==null) { "普通账单不能残留转账资料" }
            val target=WalletEffectCalculator.targetEffects(record.bill.amountCents,record.bill.direction,record.state==RecordState.POSTED,
                eligibilityActive=link.eligibility==WalletEligibility.ACTIVE,accountUid=link.accountUid,transfer=details?.target())
            require(target==snapshot.effects[record.uid].orEmpty()) { "账单已生效影响与合法目标不一致" }
            if(ambiguous)require(target.isEmpty())
            if(record.bill.standaloneRefund!=null)require(record.state==RecordState.REVIEW && record.bill.amountCents==null && target.isEmpty())
        }
        val movementUids=mutableSetOf<String>()
        snapshot.movements.forEach { m->
            require(movementUids.add(uid(m.movementUid)));uid(m.operationUid);require(m.accountUid in byAccount && m.kind in kinds && m.summary.length<=80)
            require(m.deltaCents in -2*Money.MAX_CENTS..2*Money.MAX_CENTS && m.balanceBefore in -Money.MAX_CENTS..Money.MAX_CENTS && m.balanceAfter in -Money.MAX_CENTS..Money.MAX_CENTS && Math.addExact(m.balanceBefore,m.deltaCents)==m.balanceAfter)
            require(m.createdAt in 1..4_102_444_800_000L)
            if(byAccount.getValue(m.accountUid).type==AccountType.LIABILITY && m.kind !in setOf("CALIBRATION","RESTORE_SNAPSHOT"))require(m.balanceBefore>=0 && m.balanceAfter>=0)
            m.recordUid?.let(::uid)
        }
        snapshot.aliases.forEach { (source,target)->uid(source);uid(target);require(target in byAccount) { "钱包别名缺少目标账户" } }
        val batchUids=mutableSetOf<String>()
        snapshot.audits.forEach { audit->
            require(batchUids.add(uid(audit.batchUid)));require(audit.kind in setOf("BILLS","WALLETS"))
            require(audit.fileSha256.isEmpty() || audit.fileSha256.matches(Regex("[0-9a-f]{64}")))
            require(audit.optionsDigest.isEmpty() || audit.optionsDigest.matches(Regex("[0-9a-f]{64}")))
            require(audit.createdAt in 1..4_102_444_800_000L && audit.summary.length<=8*1024*1024)
            require(audit.recordUids.distinct().size==audit.recordUids.size);audit.recordUids.forEach(::uid)
            validateAudit(audit)
        }
    }
    private fun validateAudit(audit:ImportAudit) {
        val j=JSONObject(audit.summary);require(optional(j,"batchUid",36)==audit.batchUid)
        fun count(key:String)=number(j,key).also { require(it in 0..100000) }
        val key=if(audit.kind=="BILLS")"recordUids" else "accountUids";val uids=j.getJSONArray(key);require(uids.length()<=50000)
        val values=(0 until uids.length()).map { uid(uids.get(it) as? String ?: error("审计标识必须为文本")) };require(values.distinct().size==values.size)
        if(j.optBoolean("legacy",false)) {
            BackupValues.keys(j,setOf("batchUid","legacy","imported","recordUids"));require(audit.kind=="BILLS" && bool(j,"legacy"));require(count("imported")==values.size.toLong())
        } else if(audit.kind=="BILLS") {
            BackupValues.keys(j,setOf("batchUid","imported","duplicates","invalid","posted","pendingWallets","recordUids"))
            require(count("imported")==values.size.toLong() && count("posted")+count("pendingWallets")==count("imported"));count("duplicates");count("invalid")
        } else {
            BackupValues.keys(j,setOf("batchUid","imported","updated","retained","skipped","invalid","accountUids"))
            require(count("imported")+count("updated")+count("retained")==values.size.toLong());count("skipped");count("invalid")
        }
        if(audit.kind=="BILLS")require(audit.recordUids.toSet()==values.toSet()) else require(audit.recordUids.isEmpty())
    }
}
