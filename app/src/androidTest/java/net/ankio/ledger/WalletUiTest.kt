package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletUiTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private var before=emptySet<Long>()
    private val accounts=mutableListOf<LedgerAccount>()
    @Before fun rememberExistingRecords() { before=app.store.all().map { it.id }.toSet() }
    @After fun cleanup() {
        app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
        synchronized(app.store) {
            val db=app.store.writableDatabase;db.beginTransaction()
            try {
                accounts.forEach { account ->
                    db.delete("wallet_movements","account_uid=?",arrayOf(account.uid))
                    db.delete("app_wallet_bindings","account_uid=?",arrayOf(account.uid))
                    db.delete("accounts","uid=?",arrayOf(account.uid))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        app.changed()
    }
    private fun account(amount:Long=0,bank:Boolean=false):LedgerAccount=app.store.saveAccount(LedgerAccount(
        name="合成UI钱包${UUID.randomUUID().toString().take(6)}",amountCents=amount,kind=if(bank)"bank" else "cash",
        bankCode=if(bank)"cmb" else null,cardLast4=if(bank)"0007" else null)).also(accounts::add)

    @Test fun manualSelectionPostsAndLaterNoteOnlyEditKeepsWalletAndAmount() {
        val selected=account()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("beginManual();chooseWallet('manual')")
            web.run("document.querySelector('[data-wallet-uid=\"${selected.uid}\"]').click()")
            web.run("document.querySelector('#manual-amount').value='30.00';document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
            web.await("!document.querySelector('#manual-panel').open && document.querySelector('#manual-panel').hidden")
            val record=app.store.all().single { it.id !in before }
            assertEquals(RecordState.POSTED,record.state);assertEquals(selected.uid,record.wallet!!.accountUid)
            assertTrue(record.wallet.manualLocked);assertEquals(-3000L,app.store.getAccount(selected.id)!!.amountCents)
            web.run("openRecord(${record.id})")
            web.await("document.querySelector('#record-wallet').textContent.includes('${selected.name}')")
            web.run("document.querySelector('#note').value='只修改合成备注';submitDraft()")
            web.await("!document.querySelector('#record-panel').open")
            assertEquals(selected.uid,app.store.get(record.id)!!.wallet!!.accountUid)
            assertEquals(-3000L,app.store.getAccount(selected.id)!!.amountCents)
            assertEquals("只修改合成备注",app.store.get(record.id)!!.note)
        }
    }

    @Test fun negativeBankBalanceAndLeadingZeroMetadataSurviveUiSaveAndCalibrationConflict() {
        val bank=account(-2000,true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("go('assets');editAccount(${bank.id})")
            assertEquals("0007",web.text("document.querySelector('#account-last4').value"))
            assertEquals("cmb",web.text("document.querySelector('#account-bank-code').value"))
            web.run("document.querySelector('#account-name').value='合成新名称';document.querySelector('#account-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
            web.await("!document.querySelector('#sheet').open")
            assertEquals(-2000L,app.store.getAccount(bank.id)!!.amountCents)
            assertEquals("0007",app.store.getAccount(bank.id)!!.cardLast4)
            web.run("editAccount(${bank.id});document.querySelector('#account-amount').value='-40.00'")
            val concurrent=app.store.getAccount(bank.id)!!
            app.store.saveAccount(concurrent.copy(amountCents=-2500),calibrateBalance=true)
            web.run("document.querySelector('#account-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));document.querySelector('#confirm-calibration').click()")
            web.await("document.querySelector('#account-amount-error').textContent.includes('余额已更新')")
            assertEquals("-40.00",web.text("document.querySelector('#account-amount').value"))
            assertEquals(-2500L,app.store.getAccount(bank.id)!!.amountCents)
            web.run("document.querySelector('#account-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));document.querySelector('#confirm-calibration').click()")
            web.await("!document.querySelector('#sheet').open")
            assertEquals(-4000L,app.store.getAccount(bank.id)!!.amountCents)
        }
    }

    @Test fun pendingWalletSelectionConfirmsOnceAndPagedAndDetailSummariesAgree() {
        val originalDefault=app.store.walletSettings().defaultAccountUid
        app.store.setDefaultWallet(null)
        val now=System.currentTimeMillis()
        val pending=(app.store.ingest(Capture(Source.IMAGE,"image","合成私密识别原文",now),
            ParsedBill(1200,Direction.EXPENSE,"合成待选钱包","购物",now,null,true,"",true)) as IngestResult.Saved).record
        app.store.setDefaultWallet(originalDefault)
        assertEquals("WALLET_REQUIRED",pending.wallet!!.reviewCode)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${pending.id});chooseWallet('record')")
            web.run("document.querySelector('[data-wallet-uid=\"$originalDefault\"]').click();submitDraft()")
            web.await("!document.querySelector('#record-panel').open")
            val posted=app.store.get(pending.id)!!
            assertEquals(RecordState.POSTED,posted.state);assertEquals(-1200L,posted.wallet!!.appliedCents)
            val snapshot=JSONObject(web.text("LedgerNative.snapshot()"))
            val list=snapshot.getJSONArray("records").let { rows -> (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id")==pending.id } }
            val detail=JSONObject(web.text("LedgerNative.recordDetails(${pending.id})")).getJSONObject("record")
            assertEquals(list.getJSONObject("wallet").toString(),detail.getJSONObject("wallet").toString())
            val page=JSONObject(web.text("LedgerNative.snapshotFirst('${java.time.LocalDate.now().toString().take(7)}','','EXPENSE','')"))
            val row=page.getJSONArray("records").let { rows -> (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id")==pending.id } }
            assertEquals(posted.wallet.accountUid,row.getJSONObject("wallet").getString("accountUid"))
            assertEquals(posted.wallet.appliedCents,row.getJSONObject("wallet").getLong("appliedCents"))
            assertFalse(row.toString().contains("合成私密识别原文"))
        }
    }
}
