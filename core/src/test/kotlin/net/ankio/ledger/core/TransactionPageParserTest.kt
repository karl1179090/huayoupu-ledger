package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class TransactionPageParserTest {
    private val time=RecognitionTimeContext(1794110400000,"Asia/Shanghai")
    private fun card(amount:String,tail:String,date:String,offset:Int)=listOf("招商银行","交易成功提醒",
        "交易类型：网上快捷支付","交易金额：${amount}元","交易卡号：尾号$tail","交易日期：$date","账户余额：9999.99元")
        .mapIndexed { i,s -> LocalOcrLine(s,30,offset+i*40,700,offset+i*40+30) }
    @Test fun repeatedBankCardsKeepTheirOwnAmountDateAndAccountWhenReordered() {
        val a=card("12.34","0007","2026-11-01",100)
        val b=card("56.78","0008","2026-11-02",600)
        val parsed=TransactionPageParser.parse(a+b,1080,1600,time,origin="image")
        assertEquals(listOf(1234L,5678L),parsed.map { it.bill.amountCents })
        assertEquals(listOf("0007","0008"),parsed.map { it.bill.paymentMethod!!.cardLast4 })
        assertEquals(listOf("2026-11-01","2026-11-02"),parsed.map { it.bill.receipt!!.time!!.localDate })
        assertTrue(parsed.all { it.bill.reliable })
        assertEquals(2,parsed.map { it.id }.distinct().size)
        val reversed=TransactionPageParser.parse(card("56.78","0008","2026-11-02",100)+card("12.34","0007","2026-11-01",600),1080,1600,time,origin="image")
        assertEquals(listOf(5678L,1234L),reversed.map { it.bill.amountCents })
        assertEquals(listOf("0008","0007"),reversed.map { it.bill.paymentMethod!!.cardLast4 })
    }
}
