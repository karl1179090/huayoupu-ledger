package net.ankio.ledger.core

import java.security.MessageDigest

data class WalletCandidate(
    val uid: String,
    val type: String = "ASSET",
    val kind: String = "custom",
    val bankCode: String? = null,
    val cardLast4: String? = null,
    val archived: Boolean = false,
)

data class WalletResolution(
    val accountUid: String?,
    val selectionSource: String?,
    val reviewCode: String? = null,
    val evidenceFingerprint: String? = null,
    val manualLocked: Boolean = false,
)

object WalletResolver {
    fun resolve(candidates: List<WalletCandidate>, sourcePackage: String? = null,
                evidence: PaymentMethodEvidence? = null, defaultAccountUid: String? = null,
                appBindings: Map<String, String> = emptyMap(), manualAccountUid: String? = null,
                existing: WalletResolution? = null, requireActualBank: Boolean = false): WalletResolution {
        val usable = candidates.filter { it.type == "ASSET" && !it.archived }
        val fingerprint = evidence?.let(::fingerprint)
        if (manualAccountUid != null) {
            return if (usable.any { it.uid == manualAccountUid })
                WalletResolution(manualAccountUid, "MANUAL", evidenceFingerprint = fingerprint, manualLocked = true)
            else WalletResolution(null, "MANUAL", "INVALID_WALLET", fingerprint, true)
        }
        if (existing?.manualLocked == true || existing?.selectionSource == "MANUAL")
            return if (evidence != null && evidence.medium != "UNKNOWN") existing.copy(evidenceFingerprint = fingerprint) else existing
        if(evidence?.reason=="ACTUAL_ACCOUNT_REQUIRED")
            return WalletResolution(null,"PAYMENT_METHOD","ACTUAL_ACCOUNT_REQUIRED",fingerprint)
        val packageName = sourcePackage?.let(::sourcePackage)
        val actualPlatform = when (packageName) {
            PaymentApps.WECHAT -> "wechat"
            PaymentApps.ALIPAY -> "alipay"
            else -> null
        }
        val platform = actualPlatform ?: evidence?.platform?.takeIf { packageName == null && it in setOf("wechat", "alipay") }
        val platformPackage = when (platform) { "wechat" -> PaymentApps.WECHAT; "alipay" -> PaymentApps.ALIPAY; else -> null }
        fun platformWallet(targetPlatform: String): WalletCandidate? {
            val matching = usable.filter { it.kind == targetPlatform }
            val targetPackage = when (targetPlatform) { "wechat" -> PaymentApps.WECHAT; "alipay" -> PaymentApps.ALIPAY; else -> null }
            val bound = targetPackage?.let(appBindings::get)
            return matching.singleOrNull { it.uid == bound } ?: matching.singleOrNull()
        }
        val bankMatches = if(evidence?.medium=="BANK_CARD") usable.filter { candidate ->
                val bank = BankCodes.normalize(candidate.bankCode) ?: BankCodes.normalize(candidate.kind)
                val card = candidate.kind == "bank" || candidate.kind.startsWith("bank_") || bank != null
                card && (evidence.bankCode == null || BankCodes.normalize(evidence.bankCode)?.let { it == bank } == true) &&
                    (evidence.cardLast4 == null || candidate.cardLast4 == evidence.cardLast4) &&
                    (evidence.bankCode != null || evidence.cardLast4 != null)
            } else emptyList()
        val strong = if (evidence == null || evidence.ambiguous) null else when (evidence.medium) {
            "BANK_CARD" -> bankMatches.singleOrNull()
            "WECHAT_BALANCE" -> platformWallet("wechat")
            "ALIPAY_BALANCE" -> platformWallet("alipay")
            else -> null
        }
        val fallbackReason = when {
            evidence?.ambiguous == true || bankMatches.size > 1 -> "PAYMENT_METHOD_AMBIGUOUS"
            evidence?.medium == "UNSUPPORTED" -> "PAYMENT_METHOD_UNSUPPORTED"
            evidence != null && evidence.medium != "UNKNOWN" -> "PAYMENT_METHOD_UNMATCHED"
            else -> null
        }
        if(evidence?.executed==true && evidence.medium!="BANK_CARD" && strong==null &&
            !(existing?.selectionSource=="IMPORT" && existing.accountUid!=null))
            return WalletResolution(null,"PAYMENT_METHOD","ACTUAL_ACCOUNT_REQUIRED",fingerprint)
        if (requireActualBank && existing?.accountUid != null && existing.selectionSource in setOf("APP", "DEFAULT") && strong == null &&
            (evidence?.medium == "BANK_CARD" || evidence?.bankCode != null || evidence?.cardLast4 != null))
            return WalletResolution(null, "PAYMENT_METHOD", fallbackReason ?: "PAYMENT_METHOD_UNMATCHED", fingerprint)
        if (existing?.accountUid != null) {
            if (existing.selectionSource == "PAYMENT_METHOD") {
                if (requireActualBank && evidence != null && (evidence.medium == "BANK_CARD" || evidence.bankCode != null || evidence.cardLast4 != null) &&
                    (strong == null || strong.uid != existing.accountUid))
                    return existing.copy(accountUid=null, evidenceFingerprint=fingerprint, reviewCode=if(strong!=null)"PAYMENT_EVIDENCE_CONFLICT"
                        else fallbackReason ?: "PAYMENT_METHOD_UNMATCHED")
                return if (strong != null && strong.uid != existing.accountUid)
                    existing.copy(reviewCode = "PAYMENT_EVIDENCE_CONFLICT")
                else if (strong != null) existing.copy(evidenceFingerprint = fingerprint) else existing
            }
            // Settings and cosmetic supplements never move an already selected wallet.
            if (strong == null || existing.selectionSource !in setOf("APP", "DEFAULT")) return existing
        }
        if (strong != null) return WalletResolution(strong.uid, "PAYMENT_METHOD", evidenceFingerprint = fingerprint)
        // Actual bank evidence cannot be replaced by the app hosting the receipt.
        if (evidence?.medium == "BANK_CARD" || evidence?.bankCode != null || evidence?.cardLast4 != null)
            return WalletResolution(null, "PAYMENT_METHOD", fallbackReason ?: "PAYMENT_METHOD_UNMATCHED", fingerprint)
        val binding = (packageName ?: platformPackage)?.let(appBindings::get)
        val appWallet = usable.singleOrNull { it.uid == binding } ?: if (platform != null) platformWallet(platform) else null
        if (appWallet != null) return WalletResolution(appWallet.uid, "APP", fallbackReason, fingerprint)
        if (usable.any { it.uid == defaultAccountUid }) return WalletResolution(defaultAccountUid, "DEFAULT", fallbackReason, fingerprint)
        return WalletResolution(null, null, "WALLET_REQUIRED", fingerprint)
    }

    /** Historical source prefixes are allowed; arbitrary OCR text never becomes a source package. */
    fun sourcePackage(origin: String): String? {
        val value = origin.trim().removePrefix("shake:").removePrefix("payment_auto:").removePrefix("shortcut:")
        return value.takeIf { Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+").matches(it) }
    }

    fun fingerprint(evidence: PaymentMethodEvidence): String {
        val legacy = listOf(evidence.medium, evidence.role, BankCodes.normalize(evidence.bankCode).orEmpty(),
            evidence.cardLast4.orEmpty(), evidence.platform.orEmpty(), evidence.ambiguous.toString()).joinToString("|")
        val stable=if(evidence.executed)"$legacy|executed" else legacy
        return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
