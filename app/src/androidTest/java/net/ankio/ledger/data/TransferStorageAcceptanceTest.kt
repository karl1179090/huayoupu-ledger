package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent synthetic acceptance of persistence facts, rather than UI or OCR templates. */
@RunWith(AndroidJUnit4::class)
class TransferStorageAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "transfer-independent-${UUID.randomUUID()}.db"
    private var store = LedgerStore(context, name)
    private val now = 1_790_000_000_000L

    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    private fun wallet(balance: Long = 0) = store.saveAccount(LedgerAccount(name = "合成独立钱包", amountCents = balance))
    private fun balance(account: LedgerAccount) = requireNotNull(store.getAccountByUid(account.uid)).amountCents
    private fun draft(from: LedgerAccount?, to: LedgerAccount?, amount: Long = 10_000, fee: Long = 100): LedgerRecord {
        val uid = UUID.randomUUID().toString()
        return LedgerRecord(uid = uid, capture = Capture(Source.MANUAL, "manual", "合成独立互转", now),
            bill = ParsedBill(amount, Direction.TRANSFER, "合成互转", "转账", now, null, true, ""),
            state = RecordState.POSTED, transfer = RecordTransferDetails(uid, from?.uid, to?.uid, fee, FeeMode.EXTRA))
    }
    private fun ordinary(account: LedgerAccount, direction: Direction, amount: Long = 100): LedgerRecord {
        val uid = UUID.randomUUID().toString()
        return LedgerRecord(uid = uid, capture = Capture(Source.MANUAL, "csv", "合成独立批次", now),
            bill = ParsedBill(amount, direction, "合成收支", "其他", now, null, true, ""), state = RecordState.POSTED,
            wallet = RecordWalletLink(uid, WalletEligibility.ACTIVE, account.uid, "IMPORT"))
    }
    private fun scalar(sql: String, vararg args: String): Long = store.readableDatabase.rawQuery(sql, args).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun expect(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: WalletException) { assertEquals(code, error.code) }
    }

    @Test fun excludedArchivedTransferRemainsPostedAndNeverChangesEitherBalance() {
        val a = wallet(100_000); val b = wallet(20_000)
        store.archiveAccount(a.id); store.archiveAccount(b.id)
        val saved = store.importTransactions(listOf(draft(a, b))).single()
        assertEquals(RecordState.POSTED, saved.state)
        assertEquals(WalletEligibility.IMPORT_EXCLUDED, saved.wallet!!.eligibility)
        assertTrue(saved.walletEffects.isEmpty())
        val edited = store.saveEdited(saved.copy(bill = saved.bill.copy(amountCents = 12_000)))
        assertEquals(RecordState.POSTED, edited.state)
        assertTrue(store.softDelete(saved.id, now)); assertTrue(store.undoDelete(saved.id, now + 1))
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }

    @Test fun activeImportWithOneMissingTransferLegIsReviewAndBothWalletsRemainUnchanged() {
        val a = wallet(100_000)
        val saved = store.importTransactions(listOf(draft(a, null)), linkWalletBalances = true).single()
        assertEquals(RecordState.REVIEW, saved.state)
        assertEquals("TRANSFER_WALLET_REQUIRED", saved.wallet!!.reviewCode)
        assertTrue(saved.walletEffects.isEmpty()); assertEquals(100_000L, balance(a))
        val b = wallet(20_000)
        val posted = store.saveEdited(saved.copy(transfer = saved.transfer!!.copy(toAccountUid = b.uid)))
        assertEquals(mapOf(a.uid to -10_100L, b.uid to 10_000L), posted.walletEffects)
        assertEquals(89_900L, balance(a)); assertEquals(30_000L, balance(b))
    }

    @Test fun allThreeExcludedEligibilitiesSurviveTypeEditsDeleteUndoAndReimport() {
        for (eligibility in WalletEligibility.entries.filter { it != WalletEligibility.ACTIVE }) {
            val a = wallet(100_000); val b = wallet(20_000)
            val imported = store.importTransactions(listOf(ordinary(a, Direction.EXPENSE, 10_000))).single()
            store.writableDatabase.execSQL("UPDATE record_wallet_links SET eligibility=? WHERE record_uid=?", arrayOf(eligibility.name, imported.uid))
            val original = store.get(imported.id)!!
            val converted = store.saveEdited(original.copy(bill = original.bill.copy(direction = Direction.TRANSFER),
                transfer = RecordTransferDetails(original.uid, a.uid, b.uid, 100, FeeMode.EXTRA)))
            val changed = store.saveEdited(converted.copy(bill = converted.bill.copy(amountCents = 12_000)))
            assertEquals(eligibility, changed.wallet!!.eligibility); assertTrue(changed.walletEffects.isEmpty())
            assertTrue(store.softDelete(changed.id, now)); assertTrue(store.undoDelete(changed.id, now + 1))
            assertTrue(store.importTransactions(listOf(original), linkWalletBalances = true).isEmpty())
            assertEquals(eligibility, store.get(original.id)!!.wallet!!.eligibility)
            assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
        }
    }

    @Test fun batchAuditUsesOneBatchUidForItsMovementsAndEveryImportedRecord() {
        val a = wallet(100_000); val b = wallet(20_000)
        val records = store.importTransactions(listOf(draft(a, b), ordinary(a, Direction.INCOME, 500)),
            linkWalletBalances = true, submissionToken = "independent-batch")
        val batchUid = store.readableDatabase.rawQuery("SELECT batch_uid FROM import_batches WHERE submission_token=?",
            arrayOf("independent-batch")).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertEquals(2L, scalar("SELECT COUNT(*) FROM import_batch_records WHERE batch_uid=?", batchUid))
        val uids = store.readableDatabase.rawQuery("SELECT record_uid FROM import_batch_records WHERE batch_uid=?", arrayOf(batchUid)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(records.map { it.uid }.toSet(), uids)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM wallet_movements WHERE kind='BILL_IMPORT' AND operation_uid=?", batchUid))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM wallet_movements WHERE kind='BILL_IMPORT' AND operation_uid<>?", batchUid))
        store.close(); store = LedgerStore(context, name)
        assertEquals(records.map { it.uid }, store.importTransactions(emptyList(), true, "stale", "independent-batch").map { it.uid })
        assertEquals(90_400L, balance(a)); assertEquals(30_000L, balance(b))
    }

    @Test fun batchesWithoutExternalTokensStillHaveDurableAuditAssociations() {
        val a = wallet(10_000)
        val saved = store.importTransactions(listOf(ordinary(a, Direction.EXPENSE)), true).single()
        assertEquals(1L, scalar("SELECT COUNT(*) FROM import_batch_records WHERE record_uid=?", saved.uid))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM import_batches"))
        assertEquals(9_900L, balance(a))
    }

    @Test fun historicalBatchMovementProtectsAssetTypeAfterTheImportedBillIsHardDeleted() {
        val a = wallet(1_000)
        val saved = store.importTransactions(listOf(ordinary(a, Direction.EXPENSE)), true).single()
        store.delete(saved.id)
        assertEquals(1_000L, balance(a))
        assertTrue(scalar("SELECT COUNT(*) FROM wallet_movements WHERE account_uid=? AND kind='BILL_IMPORT'", a.uid) > 0)
        expect("ACCOUNT_IN_USE") { store.saveAccount(store.getAccount(a.id)!!.copy(type = AccountType.LIABILITY)) }
    }

    @Test fun aliasOfAnUnusedZeroBalanceAccountIsRemovedOnHardDelete() {
        val a = wallet()
        val sourceUid = UUID.randomUUID().toString()
        store.saveWalletImportAlias(sourceUid, a.uid)
        assertEquals(a.uid, store.walletImportAliases()[sourceUid])
        store.deleteAccount(a.id)
        assertNull(store.getAccount(a.id)); assertNull(store.walletImportAliases()[sourceUid])
    }

    @Test fun failureWhileUpdatingSecondWalletRollsBackFirstWalletAndRetryAfterReopenWorks() {
        val a = wallet(100_000); val b = wallet(20_000)
        val secondUid = listOf(a.uid, b.uid).sorted().last()
        val originalMovements = scalar("SELECT COUNT(*) FROM wallet_movements")
        store.writableDatabase.execSQL("""CREATE TRIGGER independent_second_leg BEFORE UPDATE OF amount ON accounts
            WHEN NEW.uid='$secondUid' BEGIN SELECT RAISE(ABORT,'synthetic second-leg failure'); END""")
        val input = draft(a, b)
        var failed = false
        try { store.saveEdited(input, submissionToken = "independent-atomic") } catch (_: Exception) { failed = true }
        assertTrue(failed)
        store.close(); store = LedgerStore(context, name)
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM records"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM record_wallet_effects"))
        assertEquals(originalMovements, scalar("SELECT COUNT(*) FROM wallet_movements"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM wallet_submissions WHERE token='independent-atomic'"))
        store.writableDatabase.execSQL("DROP TRIGGER independent_second_leg")
        store.saveEdited(input, submissionToken = "independent-atomic")
        assertEquals(89_900L, balance(a)); assertEquals(30_000L, balance(b))
    }

    @Test fun staleConcurrentFormsHaveExactlyOneSuccessfulRevision() {
        val a = wallet(100_000); val b = wallet(20_000)
        val saved = store.saveEdited(draft(a, b))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index -> pool.submit<String> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                try {
                    store.saveEdited(saved.copy(bill = saved.bill.copy(amountCents = 10_000L + index * 100)), submissionToken = "independent-concurrent-$index")
                    "success"
                } catch (error: WalletException) { error.code }
            } }
            start.countDown()
            assertEquals(listOf("STALE_RECORD_REVISION", "success"), futures.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            val current = store.get(saved.id)!!
            assertEquals(saved.wallet!!.revision + 1, current.wallet!!.revision)
            assertEquals(100_000L - current.bill.amountCents!! - 100, balance(a))
            assertEquals(20_000L + current.bill.amountCents!!, balance(b))
        } finally { pool.shutdownNow() }
    }

    @Test fun identityInReviewAndSoftDeletedStatesCannotCreateASecondFinancialRecord() {
        val a = wallet(100_000); val b = wallet(20_000)
        val capture = Capture(Source.OCR, PaymentApps.WECHAT, "合成强身份", now)
        val bill = ParsedBill(10_000, Direction.EXPENSE, "合成商户", "其他", now, "SYNTHETIC-INDEPENDENT-ID", true, "")
        val pending = (store.ingest(capture, bill) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW, pending.state)
        store.ingest(capture.copy(source = Source.IMAGE), bill.copy(direction = Direction.TRANSFER, amountCents = 12_000))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM records"))
        assertTrue(store.get(pending.id)!!.walletEffects.isEmpty())
        val current = store.get(pending.id)!!
        val posted = store.saveEdited(current.copy(bill = current.bill.copy(direction = Direction.TRANSFER),
            transfer = RecordTransferDetails(current.uid, a.uid, b.uid, 100, FeeMode.EXTRA)))
        val before = posted.walletEffects
        store.ingest(capture, bill.copy(direction = Direction.TRANSFER, transfer = TransferEvidence(TransferIntent.SELF_TRANSFER,
            terminalState = TransferTerminalState.COMPLETED, amountCents = 10_000, feeCents = 200, feeMode = FeeMode.DEDUCTED)))
        assertEquals(before, store.get(posted.id)!!.walletEffects)
        assertEquals("TRANSFER_IDENTITY_CONFLICT", store.get(posted.id)!!.wallet!!.reviewCode)
        store.softDelete(posted.id, now)
        store.ingest(capture.copy(source = Source.IMAGE), bill.copy(direction = Direction.TRANSFER))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM records"))
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }

    @Test fun importNettingAtTheLimitIsOrderIndependentAndFinalOverflowRejectsTheWholeBatch() {
        val a = wallet(Money.MAX_CENTS)
        val first = listOf(ordinary(a, Direction.INCOME), ordinary(a, Direction.EXPENSE))
        store.importTransactions(first, true)
        assertEquals(Money.MAX_CENTS, balance(a))
        val second = listOf(ordinary(a, Direction.EXPENSE), ordinary(a, Direction.INCOME))
        store.importTransactions(second, true)
        assertEquals(Money.MAX_CENTS, balance(a))
        val count = scalar("SELECT COUNT(*) FROM records")
        val effects = scalar("SELECT COUNT(*) FROM record_wallet_effects")
        val batches = scalar("SELECT COUNT(*) FROM import_batches")
        expect("BALANCE_LIMIT") { store.importTransactions(listOf(ordinary(a, Direction.INCOME)), true, submissionToken = "independent-overflow") }
        assertEquals(count, scalar("SELECT COUNT(*) FROM records"))
        assertEquals(effects, scalar("SELECT COUNT(*) FROM record_wallet_effects"))
        assertEquals(batches, scalar("SELECT COUNT(*) FROM import_batches"))
        assertEquals(Money.MAX_CENTS, balance(a))
    }

    @Test fun importPreviewChangesOnLedgerDeletionCalibrationArchiveAndAliasChanges() {
        val a = wallet(100_000); val b = wallet(20_000)
        val saved = store.saveEdited(draft(a, b))
        var old = store.walletConfigurationToken()
        store.softDelete(saved.id, now)
        expect("IMPORT_PREVIEW_STALE") { store.importTransactions(listOf(ordinary(a, Direction.EXPENSE)), true, old) }
        old = store.walletConfigurationToken()
        store.saveAccount(store.getAccount(a.id)!!.copy(amountCents = 120_000), calibrateBalance = true)
        expect("IMPORT_PREVIEW_STALE") { store.importTransactions(listOf(ordinary(a, Direction.EXPENSE)), true, old) }
        old = store.walletConfigurationToken(); store.archiveAccount(b.id)
        expect("IMPORT_PREVIEW_STALE") { store.importTransactions(listOf(ordinary(a, Direction.EXPENSE)), true, old) }
        old = store.walletConfigurationToken(); store.saveWalletImportAlias(UUID.randomUUID().toString(), a.uid)
        expect("IMPORT_PREVIEW_STALE") { store.importTransactions(listOf(ordinary(a, Direction.EXPENSE)), true, old) }
    }

    @Test fun zeroAppliedFactOnAnActiveNonzeroV9BillFailsMigrationAtomically() {
        val fixtureName = "independent-invalid-v9-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(fixtureName); file.parentFile!!.mkdirs()
            val script = InstrumentationRegistry.getInstrumentation().context.assets.open("transfers/ledger-v9.sql").bufferedReader().use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                script.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';')
                    .filter { it.isNotBlank() }.forEach(db::execSQL)
                db.execSQL("UPDATE record_wallet_links SET applied_cents=0 WHERE record_uid='cccccccc-cccc-4ccc-8ccc-000000000001'")
            }
            var failed = false
            LedgerStore(context, fixtureName).use { broken ->
                try { broken.writableDatabase } catch (_: Exception) { failed = true }
            }
            assertTrue("inconsistent active financial facts must reject migration", failed)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(9, db.version)
                db.rawQuery("SELECT amount FROM accounts WHERE uid='11111111-1111-4111-8111-111111111111'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(100_000L, it.getLong(0))
                }
                db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='record_wallet_effects'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally { context.deleteDatabase(fixtureName) }
    }

    @Test fun replacingBothEndsUpdatesFourWalletsInOneOperationWithExactFinalEffects() {
        val a = wallet(100_000); val b = wallet(20_000); val c = wallet(5_000); val d = wallet()
        val original = store.saveEdited(draft(a, b))
        val changed = store.saveEdited(original.copy(transfer = original.transfer!!.copy(fromAccountUid = c.uid, toAccountUid = d.uid)))
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
        assertEquals(-5_100L, balance(c)); assertEquals(10_000L, balance(d))
        assertEquals(mapOf(c.uid to -10_100L, d.uid to 10_000L), changed.walletEffects)
        val operations = store.walletSnapshot().movements.filter { it.recordUid == original.uid && it.kind == "EDIT" }
        assertEquals(4, operations.size)
        assertEquals(1, operations.map { it.operationUid }.distinct().size)
        assertEquals(mapOf(a.uid to 10_100L, b.uid to -10_000L, c.uid to -10_100L, d.uid to 10_000L),
            operations.associate { it.accountUid to it.deltaCents })
    }

    @Test fun manualCorrectionsLockOnlyChangedTransferFieldsForLaterRecognition() {
        val a = wallet(100_000); val b = wallet(20_000); val c = wallet(5_000)
        val original = store.importTransactions(listOf(draft(a, b).let { it.copy(transfer = it.transfer!!.copy(
            fromSelectionSource = "IMPORT", toSelectionSource = "IMPORT")) }), linkWalletBalances = true).single()
        val changed = store.saveEdited(original.copy(transfer = original.transfer!!.copy(toAccountUid = c.uid, feeCents = 200)))
        assertFalse(changed.transfer!!.fromManualLocked)
        assertTrue(changed.transfer!!.toManualLocked); assertEquals("MANUAL", changed.transfer!!.toSelectionSource)
        assertTrue(changed.transfer!!.feeManualLocked)
        assertEquals(a.uid, changed.transfer!!.fromAccountUid)
    }

    @Test fun successfulBatchUidsAndReceiptSurviveLaterHardDeletionWithoutReplayingFunds() {
        val a = wallet(100_000); val b = wallet(20_000)
        val input = draft(a, b)
        val saved = store.importTransactions(listOf(input), true, submissionToken = "independent-retained-receipt").single()
        store.delete(saved.id)
        store.close(); store = LedgerStore(context, name)
        val before = scalar("SELECT COUNT(*) FROM wallet_movements")
        store.importTransactions(listOf(input), true, submissionToken = "independent-retained-receipt")
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM records"))
        assertEquals(before, scalar("SELECT COUNT(*) FROM wallet_movements"))
        store.readableDatabase.rawQuery("SELECT result_json FROM import_batches WHERE submission_token=?",
            arrayOf("independent-retained-receipt")).use {
            assertTrue(it.moveToFirst()); assertEquals(saved.uid, org.json.JSONArray(it.getString(0)).getString(0))
        }
        assertEquals(1L, scalar("SELECT COUNT(*) FROM import_batch_records WHERE record_uid=?", saved.uid))
    }

    @Test fun illegalTransferImportRowsAreRejectedForBothLinkModesInsteadOfSavedAsReview() {
        val a = wallet(100_000); val b = wallet(20_000)
        val liability = store.saveAccount(LedgerAccount(name = "合成独立负债", amountCents = 0, type = AccountType.LIABILITY))
        val base = draft(a, b)
        val cases = listOf(
            "TRANSFER_FEE_AMBIGUOUS" to base.copy(transfer = base.transfer!!.copy(feeCents = -1)),
            "TRANSFER_FEE_AMBIGUOUS" to base.copy(transfer = base.transfer!!.copy(feeCents = null)),
            "TRANSFER_FEE_AMBIGUOUS" to base.copy(transfer = base.transfer!!.copy(feeMode = null)),
            "TRANSFER_AMOUNT_CONFLICT" to base.copy(transfer = base.transfer!!.copy(feeCents = 10_000, feeMode = FeeMode.DEDUCTED)),
            "INVALID_AMOUNT" to base.copy(bill = base.bill.copy(amountCents = Money.MAX_CENTS)),
            "TRANSFER_SAME_WALLET" to base.copy(transfer = base.transfer!!.copy(toAccountUid = a.uid)),
            "TRANSFER_ACCOUNT_UNSUPPORTED" to base.copy(transfer = base.transfer!!.copy(toAccountUid = liability.uid)),
        )
        for (linked in listOf(false, true)) for ((code, record) in cases) {
            expect(code) { store.importTransactions(listOf(record), linkWalletBalances = linked) }
            assertEquals(0L, scalar("SELECT COUNT(*) FROM records"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM record_wallet_effects"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM import_batches"))
            assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
        }
    }

    @Test fun repeatedStrongIdentityRecognitionCannotActivateAnyExcludedEligibility() {
        val a = wallet(100_000)
        store.setDefaultWallet(a.uid)
        for (eligibility in WalletEligibility.entries.filter { it != WalletEligibility.ACTIVE }) {
            val id = "SYNTHETIC-EXCLUDED-${eligibility.name}"
            val record = ordinary(a, Direction.EXPENSE, 10_000).let { it.copy(
                capture = Capture(Source.OCR, PaymentApps.WECHAT, "合成排除强身份", now),
                bill = it.bill.copy(transactionId = id)) }
            val imported = store.importTransactions(listOf(record)).single()
            store.writableDatabase.execSQL("UPDATE record_wallet_links SET eligibility=? WHERE record_uid=?", arrayOf(eligibility.name, imported.uid))
            store.ingest(record.capture.copy(source = Source.IMAGE), record.bill.copy(paymentMethod = PaymentMethodEvidence("WECHAT_BALANCE", "DEBIT", platform = "wechat")))
            val saved = store.get(imported.id)!!
            assertEquals(eligibility, saved.wallet!!.eligibility)
            assertTrue(saved.walletEffects.isEmpty()); assertEquals(100_000L, balance(a))
        }
        assertEquals(3L, scalar("SELECT COUNT(*) FROM records"))
    }
}
