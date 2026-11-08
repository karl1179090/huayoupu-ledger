package net.ankio.ledger.core

enum class Source { OCR, IMAGE, SMS, NOTIFICATION, MANUAL }
enum class Direction { EXPENSE, INCOME, TRANSFER }
enum class RecordState { POSTED, REVIEW }

data class Capture(
    val source: Source,
    val origin: String,
    val text: String,
    val receivedAt: Long = System.currentTimeMillis(),
)

data class ParsedBill(
    val amountCents: Long?,
    val direction: Direction?,
    val merchant: String,
    val category: String,
    val occurredAt: Long,
    val transactionId: String?,
    val reliable: Boolean,
    val reason: String,
    val timeExplicit: Boolean = false,
    val refund: RefundInfo? = null,
    val paymentMethod: PaymentMethodEvidence? = null,
    val standaloneRefund: StandaloneRefundEvidence? = null,
    val transfer: TransferEvidence? = null,
    val receipt: ReceiptEvidence? = null,
    val categoryDecision: CategoryDecision? = null,
)

/** A completed receipt with no original total stays pending until its original payment is verified. */
data class StandaloneRefundEvidence(
    val provider: String,
    val refundedCents: Long,
    val refundId: String? = null,
    val originalTransactionId: String? = null,
)

/** Explicit payment/arrival or bank account evidence, never a transaction identity. UNKNOWN role preserves missing direction. */
data class PaymentMethodEvidence(
    val medium: String,
    val role: String,
    val bankCode: String? = null,
    val cardLast4: String? = null,
    val platform: String? = null,
    val rawLabel: String = "",
    val rawValue: String = "",
    val ambiguous: Boolean = false,
    val reason: String? = null,
)

/** Completed refund evidence; amountCents is always computed locally from these cents. */
data class RefundInfo(
    val provider: String,
    val originalAmountCents: Long,
    val refundedCents: Long,
    val mode: String = "cumulative",
    val refundId: String? = null,
)

data class ExistingBill(
    val id: Long,
    val source: Source,
    val origin: String,
    val text: String,
    val amountCents: Long?,
    val direction: Direction?,
    val merchant: String,
    val occurredAt: Long,
    val transactionId: String?,
    val timeExplicit: Boolean? = null,
)

sealed class Duplicate {
    data object None : Duplicate()
    data class Exact(val id: Long) : Duplicate()
    data class Possible(val id: Long) : Duplicate()
}
