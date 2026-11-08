package net.ankio.ledger.data

import net.ankio.ledger.core.CandidateId

/** The outcome belongs to a candidate, while legacy callers can continue using IngestResult. */
data class CandidateSubmission(val candidateId: CandidateId, val outcome: IngestResult)

/** SINGLE deliberately uses the pre-existing receipt token, including for upgraded tasks. */
internal fun candidateOperationKey(operationKey: String, candidateId: CandidateId): String =
    if (candidateId == CandidateId.SINGLE) operationKey else "$operationKey:candidate:${candidateId.value}"
