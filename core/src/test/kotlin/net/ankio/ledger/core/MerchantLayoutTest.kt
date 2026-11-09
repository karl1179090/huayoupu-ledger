package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class MerchantLayoutTest {
    @Test fun walletMerchantBelowAmountBeatsSocialProofAndNavigationAcrossProviders() {
        val rows=listOf(
            line("合成米粉",185,249,325,299),line("等2000+人喜欢",262,314,432,343),
            line("附近门店 >",187,342,306,375),line("门店",27,459,96,501),
            line("交易详情",660,462,788,497),line("-43.00",327,607,499,662),
            line("合成米粉",350,678,477,720),line("支付成功",231,832,346,869),
            line("商户全称",62,1035,179,1070),line("合成餐饮有限公司",231,1036,651,1068))
        for(origin in listOf(PaymentApps.WECHAT,PaymentApps.ALIPAY,"com.unionpay",""))
            for(input in listOf(rows,rows.reversed(),rows.shuffled(kotlin.random.Random(31)))) {
                val bill=LocalOcrBillParser.parse(input,828,1800,1_793_462_400_000L,origin=origin)!!
                assertEquals("merchant for $origin","合成米粉",bill.merchant)
                assertEquals(4300L,bill.amountCents)
            }
    }

    @Test fun socialAndNavigationLabelsNeverBecomeMerchantWhenDisplayNameIsAbsent() {
        for(copy in listOf("等2000+人喜欢","等1万+人喜欢","200人收藏","附近门店>","前往小程序 >","喜欢","门店","服务")) {
            val rows=listOf(line(copy,187,342,506,375),line("-30.00",314,466,512,532),
                line("支付成功",242,693,365,732),line("商户全称",61,868,185,905),
                line("合成美发工作室",243,869,724,906))
            assertEquals(copy,"合成美发工作室",LocalOcrBillParser.parse(rows,828,1800,1_793_462_400_000L)!!.merchant)
        }
    }
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        LocalOcrLine(text, left, top, right, bottom)

    private fun parse(lines: List<LocalOcrLine>) = LocalOcrBillParser.parse(
        lines, 828, 1800, 1_792_386_000_000L, origin = PaymentApps.TAOBAO
    )!!

    private fun order(store: String) = listOf(
        line(store, 131, 568, 430, 602), line("进店逛逛 >", 645, 586, 790, 623),
        line("88VIP好评率96%，客服平均9秒回复", 131, 614, 577, 644),
        line("实付款¥22.50", 600, 1380, 790, 1420),
        line("订单编号: 5127000000000000001", 30, 1490, 790, 1525)
    )

    @Test fun skewedStoreEntryNeverSelectsReviewOrResponseStatistics() {
        for (name in listOf("合成家居旗舰店", "合成Moonlight")) {
            val lines = order(name)
            for (input in listOf(lines, lines.reversed(), lines.shuffled(kotlin.random.Random(42)))) {
                val bill = parse(input)
                assertEquals(name, bill.merchant)
                assertEquals(2250L, bill.amountCents)
                assertEquals("5127000000000000001", bill.transactionId)
                assertTrue(bill.reliable)
            }
        }
    }

    @Test fun reviewStatisticsAloneAreNotAMerchant() {
        val bill = parse(order("合成家居旗舰店").filterNot { it.text == "合成家居旗舰店" })
        assertEquals("", bill.merchant)
    }

    private fun foodSection() = listOf(
        line("配送地址", 40, 879, 170, 910), line("合成街道8栋", 178, 879, 500, 910),
        line("商品费用", 38, 1179, 177, 1219),
        line("合成米粉 >", 38, 1258, 190, 1288), line("收藏", 687, 1256, 773, 1290),
        line("共2件", 225, 1353, 300, 1386), line("合计¥35.8", 631, 1346, 789, 1388),
        line("合成推荐店 >", 40, 1600, 300, 1635)
    )

    @Test fun lowerGoodsSectionUsesStoreBesideFavoriteInsteadOfAddressOrAdvertisement() {
        for (favorite in listOf("收藏", "台收藏")) {
            val lines = foodSection().map { if (it.text == "收藏") it.copy(text = favorite) else it }
            for (input in listOf(lines, lines.reversed())) {
                val bill = parse(input)
                assertEquals("合成米粉", bill.merchant)
                assertEquals(3580L, bill.amountCents)
                assertTrue(bill.reliable)
            }
        }
    }

    @Test fun goodsSectionWithoutFavoriteAnchorDoesNotInventMerchant() {
        assertEquals("", parse(foodSection().filterNot { it.text == "收藏" }).merchant)
    }

    @Test fun multipleStoresInGoodsSectionRemainAmbiguous() {
        val lines = foodSection() + listOf(
            line("另一家合成店 >", 38, 1305, 230, 1335), line("收藏", 687, 1305, 773, 1335)
        )
        assertEquals("", parse(lines).merchant)
    }
}
