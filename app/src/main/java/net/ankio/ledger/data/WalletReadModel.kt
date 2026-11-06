package net.ankio.ledger.data

import net.ankio.ledger.core.PaymentMethodEvidence
import net.ankio.ledger.core.StandaloneRefundEvidence
import org.json.JSONObject

/** Bounded wallet summaries shared by paged lists, legacy snapshots and detail refreshes. */
object WalletReadModel {
    fun summary(link: RecordWalletLink?, account: LedgerAccount?): JSONObject = JSONObject().apply {
        put("eligibility", link?.eligibility?.name ?: "LEGACY_EXCLUDED")
        put("accountUid", link?.accountUid ?: JSONObject.NULL); put("accountName", account?.name ?: JSONObject.NULL)
        put("kind", account?.kind ?: JSONObject.NULL); put("bankCode", account?.bankCode ?: JSONObject.NULL)
        put("cardLast4", account?.cardLast4 ?: JSONObject.NULL); put("archived", account?.archived ?: false)
        put("selectionSource", link?.selectionSource ?: JSONObject.NULL); put("manualLocked", link?.manualLocked ?: false)
        put("appliedCents", link?.appliedCents ?: 0); put("reviewCode", link?.reviewCode ?: JSONObject.NULL)
        put("revision", link?.revision ?: 0)
    }
    fun summary(store: LedgerStore, record: LedgerRecord): JSONObject =
        summary(record.wallet, record.wallet?.accountUid?.let(store::getAccountByUid))

    fun evidence(e: PaymentMethodEvidence?): Any = e?.let {
        JSONObject().put("medium", it.medium).put("role", it.role).put("bankCode", it.bankCode ?: JSONObject.NULL)
            .put("cardLast4", it.cardLast4 ?: JSONObject.NULL).put("platform", it.platform ?: JSONObject.NULL)
            .put("rawLabel", it.rawLabel).put("rawValue", it.rawValue).put("ambiguous", it.ambiguous)
            .put("reason", it.reason ?: JSONObject.NULL)
    } ?: JSONObject.NULL

    fun standalone(e: StandaloneRefundEvidence?): Any = e?.let {
        JSONObject().put("provider", it.provider).put("refundedCents", it.refundedCents)
            .put("refundId", it.refundId ?: JSONObject.NULL).put("originalTransactionId", it.originalTransactionId ?: JSONObject.NULL)
    } ?: JSONObject.NULL
}
