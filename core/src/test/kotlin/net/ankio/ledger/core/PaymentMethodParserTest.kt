package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class PaymentMethodParserTest {
    private fun parse(vararg text: String, income: Boolean = false, origin: String = PaymentApps.WECHAT) =
        PaymentMethodParser.parse(text.map { LocalOcrLine(it) }, if (income) Direction.INCOME else Direction.EXPENSE, origin)

    @Test fun explicitBankCardWinsOverThePaymentAppAndKeepsLeadingZeroes() {
        for (value in listOf("招商银行（0007）", "招行储蓄卡 ****0007", "招商银行 尾号0007")) {
            val evidence = parse("付款方式：$value")!!
            assertEquals("BANK_CARD", evidence.medium)
            assertEquals("cmb", evidence.bankCode)
            assertEquals("0007", evidence.cardLast4)
        }
    }

    @Test fun coordinatesHandleSplitRowsAndUnorderedOcrBlocks() {
        val lines = listOf(LocalOcrLine("（0007）", 550, 500, 680, 530),
            LocalOcrLine("交易单号123400070000", 20, 700, 700, 730),
            LocalOcrLine("付款方式", 20, 500, 180, 530), LocalOcrLine("招商银行", 300, 500, 500, 530))
        val evidence = PaymentMethodParser.parse(lines, Direction.EXPENSE, "shake:${PaymentApps.WECHAT}")!!
        assertEquals("cmb", evidence.bankCode); assertEquals("0007", evidence.cardLast4)
        val stacked = listOf(LocalOcrLine("0007", 250, 545, 350, 575),
            LocalOcrLine("付款方式", 20, 500, 180, 530), LocalOcrLine("招商银行", 20, 545, 210, 575))
        assertEquals("0007", PaymentMethodParser.parse(stacked, Direction.EXPENSE)!!.cardLast4)
    }

    @Test fun unrelatedDigitsAndCounterpartyAccountsAreNotOwnPaymentCards() {
        val unrelated = parse("商户名称 招行1234旗舰店", "对方账户 招商银行1234", "交易单号 202611061234000000")!!
        assertEquals("UNKNOWN", unrelated.medium); assertNull(unrelated.bankCode); assertNull(unrelated.cardLast4)
        val bill = parse("付款方式 银行卡 ****OOO7", "交易单号 202611060007000000")!!
        assertNull(bill.cardLast4)
        assertNull(parse("付款方式 银行卡622200070007")!!.cardLast4)
        for (field in listOf("交易号", "对方账户", "手机号", "优惠"))
            assertNull(field, parse("付款方式 招商银行 ${field}1234")!!.cardLast4)
        val income = parse("付款方 招商银行1234", "付款方式 招商银行1234", "到账账户 支付宝余额", income = true,
            origin = PaymentApps.ALIPAY)!!
        assertEquals("CREDIT", income.role); assertEquals("ALIPAY_BALANCE", income.medium)
        assertNull(income.cardLast4)
    }

    @Test fun platformContextIsExplicitAndConflictingBrandsDoNotCreateAnAppFallback() {
        assertEquals("WECHAT_BALANCE", parse("付款方式 零钱")!!.medium)
        assertEquals("UNKNOWN", parse("付款方式 余额")!!.medium)
        assertEquals("ALIPAY_BALANCE", parse("支付宝", "付款方式 余额", origin = "image")!!.medium)
        assertNull(parse("微信支付", "支付宝", origin = "image"))
        assertEquals("UNKNOWN", parse("微信支付", "支付宝", "付款方式 余额", origin = "image")!!.medium)
        assertEquals("UNSUPPORTED", parse("付款方式 零钱通")!!.medium)
        assertEquals("UNSUPPORTED", parse("付款方式 余额宝", origin = PaymentApps.ALIPAY)!!.medium)
    }

    @Test fun combinationsAndConflictingFieldsKeepAnExplanationForFallback() {
        for (fields in listOf(arrayOf("付款方式 招商银行1234 + 零钱"),
            arrayOf("支付方式 招商银行1234", "付款方式 工商银行0007"))) {
            val bill = parse(*fields)!!
            assertTrue(bill.ambiguous); assertEquals("MULTIPLE_PAYMENT_METHODS", bill.reason)
        }
    }

    @Test fun evidenceFingerprintIgnoresRawLabelsAndUnrelatedOcrText() {
        val a = parse("付款方式 招商银行（0007）")!!
        val b = parse("营销优惠 ¥1234", "支付方式 招行 尾号0007")!!
        assertEquals(WalletResolver.fingerprint(a), WalletResolver.fingerprint(b))
        assertNotEquals(WalletResolver.fingerprint(a), WalletResolver.fingerprint(a.copy(cardLast4 = "1234")))
    }

    @Test fun explicitlyNamedPlatformBalancesRemainStrongInOtherSourceApplications() {
        for (source in listOf("com.example.shop", "com.taobao.taobao")) {
            assertEquals("ALIPAY_BALANCE", parse("付款方式 支付宝余额", origin = source)!!.medium)
            assertEquals("WECHAT_BALANCE", parse("付款方式 微信零钱", origin = source)!!.medium)
            assertEquals("UNKNOWN", parse("付款方式 余额", origin = source)!!.medium)
            assertEquals("UNKNOWN", parse("付款方式 零钱", origin = source)!!.medium)
        }
    }
}
