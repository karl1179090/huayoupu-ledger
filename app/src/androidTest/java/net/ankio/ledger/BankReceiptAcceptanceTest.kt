package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.RecognitionBackend
import net.ankio.ledger.ocr.RecognitionResult
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic bank OCR -> processor -> coordinator -> real SQLite and financial read interfaces. */
@RunWith(AndroidJUnit4::class)
class BankReceiptAcceptanceTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()
    private val records = mutableSetOf<Long>()
    private val tasks = mutableListOf<Long>()
    private val accounts = mutableListOf<LedgerAccount>()
    private val oldDefault by lazy { app.store.walletSettings().defaultAccountUid }
    private fun account(kind: String, tail: String? = null, bank: String = "cmb"): LedgerAccount {
        oldDefault
        return app.store.saveAccount(LedgerAccount(name="合成银行验收${UUID.randomUUID().toString().take(6)}",
            amountCents=10000, kind=kind, bankCode=if(kind=="bank")bank else null, cardLast4=tail)).also { accounts += it }
    }
    private fun balances() = app.store.accounts().associate { it.uid to it.amountCents }
    private suspend fun recognize(vararg fields: String, id: String = "SYNTHETIC-BANK-${UUID.randomUUID()}",
        origin: String = "shake:com.tencent.mm", bankReceipt: Boolean = true, issuer: String = "招商银行"): IngestResult {
        val source = if(origin=="image")Source.IMAGE else Source.OCR
        val task = app.tasks.create(source, origin); tasks += task
        val raw = ((if(bankReceipt)listOf(issuer, "交易成功提醒") else listOf("支付成功")) + listOf("交易金额：12.00元", "交易单号：$id") + fields).joinToString("\n")
        val backend = RecognitionBackend { bytes, prepared, _, _, policy, actualOrigin ->
            prepared(bytes)
            RecognitionResult(raw, LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine), 0, 0,
                System.currentTimeMillis(), policy=policy, origin=actualOrigin))
        }
        return CaptureProcessor(app, OcrClient(app, backend)).processPrepared("synthetic-bank".toByteArray(),
            source, origin, task) { true }.also { if(it is IngestResult.Saved)records += it.record.id }
    }
    @After fun clean() {
        records.forEach(app.store::delete)
        tasks.forEach { id ->
            app.tasks.operationKey(id)?.let { app.store.writableDatabase.delete("wallet_submissions", "token=?", arrayOf(it)) }
            app.tasks.delete(id)
        }
        app.store.setDefaultWallet(oldDefault)
        accounts.forEach { account ->
            app.store.writableDatabase.delete("wallet_movements", "account_uid=?", arrayOf(account.uid))
            app.store.deleteAccount(account.id)
        }
    }

    @Test fun genericSuccessHasNoDirectionAndSurvivesReopenBeforeExplicitIncomeConfirmation() = runBlocking {
        app.taskRecovery.await()
        val card = account("bank", "0007"); account("wechat"); val cash = account("cash")
        app.store.setDefaultWallet(cash.uid)
        val before = balances()
        val saved = (recognize("交易卡号：尾号0007", "账户余额：988.00元") as IngestResult.Saved).record
        assertNull(saved.bill.direction)
        assertEquals(RecordState.REVIEW, saved.state)
        assertEquals("BANK_DIRECTION_REQUIRED", saved.wallet!!.reviewCode)
        assertEquals(card.uid, saved.wallet!!.accountUid)
        assertTrue(saved.walletEffects.isEmpty())
        assertEquals(before, balances())
        LedgerStore(app).use { reopened ->
            val record = reopened.get(saved.id)!!
            assertEquals(saved, record)
            val bank = (ReceiptEvidenceCodec.json(record.bill.receipt) as JSONObject).getJSONObject("bank")
            assertEquals("cmb", bank.getString("bankCode"))
            assertEquals("0007", bank.getString("cardLast4"))
            assertTrue(bank.isNull("direction"))
        }
        val token = "SYNTHETIC-DIRECTION-${UUID.randomUUID()}"
        val confirmed = saved.copy(bill=saved.bill.copy(direction=Direction.INCOME))
        val posted = app.store.saveEdited(confirmed, card.uid, true, submissionToken=token)
        assertEquals(RecordState.POSTED, posted.state)
        assertEquals(mapOf(card.uid to 1200L), posted.walletEffects)
        assertEquals(before + (card.uid to 11200L), balances())
        app.store.saveEdited(confirmed, card.uid, true, submissionToken=token)
        assertEquals(before + (card.uid to 11200L), balances())
    }

    @Test fun contradictoryBankSupplementRemovesAllEffectsAndCannotPromoteItselfBackToPosted() = runBlocking {
        app.taskRecovery.await()
        val first = account("bank", "0007"); val second = account("bank", "1234")
        account("wechat"); val cash = account("cash"); app.store.setDefaultWallet(cash.uid)
        val before = balances()
        val id = "SYNTHETIC-BANK-CONFLICT-${UUID.randomUUID()}"
        val original = (recognize("交易类型：网上快捷支付", "交易卡号：尾号0007", id=id) as IngestResult.Saved).record
        assertEquals(mapOf(first.uid to -1200L), original.walletEffects)
        recognize("交易类型：网上快捷支付", "交易卡号：尾号1234", id=id)
        val pending = app.store.get(original.id)!!
        assertEquals(RecordState.REVIEW, pending.state)
        assertEquals("PAYMENT_EVIDENCE_CONFLICT", pending.wallet!!.reviewCode)
        assertNull(pending.wallet!!.accountUid)
        assertTrue(pending.walletEffects.isEmpty())
        assertEquals(before, balances())
        recognize("交易类型：网上快捷支付", "交易卡号：尾号1234", id=id)
        val retried = app.store.get(original.id)!!
        assertEquals(RecordState.REVIEW, retried.state)
        assertEquals(before, balances())
        val token = "SYNTHETIC-CONFLICT-CONFIRM-${UUID.randomUUID()}"
        val posted = app.store.saveEdited(retried, second.uid, true, submissionToken=token)
        assertEquals(mapOf(second.uid to -1200L), posted.walletEffects)
        assertEquals(before + (second.uid to 8800L), balances())
        recognize("交易类型：网上快捷支付", "交易卡号：尾号0007", id=id)
        assertEquals(posted.walletEffects, app.store.get(posted.id)!!.walletEffects)
        assertEquals(second.uid, app.store.get(posted.id)!!.wallet!!.accountUid)
        assertEquals(before + (second.uid to 8800L), balances())
    }

    @Test fun noTailUniqueBankSelectionExplainsItselfAcrossReadsAndJsonZipRestore() = runBlocking {
        app.taskRecovery.await()
        val card = account("bank", "0007"); account("wechat"); val cash = account("cash")
        app.store.setDefaultWallet(cash.uid)
        val before = balances()
        val saved = (recognize("交易类型：网上快捷支付", "账户余额：988.00元", origin="image") as IngestResult.Saved).record
        assertEquals(RecordState.POSTED, saved.state)
        assertEquals(card.uid, saved.wallet!!.accountUid)
        assertEquals(mapOf(card.uid to -1200L), saved.walletEffects)
        assertEquals(before + (card.uid to 8800L), balances())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var bridge: LedgerWebBridge
            scenario.onActivity { bridge = LedgerWebBridge(it) }
            val month = Instant.ofEpochMilli(saved.bill.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString().take(7)
            val detail = JSONObject(bridge.recordDetails(saved.id)).getJSONObject("record")
            val views = listOf(detail) + listOf(bridge.snapshot(), bridge.snapshotFirst(month, "", "", "")).map { response ->
                val rows = JSONObject(response).getJSONArray("records")
                (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id")==saved.id }
            }
            views.forEach { row ->
                assertEquals("UNIQUE_BANK", row.getJSONObject("wallet").getString("selectionReason"))
                val receipt = row.getJSONObject("receipt")
                assertTrue(receipt.isNull("sourceApp"))
                assertEquals("招商银行", receipt.getJSONObject("receiptIssuer").getString("value"))
                assertEquals("cmb", receipt.getJSONObject("bank").getString("bankCode"))
                assertTrue(receipt.getJSONObject("bank").isNull("cardLast4"))
            }
        }
        val channels = ChannelSettings(app)
        val json = LedgerBackup.encode(app.store, channels).toByteArray()
        val zip = ByteArrayOutputStream().also { LedgerArchive.write(app.store, channels, it) }.toByteArray()
        for (bytes in listOf(json, zip)) {
            val name = "bank-restore-${UUID.randomUUID()}.db"
            try { LedgerStore(app, name).use { target ->
                LedgerArchive.read(bytes.inputStream(), app.cacheDir).use { prepared ->
                    LedgerArchive.restore(target, prepared)
                    val restored = target.all().single { it.uid==saved.uid }
                    assertEquals(saved.bill.receipt, restored.bill.receipt)
                    assertEquals(saved.walletEffects, restored.walletEffects)
                    assertEquals("UNIQUE_BANK", WalletReadModel.summary(target, restored).getString("selectionReason"))
                    assertEquals(balances(), target.accounts().associate { it.uid to it.amountCents })
                    val movements = target.walletSnapshot().movements
                    assertEquals(0, LedgerArchive.restore(target, prepared))
                    assertEquals(movements, target.walletSnapshot().movements)
                    assertEquals(balances(), target.accounts().associate { it.uid to it.amountCents })
                }
            } } finally { app.deleteDatabase(name) }
        }
    }

    @Test fun ordinarySuccessSupplementCannotEraseAnUnconfirmedBankAccountGate() = runBlocking {
        app.taskRecovery.await()
        account("bank", "1234"); account("wechat"); val cash = account("cash")
        app.store.setDefaultWallet(cash.uid)
        val before = balances()
        val id = "SYNTHETIC-BANK-PROMOTION-${UUID.randomUUID()}"
        val original = (recognize("交易类型：网上快捷支付", "交易卡号：尾号0007", id=id) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW, original.state)
        recognize("付款方式：微信零钱", id=id, bankReceipt=false)
        val retained = app.store.get(original.id)!!
        assertEquals(RecordState.REVIEW, retained.state)
        assertEquals(original.bill.receipt!!.bank, retained.bill.receipt!!.bank)
        assertNull(retained.wallet!!.accountUid)
        assertTrue(retained.walletEffects.isEmpty())
        assertEquals(before, balances())
    }

    @Test fun multipleSameBankAccountsWithoutATailRemainUnassigned() = runBlocking {
        app.taskRecovery.await()
        account("bank", "0007"); account("bank", "1234"); account("wechat"); val cash=account("cash")
        app.store.setDefaultWallet(cash.uid)
        val before=balances()
        val saved=(recognize("交易类型：网上快捷支付") as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,saved.state)
        assertEquals("PAYMENT_METHOD_AMBIGUOUS",saved.wallet!!.reviewCode)
        assertNull(saved.wallet!!.accountUid)
        assertTrue(saved.walletEffects.isEmpty())
        assertEquals(before,balances())
    }

    @Test fun absentArchivedConflictingAndUnknownBanksNeverChargeFallbackWallets() = runBlocking {
        app.taskRecovery.await()
        account("wechat"); val cash=account("cash");app.store.setDefaultWallet(cash.uid)
        suspend fun pending(expected:String,vararg fields:String,issuer:String="招商银行") {
            val before=balances()
            val saved=(recognize("交易类型：网上快捷支付",*fields,issuer=issuer) as IngestResult.Saved).record
            assertEquals(RecordState.REVIEW,saved.state)
            assertEquals(expected,saved.wallet!!.reviewCode)
            assertNull(saved.wallet!!.accountUid)
            assertTrue(saved.walletEffects.isEmpty())
            assertEquals(before,balances())
        }
        pending("PAYMENT_METHOD_UNMATCHED","交易卡号：尾号0007")
        val archived=account("bank","0007");app.store.archiveAccount(archived.id)
        pending("PAYMENT_METHOD_UNMATCHED","交易卡号：尾号0007")
        pending("PAYMENT_METHOD_AMBIGUOUS","交易卡号：建设银行尾号0007")
        account("bank","0007")
        pending("PAYMENT_METHOD_AMBIGUOUS","交易卡号：尾号0007",issuer="合成银行")
    }

    @Test fun bankAndTailMatchAcrossShakeShortcutAndImageWithoutChangingOtherWallets() = runBlocking {
        app.taskRecovery.await()
        val card=account("bank","0007");account("bank","0007","ccb");account("wechat");val cash=account("cash")
        app.store.setDefaultWallet(cash.uid)
        val before=balances()
        listOf("shake:com.tencent.mm","shortcut:com.tencent.mm","image").forEachIndexed { index,origin ->
            val saved=(recognize("交易类型：网上快捷支付","交易卡号：尾号0007","付款方式：优先使用零钱",origin=origin) as IngestResult.Saved).record
            assertEquals(RecordState.POSTED,saved.state)
            assertEquals(card.uid,saved.wallet!!.accountUid)
            assertEquals(mapOf(card.uid to -1200L),saved.walletEffects)
            assertEquals("BANK_AND_TAIL",WalletReadModel.summary(app.store,saved).getString("selectionReason"))
            assertEquals(before + (card.uid to (10000L-1200L*(index+1))),balances())
            if(origin=="image")assertNull(saved.bill.receipt!!.sourceApp)
            else assertEquals("com.tencent.mm",saved.bill.receipt!!.sourceApp!!.value)
        }
    }

    @Test fun automaticTaskNeverAddsABankNotification() = runBlocking {
        app.taskRecovery.await()
        account("bank","0007");val cash=account("cash");app.store.setDefaultWallet(cash.uid)
        val before=balances();val count=app.store.all().size
        assertEquals(IngestResult.Ignored,recognize("交易类型：网上快捷支付","交易卡号：尾号0007",origin="payment_auto:com.tencent.mm"))
        assertEquals(count,app.store.all().size)
        assertEquals(before,balances())
        assertEquals("NO_BILL",app.tasks.status(tasks.last()))
    }

    @Test fun pendingDirectionRoundtripsJsonZipAndOnlyManualConfirmationAppliesMoneyOnce() = runBlocking {
        app.taskRecovery.await()
        val card=account("bank","0007");account("wechat");val cash=account("cash");app.store.setDefaultWallet(cash.uid)
        val before=balances()
        val saved=(recognize("交易卡号：尾号0007","账户余额：1234.56元") as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,saved.state)
        assertEquals(before,balances())
        val channels=ChannelSettings(app)
        val json=LedgerBackup.encode(app.store,channels).toByteArray()
        val zip=ByteArrayOutputStream().also { LedgerArchive.write(app.store,channels,it) }.toByteArray()
        for(bytes in listOf(json,zip)) {
            val name="pending-bank-${UUID.randomUUID()}.db"
            try { LedgerStore(app,name).use { target ->
                LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                    LedgerArchive.restore(target,prepared)
                    val restored=target.all().single { it.uid==saved.uid }
                    assertEquals(saved.bill.receipt,restored.bill.receipt)
                    assertEquals("UNKNOWN",restored.bill.paymentMethod!!.role)
                    assertEquals("BANK_DIRECTION_REQUIRED",restored.wallet!!.reviewCode)
                    assertEquals(RecordState.REVIEW,restored.state)
                    assertTrue(restored.walletEffects.isEmpty())
                    assertEquals(before,target.accounts().associate { it.uid to it.amountCents })
                    val token="SYNTHETIC-RESTORED-CONFIRM-${UUID.randomUUID()}"
                    val confirm=restored.copy(bill=restored.bill.copy(direction=Direction.EXPENSE))
                    val posted=target.saveEdited(confirm,card.uid,true,submissionToken=token)
                    assertEquals(mapOf(card.uid to -1200L),posted.walletEffects)
                    assertEquals("MANUAL",WalletReadModel.summary(target,posted).getString("selectionReason"))
                    assertEquals(posted,target.saveEdited(confirm,card.uid,true,submissionToken=token))
                    assertEquals(before+(card.uid to 8800L),target.accounts().associate { it.uid to it.amountCents })
                }
            } } finally { app.deleteDatabase(name) }
        }
        assertEquals(before,balances())
    }

    @Test fun explicitTailMismatchKeepsEveryBalanceUntouchedUntilOneManualConfirmation() = runBlocking {
        app.taskRecovery.await()
        val card = account("bank", "1234"); account("wechat"); val cash = account("cash")
        app.store.setDefaultWallet(cash.uid)
        val before = balances()
        val saved = (recognize("交易类型：网上快捷支付", "交易卡号：尾号0007", "账户余额：988.00元") as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW, saved.state)
        assertEquals("PAYMENT_METHOD_UNMATCHED", saved.wallet!!.reviewCode)
        assertNull(saved.wallet!!.accountUid)
        assertTrue(saved.walletEffects.isEmpty())
        assertEquals(before, balances())
        val token = "SYNTHETIC-CONFIRM-${UUID.randomUUID()}"
        val posted = app.store.saveEdited(saved, card.uid, true, submissionToken=token)
        assertEquals(RecordState.POSTED, posted.state)
        assertTrue(posted.wallet!!.manualLocked)
        assertEquals(mapOf(card.uid to -1200L), posted.walletEffects)
        assertEquals(before + (card.uid to 8800L), balances())
        assertEquals(posted, app.store.saveEdited(saved, card.uid, true, submissionToken=token))
        assertEquals(before + (card.uid to 8800L), balances())
    }
}
