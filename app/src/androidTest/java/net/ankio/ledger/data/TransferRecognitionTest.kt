package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic text goes through the real parser, scoped identity lookup and SQLite effect transaction. */
@RunWith(AndroidJUnit4::class)
class TransferRecognitionTest {
    private val app = ApplicationProvider.getApplicationContext<LedgerApp>()
    private val name = "transfer-recognition-${UUID.randomUUID()}.db"
    private var store = LedgerStore(app, name)
    // OCR dates use the device zone; keep capture time aligned on UTC CI and local devices.
    private val now = java.time.LocalDateTime.of(2026, 11, 6, 12, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    @After fun cleanup() { store.close(); app.deleteDatabase(name) }
    private fun bank(tail: String = "0007", bank: String = "cmb") = store.saveAccount(
        LedgerAccount(name = "合成银行卡$tail", amountCents = 100_000, kind = "bank", bankCode = bank, cardLast4 = tail))
    private fun platform(kind: String) = store.saveAccount(LedgerAccount(name = "合成$kind", amountCents = 20_000, kind = kind))
    private fun balance(wallet: LedgerAccount) = store.getAccountByUid(wallet.uid)!!.amountCents
    private fun topup(id: String? = "SYNTHETIC-TRANSFER-0001", fee: String? = "手续费0.00元") = buildList {
        add("充值成功"); add("充值金额100.00元"); add("付款方式招商银行储蓄卡（0007）"); add("到账账户微信零钱")
        if (fee != null) add(fee)
        add("交易时间2026-11-06 12:00:00")
        if (id != null) add("微信转账单号$id")
    }
    private fun parse(text: List<String>, origin: String = PaymentApps.WECHAT) =
        requireNotNull(LocalOcrBillParser.parse(text.map(::LocalOcrLine), 0, 0, now, origin = origin))
    private fun ingest(text: List<String>, source: Source = Source.IMAGE, origin: String = PaymentApps.WECHAT): LedgerRecord {
        val result = store.ingest(Capture(source, origin, text.joinToString("\n"), now), parse(text, origin))
        return when (result) {
            is IngestResult.Saved -> requireNotNull(store.get(result.record.id))
            IngestResult.DuplicateSkipped -> requireNotNull(store.matchingIdentity(Capture(source, origin, text.joinToString("\n"), now), parse(text, origin)))
            else -> error("Unexpected $result")
        }
    }

    @Test fun O01FreeTopUpPostsOneBillAndBothAssetEffects() {
        val a = bank(); val b = platform("wechat")
        val saved = ingest(topup())
        assertEquals(Direction.TRANSFER, saved.bill.direction); assertEquals(RecordState.POSTED, saved.state)
        assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
        assertEquals(mapOf(a.uid to -10_000L, b.uid to 10_000L), saved.walletEffects)
        assertEquals(1, store.all().size); assertEquals(0L, saved.transfer!!.feeCents)
    }

    @Test fun O02WithdrawalProvesDeductedFeeAndNeverAddsAnExtraExpense() {
        val a = platform("alipay"); val b = bank()
        val text = listOf("提现成功", "提现金额100.00元", "实际扣款100.00元", "实际到账99.00元", "手续费1.00元",
            "到账银行卡招商银行储蓄卡（0007）", "支付宝提现单号SYNTHETIC-WITHDRAW-0001")
        val saved = ingest(text, origin = PaymentApps.ALIPAY)
        assertEquals(RecordState.POSTED, saved.state); assertEquals(FeeMode.DEDUCTED, saved.transfer!!.feeMode)
        assertEquals(10_000L, balance(a)); assertEquals(109_900L, balance(b)); assertEquals(1, store.all().size)
        assertEquals(-100L, saved.walletEffects.values.sum())
    }

    @Test fun O03MissingLocalEndIsReviewThenTheSameIdentityPostsExactlyOnceWhenCompleted() {
        val a = bank()
        val first = ingest(topup())
        assertEquals(RecordState.REVIEW, first.state); assertTrue(first.walletEffects.isEmpty()); assertEquals(100_000L, balance(a))
        assertNotNull(first.transfer!!.fromAccountUid); assertNull(first.transfer.toAccountUid)
        val b = platform("wechat")
        val completed = ingest(topup(), Source.OCR)
        assertEquals(first.uid, completed.uid); assertEquals(RecordState.POSTED, completed.state)
        assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
        ingest(topup()); assertEquals(90_000L, balance(a)); assertEquals(1, store.all().size)
    }

    @Test fun O03UnknownFeeIsPreservedUntilReliableSameIdentitySuppliesIt() {
        val a = bank(); val b = platform("wechat")
        val first = ingest(topup(fee = null))
        assertEquals(RecordState.REVIEW, first.state); assertNull(first.transfer!!.feeCents)
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
        val completed = ingest(topup())
        assertEquals(first.id, completed.id); assertEquals(RecordState.POSTED, completed.state)
        assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
    }

    @Test fun O04_O05FriendTransferWithMatchingOwnTailRemainsAnExpense() {
        val a = bank(); platform("wechat"); store.setDefaultWallet(a.uid)
        val text = listOf("转账成功", "转账金额100.00元", "收款人合成朋友", "收款银行卡招商银行（0007）", "付款方式招商银行（0007）")
        val saved = ingest(text)
        assertEquals(Direction.EXPENSE, saved.bill.direction); assertNull(saved.transfer)
        assertEquals(90_000L, balance(a))
        val incoming = parse(listOf("收款成功", "收款金额100.00元", "付款方合成朋友"))
        assertFalse(RecognitionPolicy.AUTOMATIC_PAYMENT.allows(incoming, PaymentApps.WECHAT))
    }

    @Test fun O07AutomaticShakeAndImageIdentitiesNeverChargeAgain() {
        val a = bank(); val b = platform("wechat")
        val first = ingest(topup(), Source.OCR)
        for (source in listOf(Source.IMAGE, Source.OCR, Source.IMAGE)) assertEquals(first.id, ingest(topup(), source).id)
        assertEquals(1, store.all().size); assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
        assertEquals(2, store.walletSnapshot().movements.count { it.recordUid == first.uid })
    }

    @Test fun O08ConflictingFeeRetainsOriginalRecordBalancesAndAppliedFacts() {
        val a = bank(); val b = platform("wechat")
        val original = ingest(topup(fee = "手续费1.00元") + "费用方式额外扣费")
        val conflict = ingest(topup(fee = "手续费2.00元") + "费用方式额外扣费")
        assertEquals(original.id, conflict.id); assertEquals(1, store.all().size)
        assertEquals(original.walletEffects, conflict.walletEffects); assertEquals(100L, conflict.transfer!!.feeCents)
        assertEquals("TRANSFER_IDENTITY_CONFLICT", conflict.wallet!!.reviewCode)
        assertEquals(89_900L, balance(a)); assertEquals(30_000L, balance(b))
    }

    @Test fun O08OldExpenseNeedsExplicitConversionOnItsOriginalRecord() {
        val a = bank(); val b = platform("wechat"); store.setDefaultWallet(a.uid)
        val id = "SYNTHETIC-TRANSFER-0001"
        val old = store.ingest(Capture(Source.OCR, PaymentApps.WECHAT, "微信转账单号$id", now),
            ParsedBill(10_000, Direction.EXPENSE, "合成旧误识别", "其他", now, id, true, "", true,
                paymentMethod = PaymentMethodEvidence("BANK_CARD", "DEBIT", "cmb", "0007"))) as IngestResult.Saved
        val conflict = ingest(topup())
        assertEquals(old.record.id, conflict.id); assertEquals(Direction.EXPENSE, conflict.bill.direction)
        assertEquals(90_000L, balance(a)); assertEquals(20_000L, balance(b)); assertEquals(1, store.all().size)
        val converted = store.saveEdited(conflict.copy(bill = conflict.bill.copy(direction = Direction.TRANSFER),
            transfer = RecordTransferDetails(conflict.uid, a.uid, b.uid, 0, FeeMode.EXTRA)))
        assertEquals(conflict.id, converted.id); assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
    }

    @Test fun O09EqualTransfersWithoutStrongIdentityAreCandidatesInsteadOfSilentlyMerged() {
        val a = bank(); val b = platform("wechat")
        val first = ingest(topup(id = null))
        assertTrue(first.bill.timeExplicit); assertEquals(now, first.bill.occurredAt)
        val second = ingest(topup(id = null))
        assertNotEquals(first.id, second.id); assertEquals(RecordState.REVIEW, second.state)
        assertEquals(first.id, second.duplicateId); assertTrue(second.walletEffects.isEmpty())
        assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
        store.saveEdited(second)
        assertEquals(80_000L, balance(a)); assertEquals(40_000L, balance(b)); assertEquals(2, store.all().size)
    }

    @Test fun O10BankOwnAccountCaptureCanParseOnlyUnderUserInitiatedPolicy() {
        val a = bank(); val b = bank("1234", "icbc")
        val text = listOf("本人账户转账成功", "转账金额100.00元", "转出账户招商银行储蓄卡（0007）", "转入账户工商银行储蓄卡（1234）", "免手续费")
        val origin = "com.example.syntheticbank"
        val parsed = parse(text, origin)
        assertTrue(RecognitionPolicy.USER_INITIATED.allows(parsed, origin))
        assertFalse(RecognitionPolicy.AUTOMATIC_PAYMENT.allows(parsed, origin))
        val saved = ingest(text, origin = origin)
        assertEquals(RecordState.POSTED, saved.state); assertEquals(90_000L, balance(a)); assertEquals(110_000L, balance(b))
    }

    @Test fun O11ManualEndsAndFeeStayLockedAcrossDuplicateEvidenceAndSettingsChanges() {
        val a = bank(); val b = platform("wechat"); val cash = platform("cash")
        val original = ingest(topup())
        val edited = store.saveEdited(original.copy(transfer = original.transfer!!.copy(fromAccountUid = cash.uid, feeCents = 100, feeMode = FeeMode.EXTRA)))
        assertTrue(edited.transfer!!.fromManualLocked); assertTrue(edited.transfer.feeManualLocked)
        store.setDefaultWallet(a.uid); store.setAppWallet(PaymentApps.WECHAT, b.uid)
        val supplemented = ingest(topup())
        assertEquals(cash.uid, supplemented.transfer!!.fromAccountUid); assertEquals(100L, supplemented.transfer.feeCents)
        assertEquals(100_000L, balance(a)); assertEquals(9_900L, balance(cash)); assertEquals(30_000L, balance(b))
    }

    @Test fun importedExcludedTransferNeverAcquiresEffectsFromRecognition() {
        val a = bank(); val b = platform("wechat"); val text = topup()
        val uid = UUID.randomUUID().toString()
        val imported = store.importTransactions(listOf(LedgerRecord(uid = uid, capture = Capture(Source.IMAGE, PaymentApps.WECHAT, text.joinToString("\n"), now),
            bill = parse(text), state = RecordState.POSTED, transfer = RecordTransferDetails(uid, a.uid, b.uid, 0, FeeMode.EXTRA)))).single()
        val supplemented = ingest(text, Source.OCR)
        assertEquals(imported.id, supplemented.id); assertEquals(WalletEligibility.IMPORT_EXCLUDED, supplemented.wallet!!.eligibility)
        assertTrue(supplemented.walletEffects.isEmpty()); assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }

    @Test fun deletedIdentityNeverReappearsThroughASecondCapture() {
        val a = bank(); val b = platform("wechat"); val original = ingest(topup())
        store.softDelete(original.id, now)
        val result = store.ingest(Capture(Source.IMAGE, PaymentApps.WECHAT, topup().joinToString("\n"), now), parse(topup()))
        assertEquals(IngestResult.Skipped("账单已删除"), result)
        assertTrue(store.all().isEmpty()); assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }

    @Test fun O13CommittedTransferRecoversTaskFeedbackWithoutApplyingEitherLegAgain() {
        val a = bank(); val b = platform("wechat")
        val task = app.tasks.create(Source.OCR, "payment_auto:${PaymentApps.WECHAT}")
        try {
            val operation = app.tasks.operationKey(task)!!
            store.ingestRecognition(Capture(Source.OCR, PaymentApps.WECHAT, topup().joinToString("\n"), now), parse(topup()), operation)
            assertNull(app.tasks.recordId(task)) // The simulated crash occurs before recognition.db receives its result.
            store.close(); store = LedgerStore(app, name)
            assertTrue(RecognitionCommitRecovery.recover(app.tasks, store, task))
            assertEquals("SUCCEEDED", app.tasks.status(task)); assertTrue(app.tasks.message(task)!!.contains("钱包转账"))
            assertTrue(RecognitionCommitRecovery.recover(app.tasks, store, task))
            assertEquals(90_000L, balance(a)); assertEquals(30_000L, balance(b))
            assertEquals(2, store.walletSnapshot().movements.count { it.recordUid == store.all().single().uid })
        } finally { app.tasks.delete(task) }
    }

    @Test fun transferIdentityLabelsKeepIssuerAndMerchantNamespacesSeparate() {
        val value = "SYNTHETIC-NATIVE-0001"
        val merchant = "SYNTHETIC-MERCHANT-0001"
        val capture = Capture(Source.IMAGE, PaymentApps.WECHAT, "商户转账单号$merchant\n微信转账单号$value", now)
        val ids = TransactionIdentities.from(capture, ParsedBill(10_000, Direction.TRANSFER, "合成互转", "转账", now, merchant, true, ""))
        assertEquals(listOf(TransactionIdentity("wechat", value)), ids)
        assertTrue(TransactionIdentities.reservedPrimary(capture, merchant))
        val ali = TransactionIdentities.from(capture.copy(origin = PaymentApps.ALIPAY, text = "支付宝提现单号$value"),
            ParsedBill(10_000, Direction.TRANSFER, "合成互转", "转账", now, value, true, ""))
        assertEquals(listOf(TransactionIdentity("alipay", value)), ali)
    }

    @Test fun O06NoncompletedTransferEvidenceCannotPostEvenIfARecognizerClaimsReliability() {
        val a = bank(); val b = platform("wechat")
        val base = parse(topup())
        for (status in listOf(TransferTerminalState.PENDING, TransferTerminalState.FAILED, TransferTerminalState.REVERSED)) {
            val bill = base.copy(transactionId = "SYNTHETIC-STATE-${status.name}", transfer = base.transfer!!.copy(terminalState = status))
            val result = store.ingest(Capture(Source.IMAGE, PaymentApps.WECHAT, "合成终态$status", now), bill) as IngestResult.Saved
            assertEquals(RecordState.REVIEW, result.record.state); assertTrue(result.record.walletEffects.isEmpty())
        }
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }

    @Test fun contradictoryObservedLegsPreservePostedEffectsAndRecoverAsAReviewPrompt() {
        val a = bank(); val b = platform("wechat"); val original = ingest(topup())
        val text = topup() + listOf("实际扣款100.00元", "实际到账99.00元")
        val task = app.tasks.create(Source.OCR, "payment_auto:${PaymentApps.WECHAT}")
        try {
            store.ingestRecognition(Capture(Source.OCR, PaymentApps.WECHAT, text.joinToString("\n"), now), parse(text), app.tasks.operationKey(task)!!)
            val record = store.get(original.id)!!
            assertEquals(RecordState.POSTED, record.state); assertEquals(original.walletEffects, record.walletEffects)
            assertEquals("TRANSFER_AMOUNT_CONFLICT", record.wallet!!.reviewCode)
            RecognitionCommitRecovery.recover(app.tasks, store, task)
            assertEquals("REVIEW", app.tasks.status(task)); assertTrue(app.tasks.message(task)!!.contains("不一致"))
            assertEquals(100_000L - 10_000L, balance(a)); assertEquals(30_000L, balance(b))
        } finally { app.tasks.delete(task) }
    }

    @Test fun aPendingTransferCannotFirstPostIntoAWalletArchivedBeforeCompletion() {
        val a = bank(); val first = ingest(topup())
        assertEquals(RecordState.REVIEW, first.state)
        store.archiveAccount(a.id); val b = platform("wechat")
        val repeated = ingest(topup())
        assertEquals(RecordState.REVIEW, repeated.state); assertTrue(repeated.walletEffects.isEmpty())
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }

    @Test fun automaticCoordinatorStillRejectsIncomeAndBankTransfersFromAnInjectedBackend() = runBlocking {
        val before = app.store.all().map { it.uid }.toSet()
        val income = ParsedBill(100, Direction.INCOME, "合成收入", "其他", now, null, true, "")
        val rejected = app.coordinator.acceptRecognition(Capture(Source.OCR, PaymentApps.WECHAT, "合成普通收入", now), income,
            policy = RecognitionPolicy.AUTOMATIC_PAYMENT) { true }
        assertTrue(rejected is IngestResult.Skipped)
        val origin = "com.example.syntheticbank"
        val bankTransfer = parse(listOf("本人账户转账成功", "转账金额100.00元", "转出账户招商银行储蓄卡（0007）", "转入账户工商银行储蓄卡（1234）", "免手续费"), origin)
        assertTrue(app.coordinator.acceptRecognition(Capture(Source.OCR, origin, "合成银行本人互转", now), bankTransfer,
            policy = RecognitionPolicy.AUTOMATIC_PAYMENT) { true } is IngestResult.Skipped)
        assertEquals(before, app.store.all().map { it.uid }.toSet())
    }

    @Test fun completeRecognitionEvidenceSurvivesReopenAndRemainsOriginalAfterManualCorrection() {
        bank(); platform("wechat")
        val text = topup(fee = "手续费1.00元") + listOf("实际扣款101.00元", "实际到账100.00元")
        val original = ingest(text)
        val evidence = original.bill.transfer!!
        assertEquals(TransferIntent.SELF_TRANSFER, evidence.intent)
        assertEquals(4, evidence.amountLabels.size)
        store.close(); store = LedgerStore(app, name)
        val reopened = store.get(original.id)!!
        assertEquals(evidence, reopened.bill.transfer)
        assertEquals(evidence, TransferEvidenceCodec.decode(reopened.transfer!!.recognitionEvidence!!))
        val edited = store.saveEdited(reopened.copy(bill = reopened.bill.copy(amountCents = 12_000),
            transfer = reopened.transfer.copy(feeCents = 200)))
        store.close(); store = LedgerStore(app, name)
        val corrected = store.get(edited.id)!!
        assertEquals(12_000L, corrected.bill.amountCents); assertEquals(200L, corrected.transfer!!.feeCents)
        assertEquals(evidence, corrected.bill.transfer)
        assertEquals(10_000L, corrected.bill.transfer!!.amountCents)
        assertEquals(-200L, corrected.walletEffects.values.sum())
    }

    @Test fun evidenceCodecRejectsFractionalOrCoercedMoneyEnumsVersionsAndOversizedArrays() {
        val valid = TransferEvidenceCodec.encode(parse(topup()).transfer!!)
        assertEquals(parse(topup()).transfer, TransferEvidenceCodec.decode(valid))
        for (invalid in listOf(
            JSONObject(valid).put("feeCents", 1.5), JSONObject(valid).put("amountCents", "10000"),
            JSONObject(valid).put("debitCents", true),
            JSONObject(valid).put("version", 2), JSONObject(valid).put("terminalState", "NOT_A_STATE"),
            JSONObject(valid).put("feeCents", Money.MAX_CENTS + 1),
            JSONObject(valid).put("amountLabels", JSONArray().apply { repeat(33) {
                put(JSONObject().put("role", "AMOUNT").put("label", "合成金额").put("amountCents", 100))
            } }),
            JSONObject(valid).put("from", JSONObject().put("medium", "BANK_CARD").put("role", "DEBIT")
                .put("rawLabel", "a".repeat(81)).put("rawValue", "合成").put("ambiguous", false))
        )) assertNotNull(invalid.toString(), runCatching { TransferEvidenceCodec.decode(invalid.toString()) }.exceptionOrNull())
        val decimalVersion = valid.replace(Regex("\"version\":1(?=[,}])"), "\"version\":1.0")
        assertNotEquals(valid, decimalVersion)
        assertNotNull(runCatching { TransferEvidenceCodec.decode(decimalVersion) }.exceptionOrNull())
        assertNotNull(runCatching { TransferEvidenceCodec.decode(" ".repeat(16_385)) }.exceptionOrNull())
    }

    @Test fun developmentV10EvidenceColumnIsAddedIdempotentlyWithoutInferringFinancialFacts() {
        bank(); platform("wechat"); val original = ingest(topup())
        val accounts = store.accounts(); val wallet = store.walletSnapshot()
        val details = original.transfer!!.copy(recognitionEvidence = null)
        val db = store.writableDatabase
        val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='record_transfer_details'", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }.replace(Regex(",\\s*recognition_evidence\\s+TEXT"), "")
        val columns = db.rawQuery("PRAGMA table_info(record_transfer_details)", null).use { c -> buildList {
            while(c.moveToNext()) if(c.getString(1)!="recognition_evidence") add(c.getString(1))
        } }.joinToString(",")
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE record_transfer_details RENAME TO synthetic_with_evidence")
            db.execSQL("DROP INDEX transfer_from_account"); db.execSQL("DROP INDEX transfer_to_account")
            db.execSQL(sql)
            db.execSQL("INSERT INTO record_transfer_details($columns) SELECT $columns FROM synthetic_with_evidence")
            db.execSQL("DROP TABLE synthetic_with_evidence")
            db.execSQL("CREATE INDEX transfer_from_account ON record_transfer_details(from_account_uid)")
            db.execSQL("CREATE INDEX transfer_to_account ON record_transfer_details(to_account_uid)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        repeat(3) {
            store.close(); store = LedgerStore(app, name)
            assertEquals(12, store.readableDatabase.version)
            assertEquals(accounts, store.accounts()); assertEquals(wallet.links, store.walletSnapshot().links)
            assertEquals(wallet.effects, store.walletSnapshot().effects); assertEquals(wallet.movements, store.walletSnapshot().movements)
            assertEquals(details, store.get(original.id)!!.transfer)
            assertNull(store.get(original.id)!!.bill.transfer) // No OCR rerun or invented historical evidence.
        }
    }

    @Test fun ambiguousTypeReviewRetainsEvidenceAcrossReopenAndNeverSelectsLocalWallets() {
        val a = bank(); val b = platform("wechat"); store.setDefaultWallet(a.uid)
        val text = topup() + "收款人合成朋友"
        val expected = parse(text).transfer!!
        assertEquals(TransferIntent.AMBIGUOUS, expected.intent)
        val original = ingest(text)
        assertEquals(RecordState.REVIEW, original.state); assertNull(original.bill.direction)
        assertNull(original.wallet!!.accountUid); assertTrue(original.walletEffects.isEmpty())
        assertNull(original.transfer!!.fromAccountUid); assertNull(original.transfer.toAccountUid)
        store.close(); store = LedgerStore(app, name)
        val reopened = store.get(original.id)!!
        assertEquals(expected, reopened.bill.transfer); assertNull(reopened.bill.direction)
        assertNull(reopened.wallet!!.accountUid); assertTrue(reopened.walletEffects.isEmpty())
        assertEquals(100_000L, balance(a)); assertEquals(20_000L, balance(b))
    }
}
