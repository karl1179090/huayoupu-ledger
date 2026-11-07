package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONObject

/** Pure record preparation. LedgerStore alone persists it and applies the complete effect set. */
internal object TransferRecognitionEnricher {
    fun enrich(record: LedgerRecord, incoming: ParsedBill, wallets: List<WalletCandidate>,
               bindings: Map<String, String>, allowPromotion: Boolean = true): LedgerRecord {
        val evidence = incoming.transfer ?: return record
        if (incoming.direction != Direction.TRANSFER && !(incoming.direction == null && record.state == RecordState.REVIEW)) return record
        require(incoming.refund == null && incoming.standaloneRefund == null) { "TRANSFER_REFUND_CONFLICT" }
        val old = record.transfer
        val resolution = if(evidence.intent!=TransferIntent.SELF_TRANSFER) TransferWalletResolution(
            WalletResolution(null,null,"TRANSFER_IDENTITY_CONFLICT"),WalletResolution(null,null,"TRANSFER_IDENTITY_CONFLICT"))
        else TransferWalletResolver.resolve(wallets, evidence.from, evidence.to, bindings,
            existing = old?.let { TransferWalletResolution(
                WalletResolution(it.fromAccountUid, it.fromSelectionSource, evidenceFingerprint = it.fromEvidenceFingerprint, manualLocked = it.fromManualLocked),
                WalletResolution(it.toAccountUid, it.toSelectionSource, evidenceFingerprint = it.toEvidenceFingerprint, manualLocked = it.toManualLocked)) },
            originalAccountUids = if(record.state==RecordState.POSTED) listOfNotNull(old?.fromAccountUid, old?.toAccountUid).toSet() else emptySet())
        val amount = record.bill.amountCents ?: incoming.amountCents
        val fee = old?.feeCents ?: evidence.feeCents
        val mode = (old?.feeMode ?: evidence.feeMode)?.let { if (fee == 0L) FeeMode.EXTRA else it }
        val feeConflict = old != null && (old.feeCents != null && evidence.feeCents != null && old.feeCents != evidence.feeCents ||
            old.feeMode != null && evidence.feeMode != null && fee != 0L && old.feeMode != evidence.feeMode)
        val formulaCode = if (amount != null && fee != null && mode != null) runCatching {
            TransferCalculator.verifyObserved(amount, fee, mode, evidence.debitCents, evidence.creditCents)
        }.exceptionOrNull()?.let { "TRANSFER_AMOUNT_CONFLICT" } else null
        val hardCode = evidence.reviewCodes.firstOrNull { it !in setOf("TRANSFER_WALLET_REQUIRED", "TRANSFER_FEE_AMBIGUOUS") }
        val incompleteOnly = evidence.reviewCodes.isNotEmpty() && evidence.reviewCodes.all {
            it in setOf("TRANSFER_WALLET_REQUIRED", "TRANSFER_FEE_AMBIGUOUS")
        }
        val trusted = incoming.reliable || incompleteOnly && old != null
        val forcedReview = !allowPromotion || record.state==RecordState.REVIEW && old?.reviewCode=="TRANSFER_REVIEW_REQUIRED"
        val code = when {
            forcedReview -> "TRANSFER_REVIEW_REQUIRED"
            feeConflict -> "TRANSFER_IDENTITY_CONFLICT"
            formulaCode != null -> formulaCode
            hardCode != null -> hardCode
            !trusted && !incompleteOnly -> "TRANSFER_REVIEW_REQUIRED"
            evidence.from?.ambiguous == true || evidence.to?.ambiguous == true -> "TRANSFER_PAYMENT_EVIDENCE_CONFLICT"
            resolution.reviewCode != null -> resolution.reviewCode
            resolution.from.reviewCode != null -> resolution.from.reviewCode
            resolution.to.reviewCode != null -> resolution.to.reviewCode
            amount == null || amount <= 0 -> "INVALID_AMOUNT"
            fee == null || mode == null -> "TRANSFER_FEE_AMBIGUOUS"
            !trusted -> "TRANSFER_REVIEW_REQUIRED"
            evidence.intent != TransferIntent.SELF_TRANSFER -> "TRANSFER_IDENTITY_CONFLICT"
            evidence.terminalState != TransferTerminalState.COMPLETED -> "TRANSFER_STATUS_REQUIRED"
            else -> null
        }
        // A later OCR page may prompt a correction; it never withdraws already-posted money.
        if (old != null && record.state == RecordState.POSTED && code != null)
            return record.copy(transfer = old.copy(reviewCode = code, recognitionEvidence = TransferEvidenceCodec.encode(evidence)))
        fun endEvidence(current: String?, selected: WalletResolution, value: PaymentMethodEvidence?): String? =
            if (value == null || value.ambiguous || selected.reviewCode == "TRANSFER_PAYMENT_EVIDENCE_CONFLICT") current else encode(value)
        val details = (old ?: RecordTransferDetails(record.uid)).copy(
            fromAccountUid = resolution.from.accountUid, toAccountUid = resolution.to.accountUid,
            feeCents = fee, feeMode = mode,
            fromSelectionSource = resolution.from.selectionSource, toSelectionSource = resolution.to.selectionSource,
            fromManualLocked = resolution.from.manualLocked, toManualLocked = resolution.to.manualLocked,
            fromEvidence = endEvidence(old?.fromEvidence, resolution.from, evidence.from),
            toEvidence = endEvidence(old?.toEvidence, resolution.to, evidence.to),
            fromEvidenceFingerprint = if(resolution.from.reviewCode=="TRANSFER_PAYMENT_EVIDENCE_CONFLICT") old?.fromEvidenceFingerprint else resolution.from.evidenceFingerprint,
            toEvidenceFingerprint = if(resolution.to.reviewCode=="TRANSFER_PAYMENT_EVIDENCE_CONFLICT") old?.toEvidenceFingerprint else resolution.to.evidenceFingerprint,
            evidenceVersion = evidence.evidenceVersion, reviewCode = code,
            recognitionEvidence = TransferEvidenceCodec.encode(evidence))
        val posted = code == null && record.duplicateId == null
        val state = if (record.state == RecordState.POSTED && old != null || posted) RecordState.POSTED else RecordState.REVIEW
        val reason = if (state == RecordState.POSTED) "" else if (record.duplicateId != null) record.reason else message(code)
        val completedBill = record.bill.copy(amountCents = amount,
            direction = if(evidence.intent==TransferIntent.SELF_TRANSFER) Direction.TRANSFER else null,
            occurredAt = if (record.state == RecordState.REVIEW && !record.bill.timeExplicit && incoming.timeExplicit) incoming.occurredAt else record.bill.occurredAt,
            timeExplicit = record.bill.timeExplicit || incoming.timeExplicit, reliable = posted,
            reason = reason, transfer = evidence)
        return record.copy(bill = completedBill, transfer = details, state = state, reason = reason)
    }

