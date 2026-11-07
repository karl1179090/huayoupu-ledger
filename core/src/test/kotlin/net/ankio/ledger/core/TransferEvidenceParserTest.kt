package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class TransferEvidenceParserTest {
    private val now = 1_793_937_600_000L
    private fun topup(vararg rest: String) = listOf("充值成功", "充值金额 ¥100.00", "付款方式 招商银行储蓄卡（0007）", "到账账户 微信零钱") + rest
    private fun parsed(text: List<String>, origin: String = PaymentApps.WECHAT) = TransferEvidenceParser.parse(text.map(::LocalOcrLine), origin)!!
    private fun bill(text: List<String>, policy: RecognitionPolicy = RecognitionPolicy.USER_INITIATED, origin: String = PaymentApps.WECHAT) =
        LocalOcrBillParser.parse(text.map(::LocalOcrLine), 0, 0, now, livePaymentAt = now, policy = policy, origin = origin)
    private fun page(text: List<String>, pkg: String = PaymentApps.WECHAT, activity: String = PaymentPageDetector.WECHAT_FRAMEWORK_RESULT,
                     editable: Boolean = false) = PaymentPage(pkg, 7, activity, text, editable)

    @Test fun explicitFreeTopUpUsesBothAccountRolesAndOneTransfer() {
        val result = bill(topup("手续费 ¥0.00"), RecognitionPolicy.AUTOMATIC_PAYMENT)!!
        assertEquals(Direction.TRANSFER, result.direction); assertTrue(result.reliable)
        assertEquals("BANK_CARD", result.transfer!!.from!!.medium)
        assertEquals("0007", result.transfer.from!!.cardLast4)
        assertEquals("WECHAT_BALANCE", result.transfer.to!!.medium)
        assertEquals(0L, result.transfer.feeCents); assertEquals(FeeMode.EXTRA, result.transfer.feeMode)
        assertNull(result.refund); assertNull(result.paymentMethod)
    }

    @Test fun completedWithdrawalInfersDeductedFeeFromLabelledFormula() {
        val text = listOf("提现成功", "提现金额100.00元", "实际扣款100.00元", "实际到账99.00元", "手续费1.00元", "到账银行卡招商银行储蓄卡（0007）")
        val result = parsed(text, PaymentApps.ALIPAY)
        assertEquals(TransferScenario.WITHDRAWAL, result.scenario)
        assertEquals("ALIPAY_BALANCE", result.from!!.medium); assertEquals("BANK_CARD", result.to!!.medium)
        assertEquals(FeeMode.DEDUCTED, result.feeMode); assertEquals(9900L, result.creditCents)
        assertTrue(result.reviewCodes.isEmpty()); TransferCalculator.validateEvidence(result)
        val match = PaymentPageDetector.detect(page(text, PaymentApps.ALIPAY, "com.alipay.mobile.pay.PayResultActivity"))!!
        assertEquals(FeeMode.DEDUCTED, match.transfer!!.feeMode)
    }

    @Test fun extraFeeIsUniquelyProvedByDebitPrincipalAndCredit() {
        val result = parsed(topup("实际扣款101.00元", "实际到账100.00元", "手续费1.00元"))
        assertEquals(FeeMode.EXTRA, result.feeMode)
        assertEquals(10100L, TransferCalculator.validateEvidence(result).debitCents)
    }

    @Test fun absentFeeNeverBecomesImplicitlyFreeButEqualCompleteLegsAreFreeEvidence() {
        val missing = bill(topup())!!
        assertFalse(missing.reliable); assertNull(missing.transfer!!.feeCents)
        assertTrue(missing.transfer.reviewCodes.contains("TRANSFER_FEE_AMBIGUOUS"))
        val equal = parsed(topup("实际扣款100.00元", "实际到账100.00元"))
        assertEquals(0L, equal.feeCents); assertEquals(FeeMode.EXTRA, equal.feeMode)
        assertTrue(equal.reviewCodes.isEmpty())
    }

    @Test fun missingModeWithNonzeroFeeStaysReviewUntilObservedLegsOrModeConfirmIt() {
        val missing = parsed(topup("手续费1.00元"))
        assertEquals(100L, missing.feeCents); assertNull(missing.feeMode)
        assertTrue(missing.reviewCodes.contains("TRANSFER_FEE_AMBIGUOUS"))
        val explicit = parsed(topup("手续费1.00元", "手续费方式额外扣费"))
        assertEquals(FeeMode.EXTRA, explicit.feeMode); assertTrue(explicit.reviewCodes.isEmpty())
    }

    @Test fun inconsistentAmountsAreReviewAndCannotAuthorizeAutomaticPixels() {
        val text = topup("实际扣款100.00元", "实际到账98.00元", "手续费1.00元")
        val evidence = parsed(text)
        assertTrue(evidence.reviewCodes.contains("TRANSFER_AMOUNT_CONFLICT"))
        assertFalse(bill(text)!!.reliable)
        assertNull(PaymentPageDetector.detect(page(text)))
    }

    @Test fun friendTransfersAndMatchingCounterpartyCardTailsRemainOrdinaryExpenses() {
        val text = listOf("转账成功", "转账金额100.00元", "收款人合成朋友", "收款银行卡招商银行（0007）", "付款方式微信零钱")
        assertNull(TransferEvidenceParser.parse(text.map(::LocalOcrLine), PaymentApps.WECHAT))
        val result = bill(text)!!
        assertEquals(Direction.EXPENSE, result.direction); assertNull(result.transfer)
        val incoming = listOf("收款成功", "收款金额100.00元", "付款方合成朋友")
        assertEquals(Direction.INCOME, bill(incoming)!!.direction)
        assertNull(bill(incoming, RecognitionPolicy.AUTOMATIC_PAYMENT))
    }

    @Test fun ownPaymentCardWordingCannotProveThatAnExternalRecipientIsAlsoSelf() {
        val text = listOf("转账成功", "转账金额100.00元", "收款人合成朋友", "付款方式本人银行卡招商银行（0007）")
        assertNull(TransferEvidenceParser.parse(text.map(::LocalOcrLine), PaymentApps.WECHAT))
        assertEquals(Direction.EXPENSE, bill(text)!!.direction)
        val contradictory = topup("手续费0.00元", "收款人合成朋友")
        assertNull(PaymentPageDetector.detect(page(contradictory)))
        assertFalse(bill(contradictory)!!.reliable)
    }

    @Test fun bankOwnAccountDetailsRequireExplicitCaptureAndNeverEnableAutomaticBankMonitoring() {
        val text = listOf("本人账户转账成功", "转账金额100.00元", "转出账户招商银行储蓄卡（0007）", "转入账户工商银行储蓄卡（1234）", "免手续费")
        val origin = "com.example.syntheticbank"
        assertEquals(Direction.TRANSFER, bill(text, origin = origin)!!.direction)
        assertNull(bill(text, RecognitionPolicy.AUTOMATIC_PAYMENT, origin))
        assertNull(PaymentPageDetector.detect(page(text, origin, "$origin.TransferResultActivity")))
    }

    @Test fun twoAccountRolesWithoutProofOfOwnershipRemainAnUnpostedTypeReview() {
        val text = listOf("转账成功", "转账金额100.00元", "转出账户招商银行（0007）", "转入账户工商银行（1234）", "手续费0.00元")
        val result = bill(text, origin = "com.example.syntheticbank")!!
        assertEquals(TransferIntent.AMBIGUOUS, result.transfer!!.intent)
        assertNull(result.direction); assertFalse(result.reliable)
        assertTrue(result.reason.contains("本人"))
    }

    @Test fun pendingFailureReversalSensitiveEmptyUnknownAndHistoryPagesNeverAutoCapture() {
        for (status in listOf("提现申请已提交", "充值失败", "充值退回", "充值成功预计两小时内到账")) {
            val text = topup("手续费0.00元").drop(1) + status
            assertNull(PaymentPageDetector.detect(page(text)))
        }
        assertNull(PaymentPageDetector.detect(page(topup("手续费0.00元"), editable = true)))
        assertNull(PaymentPageDetector.detect(page(topup("手续费0.00元") + "请输入支付密码")))
        assertNull(PaymentPageDetector.detect(page(emptyList())))
        assertNull(PaymentPageDetector.detect(page(topup("手续费0.00元"), activity = "UnknownWindow")))
        assertNull(PaymentPageDetector.detect(page(topup("手续费0.00元") + "账单详情")))
    }

    @Test fun unsupportedProductsAndCurrenciesNeverBecomeSupportedAutomaticTransfers() {
        for (product in listOf("信用卡还款", "花呗还款", "零钱通", "余额宝", "USD100.00", "外币兑换")) {
            val text = topup("手续费0.00元") + product
            assertNull(PaymentPageDetector.detect(page(text)))
            assertFalse(bill(text)!!.reliable)
            assertTrue(bill(text)!!.transfer!!.reviewCodes.contains("TRANSFER_ACCOUNT_UNSUPPORTED"))
        }
    }

    @Test fun merchantRechargeDoesNotBecomeWalletRecharge() {
        val text = listOf("充值成功", "话费充值", "付款金额100.00元", "付款方式招商银行（0007）")
        assertNull(TransferEvidenceParser.parse(text.map(::LocalOcrLine), PaymentApps.WECHAT))
        assertNull(PaymentPageDetector.detect(page(text)))
    }

    @Test fun promotionsBalancesAndUnlabelledNumbersNeverSupplyTransferAmounts() {
        val text = topup("手续费0.00元", "剩余余额999.00元", "优惠券2.00元", "额度5000.00元")
        val result = parsed(text)
        assertEquals(10000L, result.amountCents)
        assertEquals(2, result.amountLabels.size)
        assertNotNull(PaymentPageDetector.detect(page(text)))
        val bare = listOf("充值成功", "100.00", "99.00", "1.00", "付款方式招商银行（0007）")
        assertNull(parsed(bare).amountCents); assertNull(PaymentPageDetector.detect(page(bare)))
    }

    @Test fun ordinaryPaymentsKeepTheirExistingAmbiguousMultipleAmountGate() {
        assertNull(PaymentPageDetector.detect(page(listOf("支付成功", "¥100.00", "¥99.00", "完成"))))
    }

    @Test fun nativeRechargeAndWithdrawalCannotUseTheOtherPlatformsBalanceEnd() {
        val topup = topup("手续费0.00元").map { it.replace("到账账户 微信零钱", "到账账户 支付宝余额") }
        assertTrue(parsed(topup).reviewCodes.contains("TRANSFER_PAYMENT_EVIDENCE_CONFLICT"))
        assertNull(PaymentPageDetector.detect(page(topup)))
        val withdrawal = listOf("提现成功", "提现金额100.00元", "转出账户支付宝余额", "到账银行卡招商银行储蓄卡（0007）", "手续费0.00元")
        assertTrue(parsed(withdrawal).reviewCodes.contains("TRANSFER_PAYMENT_EVIDENCE_CONFLICT"))
        assertNull(PaymentPageDetector.detect(page(withdrawal)))
    }

    @Test fun amountAndWalletLabelsUseGeometricNeighboursInUnorderedOcr() {
        val lines = listOf(LocalOcrLine("99.00元", 210, 300, 330, 330), LocalOcrLine("提现成功", 20, 30, 200, 60),
            LocalOcrLine("实际到账", 20, 300, 160, 330), LocalOcrLine("100.00元", 210, 180, 330, 210),
            LocalOcrLine("提现金额", 20, 180, 160, 210), LocalOcrLine("实际扣款100.00元", 20, 240, 330, 270),
            LocalOcrLine("手续费1.00元", 20, 360, 330, 390), LocalOcrLine("到账银行卡招商银行储蓄卡（0007）", 20, 420, 350, 450))
        val evidence = TransferEvidenceParser.parse(lines, PaymentApps.ALIPAY)!!
        assertEquals(10000L, evidence.amountCents); assertEquals(9900L, evidence.creditCents)
        assertEquals(FeeMode.DEDUCTED, evidence.feeMode); assertTrue(evidence.reviewCodes.isEmpty())
    }

    @Test fun transferStrongIdentitySurvivesDirectionAndFeeAmountChanges() {
        val capture = Capture(Source.IMAGE, PaymentApps.WECHAT, "转账单号SYNTHETIC-TRANSFER-0001", now)
        val old = ExistingBill(8, Source.OCR, PaymentApps.WECHAT, "", 10000, Direction.EXPENSE, "旧误识别", now, "SYNTHETIC-TRANSFER-0001", true)
        val incoming = ParsedBill(9900, Direction.TRANSFER, "本人钱包转账", "转账", now, old.transactionId, true, "", true)
        assertEquals(Duplicate.Exact(8), DuplicateDetector.find(capture, incoming, listOf(old)))
        assertEquals(Duplicate.None, DuplicateDetector.find(capture.copy(origin = PaymentApps.ALIPAY), incoming, listOf(old)))
    }
}
