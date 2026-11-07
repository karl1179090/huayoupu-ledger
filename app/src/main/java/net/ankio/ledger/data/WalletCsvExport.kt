package net.ankio.ledger.data

import java.io.OutputStream

object WalletCsvExport {
    internal val header=listOf("格式版本","钱包UID","名称","账户类型","钱包类型","银行代码","银行卡尾号","余额","币种","说明","是否归档","导出时间")
    fun write(store:LedgerStore,output:OutputStream):Int=write(store.exportFileSnapshot().accounts,output)
    fun write(accounts:List<LedgerAccount>,output:OutputStream):Int {
        accounts.forEach(LedgerAccount::validate)
        val at=PortableCsv.iso(System.currentTimeMillis())
        PortableCsv.write(sequenceOf(header)+accounts.asSequence().map { a->listOf("1",a.uid,a.name,if(a.type==AccountType.ASSET)"资产" else "负债",
            a.kind,a.bankCode.orEmpty(),a.cardLast4.orEmpty(),net.ankio.ledger.core.Money.format(a.amountCents),"CNY",a.description,if(a.archived)"是" else "否",at) },output)
        return accounts.size
    }
    fun template(output:OutputStream)=write(listOf(LedgerAccount(uid="11111111-1111-4111-8111-111111111111",name="合成银行卡",amountCents=-12000,
        kind="bank",bankCode="cmb",cardLast4="0007"),LedgerAccount(uid="22222222-2222-4222-8222-222222222222",name="合成余额",amountCents=20000,kind="wechat")),output)
}
