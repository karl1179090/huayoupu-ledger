package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class TransferCalculatorTest {
    @Test fun extraFeeDebitsPrincipalAndFeeButCreditsPrincipal() {
        val result = TransferCalculator.calculate(10_000, 100, FeeMode.EXTRA)
        assertEquals(10_100L, result.debitCents)
        assertEquals(10_000L, result.creditCents)
        assertEquals(100L, result.debitCents - result.creditCents)
    }

    @Test fun deductedFeeDebitsPrincipalAndCreditsNetAmount() {
        val result = TransferCalculator.calculate(10_000, 100, FeeMode.DEDUCTED)
        assertEquals(10_000L, result.debitCents)
        assertEquals(9_900L, result.creditCents)
        assertEquals(100L, result.debitCents - result.creditCents)
    }

    @Test fun freeTransfersHaveOneCanonicalModeAndEqualLegs() {
        for (mode in FeeMode.entries) {
            val result = TransferCalculator.calculate(1, 0, mode)
            assertEquals(FeeMode.EXTRA, result.feeMode)
            assertEquals(1L, result.debitCents)
            assertEquals(result.debitCents, result.creditCents)
        }
    }

    @Test fun minimumAndMaximumAmountsRemainExact() {
        val max = Money.MAX_CENTS
        assertEquals(max, TransferCalculator.calculate(max, 0).debitCents)
        assertEquals(max, TransferCalculator.calculate(max - 1, 1).debitCents)
        assertEquals(1L, TransferCalculator.calculate(max, max - 1, FeeMode.DEDUCTED).creditCents)
        assertEquals(1L, TransferCalculator.calculate(2, 1, FeeMode.DEDUCTED).creditCents)
    }

    @Test fun invalidPrincipalAndFeeNeverProduceAPartialTransfer() {
        for (amount in listOf(0L, -1L, Money.MAX_CENTS + 1, Long.MAX_VALUE, Long.MIN_VALUE))
            rejects("INVALID_AMOUNT") { TransferCalculator.calculate(amount, 0) }
        for (fee in listOf(-1L, Money.MAX_CENTS + 1, Long.MAX_VALUE))
            rejects("TRANSFER_FEE_AMBIGUOUS") { TransferCalculator.calculate(100, fee) }
        rejects("INVALID_AMOUNT") { TransferCalculator.calculate(Money.MAX_CENTS, 1) }
        rejects("TRANSFER_AMOUNT_CONFLICT") { TransferCalculator.calculate(100, 100, FeeMode.DEDUCTED) }
        rejects("TRANSFER_AMOUNT_CONFLICT") { TransferCalculator.calculate(100, 101, FeeMode.DEDUCTED) }
    }

    @Test fun labelledDebitAndCreditMustBothMatchTheSelectedFormula() {
        assertEquals(100L, TransferCalculator.verifyObserved(10_000, 100, FeeMode.EXTRA, 10_100, 10_000).feeCents)
        rejects("TRANSFER_AMOUNT_CONFLICT") { TransferCalculator.verifyObserved(10_000, 100, FeeMode.EXTRA, 10_000, 9_900) }
        rejects("TRANSFER_AMOUNT_CONFLICT") { TransferCalculator.verifyObserved(10_000, 100, FeeMode.DEDUCTED, creditCents = 10_000) }
    }

    @Test fun missingRecognitionFeeIsDistinctFromExplicitlyFree() {
        val evidence = completed()
        rejects("TRANSFER_FEE_AMBIGUOUS") { TransferCalculator.validateEvidence(evidence.copy(feeCents = null)) }
        rejects("TRANSFER_FEE_AMBIGUOUS") { TransferCalculator.validateEvidence(evidence.copy(feeMode = null)) }
        assertEquals(10_000L, TransferCalculator.validateEvidence(evidence).creditCents)
    }

    @Test fun onlyCompletedSelfTransfersMayBePostedFromRecognition() {
        for (state in TransferTerminalState.entries.filter { it != TransferTerminalState.COMPLETED })
            rejects("TRANSFER_STATUS_REQUIRED") { TransferCalculator.validateEvidence(completed().copy(terminalState = state)) }
        for (intent in listOf(TransferIntent.EXTERNAL, TransferIntent.AMBIGUOUS))
            rejects("TRANSFER_IDENTITY_CONFLICT") { TransferCalculator.validateEvidence(completed().copy(intent = intent)) }
    }

    @Test fun transferEvidenceCannotCarryOrdinaryRefundHistory() {
        rejects("TRANSFER_REFUND_CONFLICT") {
            TransferCalculator.validateEvidence(completed(), refund = RefundInfo("wechat", 10_000, 100))
        }
        rejects("TRANSFER_REFUND_CONFLICT") {
            TransferCalculator.validateEvidence(completed(), standaloneRefund = StandaloneRefundEvidence("wechat", 100))
        }
    }

    private fun completed() = TransferEvidence(TransferIntent.SELF_TRANSFER,
        terminalState = TransferTerminalState.COMPLETED, amountCents = 10_000, feeCents = 0, feeMode = FeeMode.EXTRA)

    private fun rejects(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: IllegalArgumentException) { assertEquals(code, error.message) }
    }
}
