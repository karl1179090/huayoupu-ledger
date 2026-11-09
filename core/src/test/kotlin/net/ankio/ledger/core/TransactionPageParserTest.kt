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
    @Test fun identicalCardsRemainDistinctAndShareOneFrozenMissingDate() {
        val lines=listOf("招商银行","交易成功提醒","交易类型：网上快捷支付","交易金额：12.34元","交易卡号：尾号0007")
        val parsed=TransactionPageParser.parse((lines+lines).map(::LocalOcrLine),0,0,time,origin="image")
        assertEquals(2,parsed.size);assertNotEquals(parsed[0].id,parsed[1].id)
        assertEquals(parsed[0].bill.copy(receipt=null),parsed[1].bill.copy(receipt=null))
        assertNotEquals(parsed[0].bill.receipt!!.blockId,parsed[1].bill.receipt!!.blockId)
        assertEquals(listOf(time.recognitionAt,time.recognitionAt),parsed.map { it.bill.occurredAt })
        assertTrue(parsed.all { it.bill.receipt!!.time!!.source=="RECOGNITION_FALLBACK" })
    }
    @Test fun failedCardCannotRejectAnotherCardOrDonateItsFields() {
        val failed=card("12.34","0007","2026-11-01",100)+LocalOcrLine("交易失败",30,450,700,480)
        val parsed=TransactionPageParser.parse(failed+card("56.78","0008","2026-11-02",600),1080,1600,time,origin="image")
        assertEquals(1,parsed.size);assertEquals(5678L,parsed.single().bill.amountCents)
        assertEquals("0008",parsed.single().bill.paymentMethod!!.cardLast4)
        assertFalse(parsed.single().rawText!!.contains("交易失败"))
    }

    @Test fun bankPortalHeadersAndInlineTailsRemainInTheirOwnTransactionBlocks() {
        fun card(tail:String,amount:String,date:String,y:Int)=listOf(
            LocalOcrLine("招商银行微银行",30,y,550,y+30),LocalOcrLine("交易成功提醒",30,y+50,450,y+80),
            LocalOcrLine("交易时间：",30,y+100,180,y+130),LocalOcrLine(date,250,y+100,650,y+130),
            LocalOcrLine("交易类型：",30,y+150,180,y+180),LocalOcrLine("网上快捷支付（尾号$tail）",250,y+150,750,y+180),
            LocalOcrLine("交易金额：",30,y+200,180,y+230),LocalOcrLine("人民币$amount",250,y+200,650,y+230),
            LocalOcrLine("账户余额：",30,y+250,180,y+280),LocalOcrLine("88,888.88元",250,y+250,650,y+280))
        val lines=card("0007","23.71","11月01日 09:12",100)+LocalOcrLine("昨天20:30",350,480,650,510)+
            card("0008","68.42","11月02日 17:24",600)
        val parsed=TransactionPageParser.parse(lines,1080,1600,time,origin="image")
        assertEquals(listOf(Direction.EXPENSE,Direction.EXPENSE),parsed.map { it.bill.direction })
        assertEquals(listOf(2371L,6842L),parsed.map { it.bill.amountCents })
        assertEquals(listOf("0007","0008"),parsed.map { it.bill.paymentMethod!!.cardLast4 })
        assertTrue(parsed.all { it.bill.paymentMethod!!.bankCode=="cmb" && it.bill.reliable })
        assertTrue(parsed.all { it.rawText!!.startsWith("招商银行微银行") && it.bill.merchant.isEmpty() })
        assertEquals(listOf("2026-11-01","2026-11-02"),parsed.map { it.bill.receipt!!.time!!.localDate })
        assertTrue(parsed.all { !it.bill.receipt!!.time!!.rawText.contains("20:30") })
        assertTrue(TransactionPageParser.parse(lines,1080,1600,time,policy=RecognitionPolicy.AUTOMATIC_PAYMENT,
            origin="payment_auto:com.tencent.mm").isEmpty())
    }

}
