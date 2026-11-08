package net.ankio.ledger.core

/** Automatic capture keeps its existing income/privacy boundary; explicit capture may review any bill. */
enum class RecognitionPolicy {
    AUTOMATIC_PAYMENT, USER_INITIATED;

    fun allows(bill: ParsedBill, origin: String): Boolean = this == USER_INITIATED || bill.receipt?.bank == null && when (bill.direction) {
        Direction.EXPENSE -> bill.refund == null && bill.standaloneRefund == null
        Direction.TRANSFER -> bill.transfer?.let { TransferEvidenceParser.automaticCandidate(it, origin) } == true
        else -> false
    }

    companion object {
        fun fromExpenseOnly(expenseOnly: Boolean) = if (expenseOnly) AUTOMATIC_PAYMENT else USER_INITIATED
    }
}
