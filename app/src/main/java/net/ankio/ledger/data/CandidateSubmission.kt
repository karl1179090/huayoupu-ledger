package net.ankio.ledger.data

import net.ankio.ledger.core.CandidateId

/** The outcome belongs to a candidate, while legacy callers can continue using IngestResult. */
data class CandidateSubmission(val candidateId: CandidateId, val outcome: IngestResult)

/** SINGLE deliberately uses the pre-existing receipt token, including for upgraded tasks. */
internal fun candidateOperationKey(operationKey: String, candidateId: CandidateId): String =
    if (candidateId == CandidateId.SINGLE) operationKey else "recognition-child:" +
        java.security.MessageDigest.getInstance("SHA-256").digest(
            ("ledger-candidate-v1\u0000"+operationKey+"\u0000"+candidateId.value).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
