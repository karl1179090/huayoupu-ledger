package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** The exact documented schemas only; imported source labels never choose a local wallet. */
object LedgerCsvImport {
    const val MAX_BYTES=PortableCsv.MAX_BYTES
    data class RowError(val row:Int,val message:String)
    data class Document(val format:String,val records:List<LedgerRecord>,val errors:List<RowError>,val totalRows:Int,
        val fileSha256:String="",val schema:String="legacy",val rowNumbers:Map<String,Int> = emptyMap())
    data class WalletImpact(val accountUid:String?,val accountName:String?,val deltaCents:Long,val pending:Int=0,
        val beforeCents:Long?=null,val afterCents:Long?=null)
    data class Preview(val format:String,val total:Int,val ready:Int,val duplicates:Int,val errors:List<RowError>,val samples:List<LedgerRecord>,
        val pendingWallets:Int=0,val posted:Int=0,val walletImpacts:List<WalletImpact> = emptyList(),val previewToken:String="",
        val plan:ImportPlan?=null,val options:BillImportOptions=BillImportOptions())
    data class Result(val imported:Int,val duplicates:Int,val invalid:Int,val posted:Int=imported,val pendingWallets:Int=0,
        val recordUids:List<String> = emptyList(),val batchUid:String="") {
        fun json()=JSONObject().put("imported",imported).put("duplicates",duplicates).put("invalid",invalid).put("posted",posted)
            .put("pendingWallets",pendingWallets).put("recordUids",org.json.JSONArray(recordUids)).put("batchUid",batchUid).toString()
        companion object { fun decode(value:String):Result { val j=JSONObject(value);val u=j.getJSONArray("recordUids")
            return Result(j.getInt("imported"),j.getInt("duplicates"),j.getInt("invalid"),j.getInt("posted"),j.getInt("pendingWallets"),
                (0 until u.length()).map(u::getString),j.getString("batchUid")) } }
    }
    fun read(input:InputStream):Document {
        val (rows,sha)=PortableCsv.read(input);val header=rows.first()
        fun matches(expected:List<String>)=header.size==expected.size && header.toSet()==expected.toSet()
        val v2=matches(LedgerCsvExport.generalV2);val own=v2 || matches(LedgerCsvExport.generalV1);val qi=matches(LedgerCsvExport.qianji)
        if(header.toSet()==WalletCsvExport.header.toSet())throw IllegalArgumentException("这是钱包账户文件，请使用钱包导入入口")
        require(own || qi) { "请选择已知版本的花有谱账单 CSV 或钱迹 V10 模板" }
        if(v2)require(rows.drop(1).all { header.indexOf("格式版本") in it.indices && PortableCsv.unescape(it[header.indexOf("格式版本")])=="2" }) { "账单格式版本不支持或混用了版本" }
        val records=mutableListOf<LedgerRecord>();val errors=mutableListOf<RowError>();val occurrences=mutableMapOf<String,Int>();val rowNumbers=mutableMapOf<String,Int>()
        rows.drop(1).forEachIndexed { index,row->try {
            require(row.size==header.size) { "列数与表头不一致" }
            val fields=header.zip(row).associate { it.first to PortableCsv.unescape(it.second) }
            require(fields.values.all { it.length<=8192 }) { "字段过长" }
            val record=if(own)generalRecord(fields,v2) else {
                val signature=LedgerCsvExport.qianji.joinToString("") { key->fields.getValue(key).let { "${it.length}:$it" } }
                val occurrence=occurrences.getOrDefault(signature,0);occurrences[signature]=occurrence+1
                qianjiRecord(fields,UUID.nameUUIDFromBytes(("qianji-v10\u0000$signature\u0000$occurrence").toByteArray()).toString())
            }
            records.add(record.copy(bill=record.bill.copy(categoryDecision=if(record.bill.direction==Direction.TRANSFER)null else CategoryDecision(null,CategorySource.IMPORT,true,explanation="保留导入分类"))));rowNumbers[record.uid]=index+2
        } catch(error:Exception) { errors.add(RowError(index+2,error.message ?: "字段无效")) } }
        return Document(if(qi)"钱迹 V10 模板" else if(v2)"花有谱通用 CSV v2" else "花有谱通用 CSV v1（本地时区）",
            records,errors,rows.size-1,sha,if(qi)"qianji-v10" else if(v2)"ledger-v2" else "ledger-v1",rowNumbers)
    }
    fun preview(store:LedgerStore,document:Document,linkWalletBalances:Boolean=false):Preview =
        prepare(store,document,BillImportOptions(linkWalletBalances,useDefaultWallet=linkWalletBalances))
    fun prepare(store:LedgerStore,document:Document,options:BillImportOptions=BillImportOptions(),submissionToken:String=UUID.randomUUID().toString()):Preview=store.importReadSnapshot {
        val selected=select(store,document.records);val accounts=store.accounts().associateBy { it.uid };val aliases=store.walletImportAliases()
        val names=accounts.values.groupBy { it.name }
        val mappings=linkedMapOf<String,ImportMapping>();val errors=document.errors.toMutableList();val prepared=mutableListOf<LedgerRecord>();val impacts=linkedMapOf<String,Long>()
        fun resolve(reference:WalletFileReference?):String? {
            if(reference==null)return if(options.useDefaultWallet)store.walletSettings().defaultAccountUid else null
            val explicit=options.mappings[reference.key]
            val local=if(explicit!=null)explicit.takeIf(String::isNotBlank) else reference.uid?.let { if(it in accounts)it else aliases[it] }
            if(local!=null)require(local in accounts) { "映射钱包已不存在，请重新选择" }
            mappings[reference.key]=ImportMapping(reference,local,if(reference.name.isBlank())emptyList() else names[reference.name].orEmpty().map { it.uid },
                explicit!=null || local!=null)
            return local
        }
        selected.forEach { original->try {
            val bill=original.bill;var record=original
            if(bill.direction==Direction.TRANSFER) {
                val transfer=requireNotNull(original.transfer) { "转账资料不完整" }
                val fromRef=WalletFileReference.from(transfer.fromReference) ?: transfer.fromAccountUid?.let { WalletFileReference(it) }
                val toRef=WalletFileReference.from(transfer.toReference) ?: transfer.toAccountUid?.let { WalletFileReference(it) }
                require(fromRef!=null && toRef!=null) { "转账必须有双方文件引用" }
                val from=resolve(fromRef);val to=resolve(toRef)
                require(from==null || from!=to) { "转出与转入映射为同一个钱包" }
                listOfNotNull(from,to).forEach { require(accounts.getValue(it).type==AccountType.ASSET) { "转账仅支持资产钱包" } }
                val code=if(!options.linkWalletBalances)null else if(from==null || to==null)"TRANSFER_WALLET_REQUIRED"
                    else if(accounts.getValue(from).archived || accounts.getValue(to).archived)"INVALID_WALLET" else null
                record=record.copy(state=if(code==null)RecordState.POSTED else RecordState.REVIEW,reason=if(code==null)"" else "请确认转账双方钱包",
                    transfer=transfer.copy(fromAccountUid=from,toAccountUid=to,fromSelectionSource="IMPORT",toSelectionSource="IMPORT",reviewCode=code),
                    wallet=RecordWalletLink(record.uid,if(options.linkWalletBalances)WalletEligibility.ACTIVE else WalletEligibility.IMPORT_EXCLUDED,reviewCode=code))
            } else {
                val reference=WalletFileReference.from(original.wallet?.accountReference) ?: original.wallet?.accountUid?.let { WalletFileReference(it) }
                val uid=resolve(reference);uid?.let { require(accounts.getValue(it).type==AccountType.ASSET) { "账单仅支持资产钱包" } }
                val code=if(!options.linkWalletBalances)null else if(uid==null)"WALLET_REQUIRED" else if(accounts.getValue(uid).archived)"INVALID_WALLET" else null
                record=record.copy(state=if(code==null)RecordState.POSTED else RecordState.REVIEW,reason=if(code==null)"" else "请选择钱包后入账",
                    wallet=RecordWalletLink(record.uid,if(options.linkWalletBalances)WalletEligibility.ACTIVE else WalletEligibility.IMPORT_EXCLUDED,
                        uid,"IMPORT",reviewCode=code,accountReference=reference?.json()))
            }
            val target=WalletEffectCalculator.targetEffects(bill.amountCents,bill.direction,record.state==RecordState.POSTED,
                eligibilityActive=options.linkWalletBalances,accountUid=record.wallet?.accountUid,transfer=record.transfer?.target())
            target.forEach { (uid,delta)->impacts[uid]=Math.addExact(impacts[uid] ?: 0L,delta) };prepared.add(record)
        } catch(error:IllegalArgumentException) { errors.add(RowError(document.rowNumbers[original.uid] ?: document.records.indexOf(original)+2,error.message ?: "钱包映射无效")) } }
        val deltas=impacts.map { (uid,delta)->val account=accounts.getValue(uid)
            val after=try { Math.addExact(account.amountCents,delta) } catch(_:ArithmeticException) { throw WalletException("BALANCE_LIMIT","钱包 ${account.name} 超出余额范围") }
            if(after !in -Money.MAX_CENTS..Money.MAX_CENTS)throw WalletException("BALANCE_LIMIT","钱包 ${account.name} 超出余额范围，请调整计划")
            WalletImpact(uid,account.name,delta,beforeCents=account.amountCents,afterCents=after)
        }
        val pending=prepared.count { it.state==RecordState.REVIEW }
        val facts=store.importFactsToken(options.linkWalletBalances)
        val plan=ImportPlan("BILLS",document.schema,fileDigest(document),options.digest(),facts,submissionToken,prepared,errors,
            document.records.size-selected.size,pending,impacts,mappings.values.toList())
        Preview(document.format,document.totalRows,prepared.size,plan.duplicates,errors,prepared.take(3),pending,prepared.size-pending,deltas,facts,plan,options)
    }
    fun applyPreview(store:LedgerStore,document:Document,preview:Preview,cancellationCheck:()->Unit={}):Result {
        val plan=requireNotNull(preview.plan)
        require(plan.fileSha256==fileDigest(document) && plan.optionsDigest==preview.options.digest()) { "导入计划与文件不一致" }
        return store.commitBillImport(plan,preview.options.linkWalletBalances,cancellationCheck)
    }
    fun apply(store:LedgerStore,document:Document,expectedPreviewToken:String?=null,linkWalletBalances:Boolean=false):Result=synchronized(store) {
        val preview=preview(store,document,linkWalletBalances)
        if(expectedPreviewToken!=null && expectedPreviewToken!=preview.previewToken)throw WalletException("CSV_PREVIEW_STALE","账本或钱包已更新，请重新预览")
        applyPreview(store,document,preview)
    }
    fun writeErrors(errors:List<RowError>,output:OutputStream)=PortableCsv.write(sequenceOf(listOf("行号","原因"))+errors.asSequence().map { listOf(it.row.toString(),it.message) },output)
    private fun fileDigest(document:Document)=document.fileSha256.ifEmpty { PortableCsv.digest(document.records.joinToString("|") { "${it.uid}:${it.capture.source}:${it.capture.origin}:${it.capture.receivedAt}:${it.bill}:${it.note}:${it.identities}:${it.wallet}:${it.transfer}" }.toByteArray()) }
    private fun select(store:LedgerStore,incoming:List<LedgerRecord>):List<LedgerRecord> {
        val existing=store.importIdentityRecords();val uids=existing.mapTo(mutableSetOf()) { it.uid };val identities=mutableSetOf<TransactionIdentity>()
        fun ids(record:LedgerRecord)=(record.identities+TransactionIdentities.from(record.capture,record.bill)).distinct()
        existing.forEach { identities.addAll(ids(it)) }
        return incoming.filter { record->val keys=ids(record);if(record.uid in uids || keys.any { it in identities })false else { uids.add(record.uid);identities.addAll(keys);true } }
    }
    private fun generalRecord(f:Map<String,String>,v2:Boolean):LedgerRecord {
        fun field(key:String,max:Int=1024)=f.getValue(key).also { require(it.length<=max) { "$key 过长" } }
        require(field("币种").uppercase(Locale.ROOT) in setOf("CNY","RMB","人民币")) { "仅支持人民币" }
        require(field("账单状态")=="已入账") { "仅支持已入账账单" }
        val uid=PortableCsv.uuid(field("账单UID",160));val direction=direction(field("类型"));require(v2 || direction!=Direction.TRANSFER) { "v1不支持转账" }
        val amount=PortableCsv.cents(field("金额"));val original=field("原付款金额");val refunded=field("已退款金额")
        val refund=if(original.isNotEmpty() || refunded.isNotEmpty()) {
            val a=PortableCsv.cents(original,zero=false);val r=PortableCsv.cents(refunded);val provider=field("退款渠道");val mode=field("退款模式");val refundId=field("退款单号",160).ifBlank { null }
            require(direction==Direction.EXPENSE && provider in setOf("wechat","alipay") && mode in setOf("single","cumulative") && r<=a && amount==a-r && (mode!="single" || refundId!=null)) { "退款净额或退款信息不一致" }
            RefundInfo(provider,a,r,mode,refundId)
        } else { require(listOf("退款渠道","退款模式","退款单号").all { field(it).isEmpty() }) { "退款信息不完整" };null }
        require(amount>0 || refund!=null) { "零金额账单缺少完整退款信息" }
        val source=when(field("识别来源")) { "屏幕识别"->Source.OCR;"图片导入"->Source.IMAGE;"短信识别"->Source.SMS;"通知识别"->Source.NOTIFICATION;"手动记账"->Source.MANUAL;else->throw IllegalArgumentException("识别来源不支持") }
        val explicit=field("时间来源").also { require(it in setOf("交易时间","识别时间")) { "时间来源无效" } }=="交易时间"
        val at=if(v2)PortableCsv.instant(field("时间")) else date(field("时间"));val received=if(v2)PortableCsv.instant(field("录入时间")) else date(field("录入时间"))
        var transfer:RecordTransferDetails?=null;var link:RecordWalletLink?=null;var identities=emptyList<TransactionIdentity>()
        if(v2) {
            require(field("格式版本")=="2") { "不支持的格式版本" }
            require(field("余额联动状态") in setOf("参与联动","导入不联动","历史不联动","恢复不联动")) { "余额联动状态无效" }
            fun reference(prefix:String):WalletFileReference? { val id=field("${prefix}UID",160).ifBlank { null }?.let(PortableCsv::uuid);val name=field("${prefix}名称",40)
                return if(id==null && name.isBlank())null else WalletFileReference(id,name) }
            val identityText=field("交易身份集",8192)
            if(identityText.isNotBlank()) { val j=JSONObject(identityText);require(j.keys().asSequence().toSet()==setOf("version","identities") && j.get("version")==1) { "交易身份集版本无效" }
                val values=j.getJSONArray("identities")
                for(index in 0 until values.length()) { val identity=values.getJSONObject(index)
                    require(identity.keys().asSequence().toSet()==setOf("scope","value") && identity.get("scope") is String && identity.get("value") is String) { "交易身份集字段类型无效" }
                }
                identities=TransactionIdentities.decode(values.toString()) }
            if(direction==Direction.TRANSFER) {
                require(field("钱包UID").isBlank() && field("钱包名称").isBlank() && refund==null) { "转账不能包含普通钱包或消费退款字段" }
                val from=requireNotNull(reference("转出钱包")) { "缺少转出钱包引用" };val to=requireNotNull(reference("转入钱包")) { "缺少转入钱包引用" }
                require(from.uid==null || from.uid!=to.uid) { "转出与转入钱包不能相同" }
                val fee=PortableCsv.cents(field("手续费"));val mode=when(field("手续费方式")) { "额外扣费"->FeeMode.EXTRA;"到账扣费"->FeeMode.DEDUCTED;else->throw IllegalArgumentException("手续费方式无效") }
                val amounts=TransferCalculator.verifyObserved(amount,fee,mode,field("实际扣款").takeIf(String::isNotBlank)?.let { PortableCsv.cents(it) },field("实际到账").takeIf(String::isNotBlank)?.let { PortableCsv.cents(it) })
                transfer=RecordTransferDetails(uid,feeCents=fee,feeMode=amounts.feeMode,fromReference=from.json(),toReference=to.json())
            } else {
                require(listOf("转出钱包UID","转出钱包名称","转入钱包UID","转入钱包名称","手续费","手续费方式","实际扣款","实际到账").all { field(it).isEmpty() }) { "普通收支不能包含转账专用字段" }
                link=RecordWalletLink(uid,WalletEligibility.IMPORT_EXCLUDED,accountReference=reference("钱包")?.json())
            }
        }
        return LedgerRecord(uid=uid,capture=Capture(source,field("来源应用"),"",received),bill=ParsedBill(amount,direction,field("商户").ifBlank { "未命名商户" },field("分类").ifBlank { "其他" },at,field("交易订单号",160).ifBlank { null },true,"",explicit,refund),
            state=RecordState.POSTED,note=field("备注",500),identities=identities,wallet=link,transfer=transfer)
    }
    private fun qianjiRecord(f:Map<String,String>,uid:String):LedgerRecord {
        require(f.values.all { it.length<=1024 }) { "字段过长" };val direction=direction(f.getValue("类型"));val amount=PortableCsv.cents(f.getValue("金额"),zero=false)
        require(f.getValue("账单标记").isBlank()) { "不计收支或其他账单标记不支持，请用通用 CSV 保留退款记录" }
        require(f.getValue("优惠券").isBlank() || PortableCsv.cents(f.getValue("优惠券"))==0L) { "含优惠券，请先整理为最终金额" }
        val fee=f.getValue("手续费").takeIf(String::isNotBlank)?.let { PortableCsv.cents(it) } ?: 0L
        require(direction==Direction.TRANSFER || fee==0L) { "非转账的手续费不支持" }
        val rawNote=f.getValue("备注");val merchant=Regex("^商户：([^；]*)").find(rawNote)?.groupValues?.get(1).orEmpty().ifBlank { "导入账单" }
        val transaction=Regex("(?:^|；)交易订单号：([^；]+)").find(rawNote)?.groupValues?.get(1);val at=date(f.getValue("时间"),qianji=true)
        val category=listOf(f.getValue("分类"),f.getValue("二级分类")).filter(String::isNotBlank).joinToString("/").ifBlank { "其他" }
        val note=buildList { if(rawNote.isNotBlank())add(rawNote);if(f.getValue("标签").isNotBlank())add("标签：${f.getValue("标签")}") }.joinToString("；").also { require(it.length<=500) { "备注超过500字" } }
        val from=f.getValue("账户1").also { require(it.length<=40) { "钱包名称过长" } };val to=f.getValue("账户2").also { require(it.length<=40) { "钱包名称过长" } }
        val transfer=if(direction==Direction.TRANSFER) { require(from.isNotBlank() && to.isNotBlank()) { "转账缺少双方账户名称" }
            val a=TransferCalculator.calculate(amount,fee,FeeMode.DEDUCTED)
            RecordTransferDetails(uid,feeCents=fee,feeMode=a.feeMode,fromReference=WalletFileReference(name=from).json(),toReference=WalletFileReference(name=to).json()) } else null
        return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv:qianji","",at),bill=ParsedBill(amount,direction,merchant,category,at,transaction,true,"",true),
            state=RecordState.POSTED,note=note,transfer=transfer,wallet=if(direction!=Direction.TRANSFER && from.isNotBlank())RecordWalletLink(uid,WalletEligibility.IMPORT_EXCLUDED,accountReference=WalletFileReference(name=from).json()) else null)
    }
    private fun direction(value:String)=when(value) { "支出"->Direction.EXPENSE;"收入"->Direction.INCOME;"转账"->Direction.TRANSFER;else->throw IllegalArgumentException("交易类型不支持") }
    private fun date(value:String,qianji:Boolean=false):Long {
        require(value.length<=40) { "时间过长" }
        for(pattern in listOf("yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm") + if(qianji)listOf("yyyy-M-d H:mm","yyyy/M/d H:mm","yyyy-M-d","yyyy/M/d") else emptyList()) {
            val format=SimpleDateFormat(pattern,Locale.ROOT).apply { isLenient=false };val pos=ParsePosition(0);val parsed=format.parse(value,pos)
            if(parsed!=null && pos.index==value.length && format.format(parsed)==value && parsed.time in 1..4_102_444_800_000L)return parsed.time
        };throw IllegalArgumentException("时间格式须为 yyyy-MM-dd HH:mm[:ss]")
    }
}
