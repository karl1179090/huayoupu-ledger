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
}
