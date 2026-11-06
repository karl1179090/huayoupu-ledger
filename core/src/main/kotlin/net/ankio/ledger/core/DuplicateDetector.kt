package net.ankio.ledger.core

import kotlin.math.abs

object DuplicateDetector {
    private val merchantSeparators = Regex("[\\s·•:：()（）]")

    fun find(capture: Capture, bill: ParsedBill, existing: List<ExistingBill>): Duplicate {
        val currentProvider = identityScope(capture)
        val exact = existing.firstOrNull { old ->
            if (bill.transactionId.isNullOrBlank() || bill.transactionId != old.transactionId) return@firstOrNull false
            val oldProvider = identityScope(Capture(old.source, old.origin, old.text))
            currentProvider != null && currentProvider == oldProvider &&
                (bill.direction == Direction.TRANSFER || old.direction == Direction.TRANSFER ||
                    bill.direction == old.direction && bill.amountCents != null && bill.amountCents == old.amountCents)
        }
        if (exact != null) return Duplicate.Exact(exact.id)
        if (bill.amountCents == null || bill.direction == null) return Duplicate.None
        // Import time is not transaction time. It cannot establish proximity to today's purchases.
        if (capture.source in imageSources && !bill.timeExplicit) return Duplicate.None
        val merchant = normalizedMerchant(bill.merchant)
        val usableMerchant = !genericMerchant(bill.merchant)
        val currentText = normalize(capture.text).trim()
        val possible = existing.firstOrNull { old ->
            if (bill.amountCents != old.amountCents || bill.direction != old.direction ||
                abs(bill.occurredAt - old.occurredAt) > 120_000L ||
                old.source in imageSources && old.timeExplicit != true) return@firstOrNull false
            val samePayload = old.source == capture.source && old.origin == capture.origin &&
                normalize(old.text).trim() == currentText
            val oldProvider = identityScope(Capture(old.source, old.origin, old.text))
            // A payment reminder may omit the counterparty completely. Link it only as
            // a candidate to an explicit-time image from the SAME payment provider.
            // This is never Exact: two genuine purchases can share amount and minute.
            val reminderAndImage = currentProvider != null && currentProvider == oldProvider &&
                ((capture.source == Source.NOTIFICATION && old.source in imageSources && genericMerchant(bill.merchant)) ||
                    (old.source == Source.NOTIFICATION && capture.source in imageSources && genericMerchant(old.merchant)))
            (samePayload || usableMerchant && merchant == normalizedMerchant(old.merchant) || reminderAndImage) &&
                // Distinct explicit IDs from a provider identify distinct real transactions.
                !(currentProvider != null && currentProvider == oldProvider &&
                    bill.transactionId != null && old.transactionId != null && bill.transactionId != old.transactionId)
        }
        return possible?.let { Duplicate.Possible(it.id) } ?: Duplicate.None
    }

    private val imageSources = setOf(Source.IMAGE, Source.OCR)
    fun identityScope(capture:Capture):String? = provider(capture.origin) ?: imageProvider(capture) ?:
        capture.origin.substringAfter(':').takeIf { capture.source in imageSources && CapturePrivacy.manualPackageAllowed(it) }?.let { "app:$it" }
    private fun genericMerchant(value: String) = normalizedMerchant(value).let {
        it.isBlank() || it in setOf("未知商户", "其他", "微信支付", "微信支出", "微信收入", "支付宝", "支付宝支出", "支付宝收入")
    }

    private fun normalizedMerchant(value: String): String = normalize(value).lowercase()
        .replace(merchantSeparators, "").trim()

    private fun imageProvider(capture: Capture): String? {
        if (capture.source != Source.IMAGE) return null
        return when {
            capture.text.contains("微信") && !capture.text.contains("支付宝") -> "wechat"
            capture.text.contains("支付宝") && !capture.text.contains("微信") -> "alipay"
            else -> null
        }
    }
}
