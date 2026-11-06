package net.ankio.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Independent S1 acceptance cases use synthetic values and public calendar rules. */
class TransferRulesAcceptanceTest {
    @Test fun validExtremeCombinationsConservePrincipalAndChargeExactlyOneFee() {
        for (amount in listOf(1L, 2L, 100L, Money.MAX_CENTS / 2, Money.MAX_CENTS - 1, Money.MAX_CENTS)) {
            for (fee in listOf(0L, 1L, amount - 1, Money.MAX_CENTS - amount).distinct()) {
                for (mode in FeeMode.entries) {
                    if (mode == FeeMode.EXTRA && amount + fee > Money.MAX_CENTS ||
                        mode == FeeMode.DEDUCTED && fee >= amount) continue
                    val result = TransferCalculator.calculate(amount, fee, mode)
                    assertEquals(fee, result.debitCents - result.creditCents)
                    assertTrue(result.debitCents in 1..Money.MAX_CENTS)
                    assertTrue(result.creditCents in 1..Money.MAX_CENTS)
                    val effects = WalletEffectCalculator.targetEffects(amount, Direction.TRANSFER, true,
                        transfer = TransferTarget("from", "to", fee, mode))
                    assertEquals(-fee, effects.values.sum())
                    assertEquals(-result.debitCents, WalletEffectCalculator.checkedBalance(0, effects.getValue("from")))
                    if (fee == 0L) assertEquals(FeeMode.EXTRA, result.feeMode)
                }
            }
        }
    }

    @Test fun allOverlappingAndSwappedWalletPairsProduceTheSameFinalEffectsAsFreshPosting() {
        val previous = mapOf("a" to -10_100L, "b" to 10_000L)
        for (from in listOf("a", "b", "c", "d")) for (to in listOf("a", "b", "c", "d")) {
            if (from == to) continue
            val target = WalletEffectCalculator.targetEffects(12_000, Direction.TRANSFER, true,
                transfer = TransferTarget(from, to, 200, FeeMode.DEDUCTED))
            val delta = WalletEffectCalculator.changes(previous, target)
            assertEquals(delta.size, delta.map { it.accountUid }.distinct().size)
            for (uid in listOf("a", "b", "c", "d")) {
                assertEquals(target[uid] ?: 0L,
                    Math.addExact(previous[uid] ?: 0L, delta.singleOrNull { it.accountUid == uid }?.deltaCents ?: 0L))
            }
            assertEquals(-100L, delta.sumOf { it.deltaCents })
        }
    }

    @Test fun anExplicitManualCorrectionClearsOnlyThatEndsEvidenceConflict() {
        val candidates = listOf(WalletCandidate("cash", kind = "cash"),
            WalletCandidate("bank", kind = "bank", bankCode = "cmb", cardLast4 = "0007"),
            WalletCandidate("wechat", kind = "wechat"))
        val debit = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007")
        val credit = PaymentMethodEvidence("WECHAT_BALANCE", "CREDIT", platform = "wechat")
        val chosen = TransferWalletResolver.resolve(candidates, toEvidence = credit, manualFromAccountUid = "cash")
        val conflict = TransferWalletResolver.resolve(candidates, debit, credit, existing = chosen)
        assertEquals("cash", conflict.from.accountUid)
        assertFalse(conflict.complete)
        val corrected = TransferWalletResolver.resolve(candidates, debit, credit,
            manualFromAccountUid = "bank", existing = conflict)
        assertTrue(corrected.complete)
        assertTrue(corrected.from.manualLocked)
        assertFalse(corrected.to.manualLocked)
        assertEquals("bank", corrected.from.accountUid)
        assertEquals(conflict.to.accountUid, corrected.to.accountUid)
    }

