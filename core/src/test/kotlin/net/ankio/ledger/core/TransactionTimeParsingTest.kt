package net.ankio.ledger.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TransactionTimeParsingTest {
    private val context = RecognitionTimeContext(Instant.parse("2026-10-18T04:00:00Z").toEpochMilli(), "Asia/Shanghai")
    private fun parse(vararg fields: String, context: RecognitionTimeContext = this.context): ParsedBill =
        LocalOcrBillParser.parse((listOf("支付成功", "付款金额：12.00元") + fields).map(::LocalOcrLine),
            0, 0, context.recognitionAt, origin="image", timeContext=context)!!

    @Test fun dateOnlyReceiptUsesItsCalendarDayWithoutInventingPrintedMidnight() {
        val bill = parse("交易日期：2026年10月17日")
        assertEquals(Instant.parse("2026-10-16T16:00:00Z").toEpochMilli(), bill.occurredAt)
        assertTrue(bill.timeExplicit)
        val time = bill.receipt!!.time!!
        assertEquals("2026-10-17", time.localDate)
        assertEquals("DATE", time.precision)
        assertEquals("EXPLICIT", time.source)
        assertEquals("交易日期：2026年10月17日", time.rawText)
    }
    @Test fun splitReceiptDateAndClockAreAssociatedWithoutUsingStatusBarClock() {
        val bill = parse("23:59", "交易时间", "2026-10-17", "18:25:36", "通知时间：2026-10-18 09:00")
        assertEquals(Instant.parse("2026-10-17T10:25:36Z").toEpochMilli(), bill.occurredAt)
        assertEquals("SECOND", bill.receipt!!.time!!.precision)
        assertEquals("交易时间\n2026-10-17\n18:25:36", bill.receipt.time!!.rawText)
    }

    @Test fun missingYearUsesNearestLegalCalendarDateNotLaterThanItsTrustedReferenceDay() {
        val rows = listOf(
            Triple(RecognitionTimeContext(Instant.parse("2026-01-01T00:01:00Z").toEpochMilli(), "Asia/Shanghai"), "12月31日", "2025-12-31"),
            Triple(RecognitionTimeContext(Instant.parse("2026-02-28T12:00:00Z").toEpochMilli(), "Asia/Shanghai"), "02月29日", "2024-02-29"),
            Triple(context.copy(trustedOriginalCaptureDate="2023-06-01"), "05-30", "2023-05-30"),
        )
        for ((reference, printed, expected) in rows) {
            val time = parse("交易日期：$printed", context=reference).receipt!!.time!!
            assertEquals(printed, time.rawText.substringAfter('：'))
            assertEquals(expected, time.localDate)
            assertEquals("YEAR_INFERRED", time.source)
            assertEquals("DATE", time.precision)
            assertEquals(if (reference.trustedOriginalCaptureDate == null) "RECOGNITION" else "ORIGINAL_CAPTURE", time.reference)
        }
    }

    @Test fun nativeLiveCaptureClockDoesNotReplaceFirstRecognitionFallback() {
        val bill = LocalOcrBillParser.parse(listOf("支付成功", "付款金额：12.00元").map(::LocalOcrLine),
            0, 0, context.recognitionAt, context.recognitionAt - 60000, true, PaymentApps.WECHAT, context)!!
        assertEquals(context.recognitionAt, bill.occurredAt)
        assertEquals("RECOGNITION_FALLBACK", bill.receipt!!.time!!.source)
        assertTrue(bill.reliable)
        val transfer = LocalOcrBillParser.parse(listOf("充值成功", "充值金额：100.00元", "充值到：支付宝余额",
            "付款方式：招商银行(0007)").map(::LocalOcrLine), 0, 0, context.recognitionAt,
            context.recognitionAt - 60000, false, PaymentApps.ALIPAY, context)!!
        assertEquals(context.recognitionAt, transfer.occurredAt)
        assertEquals("RECOGNITION_FALLBACK", transfer.receipt!!.time!!.source)
    }

    @Test fun separateDateAndClockLabelsUseTheirReceiptDateWithMinutePrecision() {
        val time = parse("交易日期：2026-10-17", "交易时间：18:25").receipt!!.time!!
        assertEquals(Instant.parse("2026-10-17T10:25:00Z").toEpochMilli(), time.accountingAt)
        assertEquals("MINUTE", time.precision)
        assertEquals("EXPLICIT", time.source)
        assertEquals("交易日期：2026-10-17\n交易时间：18:25", time.rawText)
    }

    @Test fun invalidOrUnestablishedDatesFallBackWithoutReviewAndPreserveTransactionTimeRawText() {
        for (printed in listOf("交易时间：2026-02-30 11:00", "交易时间：18:25", "交易时间：2026-10-17 25:00")) {
            val bill = parse(printed)
            val time = bill.receipt!!.time!!
            assertTrue(bill.reason,bill.reliable)
            assertFalse(bill.timeExplicit)
            assertEquals(context.recognitionAt,bill.occurredAt)
            assertEquals("RECOGNITION_FALLBACK",time.source)
            assertEquals(printed,time.rawText)
        }
        val outer = parse("23:59", "图片查看器", "2024-01-01 12:00", "通知时间：2023-05-01 10:00", "导入时间：2026-10-17 11:00")
        assertEquals(context.recognitionAt,outer.occurredAt)
        assertEquals("",outer.receipt!!.time!!.rawText)
        assertTrue(outer.reliable)
    }

    @Test fun explicitDateOverridesReferenceAndDateOnlyUsesCalendarBoundariesAcrossDst() {
        val explicit = parse("交易日期：2025-06-01", context=context.copy(trustedOriginalCaptureDate="2023-01-01"))
        assertEquals("2025-06-01",explicit.receipt!!.time!!.localDate)
        assertEquals("EXPLICIT",explicit.receipt.time!!.source)
        val newYork=RecognitionTimeContext(Instant.parse("2026-03-10T12:00:00Z").toEpochMilli(),"America/New_York")
        val springDay=parse("交易日期：2026-03-08",context=newYork).receipt!!.time!!
        val nextDay=parse("交易日期：2026-03-09",context=newYork).receipt!!.time!!
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(),springDay.accountingAt)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(),nextDay.accountingAt)
        assertEquals(23 * 60 * 60 * 1000L,nextDay.accountingAt-springDay.accountingAt)
        assertEquals("DATE",springDay.precision)
    }

    @Test fun nearbyNotificationDateNeverEntersTheTransactionTimeEvidence() {
        val lines=listOf(LocalOcrLine("支付成功"),LocalOcrLine("付款金额：12.00元"),
            LocalOcrLine("交易时间",20,300,130,330),LocalOcrLine("2026-10-17 18:25",200,300,500,330),
            LocalOcrLine("通知时间",20,340,130,370),LocalOcrLine("2026-10-18 09:00",200,340,500,370))
        val time=LocalOcrBillParser.parse(lines.reversed(),600,1000,context.recognitionAt,
            origin="image",timeContext=context)!!.receipt!!.time!!
        assertEquals(Instant.parse("2026-10-17T10:25:00Z").toEpochMilli(),time.accountingAt)
        assertEquals("交易时间\n2026-10-17 18:25",time.rawText)
    }

    @Test fun ocrMayJoinAValidDateAndClockWithoutWhitespaceButCannotJoinAnOrderNumber() {
        for (printed in listOf("2026-10-1713:11:55", "10月17日13:11:55")) {
            val time=parse("付款时间",printed,"发货时间：2026-10-18 10:00:00").receipt!!.time!!
            assertEquals(Instant.parse("2026-10-17T05:11:55Z").toEpochMilli(),time.accountingAt)
            assertEquals("SECOND",time.precision)
            assertEquals("付款时间\n$printed",time.rawText)
        }
        assertEquals(context.recognitionAt,parse("交易单号：202610171311552026").occurredAt)
    }

    @Test fun ambiguousJoinedSingleDigitDayOrHourFallsBackInsteadOfGuessingADifferentDate() {
        for (printed in listOf("2026-10-112:55", "2026-10-12:55")) {
            val time=parse("付款时间：$printed").receipt!!.time!!
            assertEquals(context.recognitionAt,time.accountingAt)
            assertEquals("RECOGNITION_FALLBACK",time.source)
            assertEquals("付款时间：$printed",time.rawText)
        }
        val marked=parse("付款时间：2026年10月1日2:55").receipt!!.time!!
        assertEquals(Instant.parse("2026-09-30T18:55:00Z").toEpochMilli(),marked.accountingAt)
    }

}
