package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Independent acceptance checks for durable evidence, retry identities and rollback boundaries. */
@RunWith(AndroidJUnit4::class)
class WalletAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stores = mutableListOf<Pair<String, LedgerStore>>()
    private val now = 1_790_000_000_000L
    private fun store(): LedgerStore {
        val name = "wallet-acceptance-${UUID.randomUUID()}.db"
        return LedgerStore(context, name).also { stores.add(name to it) }
    }
    private fun account(store: LedgerStore, amount: Long = 20_000, last4: String? = null) =
        store.saveAccount(LedgerAccount(name = "合成验收钱包", amountCents = amount,
            kind = if (last4 == null) "cash" else "bank", bankCode = if (last4 == null) null else "cmb", cardLast4 = last4))
    private fun bill(evidence: PaymentMethodEvidence? = null) = ParsedBill(10_000, Direction.EXPENSE,
        "合成验收商户", "购物", now, "SYNTHETIC-ACCEPTANCE-PAYMENT", true, "", true, paymentMethod = evidence)
    private fun capture(text: String = "合成付款详情") = Capture(Source.OCR, "com.tencent.mm", text, now)
    private fun ingest(store: LedgerStore, bill: ParsedBill) = (store.ingest(capture(), bill) as IngestResult.Saved).record
    private fun snapshot(store: LedgerStore) = LedgerBackup.decode(LedgerBackup.encode(store, ChannelSettings(context)).byteInputStream())
    private fun expect(code: String, action: () -> Unit) {
        try { action(); fail("expected $code") } catch (error: WalletException) { assertEquals(code, error.code) }
    }
    private fun assertEvidenceConsistent(record: LedgerRecord, expected: PaymentMethodEvidence) {
        assertEquals(expected, record.bill.paymentMethod)
        assertEquals(WalletResolver.fingerprint(expected), record.wallet!!.evidenceFingerprint)
        assertEquals(expected.medium, JSONObject(record.wallet.paymentEvidence!!).getString("medium"))
        assertEquals(expected.cardLast4, JSONObject(record.wallet.paymentEvidence).optString("cardLast4").takeUnless { it == "null" || it.isEmpty() })
    }
    @After fun cleanup() { stores.forEach { (name, store) -> store.close(); context.deleteDatabase(name) } }

    @Test fun unknownSupplementKeepsStrongEvidenceAndItsFingerprintRestorable() {
        val store = store(); val card = account(store, last4 = "0007")
        val evidence = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007", rawLabel = "付款方式", rawValue = "招商银行（0007）")
        val original = ingest(store, bill(evidence))
        val before = store.walletMovements(card.uid)
        store.ingest(capture("仅补充商户备注"), bill(PaymentMethodEvidence("UNKNOWN", "DEBIT", rawValue = "余额")))
        val current = store.get(original.id)!!
        assertEquals(card.uid, current.wallet!!.accountUid); assertEquals(10_000L, store.getAccount(card.id)!!.amountCents)
        assertEvidenceConsistent(current, evidence); assertEquals(before, store.walletMovements(card.uid))
        val target = store(); LedgerBackup.restore(target, snapshot(store))
        assertEvidenceConsistent(target.all().single(), evidence)
        assertEquals(10_000L, target.getAccountByUid(card.uid)!!.amountCents)
    }

    @Test fun manualLockCanStoreNewEvidenceWithoutMovingWalletOrBreakingFingerprint() {
        val store = store(); val chosen = account(store); val card = account(store, last4 = "1234")
        store.setDefaultWallet(chosen.uid)
        val original = ingest(store, bill())
        val locked = store.saveEdited(original, chosen.uid, true)
        val evidence = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "1234", rawValue = "招行尾号1234")
        store.ingest(capture(), bill(evidence))
        val current = store.get(locked.id)!!
        assertTrue(current.wallet!!.manualLocked); assertEquals("MANUAL", current.wallet.selectionSource)
        assertEquals(chosen.uid, current.wallet.accountUid); assertEvidenceConsistent(current, evidence)
        store.ingest(capture("不充分付款方式"), bill(PaymentMethodEvidence("UNKNOWN", "DEBIT")))
        assertEvidenceConsistent(store.get(locked.id)!!, evidence)
        val target = store(); LedgerBackup.restore(target, snapshot(store))
        assertEvidenceConsistent(target.all().single(), evidence)
        assertEquals(10_000L, target.getAccountByUid(chosen.uid)!!.amountCents)
        assertEquals(20_000L, target.getAccountByUid(card.uid)!!.amountCents)
    }

    @Test fun conflictingStrongCardRetainsOriginalEvidenceAndRequiresReviewWithoutTransfer() {
        val store = store(); val first = account(store, last4 = "0007"); val second = account(store, last4 = "1234")
        val evidence = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007")
        val original = ingest(store, bill(evidence))
        store.ingest(capture(), bill(evidence.copy(cardLast4 = "1234")))
        val current = store.get(original.id)!!
        assertEquals("PAYMENT_EVIDENCE_CONFLICT", current.wallet!!.reviewCode)
        assertEquals(first.uid, current.wallet.accountUid); assertEvidenceConsistent(current, evidence)
        assertEquals(10_000L, store.getAccount(first.id)!!.amountCents); assertEquals(20_000L, store.getAccount(second.id)!!.amountCents)
        LedgerBackup.restore(store(), snapshot(store))
    }

    @Test fun saveTokenRetriesPrecedeRevisionChecksAndNewTokenMakesAnotherRealPayment() {
        val store = store(); val wallet = account(store, 50_000); store.setDefaultWallet(wallet.uid)
        fun manual() = LedgerRecord(capture = Capture(Source.MANUAL, "manual", "", now),
            bill = bill().copy(transactionId = null), state = RecordState.POSTED)
        val draft = manual()
        val first = store.saveEdited(draft, submissionToken = "synthetic-save-1")
        assertEquals(first, store.saveEdited(draft, submissionToken = "synthetic-save-1"))
        val changed = store.saveEdited(first.copy(note = "已保存的合成修改"), submissionToken = "synthetic-edit-1")
        assertEquals(changed, store.saveEdited(first.copy(note = "响应丢失后的重试"), submissionToken = "synthetic-edit-1"))
        expect("STALE_RECORD_REVISION") { store.saveEdited(first.copy(note = "陈旧新提交"), submissionToken = "synthetic-edit-2") }
        store.saveEdited(manual(), submissionToken = "synthetic-save-2")
        assertEquals(2, store.all().size); assertEquals(30_000L, store.getAccount(wallet.id)!!.amountCents)
        assertEquals(2, store.walletMovements(wallet.uid).count { it.recordUid != null })
    }

    @Test fun editAndTwoWalletTransferOverflowLeaveEveryFinancialRowUnchanged() {
        val store = store(); val source = account(store); val target = account(store, -Money.MAX_CENTS + 500)
        store.setDefaultWallet(source.uid)
        val original = ingest(store, bill().copy(amountCents = 1000))
        val before = store.walletSnapshot(); val beforeAccounts = store.accounts()
        expect("BALANCE_LIMIT") { store.saveEdited(original, target.uid, true, submissionToken = "synthetic-transfer") }
        assertEquals(original, store.get(original.id)); assertEquals(before, store.walletSnapshot()); assertEquals(beforeAccounts, store.accounts())
        val nearLimit = store.saveAccount(store.getAccount(source.id)!!.copy(amountCents = -Money.MAX_CENTS + 500), calibrateBalance = true)
        val beforeEdit = store.walletSnapshot()
        expect("BALANCE_LIMIT") { store.saveEdited(original.copy(bill = original.bill.copy(amountCents = 2000))) }
        assertEquals(original, store.get(original.id)); assertEquals(beforeEdit, store.walletSnapshot())
        assertEquals(nearLimit, store.getAccount(source.id))
    }

    @Test fun singleRefundIdAndOlderCumulativeEvidenceNeverReturnMoneyTwice() {
        val store = store(); val wallet = account(store); store.setDefaultWallet(wallet.uid)
        val original = ingest(store, bill())
        val single = bill().copy(amountCents = 7000, refund = RefundInfo("wechat", 10_000, 3000, "single", "SYNTHETIC-REFUND-0001"))
        store.ingest(capture(), single); store.ingest(capture("相同退款重试"), single)
        assertEquals(13_000L, store.getAccount(wallet.id)!!.amountCents)
        assertEquals(7000L, store.get(original.id)!!.bill.amountCents)
        val cumulativeStore = store(); val cumulativeWallet = account(cumulativeStore); cumulativeStore.setDefaultWallet(cumulativeWallet.uid)
        val cumulativeOriginal = ingest(cumulativeStore, bill())
        fun cumulative(refunded: Long) = bill().copy(amountCents = 10_000 - refunded, refund = RefundInfo("wechat", 10_000, refunded))
        cumulativeStore.ingest(capture(), cumulative(4000)); cumulativeStore.ingest(capture(), cumulative(2000))
        assertEquals(14_000L, cumulativeStore.getAccount(cumulativeWallet.id)!!.amountCents)
        assertEquals(6000L, cumulativeStore.get(cumulativeOriginal.id)!!.bill.amountCents)
    }

    @Test fun oldBackupRefundEditAndDeletionKeepLegacyEligibilityAndBalance() {
        for (version in 1..3) {
            val target = store(); val oldWallet = LedgerAccount(name = "合成旧余额", amountCents = 20_000, kind = "wechat")
            target.saveAccount(oldWallet)
            val before = target.walletSnapshot().movements
            val original = LedgerRecord(capture = capture(), bill = bill(), state = RecordState.POSTED)
            val json = JSONObject(LedgerBackup.encode(listOf(original), ChannelSettings(context), listOf(oldWallet))).put("version", version)
            json.remove("wallets")
            if (version == 1) { json.remove("accounts"); json.remove("customCategories") }
            LedgerBackup.restore(target, LedgerBackup.decode(json.toString().byteInputStream()))
            val restored = target.all().single()
            target.ingest(capture(), bill().copy(amountCents = 7000, refund = RefundInfo("wechat", 10_000, 3000)))
            val refunded = target.get(restored.id)!!
            assertEquals(7000L, refunded.bill.amountCents); assertEquals(WalletEligibility.LEGACY_EXCLUDED, refunded.wallet!!.eligibility)
            target.saveEdited(refunded.copy(note = "合成历史修订")); target.softDelete(restored.id, now)
            target.undoDelete(restored.id, now + 1); target.delete(restored.id)
            assertEquals(20_000L, target.getAccountByUid(oldWallet.uid)!!.amountCents)
            assertEquals(before, target.walletSnapshot().movements)
        }
    }

    @Test fun failedRestoreReleasesImportedMediaEvenWhenUnwindingIncomeWouldOverflow() {
        val source = store(); val wallet = account(source, 0); source.setDefaultWallet(wallet.uid)
        ingest(source, bill().copy(amountCents = 1, direction = Direction.INCOME))
        source.saveAccount(source.getAccount(wallet.id)!!.copy(amountCents = -Money.MAX_CENTS), calibrateBalance = true)
        val backup = snapshot(source); val target = store()
        val screenshots = ScreenshotStore.get(context)
        val beforeOwners = screenshots.availableOwners()
        val failure = IllegalStateException("synthetic media failure at calibrated balance limit")
        val result = runCatching {
            LedgerBackup.restore(target, backup) { restored ->
                target.attachScreenshot(restored.id, UUID.randomUUID().toString().toByteArray())
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(target.all().isEmpty()); assertTrue(target.accounts().isEmpty())
        assertTrue(target.walletSnapshot().movements.isEmpty()); assertNull(target.walletSettings().defaultAccountUid)
        assertEquals(beforeOwners, screenshots.availableOwners())
    }

    @Test fun fullyRefundedFallbackCanUpgradeToCardAndRoundTripItsZeroAppliedAssociation() {
        val source = store()
        val app = source.saveAccount(LedgerAccount(name = "合成微信零钱", amountCents = 20_000, kind = "wechat"))
        val card = account(source, last4 = "0007")
        val original = ingest(source, bill())
        source.ingest(capture(), bill().copy(amountCents = 0, refund = RefundInfo("wechat", 10_000, 10_000)))
        val beforeMovements = source.walletSnapshot().movements
        val evidence = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007")
        source.ingest(capture("全退后的付款卡详情"), bill(evidence))
        val updated = source.get(original.id)!!
        assertEquals(0L, updated.bill.amountCents); assertEquals(0L, updated.wallet!!.appliedCents)
        assertEquals(card.uid, updated.wallet.accountUid); assertEquals(card.uid, updated.wallet.appliedAccountUid)
        assertEquals(beforeMovements, source.walletSnapshot().movements)
        assertEquals(20_000L, source.getAccount(app.id)!!.amountCents); assertEquals(20_000L, source.getAccount(card.id)!!.amountCents)
        val target = store(); LedgerBackup.restore(target, snapshot(source))
        val restored = target.all().single()
        assertEquals(updated.wallet.copy(paymentEvidence = restored.wallet!!.paymentEvidence), restored.wallet)
        assertEvidenceConsistent(restored, evidence)
        target.delete(target.all().single().id)
        assertEquals(20_000L, target.getAccountByUid(card.uid)!!.amountCents)
    }

    @Test fun calibrationHistoryAllowsUnlinkedTypeChangesWhileBillHistoryLocksTheType() {
        val store = store(); val unlinked = account(store, 10_000)
        val liability = store.saveAccount(unlinked.copy(type = AccountType.LIABILITY))
        assertEquals(AccountType.LIABILITY, liability.type); assertEquals(10_000L, liability.amountCents)
        val asset = store.saveAccount(liability.copy(type = AccountType.ASSET))
        assertEquals(10_000L, asset.amountCents)
        store.setDefaultWallet(asset.uid)
        val original = ingest(store, bill().copy(amountCents = 1000))
        expect("ACCOUNT_IN_USE") { store.saveAccount(store.getAccount(asset.id)!!.copy(type = AccountType.LIABILITY)) }
        store.delete(original.id)
        expect("ACCOUNT_IN_USE") { store.saveAccount(store.getAccount(asset.id)!!.copy(type = AccountType.LIABILITY)) }
        assertEquals(AccountType.ASSET, store.getAccount(asset.id)!!.type)
    }

    @Test fun negativeSnapshotWithPartialRefundRestoresAppliedNetAndLaterDeleteExactly() {
        val source = store(); val wallet = account(source, 1000); source.setDefaultWallet(wallet.uid)
        val original = ingest(source, bill())
        source.ingest(capture(), bill().copy(amountCents = 7000, refund = RefundInfo("wechat", 10_000, 3000)))
        val target = store(); LedgerBackup.restore(target, snapshot(source))
        val restored = target.all().single()
        assertEquals(original.uid, restored.uid); assertEquals(7000L, restored.bill.amountCents)
        assertEquals(3000L, restored.bill.refund!!.refundedCents); assertEquals(-7000L, restored.wallet!!.appliedCents)
        assertEquals(-6000L, target.getAccountByUid(wallet.uid)!!.amountCents)
        target.delete(restored.id); assertEquals(1000L, target.getAccountByUid(wallet.uid)!!.amountCents)
    }

    @Test fun automaticShakeImageAndManualOriginsShareSignedExpenseAndIncomeAccounting() {
        for ((source, origin) in listOf(Source.OCR to "payment_auto:com.tencent.mm", Source.OCR to "shake:com.tencent.mm",
            Source.IMAGE to "合成截图导入", Source.MANUAL to "manual")) {
            val store = store(); val wallet = account(store, 1000); store.setDefaultWallet(wallet.uid)
            val capture = Capture(source, origin, "合成入口验收", now)
            fun save(bill: ParsedBill) = if (source == Source.MANUAL)
                store.saveEdited(LedgerRecord(capture = capture, bill = bill, state = RecordState.POSTED))
                else (store.ingest(capture, bill) as IngestResult.Saved).record
            val expense = save(bill().copy(amountCents = 3000))
            assertEquals(RecordState.POSTED, expense.state); assertEquals(-2000L, store.getAccount(wallet.id)!!.amountCents)
            val income = save(bill().copy(amountCents = 500, direction = Direction.INCOME, transactionId = "SYNTHETIC-INCOME-ENTRY"))
            assertEquals(RecordState.POSTED, income.state); assertEquals(500L, income.wallet!!.appliedCents)
            assertEquals(-1500L, store.getAccount(wallet.id)!!.amountCents)
        }
    }

    @Test fun concurrentTransactionsMetadataAndRevisionCheckedCalibrationsDoNotLoseUpdates() {
        val store = store(); val original = account(store, 10_000); store.setDefaultWallet(original.uid)
        val start = CountDownLatch(1); val accepted = AtomicInteger(); val rejected = AtomicInteger()
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = mutableListOf<java.util.concurrent.Future<*>>()
            for (index in 1..20) {
                futures += executor.submit { start.await(); ingest(store, bill().copy(amountCents = 100, transactionId = "SYNTHETIC-MIXED-$index")) }
                if (index % 2 == 0) futures += executor.submit {
                    start.await(); store.saveAccount(original.copy(name = "合成并发改名$index"))
                }
                if (index % 4 == 0) futures += executor.submit {
                    start.await()
                    val current = store.getAccount(original.id)!!
                    try {
                        store.saveAccount(current.copy(amountCents = current.amountCents + 7), calibrateBalance = true,
                            expectedRevision = current.revision, submissionToken = "synthetic-mixed-calibration-$index")
                        accepted.incrementAndGet()
                    } catch (error: WalletException) {
                        assertEquals("STALE_ACCOUNT_REVISION", error.code); rejected.incrementAndGet()
                    }
                }
            }
            start.countDown(); futures.forEach { it.get() }
        } finally { executor.shutdownNow() }
        assertEquals(5, accepted.get() + rejected.get()); assertEquals(20, store.all().size)
        val balance = store.getAccount(original.id)!!.amountCents
        assertEquals(8000L + accepted.get() * 7L, balance)
        assertEquals(balance, store.walletMovements(original.uid, limit = 100).sumOf { it.deltaCents })
        assertEquals(20, store.walletMovements(original.uid, limit = 100).count { it.kind == "POST" })
    }

    @Test fun csvExistingIdentityNeverPostsChangedAmountDirectionOrRefundNetAsAnotherTransaction() {
        val store = store(); val wallet = account(store, 10_000); store.setDefaultWallet(wallet.uid)
        val original = LedgerRecord(capture = capture("合成CSV原始付款"), bill = bill().copy(amountCents = 1000), state = RecordState.POSTED)
        fun document(vararg records: LedgerRecord) = LedgerCsvImport.Document("合成CSV身份", records.toList(), emptyList(), records.size)
        assertEquals(1, LedgerCsvImport.apply(store, document(original), linkWalletBalances = true).posted)
        val before = store.walletSnapshot().movements
        val variants = listOf(original.copy(uid = UUID.randomUUID().toString(), bill = original.bill.copy(amountCents = 2000)),
            original.copy(uid = UUID.randomUUID().toString(), bill = original.bill.copy(direction = Direction.INCOME)),
            original.copy(uid = UUID.randomUUID().toString(), bill = original.bill.copy(amountCents = 400, refund = RefundInfo("wechat", 1000, 600))))
        for (variant in variants) {
            assertEquals(0, LedgerCsvImport.preview(store, document(variant), linkWalletBalances = true).ready)
            assertEquals(0, LedgerCsvImport.apply(store, document(variant), linkWalletBalances = true).imported)
        }
        assertEquals(9000L, store.getAccount(wallet.id)!!.amountCents); assertEquals(before, store.walletSnapshot().movements)
        store.ingest(original.capture, original.bill.copy(amountCents = 400, refund = RefundInfo("wechat", 1000, 600)))
        assertEquals(9600L, store.getAccount(wallet.id)!!.amountCents)
        assertEquals(0, LedgerCsvImport.apply(store, document(original.copy(uid = UUID.randomUUID().toString())), linkWalletBalances = true).imported)
        assertEquals(400L, store.all().single().bill.amountCents)
        val realNew = (1..2).map { index -> original.copy(uid = UUID.randomUUID().toString(),
            bill = original.bill.copy(transactionId = "SYNTHETIC-CSV-NEW-$index")) }
        assertEquals(2, LedgerCsvImport.apply(store, document(*realNew.toTypedArray()), linkWalletBalances = true).posted)
        assertEquals(3, store.all().size); assertEquals(7600L, store.getAccount(wallet.id)!!.amountCents)
    }

    @Test fun csvCannotBypassUndoHiddenRecordIdentityOrUidToChargeItAgain() {
        val store = store(); val wallet = account(store, 10_000); store.setDefaultWallet(wallet.uid)
        val original = LedgerRecord(capture = capture(), bill = bill().copy(amountCents = 1000), state = RecordState.POSTED)
        fun document(records: List<LedgerRecord>) = LedgerCsvImport.Document("合成CSV删除窗口", records, emptyList(), records.size)
        LedgerCsvImport.apply(store, document(listOf(original)), linkWalletBalances = true)
        val saved = store.all().single(); store.softDelete(saved.id, now)
        assertTrue(store.all().isEmpty()); assertEquals(10_000L, store.getAccount(wallet.id)!!.amountCents)
        val replay = listOf(original.copy(uid = UUID.randomUUID().toString(), bill = original.bill.copy(amountCents = 2000, direction = Direction.INCOME)),
            original.copy(bill = original.bill.copy(transactionId = "SYNTHETIC-CSV-DIFFERENT-ORDER")))
        val preview = LedgerCsvImport.preview(store, document(replay), linkWalletBalances = true)
        assertEquals(0, preview.ready); assertEquals(2, preview.duplicates)
        assertEquals(0, LedgerCsvImport.apply(store, document(replay), linkWalletBalances = true).imported)
        assertEquals(10_000L, store.getAccount(wallet.id)!!.amountCents); assertNull(store.get(saved.id))
        assertTrue(store.undoDelete(saved.id, now + 1)); assertEquals(9000L, store.getAccount(wallet.id)!!.amountCents)
        assertEquals(listOf(original.uid), store.all().map { it.uid })
    }

    @Test fun negativeUnlinkedAssetCanBecomePositiveLiabilityAndPreserveSignedAuditInV4() {
        val source = store(); val asset = account(source, -1000)
        val originalAudit = source.walletSnapshot().movements
        val liability = source.saveAccount(asset.copy(type = AccountType.LIABILITY, amountCents = 500),
            calibrateBalance = true, expectedRevision = asset.revision, submissionToken = "synthetic-asset-to-liability")
        assertEquals(AccountType.LIABILITY, liability.type); assertEquals(500L, liability.amountCents)
        assertEquals(liability, source.saveAccount(asset.copy(type = AccountType.LIABILITY, amountCents = 500),
            calibrateBalance = true, expectedRevision = asset.revision, submissionToken = "synthetic-asset-to-liability"))
        assertEquals(2, source.walletSnapshot().movements.size)
        assertEquals(originalAudit, source.walletSnapshot().movements.take(originalAudit.size))
        val adjustment = source.walletSnapshot().movements.last()
        assertEquals(-1000L, adjustment.balanceBefore); assertEquals(1500L, adjustment.deltaCents); assertEquals(500L, adjustment.balanceAfter)
        val target = store(); LedgerBackup.restore(target, snapshot(source))
        val restored = target.getAccountByUid(asset.uid)!!
        assertEquals(liability.copy(id = 0), restored.copy(id = 0))
        assertEquals(source.walletSnapshot().movements, target.walletSnapshot().movements.filter { it.kind != "RESTORE_SNAPSHOT" })
        assertNotNull(runCatching { target.saveAccount(restored.copy(amountCents = -1), calibrateBalance = true) }.exceptionOrNull())
        val assetAgain = target.saveAccount(restored.copy(type = AccountType.ASSET, amountCents = -200), calibrateBalance = true)
        assertEquals(AccountType.ASSET, assetAgain.type); assertEquals(-200L, assetAgain.amountCents)
    }

    @Test fun configuredUnlinkedAssetMustReleaseDefaultAndAppBindingsBeforeLiabilityConversion() {
        val source = store(); val asset = account(source, 1000)
        source.setDefaultWallet(asset.uid); source.setAppWallet("com.example.synthetic", asset.uid)
        val before = source.walletSnapshot()
        expect("WALLET_CONFIGURATION_IN_USE") { source.saveAccount(asset.copy(type = AccountType.LIABILITY)) }
        assertEquals(asset, source.getAccount(asset.id)); assertEquals(before, source.walletSnapshot())
        source.setDefaultWallet(null)
        expect("WALLET_CONFIGURATION_IN_USE") { source.saveAccount(asset.copy(type = AccountType.LIABILITY)) }
        assertEquals(asset, source.getAccount(asset.id))
        source.setAppWallet("com.example.synthetic", null)
        val liability = source.saveAccount(asset.copy(type = AccountType.LIABILITY))
        assertEquals(AccountType.LIABILITY, liability.type); assertEquals(1000L, liability.amountCents)
        val target = store(); LedgerBackup.restore(target, snapshot(source))
        assertEquals(AccountType.LIABILITY, target.getAccountByUid(asset.uid)!!.type)
        assertNull(target.walletSettings().defaultAccountUid); assertTrue(target.walletSettings().appBindings.isEmpty())
    }
}