    fun message(code: String?): String = when (code) {
        "TRANSFER_IDENTITY_CONFLICT", "TRANSFER_PAYMENT_EVIDENCE_CONFLICT" -> "同一交易的转账信息有冲突，请打开原账单核对"
        "TRANSFER_ACCOUNT_UNSUPPORTED" -> "转账仅支持本人的资产钱包，请核对账户与币种"
        "TRANSFER_FEE_AMBIGUOUS" -> "请确认转账手续费及费用方式"
        "TRANSFER_STATUS_REQUIRED" -> "转账尚未确认完成，请核对终态"
        "TRANSFER_REVIEW_REQUIRED" -> "请人工核对转账信息后确认入账"
        "TRANSFER_AMOUNT_CONFLICT", "INVALID_AMOUNT" -> "转账金额与扣款、到账或手续费不一致，请核对"
        "TRANSFER_SAME_WALLET" -> "转出与转入钱包不能相同"
        else -> "请确认转出与转入钱包后入账"
    }

    private fun encode(value: PaymentMethodEvidence): String = JSONObject().apply {
        put("version", 1); put("medium", value.medium); put("role", value.role); put("bankCode", value.bankCode)
        put("cardLast4", value.cardLast4); put("platform", value.platform); put("rawLabel", value.rawLabel.take(80))
        put("rawValue", value.rawValue.take(160)); put("ambiguous", value.ambiguous); put("reason", value.reason)
    }.toString()
}
