package net.ankio.ledger.data

import net.ankio.ledger.core.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** Explicit, previewed financial import. CSV never grants capture permissions or loads image URLs. */
object LedgerCsvImport {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private val general = listOf("时间","分类","类型","金额","币种","商户","备注","账单UID","交易订单号","识别来源","来源应用","录入时间","时间来源","原付款金额","已退款金额","退款渠道","退款模式","退款单号","账单状态")
    private val qianji = listOf("时间","分类","二级分类","类型","金额","账户1","账户2","备注","账单标记","手续费","优惠券","标签","账单图片")
    data class RowError(val row:Int, val message:String)
    data class Document(val format:String, val records:List<LedgerRecord>, val errors:List<RowError>, val totalRows:Int)
    data class WalletImpact(val accountUid:String?,val accountName:String?,val deltaCents:Long,val pending:Int=0)
    data class Preview(val format:String, val total:Int, val ready:Int, val duplicates:Int, val errors:List<RowError>, val samples:List<LedgerRecord>,
        val pendingWallets:Int=0,val posted:Int=0,val walletImpacts:List<WalletImpact> = emptyList(),val previewToken:String="")
    data class Result(val imported:Int, val duplicates:Int, val invalid:Int,val posted:Int=imported,val pendingWallets:Int=0)

