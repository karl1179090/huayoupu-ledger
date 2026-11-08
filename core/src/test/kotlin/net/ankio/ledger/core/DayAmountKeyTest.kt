package net.ankio.ledger.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class DayAmountKeyTest {
    @Test fun provenDateOnlyEvidenceKeepsItsCalendarDayAcrossZones() {
        val time=RecognitionTimeContext(Instant.parse("2026-11-08T04:00:00Z").toEpochMilli(),"Asia/Shanghai")
        val bill=LocalOcrBillParser.parse(listOf("招商银行","交易成功提醒","交易类型：网上快捷支付",
            "交易金额：12.34元","交易卡号：尾号0007","交易日期：2026-11-01").map(::LocalOcrLine),0,0,
            time.recognitionAt,origin="image",timeContext=time)!!
        assertEquals(DayAmountKey("2026-11-01",1234),DayAmountKey.from(bill,"America/Los_Angeles"))
    }
    @Test fun repeatedDstHourUsesTheFrozenCalendarZoneRatherThanAnElapsedDayWindow() {
        fun bill(instant:String)=ParsedBill(1234,Direction.EXPENSE,"合成夏令时","其他",Instant.parse(instant).toEpochMilli(),null,true,"")
        val early=bill("2026-11-01T08:30:00Z")
        val later=bill("2026-11-01T09:30:00Z")
        assertEquals(DayAmountKey("2026-11-01",1234),DayAmountKey.from(early,"America/Los_Angeles"))
        assertEquals(DayAmountKey.from(early,"America/Los_Angeles"),DayAmountKey.from(later,"America/Los_Angeles"))
        assertEquals(DayAmountKey("2026-10-31",1234),DayAmountKey.from(bill("2026-11-01T06:59:00Z"),"America/Los_Angeles"))
    }
}
