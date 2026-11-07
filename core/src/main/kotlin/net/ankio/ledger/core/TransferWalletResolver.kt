package net.ankio.ledger.core

data class TransferWalletResolution(
    val from: WalletResolution,
    val to: WalletResolution,
    val reviewCode: String? = null,
) {
    val complete: Boolean get() = reviewCode == null && from.reviewCode == null && to.reviewCode == null &&
        from.accountUid != null && to.accountUid != null && from.accountUid != to.accountUid
}

/** Both legs need explicit evidence or a manual choice. There is no default-wallet fallback. */
object TransferWalletResolver {
    fun resolve(candidates: List<WalletCandidate>, fromEvidence: PaymentMethodEvidence? = null,
                toEvidence: PaymentMethodEvidence? = null, appBindings: Map<String, String> = emptyMap(),
                manualFromAccountUid: String? = null, manualToAccountUid: String? = null,
                existing: TransferWalletResolution? = null,
                originalAccountUids: Set<String> = emptySet()): TransferWalletResolution {
        require(candidates.map { it.uid }.distinct().size == candidates.size) { "TRANSFER_IDENTITY_CONFLICT" }
        val from = resolveEnd(candidates, fromEvidence, "DEBIT", appBindings,
            manualFromAccountUid, existing?.from, originalAccountUids)
        val to = resolveEnd(candidates, toEvidence, "CREDIT", appBindings,
            manualToAccountUid, existing?.to, originalAccountUids)
        val same = from.accountUid != null && from.accountUid == to.accountUid
        return TransferWalletResolution(from, to, if (same) "TRANSFER_SAME_WALLET" else null)
    }

    private fun resolveEnd(candidates: List<WalletCandidate>, evidence: PaymentMethodEvidence?, role: String,
                           appBindings: Map<String, String>, manualAccountUid: String?,
                           existing: WalletResolution?, originalAccountUids: Set<String>): WalletResolution {
        val fingerprint = evidence?.let(WalletResolver::fingerprint)
        fun validate(uid: String): String? {
            val candidate = candidates.singleOrNull { it.uid == uid } ?: return "TRANSFER_WALLET_REQUIRED"
            if (candidate.type != "ASSET") return "TRANSFER_ACCOUNT_UNSUPPORTED"
            if (candidate.archived && candidate.uid !in originalAccountUids) return "TRANSFER_WALLET_REQUIRED"
            return null
        }
        if (manualAccountUid != null) {
            val error = validate(manualAccountUid)
            return WalletResolution(manualAccountUid.takeIf { error == null }, "MANUAL", error,
                fingerprint, manualLocked = true)
        }
        val usable = candidates.filter { validate(it.uid) == null }
        val strong = evidence?.takeIf { !it.ambiguous && it.role == role }?.let { value ->
            when (value.medium) {
                "BANK_CARD" -> usable.filter { candidate ->
                    val bank = BankCodes.normalize(candidate.bankCode) ?: BankCodes.normalize(candidate.kind)
                    val isBank = candidate.kind == "bank" || candidate.kind.startsWith("bank_") || bank != null
                    isBank && (value.bankCode == null || BankCodes.normalize(value.bankCode)?.let { it == bank } == true) &&
                        (value.cardLast4 == null || candidate.cardLast4 == value.cardLast4) &&
                        (value.bankCode != null || value.cardLast4 != null)
                }.singleOrNull()
                "WECHAT_BALANCE" -> platformWallet(usable, "wechat", value, appBindings)
                "ALIPAY_BALANCE" -> platformWallet(usable, "alipay", value, appBindings)
                else -> null
            }
        }
        if (existing?.accountUid != null) {
            val error = validate(existing.accountUid)
            if (error != null) return existing.copy(reviewCode = error)
            // Neither a new OCR pass nor changed settings may replace a selected end.
            val conflict = strong != null && strong.uid != existing.accountUid
            return existing.copy(
                reviewCode = if (conflict) "TRANSFER_PAYMENT_EVIDENCE_CONFLICT" else existing.reviewCode,
                evidenceFingerprint = fingerprint ?: existing.evidenceFingerprint,
            )
        }
        if (existing?.manualLocked == true || existing?.selectionSource == "MANUAL") return existing
        if (strong != null) return WalletResolution(strong.uid, "PAYMENT_METHOD", evidenceFingerprint = fingerprint)
        val unsupported = evidence?.medium == "UNSUPPORTED" || evidence?.medium == "LIABILITY"
        return WalletResolution(null, null,
            if (unsupported) "TRANSFER_ACCOUNT_UNSUPPORTED" else "TRANSFER_WALLET_REQUIRED", fingerprint)
    }

    private fun platformWallet(candidates: List<WalletCandidate>, platform: String,
                               evidence: PaymentMethodEvidence, appBindings: Map<String, String>): WalletCandidate? {
        if (evidence.platform != null && evidence.platform != platform) return null
        val matching = candidates.filter { it.kind == platform }
        val packageName = if (platform == "wechat") PaymentApps.WECHAT else PaymentApps.ALIPAY
        val bound = appBindings[packageName]
        return matching.singleOrNull { it.uid == bound } ?: matching.singleOrNull()
    }
}
