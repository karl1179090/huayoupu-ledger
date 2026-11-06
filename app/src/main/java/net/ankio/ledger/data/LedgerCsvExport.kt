package net.ankio.ledger.data

import net.ankio.ledger.core.*
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Portable financial export; private OCR bodies and images remain in full backups. */
object LedgerCsvExport {
    enum class Format(val key:String) { GENERAL("general"),QIANJI("qianji");companion object {
        fun fromKey(value:String)=entries.firstOrNull { it.key==value } ?: throw IllegalArgumentException("不支持的账单导出格式")
    } }
    internal val generalV1=listOf("时间","分类","类型","金额","币种","商户","备注","账单UID","交易订单号","识别来源","来源应用","录入时间","时间来源","原付款金额","已退款金额","退款渠道","退款模式","退款单号","账单状态")
    internal val generalV2=generalV1+listOf("格式版本","钱包UID","钱包名称","转出钱包UID","转出钱包名称","转入钱包UID","转入钱包名称","手续费","手续费方式","实际扣款","实际到账","余额联动状态","交易身份集")
    internal val qianji=listOf("时间","分类","二级分类","类型","金额","账户1","账户2","备注","账单标记","手续费","优惠券","标签","账单图片")
    data class Snapshot(val records:List<LedgerRecord>,val accounts:List<LedgerAccount>,val pending:Int)
    fun write(store:LedgerStore,format:Format,output:OutputStream):Int=store.exportFileSnapshot().let { write(it.records,format,output,it.accounts) }
    fun write(records:List<LedgerRecord>,format:Format,output:OutputStream,accounts:List<LedgerAccount> = emptyList()):Int {
        val posted=records.filter { it.state==RecordState.POSTED };val accountByUid=accounts.associateBy { it.uid }
        fun reference(uid:String?,json:String?):WalletFileReference?=uid?.let(accountByUid::get)?.let(WalletFileReference::of)
            ?: WalletFileReference.from(json) ?: uid?.let { WalletFileReference(it) }
        // Construct and validate all rows before the first byte, including unavailable Qianji endpoint names.
        val rows=posted.map { record->
            val bill=record.bill;val amount=bill.amountCents
            require(amount!=null && amount in 0..Money.MAX_CENTS && bill.direction!=null) { "已入账记录缺少有效金额或交易类型" }
            val transfer=record.transfer;val isTransfer=bill.direction==Direction.TRANSFER
            require(!isTransfer || transfer!=null && bill.refund==null && bill.standaloneRefund==null) { "转账资料不完整：${record.uid}" }
            val amounts=if(isTransfer)TransferCalculator.calculate(amount,requireNotNull(transfer!!.feeCents),requireNotNull(transfer.feeMode)) else null
            val single=if(!isTransfer)reference(record.wallet?.accountUid,record.wallet?.accountReference) else null
            val from=transfer?.let { reference(it.fromAccountUid,it.fromReference) };val to=transfer?.let { reference(it.toAccountUid,it.toReference) }
            require(!isTransfer || from!=null && to!=null) { "转账缺少双端引用：${record.uid}" }
            val direction=when(bill.direction) { Direction.EXPENSE->"支出";Direction.INCOME->"收入";Direction.TRANSFER->"转账";null->error("交易类型缺失") }
            val refund=bill.refund
            if(format==Format.GENERAL) {
                val ids=(record.identities+TransactionIdentities.from(record.capture,bill)).distinct()
                require(ids.all(TransactionIdentities::valid) && ids.groupBy { it.scope }.all { it.value.size==1 }) { "交易身份冲突" }
                // Valid identities contain only the restricted identifier alphabet, so JSON escaping is unnecessary here.
                val identityJson="{\"version\":1,\"identities\":["+ids.joinToString(",") { "{\"scope\":\"${it.scope}\",\"value\":\"${it.value}\"}" }+"]}"
                listOf(PortableCsv.iso(bill.occurredAt),bill.category,direction,Money.format(amount),"CNY",bill.merchant,record.note,record.uid,
                    bill.transactionId.orEmpty(),source(record.capture.source),record.capture.origin,PortableCsv.iso(record.capture.receivedAt),
                    if(bill.timeExplicit)"交易时间" else "识别时间",refund?.let { Money.format(it.originalAmountCents) }.orEmpty(),
                    refund?.let { Money.format(it.refundedCents) }.orEmpty(),refund?.provider.orEmpty(),refund?.mode.orEmpty(),refund?.refundId.orEmpty(),"已入账",
                    "2",single?.uid.orEmpty(),single?.name.orEmpty(),from?.uid.orEmpty(),from?.name.orEmpty(),to?.uid.orEmpty(),to?.name.orEmpty(),
                    amounts?.let { Money.format(it.feeCents) }.orEmpty(),amounts?.let { if(it.feeMode==FeeMode.EXTRA)"额外扣费" else "到账扣费" }.orEmpty(),
                    amounts?.let { Money.format(it.debitCents) }.orEmpty(),amounts?.let { Money.format(it.creditCents) }.orEmpty(),
                    when(record.wallet?.eligibility) { WalletEligibility.ACTIVE->"参与联动";WalletEligibility.IMPORT_EXCLUDED->"导入不联动";WalletEligibility.RESTORE_EXCLUDED->"恢复不联动";else->"历史不联动" },identityJson)
            } else {
                require(!isTransfer || !from?.name.isNullOrBlank() && !to?.name.isNullOrBlank()) { "转账 ${record.uid} 缺少钱包名称，请使用通用 CSV" }
                val note=buildList { if(bill.merchant.isNotBlank())add("商户：${bill.merchant}");if(record.note.isNotBlank())add(record.note)
                    bill.transactionId?.takeIf(String::isNotBlank)?.let { add("交易订单号：$it") }
                    if(refund!=null)add("原付款：${Money.format(refund.originalAmountCents)}；已退款：${Money.format(refund.refundedCents)}") }.joinToString("；")
                listOf(SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT).format(Date(bill.occurredAt)),bill.category,"",direction,
                    Money.format(amounts?.debitCents ?: amount),if(isTransfer)from!!.name else single?.name.orEmpty(),if(isTransfer)to!!.name else "",note,
                    if(amount==0L)"不计收支" else "",amounts?.let { Money.format(it.feeCents) }.orEmpty(),"","","")
            }
        }
        PortableCsv.write(sequenceOf(if(format==Format.GENERAL)generalV2 else qianji)+rows.asSequence(),output)
        return posted.size
    }
    fun template(output:OutputStream) {
        val from="11111111-1111-4111-8111-111111111111";val to="22222222-2222-4222-8222-222222222222"
        val uid="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";val at=1_793_937_600_000L
        write(listOf(LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv","",at),
            bill=ParsedBill(10000,Direction.TRANSFER,"合成钱包互转","转账",at,null,true,"",true),state=RecordState.POSTED,
            transfer=RecordTransferDetails(uid,from,to,100,FeeMode.EXTRA,fromReference=WalletFileReference(from,"合成银行卡").json(),toReference=WalletFileReference(to,"合成余额").json()))),Format.GENERAL,output)
    }
    private fun source(value:Source)=when(value) { Source.OCR->"屏幕识别";Source.IMAGE->"图片导入";Source.SMS->"短信识别";Source.NOTIFICATION->"通知识别";Source.MANUAL->"手动记账" }
}
