package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class TransferWalletResolverTest {
    private val bank = WalletCandidate("bank", kind = "bank", bankCode = "cmb", cardLast4 = "0007")
    private val wechat = WalletCandidate("wechat", kind = "wechat")
    private val cash = WalletCandidate("cash", kind = "cash")
    private val debit = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007", "wechat")
    private val credit = PaymentMethodEvidence("WECHAT_BALANCE", "CREDIT", platform = "wechat")

    @Test fun explicitTopUpResolvesBothSidesAndWithdrawalReversesRoles() {
        val topup = TransferWalletResolver.resolve(listOf(bank, wechat), debit, credit)
        assertTrue(topup.complete)
        assertEquals("bank", topup.from.accountUid)
        assertEquals("wechat", topup.to.accountUid)
        val withdrawal = TransferWalletResolver.resolve(listOf(bank, wechat), credit.copy(role = "DEBIT"), debit.copy(role = "CREDIT"))
        assertTrue(withdrawal.complete)
        assertEquals("wechat", withdrawal.from.accountUid)
        assertEquals("bank", withdrawal.to.accountUid)
    }

    @Test fun missingOrAmbiguousEndHasNoAppOrDefaultFallback() {
        val bindings = mapOf(PaymentApps.WECHAT to "cash")
        for (evidence in listOf(null, debit.copy(ambiguous = true), debit.copy(medium = "UNKNOWN"))) {
            val result = TransferWalletResolver.resolve(listOf(bank, wechat, cash), evidence, credit, bindings)
            assertFalse(result.complete)
            assertNull(result.from.accountUid)
            assertEquals("TRANSFER_WALLET_REQUIRED", result.from.reviewCode)
        }
    }

    @Test fun bankAndTailAreMatchedTogetherAndTailKeepsLeadingZeros() {
        val wrong = bank.copy(uid = "wrong", cardLast4 = "0008")
        assertEquals("bank", TransferWalletResolver.resolve(listOf(bank, wrong, wechat), debit, credit).from.accountUid)
        assertNull(TransferWalletResolver.resolve(listOf(wrong, wechat), debit, credit).from.accountUid)
        assertNull(TransferWalletResolver.resolve(listOf(bank, bank.copy(uid = "second"), wechat), debit, credit).from.accountUid)
    }

    @Test fun evidenceForTheOppositeRoleCannotSelectAWallet() {
        val result = TransferWalletResolver.resolve(listOf(bank, wechat), debit.copy(role = "CREDIT"), credit.copy(role = "DEBIT"))
        assertNull(result.from.accountUid)
        assertNull(result.to.accountUid)
    }

    @Test fun aPlatformBindingMustPointAtThatPlatformKind() {
        val second = wechat.copy(uid = "wechat2")
        val invalidBinding = TransferWalletResolver.resolve(listOf(bank, wechat, second), debit, credit,
            mapOf(PaymentApps.WECHAT to bank.uid))
        assertNull(invalidBinding.to.accountUid)
        val validBinding = TransferWalletResolver.resolve(listOf(bank, wechat, second), debit, credit,
            mapOf(PaymentApps.WECHAT to second.uid))
        assertEquals(second.uid, validBinding.to.accountUid)
        assertEquals("PAYMENT_METHOD", validBinding.to.selectionSource)
        assertNull(TransferWalletResolver.resolve(listOf(bank, wechat), debit, credit.copy(platform = "alipay")).to.accountUid)
    }

    @Test fun liabilityAccountsAndNewArchivedReferencesAreRejected() {
        val liability = TransferWalletResolver.resolve(listOf(bank.copy(type = "LIABILITY"), wechat),
            manualFromAccountUid = "bank", manualToAccountUid = "wechat")
        assertEquals("TRANSFER_ACCOUNT_UNSUPPORTED", liability.from.reviewCode)
        val archived = TransferWalletResolver.resolve(listOf(bank.copy(archived = true), wechat), debit, credit)
        assertNull(archived.from.accountUid)
        assertFalse(archived.complete)
    }

    @Test fun originalArchivedWalletsRemainUsableForEditingTheirOwnReference() {
        val result = TransferWalletResolver.resolve(listOf(bank.copy(archived = true), wechat), debit, credit,
            originalAccountUids = setOf("bank"))
        assertTrue(result.complete)
        assertEquals("bank", result.from.accountUid)
        val manual = TransferWalletResolver.resolve(listOf(bank.copy(archived = true), wechat),
            manualFromAccountUid = "bank", manualToAccountUid = "wechat", originalAccountUids = setOf("bank"))
        assertTrue(manual.complete)
    }

    @Test fun manualEndsAreLockedSeparatelyAndLaterOcrCannotReplaceEitherOne() {
        val chosen = TransferWalletResolver.resolve(listOf(bank, wechat, cash), manualFromAccountUid = "cash", toEvidence = credit)
        assertTrue(chosen.from.manualLocked)
        assertFalse(chosen.to.manualLocked)
        val supplemented = TransferWalletResolver.resolve(listOf(bank, wechat, cash), debit, credit, existing = chosen)
        assertEquals("cash", supplemented.from.accountUid)
        assertTrue(supplemented.from.manualLocked)
        assertEquals("TRANSFER_PAYMENT_EVIDENCE_CONFLICT", supplemented.from.reviewCode)
        assertFalse(supplemented.complete)
    }

    @Test fun selectedStrongEvidenceDoesNotMoveOnConflictingNewEvidence() {
        val original = TransferWalletResolver.resolve(listOf(bank, wechat), debit, credit)
        val other = bank.copy(uid = "other", cardLast4 = "1234")
        val result = TransferWalletResolver.resolve(listOf(bank, other, wechat), debit.copy(cardLast4 = "1234"), credit, existing = original)
        assertEquals("bank", result.from.accountUid)
        assertEquals("TRANSFER_PAYMENT_EVIDENCE_CONFLICT", result.from.reviewCode)
    }

    @Test fun sameWalletAndInvalidManualSelectionsStayPending() {
        val same = TransferWalletResolver.resolve(listOf(wechat), manualFromAccountUid = "wechat", manualToAccountUid = "wechat")
        assertEquals("TRANSFER_SAME_WALLET", same.reviewCode)
        assertFalse(same.complete)
        val invalid = TransferWalletResolver.resolve(listOf(bank, wechat), debit, credit, manualFromAccountUid = "missing")
        assertNull(invalid.from.accountUid)
        assertTrue(invalid.from.manualLocked)
        assertFalse(invalid.complete)
    }
}
