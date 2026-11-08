package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class WalletResolverTest {
    private val wechat = WalletCandidate("wechat", kind = "wechat")
    private val bank = WalletCandidate("card", kind = "bank", bankCode = "cmb", cardLast4 = "0007")
    private val cash = WalletCandidate("cash", kind = "cash")
    private val evidence = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007", "wechat")
    private fun resolve(accounts: List<WalletCandidate>, e: PaymentMethodEvidence? = evidence,
                        existing: WalletResolution? = null, defaults: String? = "cash") =
        WalletResolver.resolve(accounts, "payment_auto:${PaymentApps.WECHAT}", e, defaults, existing = existing)

    @Test fun matchingCardPrecedesFallbackAndUnmatchedBankEvidenceStaysUnassigned() {
        assertEquals("card", resolve(listOf(wechat, bank, cash)).accountUid)
        assertEquals("PAYMENT_METHOD", resolve(listOf(wechat, bank, cash)).selectionSource)
        assertNull(resolve(listOf(wechat, cash)).accountUid)
        assertNull(resolve(listOf(cash)).accountUid)
        assertEquals("PAYMENT_METHOD_UNMATCHED", resolve(emptyList()).reviewCode)
    }

    @Test fun bankAndTailMustBothMatchAndAmbiguityNeverPicksAnArbitraryCard() {
        val wrong = bank.copy(cardLast4 = "1234")
        assertNull(resolve(listOf(wechat, wrong)).accountUid)
        assertEquals("card", resolve(listOf(bank), evidence.copy(cardLast4 = null)).accountUid)
        assertNull(resolve(listOf(bank, bank.copy(uid = "second")), evidence.copy(cardLast4 = null)).accountUid)
        assertEquals("card", resolve(listOf(bank), evidence.copy(bankCode = null)).accountUid)
        assertNull(resolve(listOf(wechat, bank), evidence.copy(ambiguous = true)).accountUid)
        assertNull(resolve(listOf(wechat, bank.copy(type = "LIABILITY"))).accountUid)
        assertNull(resolve(listOf(wechat, bank.copy(archived = true))).accountUid)
    }

    @Test fun incomingUnmatchedBankEvidenceCannotPreserveAnUnconfirmedAppFallback() {
        val fallback = resolve(listOf(wechat, cash), e = null)
        val unresolved = WalletResolver.resolve(listOf(wechat, cash, bank.copy(cardLast4="1234")), PaymentApps.WECHAT, evidence, "cash",
            existing=fallback, requireActualBank=true)
        assertNull(unresolved.accountUid)
        assertEquals("PAYMENT_METHOD_UNMATCHED", unresolved.reviewCode)
    }

    @Test fun ordinaryPaymentSupplementPreservesAnEstablishedWalletWhenNewCardCannotBeVerified() {
        val fallback = resolve(listOf(wechat, cash), e = null)
        val preserved = resolve(listOf(wechat, cash, bank.copy(cardLast4="1234")), existing=fallback)
        assertEquals(fallback, preserved)
    }

    @Test fun bindingsAreAppFallbacksUnlessTheyHaveTheRequestedPlatformKind() {
        val balance = PaymentMethodEvidence("WECHAT_BALANCE", "DEBIT", platform = "wechat")
        val explicit = WalletResolver.resolve(listOf(bank, wechat), PaymentApps.WECHAT, balance,
            appBindings = mapOf(PaymentApps.WECHAT to bank.uid))
        assertEquals("wechat", explicit.accountUid); assertEquals("PAYMENT_METHOD", explicit.selectionSource)
        val fallback = WalletResolver.resolve(listOf(bank), PaymentApps.WECHAT, balance,
            appBindings = mapOf(PaymentApps.WECHAT to bank.uid))
        assertEquals("card", fallback.accountUid); assertEquals("APP", fallback.selectionSource)
        assertEquals("cash", resolve(listOf(wechat, wechat.copy(uid = "wx2"), cash), balance).accountUid)
    }

    @Test fun manualChoiceLocksAndInvalidExplicitChoiceCannotFallBack() {
        val manual = WalletResolver.resolve(listOf(bank, wechat), manualAccountUid = "wechat")
        assertTrue(manual.manualLocked)
        val supplemented = resolve(listOf(bank, wechat), existing = manual)
        assertEquals(manual.accountUid, supplemented.accountUid); assertTrue(supplemented.manualLocked)
        assertEquals(WalletResolver.fingerprint(evidence), supplemented.evidenceFingerprint)
        val invalid = WalletResolver.resolve(listOf(bank), defaultAccountUid = "card", manualAccountUid = "gone")
        assertNull(invalid.accountUid); assertEquals("INVALID_WALLET", invalid.reviewCode)
    }

    @Test fun fallbackCanUpgradeOnceButSettingsAndConflictingNewCardsDoNotMoveItAgain() {
        val fallback = resolve(listOf(wechat, cash), e = null)
        assertEquals(fallback, resolve(listOf(wechat, bank, cash), null, fallback, "card"))
        val upgraded = resolve(listOf(wechat, bank, cash), existing = fallback)
        assertEquals("card", upgraded.accountUid)
        val conflict = resolve(listOf(bank, bank.copy(uid = "second", cardLast4 = "1234")),
            evidence.copy(cardLast4 = "1234"), upgraded)
        assertEquals("card", conflict.accountUid); assertEquals("PAYMENT_EVIDENCE_CONFLICT", conflict.reviewCode)
        assertEquals(upgraded, resolve(listOf(bank.copy(archived = true)), null, upgraded))
    }

    @Test fun sourceIdentityCannotBeReplacedByFullTextPlatformMentions() {
        assertNull(WalletResolver.sourcePackage("微信支付 招商银行"))
        val e = PaymentMethodEvidence("UNKNOWN", "DEBIT", platform = "alipay")
        val alipay = WalletCandidate("alipay", kind = "alipay")
        assertEquals("wechat", WalletResolver.resolve(listOf(wechat, alipay), PaymentApps.WECHAT, e).accountUid)
        assertNull(WalletResolver.resolve(listOf(wechat, alipay), "com.example.shop", e).accountUid)
    }

    @Test fun explicitPlatformBalancePrecedesGenericSourceBindingButUnknownPlatformDoesNot() {
        val alipay = WalletCandidate("alipay", kind = "alipay")
        for (source in listOf("com.example.shop", "com.taobao.taobao")) {
            val evidence = PaymentMethodParser.parse(listOf(LocalOcrLine("付款方式：支付宝余额")), Direction.EXPENSE, source)!!
            assertEquals("ALIPAY_BALANCE", evidence.medium)
            val bindings = mapOf(source to bank.uid, PaymentApps.ALIPAY to alipay.uid)
            val explicit = WalletResolver.resolve(listOf(alipay, bank, cash), source, evidence, cash.uid, bindings)
            assertEquals(alipay.uid, explicit.accountUid); assertEquals("PAYMENT_METHOD", explicit.selectionSource)
            val unknown = WalletResolver.resolve(listOf(alipay, bank, cash), source, evidence.copy(medium = "UNKNOWN"), cash.uid, bindings)
            assertEquals(bank.uid, unknown.accountUid); assertEquals("APP", unknown.selectionSource)
            val noSourceBinding = WalletResolver.resolve(listOf(alipay, bank, cash), source, evidence.copy(medium = "UNKNOWN"),
                cash.uid, mapOf(PaymentApps.ALIPAY to alipay.uid))
            assertEquals(cash.uid, noSourceBinding.accountUid); assertEquals("DEFAULT", noSourceBinding.selectionSource)
        }
        val second = alipay.copy(uid = "alipay2")
        val bound = WalletResolver.resolve(listOf(alipay, second, bank), "com.example.shop",
            PaymentMethodEvidence("ALIPAY_BALANCE", "DEBIT", platform = "alipay"),
            appBindings = mapOf("com.example.shop" to bank.uid, PaymentApps.ALIPAY to second.uid))
        assertEquals(second.uid, bound.accountUid); assertEquals("PAYMENT_METHOD", bound.selectionSource)
    }
}