    @Test fun changingAPlatformBindingCannotReplaceAnAlreadySelectedTransferEnd() {
        val candidates = listOf(WalletCandidate("bank", kind = "bank", cardLast4 = "0007"),
            WalletCandidate("wechat-1", kind = "wechat"), WalletCandidate("wechat-2", kind = "wechat"))
        val from = PaymentMethodEvidence("BANK_CARD", "DEBIT", cardLast4 = "0007")
        val to = PaymentMethodEvidence("WECHAT_BALANCE", "CREDIT", platform = "wechat")
        val previous = TransferWalletResolver.resolve(candidates, from, to,
            appBindings = mapOf(PaymentApps.WECHAT to "wechat-1"))
        val updated = TransferWalletResolver.resolve(candidates, from, to,
            appBindings = mapOf(PaymentApps.WECHAT to "wechat-2"), existing = previous)
        assertEquals("wechat-1", updated.to.accountUid)
        assertEquals("bank", updated.from.accountUid)
        assertEquals("TRANSFER_PAYMENT_EVIDENCE_CONFLICT", updated.to.reviewCode)
    }

    @Test fun originalArchivedReferenceExceptionNeverMakesALiabilityATransferWallet() {
        val result = TransferWalletResolver.resolve(listOf(
            WalletCandidate("liability", type = "LIABILITY", archived = true), WalletCandidate("cash", kind = "cash")),
            manualFromAccountUid = "liability", manualToAccountUid = "cash", originalAccountUids = setOf("liability"))
        assertFalse(result.complete)
        assertNull(result.from.accountUid)
        assertEquals("TRANSFER_ACCOUNT_UNSUPPORTED", result.from.reviewCode)
    }

    @Test fun aNaturalWeekCrossingAMonthHasDifferentTotalsFromItsClippedMonthSegment() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-11-30T04:00:00Z")
        val week = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, now, zone)
        val month = ExpensePeriodBuckets.create(ExpensePeriod.MONTH, now, zone)
        val contributions = listOf(
            ExpenseContribution(LocalDate.parse("2026-11-30").atStartOfDay(zone).toInstant().toEpochMilli(), 100),
            ExpenseContribution(LocalDate.parse("2026-12-01").atStartOfDay(zone).toInstant().toEpochMilli(), 200))
        assertEquals(300L, ExpensePeriodBuckets.sum(week, contributions).totalCents)
        assertEquals(100L, ExpensePeriodBuckets.sum(month, contributions).totalCents)
        assertEquals("30日", month.buckets.last().label)
    }

    @Test fun halfHourDaylightSavingChangesAlsoUseRealLocalMidnights() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        for ((date, minutes) in listOf("2026-10-04" to 1410L, "2026-04-05" to 1470L)) {
            val day = LocalDate.parse(date)
            val range = ExpensePeriodBuckets.create(ExpensePeriod.WEEK, day.atTime(12, 0).atZone(zone).toInstant(), zone)
            val bucket = range.buckets.single { it.startDate == day }
            assertEquals(minutes * 60_000, bucket.endMillis - bucket.startMillis)
            assertEquals(day.plusDays(1), Instant.ofEpochMilli(bucket.endMillis).atZone(zone).toLocalDate())
        }
    }

    @Test fun invalidObservedLegAndNegativeStatisticalContributionAreRejected() {
        try { TransferCalculator.verifyObserved(10_000, 0, FeeMode.EXTRA, debitCents = -1); fail("Invalid leg accepted") }
        catch (error: IllegalArgumentException) { assertEquals("TRANSFER_AMOUNT_CONFLICT", error.message) }
        val range = ExpensePeriodBuckets.create(ExpensePeriod.YEAR, Instant.parse("2026-11-06T04:00:00Z"), ZoneId.of("UTC"))
        try { ExpensePeriodBuckets.sum(range, listOf(ExpenseContribution(range.startMillis, -1))); fail("Negative contribution accepted") }
        catch (error: IllegalArgumentException) { assertEquals("INVALID_AMOUNT", error.message) }
    }
}
