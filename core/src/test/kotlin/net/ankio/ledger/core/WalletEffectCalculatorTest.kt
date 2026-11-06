package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class WalletEffectCalculatorTest {
    @Test fun accountSignsDoNotRelaxOrdinaryTransactionAmounts() {
        assertEquals(-12345L, AccountMoney.parseCents("−123.45"))
        assertEquals(7L, AccountMoney.parseCents("+0.07"))
        assertEquals(-Money.MAX_CENTS, AccountMoney.parseCents("-999999999.99"))
        assertNull(AccountMoney.parseCents("-1000000000")); assertNull(AccountMoney.parseCents("--1"))
        assertNull(AccountMoney.parseCents("-1.001")); assertNull(Money.parseCents("-123.45"))
    }

    @Test fun editsDirectionChangesRefundsAndRetriesUseOnlyTheDifference() {
        assertEquals(-10000L, WalletEffectCalculator.target(10000, Direction.EXPENSE, true))
        assertEquals(listOf(WalletDelta("a", -2000)), WalletEffectCalculator.changes("a", -10000, "a", -12000))
        assertEquals(listOf(WalletDelta("a", 20000)), WalletEffectCalculator.changes("a", -10000, "a", 10000))
        assertEquals(listOf(WalletDelta("a", 3000)), WalletEffectCalculator.changes("a", -10000, "a", -7000))
        assertTrue(WalletEffectCalculator.changes("a", -7000, "a", -7000).isEmpty())
        assertEquals(listOf(WalletDelta("a", 7000)), WalletEffectCalculator.changes("a", -7000, "a", 0))
    }

    @Test fun walletCorrectionsTransferTheRefundedNetAmountAndFullRefundIsZero() {
        assertEquals(listOf(WalletDelta("app", 7000), WalletDelta("card", -7000)),
            WalletEffectCalculator.changes("app", -7000, "card", -7000))
        assertEquals(0L, WalletEffectCalculator.target(0, Direction.EXPENSE, true))
        assertTrue(WalletEffectCalculator.changes("card", 0, "card", 0).isEmpty())
        assertEquals(0L, WalletEffectCalculator.target(100, Direction.EXPENSE, true, eligibilityActive = false))
        assertEquals(0L, WalletEffectCalculator.target(100, Direction.EXPENSE, true, deleted = true))
        assertEquals(0L, WalletEffectCalculator.target(null, null, false))
    }

    @Test fun balancesMayBecomeNegativeButNeverOverflowOrExceedTheSupportedRange() {
        assertEquals(-2000L, WalletEffectCalculator.checkedBalance(1000, -3000))
        for ((before, delta) in listOf(Money.MAX_CENTS to 1L, -Money.MAX_CENTS to -1L, Long.MAX_VALUE to 1L)) {
            try { WalletEffectCalculator.checkedBalance(before, delta); fail("Expected BALANCE_LIMIT") }
            catch (error: IllegalArgumentException) { assertEquals("BALANCE_LIMIT", error.message) }
        }
    }

    @Test fun transferTargetContainsOnlyItsTwoNetLegs() {
        val extra = WalletEffectCalculator.targetEffects(10_000, Direction.TRANSFER, true,
            transfer = TransferTarget("a", "b", 100, FeeMode.EXTRA))
        assertEquals(mapOf("a" to -10_100L, "b" to 10_000L), extra)
        val deducted = WalletEffectCalculator.targetEffects(10_000, Direction.TRANSFER, true,
            transfer = TransferTarget("a", "b", 100, FeeMode.DEDUCTED))
        assertEquals(mapOf("a" to -10_000L, "b" to 9_900L), deducted)
        assertEquals(-100L, deducted.values.sum())
    }

    @Test fun allNonActiveOrUnpostedStatesAlwaysHaveNoEffects() {
        for (direction in Direction.entries) {
            assertTrue(WalletEffectCalculator.targetEffects(null, direction, false).isEmpty())
            assertTrue(WalletEffectCalculator.targetEffects(null, direction, true, deleted = true).isEmpty())
            assertTrue(WalletEffectCalculator.targetEffects(null, direction, true, eligibilityActive = false).isEmpty())
        }
    }

    @Test fun replacingBothTransferEndsCalculatesAllFourWalletDifferences() {
        val original = mapOf("a" to -10_100L, "b" to 10_000L)
        val target = mapOf("c" to -12_100L, "d" to 12_000L)
        assertEquals(listOf(WalletDelta("a", 10_100), WalletDelta("b", -10_000),
            WalletDelta("c", -12_100), WalletDelta("d", 12_000)), WalletEffectCalculator.changes(original, target))
    }

    @Test fun overlappingOrSwappedTransferWalletsAreNettedByUid() {
        val original = mapOf("a" to -10_100L, "b" to 10_000L)
        assertEquals(listOf(WalletDelta("a", 10_100), WalletDelta("b", -22_000), WalletDelta("c", 11_900)),
            WalletEffectCalculator.changes(original, mapOf("b" to -12_000L, "c" to 11_900L)))
        val max = Money.MAX_CENTS
        assertEquals(listOf(WalletDelta("a", 2 * max), WalletDelta("b", -2 * max)),
            WalletEffectCalculator.changes(mapOf("a" to -max, "b" to max), mapOf("a" to max, "b" to -max)))
    }

    @Test fun editsDeleteUndoAndRetryOnlyApplyChangesInPersistedEffects() {
        val target = mapOf("a" to -10_100L, "b" to 10_000L)
        assertTrue(WalletEffectCalculator.changes(target, target).isEmpty())
        assertEquals(listOf(WalletDelta("a", 10_100), WalletDelta("b", -10_000)),
            WalletEffectCalculator.changes(target, emptyMap()))
        assertTrue(WalletEffectCalculator.changes(emptyMap(), emptyMap()).isEmpty())
        assertEquals(listOf(WalletDelta("a", -10_100), WalletDelta("b", 10_000)),
            WalletEffectCalculator.changes(emptyMap(), target))
        assertEquals(listOf(WalletDelta("a", 100), WalletDelta("b", -100)),
            WalletEffectCalculator.changes(target, mapOf("a" to -10_000L, "b" to 9_900L)))
    }

    @Test fun ordinaryAndTransferTypeCorrectionsUseTheSameEffectsInterface() {
        val expense = WalletEffectCalculator.targetEffects(10_000, Direction.EXPENSE, true, accountUid = "a")
        val transfer = WalletEffectCalculator.targetEffects(10_000, Direction.TRANSFER, true,
            transfer = TransferTarget("a", "b", 100, FeeMode.EXTRA))
        assertEquals(listOf(WalletDelta("a", -100), WalletDelta("b", 10_000)),
            WalletEffectCalculator.changes(expense, transfer))
        val income = WalletEffectCalculator.targetEffects(10_000, Direction.INCOME, true, accountUid = "b")
        assertEquals(listOf(WalletDelta("a", 10_100)), WalletEffectCalculator.changes(transfer, income))
        assertTrue(WalletEffectCalculator.targetEffects(0, Direction.EXPENSE, true, accountUid = "a").isEmpty())
    }

    @Test fun incompleteOrSameWalletActiveTransferAndOldScalarTransferAreRejected() {
        for (target in listOf(null, TransferTarget("a", null, 0, FeeMode.EXTRA),
            TransferTarget("a", "a", 0, FeeMode.EXTRA), TransferTarget("a", "b", null, null))) {
            try {
                WalletEffectCalculator.targetEffects(100, Direction.TRANSFER, true, transfer = target)
                fail("Expected invalid transfer")
            } catch (_: IllegalArgumentException) { }
        }
        try { WalletEffectCalculator.target(100, Direction.TRANSFER, true); fail("Expected transfer rejection") }
        catch (error: IllegalArgumentException) { assertEquals("TRANSFER_WALLET_REQUIRED", error.message) }
    }
}
