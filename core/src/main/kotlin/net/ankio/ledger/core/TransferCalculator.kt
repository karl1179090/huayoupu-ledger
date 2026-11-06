package net.ankio.ledger.core

enum class FeeMode { EXTRA, DEDUCTED }
enum class TransferIntent { SELF_TRANSFER, EXTERNAL, AMBIGUOUS }
enum class TransferScenario { TOP_UP, WITHDRAWAL, OWN_ACCOUNTS, UNKNOWN }
enum class TransferTerminalState { COMPLETED, PENDING, FAILED, REVERSED, UNKNOWN }

/** OCR facts contain account evidence, never trusted local wallet identifiers. */
data class TransferEvidence(
    val intent: TransferIntent,
    val scenario: TransferScenario = TransferScenario.UNKNOWN,
    val terminalState: TransferTerminalState = TransferTerminalState.UNKNOWN,
    val from: PaymentMethodEvidence? = null,
    val to: PaymentMethodEvidence? = null,
    val amountCents: Long? = null,
    val feeCents: Long? = null,
    val feeMode: FeeMode? = null,
    val debitCents: Long? = null,
    val creditCents: Long? = null,
    val amountLabels: List<TransferAmountEvidence> = emptyList(),
    val reviewCodes: List<String> = emptyList(),
    val evidenceVersion: Int = 1,
)

data class TransferAmountEvidence(val role: String, val label: String, val amountCents: Long)

data class TransferAmounts(
    val amountCents: Long,
    val feeCents: Long,
    val feeMode: FeeMode,
    val debitCents: Long,
    val creditCents: Long,
)

/** A local wallet selection, distinct from recognition evidence or a file account reference. */
data class TransferTarget(
    val fromAccountUid: String?,
    val toAccountUid: String?,
    val feeCents: Long?,
    val feeMode: FeeMode?,
)

object TransferCalculator {
    fun calculate(amountCents: Long, feeCents: Long, feeMode: FeeMode = FeeMode.EXTRA): TransferAmounts {
        require(amountCents in 1..Money.MAX_CENTS) { "INVALID_AMOUNT" }
        require(feeCents in 0..Money.MAX_CENTS) { "TRANSFER_FEE_AMBIGUOUS" }
        val mode = if (feeCents == 0L) FeeMode.EXTRA else feeMode
        val debit = when (mode) {
            FeeMode.EXTRA -> checkedAdd(amountCents, feeCents)
            FeeMode.DEDUCTED -> amountCents
        }
        val credit = when (mode) {
            FeeMode.EXTRA -> amountCents
            FeeMode.DEDUCTED -> {
                require(feeCents < amountCents) { "TRANSFER_AMOUNT_CONFLICT" }
                Math.subtractExact(amountCents, feeCents)
            }
        }
        require(debit <= Money.MAX_CENTS) { "INVALID_AMOUNT" }
        check(Math.subtractExact(debit, credit) == feeCents)
        return TransferAmounts(amountCents, feeCents, mode, debit, credit)
    }

    /** Compare only explicitly labelled amounts; missing fee information is never assumed free. */
    fun verifyObserved(amountCents: Long, feeCents: Long, feeMode: FeeMode,
                       debitCents: Long? = null, creditCents: Long? = null): TransferAmounts {
        val amounts = calculate(amountCents, feeCents, feeMode)
        require(debitCents == null || debitCents == amounts.debitCents) { "TRANSFER_AMOUNT_CONFLICT" }
        require(creditCents == null || creditCents == amounts.creditCents) { "TRANSFER_AMOUNT_CONFLICT" }
        return amounts
    }

    fun validateEvidence(evidence: TransferEvidence, refund: RefundInfo? = null,
                         standaloneRefund: StandaloneRefundEvidence? = null): TransferAmounts {
        require(refund == null && standaloneRefund == null) { "TRANSFER_REFUND_CONFLICT" }
        require(evidence.intent == TransferIntent.SELF_TRANSFER) { "TRANSFER_IDENTITY_CONFLICT" }
        require(evidence.terminalState == TransferTerminalState.COMPLETED) { "TRANSFER_STATUS_REQUIRED" }
        require(evidence.feeCents != null && evidence.feeMode != null) { "TRANSFER_FEE_AMBIGUOUS" }
        require(evidence.amountCents != null) { "INVALID_AMOUNT" }
        return verifyObserved(evidence.amountCents, evidence.feeCents, evidence.feeMode,
            evidence.debitCents, evidence.creditCents)
    }

    private fun checkedAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("INVALID_AMOUNT")
    }
}
