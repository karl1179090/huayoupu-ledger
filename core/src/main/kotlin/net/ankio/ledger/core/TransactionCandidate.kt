package net.ankio.ledger.core

/** Identity within one recognition task, independent of a bank or payment transaction ID. */
data class CandidateId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }

    companion object {
        /** Existing single-result tasks keep their original operation key and receipt. */
        val SINGLE = CandidateId("single")
    }
}

/** One extracted transaction; the existing ParsedBill continues to own its financial evidence. */
data class TransactionCandidate(val id: CandidateId, val bill: ParsedBill)
