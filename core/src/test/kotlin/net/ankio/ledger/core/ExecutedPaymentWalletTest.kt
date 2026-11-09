package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class ExecutedPaymentWalletTest {
    @Test fun executedBalanceDebitWithoutItsWalletCannotSelectDefaultCashOrAnAppBinding() {
        val evidence=PaymentMethodParser.parse(listOf(LocalOcrLine("通过零钱扣款")),Direction.EXPENSE,PaymentApps.WECHAT)!!
        val result=WalletResolver.resolve(listOf(WalletCandidate("cash",kind="cash")),sourcePackage=PaymentApps.WECHAT,
            evidence=evidence,defaultAccountUid="cash",appBindings=mapOf(PaymentApps.WECHAT to "cash"))
        assertNull(result.accountUid)
        assertEquals("ACTUAL_ACCOUNT_REQUIRED",result.reviewCode)
    }

    @Test fun ambiguousAndArchivedBalanceWalletsStayUnresolvedButManualSelectionWins() {
        val evidence=PaymentMethodParser.parse(listOf(LocalOcrLine("通过零钱扣款")),Direction.EXPENSE,"image")!!
        assertTrue(evidence.executed);assertEquals("WECHAT_BALANCE",evidence.medium)
        val cash=WalletCandidate("cash",kind="cash")
        for(candidates in listOf(listOf(cash,WalletCandidate("a",kind="wechat"),WalletCandidate("b",kind="wechat")),
            listOf(cash,WalletCandidate("archived",kind="wechat",archived=true)))) {
            val result=WalletResolver.resolve(candidates,evidence=evidence,defaultAccountUid="cash")
            assertNull(result.accountUid);assertEquals("ACTUAL_ACCOUNT_REQUIRED",result.reviewCode)
            val manual=WalletResolver.resolve(candidates,evidence=evidence,defaultAccountUid="cash",manualAccountUid="cash")
            assertEquals("cash",manual.accountUid);assertTrue(manual.manualLocked)
            val existing=WalletResolver.resolve(candidates,evidence=evidence,defaultAccountUid="cash",
                existing=WalletResolution("cash","MANUAL",manualLocked=true))
            assertEquals("cash",existing.accountUid);assertTrue(existing.manualLocked)
        }
    }

    @Test fun anAvailableBalanceWalletMatchesWhilePreferencesKeepTheExistingWeakFallback() {
        val wallets=listOf(WalletCandidate("cash",kind="cash"),WalletCandidate("wechat",kind="wechat"))
        val actual=PaymentMethodParser.parse(listOf(LocalOcrLine("通过零钱扣款")),Direction.EXPENSE,PaymentApps.WECHAT)!!
        assertEquals("wechat",WalletResolver.resolve(wallets,evidence=actual,defaultAccountUid="cash").accountUid)
        val preference=PaymentMethodParser.parse(listOf(LocalOcrLine("支付方式：优先从零钱支付")),Direction.EXPENSE,PaymentApps.WECHAT)!!
        assertFalse(preference.executed)
        assertEquals("cash",WalletResolver.resolve(wallets,sourcePackage=PaymentApps.WECHAT,evidence=preference,
            defaultAccountUid="cash",appBindings=mapOf(PaymentApps.WECHAT to "cash")).accountUid)
        assertFalse(PaymentMethodParser.parse(listOf(LocalOcrLine("通过优先零钱扣款")),Direction.EXPENSE,PaymentApps.WECHAT)!!.executed)
        assertNotEquals(WalletResolver.fingerprint(actual.copy(executed=false)),WalletResolver.fingerprint(actual))
    }

    @Test fun anExplicitImportedWalletKeepsItsExistingAccountingAttribution() {
        val evidence=PaymentMethodParser.parse(listOf(LocalOcrLine("通过零钱扣款")),Direction.EXPENSE,"image")!!
        val previous=WalletResolution("cash","IMPORT")
        assertEquals(previous,WalletResolver.resolve(listOf(WalletCandidate("cash",kind="cash")),evidence=evidence,existing=previous))
        assertEquals(previous,WalletResolver.resolve(listOf(WalletCandidate("cash",kind="cash")),
            evidence=PaymentMethodEvidence("UNKNOWN","DEBIT",ambiguous=true,reason="ACTUAL_ACCOUNT_REQUIRED"),existing=previous))
    }
}