    fun read(input:InputStream):Document {
        val bytes=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer)
            if(count<0)break
            require(bytes.size()+count<=MAX_BYTES) { "CSV 超过 8 MB，请分批导入" }
            bytes.write(buffer,0,count)
        }
        val text=try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        } catch(_:java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("CSV 编码须为 UTF-8") }
        val rows=parse(text)
        require(rows.isNotEmpty()) { "CSV 没有表头" }
        val header=rows.first()
        val own=header.size==general.size && header.toSet()==general.toSet()
        val qi=header.size==qianji.size && header.toSet()==qianji.toSet()
        require(own || qi) { "请选择花有谱通用 CSV 或钱迹 V10 模板" }
        val records=mutableListOf<LedgerRecord>()
        val errors=mutableListOf<RowError>()
        val occurrences=mutableMapOf<String,Int>()
        for((index,row) in rows.drop(1).withIndex()) {
            try {
                require(row.size==header.size) { "列数与表头不一致" }
                val fields=header.zip(row).toMap()
                // Only the two exact, known export schemas are decoded. Arbitrary CSV is never evaluated.
                val normalized=fields.mapValues { (_,value) -> unescape(value) }
                val record=if(own)generalRecord(normalized) else {
                    // The file may reorder columns; identity follows the known schema's order.
                    val signature=qianji.joinToString("") { key -> normalized.getValue(key).let { "${it.length}:$it" } }
                    val occurrence=occurrences.getOrDefault(signature,0)
                    occurrences[signature]=occurrence+1
                    qianjiRecord(normalized,UUID.nameUUIDFromBytes(("qianji-v10\u0000$signature\u0000$occurrence").toByteArray()).toString())
                }
                records.add(record)
            } catch(error:IllegalArgumentException) {
                errors.add(RowError(index+2,error.message ?: "字段无效"))
            }
        }
        return Document(if(own)"花有谱通用 CSV" else "钱迹 V10 模板",records,errors,rows.size-1)
    }

    fun preview(store:LedgerStore,document:Document,linkWalletBalances:Boolean=false):Preview=synchronized(store) {
        val selected=select(store,document.records)
        if(!linkWalletBalances)return@synchronized Preview(document.format,document.totalRows,selected.size,
            document.records.size-selected.size,document.errors,selected.take(3),0,selected.size,emptyList(),store.walletConfigurationToken())
        val balances=store.accounts().associate { it.uid to it.amountCents }.toMutableMap()
        val names=store.accounts().associate { it.uid to it.name }
        val effects=linkedMapOf<String?,Long>();val pendingCounts=linkedMapOf<String?,Int>()
        var pending=0
        selected.forEach { record->
            val resolution=store.previewWallet(record)
            val uid=resolution.accountUid
            val target=WalletEffectCalculator.target(record.bill.amountCents,record.bill.direction,record.state==RecordState.POSTED)
            if(uid==null) {
                pending++;pendingCounts[null]=pendingCounts.getOrDefault(null,0)+1
            } else effects[uid]=Math.addExact(effects.getOrDefault(uid,0L),target)
        }
        effects.forEach { (uid,delta)->
            val next=uid?.let { Math.addExact(balances.getValue(it),delta) }
            if(next==null || next !in -Money.MAX_CENTS..Money.MAX_CENTS)
                throw WalletException("BALANCE_LIMIT","钱包余额超出允许范围，请调整导入计划")
        }
        val impacts=(effects.keys+pendingCounts.keys).map { uid->WalletImpact(uid,names[uid],effects[uid] ?: 0L,pendingCounts[uid] ?: 0) }
        Preview(document.format,document.totalRows,selected.size,document.records.size-selected.size,document.errors,selected.take(3),
            pending,selected.size-pending,impacts,store.walletConfigurationToken())
    }

    /** Recheck under the store monitor: recognition may finish while the preview is open. */
    fun apply(store:LedgerStore,document:Document,expectedPreviewToken:String?=null,linkWalletBalances:Boolean=false):Result=synchronized(store) {
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            if(expectedPreviewToken!=null && expectedPreviewToken!=store.walletConfigurationToken())
                throw WalletException("CSV_PREVIEW_STALE","钱包或余额已更新，请刷新导入预览")
            val selected=select(store,document.records)
            val saved=store.importTransactions(selected,linkWalletBalances)
            db.setTransactionSuccessful()
            Result(saved.size,document.records.size-saved.size,document.errors.size,
                saved.count { it.state==RecordState.POSTED },saved.count { it.state==RecordState.REVIEW })
        } finally { db.endTransaction() }
    }

    private fun select(store:LedgerStore,incoming:List<LedgerRecord>):List<LedgerRecord> {
        val existing=store.importIdentityRecords()
        val uids=existing.mapTo(mutableSetOf()) { it.uid }
        fun identities(record:LedgerRecord)=record.identities+TransactionIdentities.from(record.capture,record.bill)
        val byIdentity=mutableMapOf<TransactionIdentity,MutableList<LedgerRecord>>()
        fun index(record:LedgerRecord) { identities(record).distinct().forEach { byIdentity.getOrPut(it) { mutableListOf() }.add(record) } }
        existing.forEach(::index)
        return incoming.filter { record ->
            val keys=identities(record)
            // A changed amount, direction or refund net cannot turn an existing order into new money.
            val duplicate=keys.any { byIdentity[it].orEmpty().isNotEmpty() }
            if(record.uid in uids || duplicate)false
            else { uids.add(record.uid);index(record);true }
        }
    }

    private fun generalRecord(f:Map<String,String>):LedgerRecord {
        fun field(key:String,max:Int=1024)=f.getValue(key).also { require(it.length<=max) { "$key 过长" } }
        require(field("币种").uppercase(Locale.ROOT) in setOf("CNY","RMB","人民币")) { "仅支持人民币" }
        require(field("账单状态")=="已入账") { "仅支持已入账账单" }
        val uid=field("账单UID",160).also {
            val canonical=runCatching { UUID.fromString(it).toString() }.getOrNull()
            require(canonical==it) { "账单 UID 须为规范 UUID" }
        }
        val direction=direction(field("类型"))
        val amount=money(field("金额"),true)
        val original=field("原付款金额")
        val refunded=field("已退款金额")
        val refund=if(original.isNotEmpty() || refunded.isNotEmpty()) {
            val originalCents=money(original)
            val refundedCents=money(refunded)
            val provider=field("退款渠道")
            val mode=field("退款模式")
            val refundId=field("退款单号",160).ifBlank { null }
            require(direction==Direction.EXPENSE && provider in setOf("wechat","alipay") &&
                mode in setOf("single","cumulative") && refundedCents<=originalCents &&
                amount==originalCents-refundedCents && (mode!="single" || refundId!=null)) { "退款净额或退款信息不一致" }
            RefundInfo(provider,originalCents,refundedCents,mode,refundId)
        } else null
        require(amount>0 || refund!=null) { "零金额账单缺少完整退款信息" }
        val source=when(field("识别来源")) {
            "屏幕识别" -> Source.OCR;"图片导入" -> Source.IMAGE;"短信识别" -> Source.SMS
            "通知识别" -> Source.NOTIFICATION;"手动记账" -> Source.MANUAL
            else -> throw IllegalArgumentException("识别来源不支持")
        }
        val explicit=field("时间来源").also { require(it in setOf("交易时间","识别时间")) { "时间来源无效" } }=="交易时间"
        return LedgerRecord(uid=uid,capture=Capture(source,field("来源应用"),"",date(field("录入时间"))),
            bill=ParsedBill(amount,direction,field("商户").ifBlank { "未命名商户" },field("分类").ifBlank { "其他" },
                date(field("时间")),field("交易订单号",160).ifBlank { null },true,"",explicit,refund),
            state=RecordState.POSTED,note=field("备注",500))
    }

    private fun qianjiRecord(f:Map<String,String>,uid:String):LedgerRecord {
        require(f.values.all { it.length<=1024 }) { "字段过长" }
        val direction=direction(f.getValue("类型"))
        val amount=money(f.getValue("金额"))
        require(f.getValue("账单标记").isBlank()) {
            if(f.getValue("账单标记")=="不计收支")"暂不支持不计收支账单，请用通用 CSV 保留退款记录" else "账单标记不支持"
        }
        require(listOf("手续费","优惠券").all { key -> f.getValue(key).isBlank() || money(f.getValue(key),true)==0L }) { "含手续费或优惠券，请先整理为最终金额" }
        val rawNote=f.getValue("备注")
        val merchant=Regex("^商户：([^；]*)").find(rawNote)?.groupValues?.get(1).orEmpty().ifBlank { "导入账单" }
        val transaction=Regex("(?:^|；)交易订单号：([^；]+)").find(rawNote)?.groupValues?.get(1)
        val at=date(f.getValue("时间"))
        val category=listOf(f.getValue("分类"),f.getValue("二级分类")).filter { it.isNotBlank() }.joinToString("/").ifBlank { "其他" }
        val note=buildList {
            if(rawNote.isNotBlank())add(rawNote)
            if(f.getValue("标签").isNotBlank())add("标签：${f.getValue("标签")}")
        }.joinToString("；").also { require(it.length<=500) { "备注超过 500 字" } }
        return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv:qianji","",at),
            bill=ParsedBill(amount,direction,merchant,category,at,transaction,true,"",true),state=RecordState.POSTED,note=note)
    }

    private fun direction(value:String)=when(value) {
        "支出" -> Direction.EXPENSE;"收入" -> Direction.INCOME
        else -> throw IllegalArgumentException("仅支持支出或收入，不支持转账")
    }
    private fun money(value:String,zero:Boolean=false):Long {
        require(value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "金额须为非负数字，最多两位小数" }
        val parts=value.split('.')
        val whole=parts[0].toLongOrNull() ?: throw IllegalArgumentException("金额过大")
        require(whole<=Money.MAX_CENTS/100) { "金额过大" }
        val cents=whole*100+(parts.getOrNull(1) ?: "").padEnd(2,'0').toLong()
        require(cents in (if(zero)0 else 1)..Money.MAX_CENTS) { "金额须大于零且在支持范围内" }
        return cents
    }
    private fun date(value:String):Long {
        for(pattern in listOf("yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm")) {
            val format=SimpleDateFormat(pattern,Locale.ROOT).apply { isLenient=false }
            val position=ParsePosition(0)
            val parsed=format.parse(value,position)
            if(parsed!=null && position.index==value.length && format.format(parsed)==value && parsed.time in 1..4_102_444_800_000L)return parsed.time
        }
        throw IllegalArgumentException("时间格式须为 yyyy-MM-dd HH:mm[:ss]")
    }
    private fun unescape(value:String):String {
        if(!value.startsWith("'"))return value
        val rest=value.substring(1)
        return if(rest.startsWith("'") || rest.length>=15 && rest.all(Char::isDigit) || rest.trimStart().firstOrNull() in listOf('=','+','-','@') ||
            rest.startsWith('\t') || rest.startsWith('\r') || rest.startsWith('\n'))rest else value
    }

    private fun parse(text:String):List<List<String>> {
        val rows=mutableListOf<List<String>>()
        var row=mutableListOf<String>()
        val cell=StringBuilder()
        var quoted=false;var closed=false;var i=0
        fun addCell() { require(row.size<64) { "CSV 列数过多" };row.add(cell.toString());cell.setLength(0);closed=false }
        fun addRow() { addCell();if(row.any { it.isNotEmpty() })rows.add(row);row=mutableListOf();require(rows.size<=MAX_ROWS+1) { "最多导入 50000 笔，请分批导入" } }
        while(i<text.length) {
            val c=text[i++]
            if(quoted) {
                if(c=='"') { if(i<text.length && text[i]=='"') { cell.append('"');i++ } else { quoted=false;closed=true } }
                else cell.append(c)
            } else when(c) {
                '"' -> { require(cell.isEmpty() && !closed) { "CSV 引号格式错误" };quoted=true }
                ',' -> addCell()
                '\r' -> { require(i<text.length && text[i++]=='\n') { "CSV 换行格式错误" };addRow() }
                '\n' -> addRow()
                else -> { require(!closed && c!='\u0000') { "CSV 字段格式错误" };cell.append(c) }
            }
            require(cell.length<=32_768) { "CSV 单元格过长" }
        }
        require(!quoted) { "CSV 引号未闭合，文件可能不完整" }
        if(cell.isNotEmpty() || row.isNotEmpty() || closed)addRow()
        return rows
    }
}
