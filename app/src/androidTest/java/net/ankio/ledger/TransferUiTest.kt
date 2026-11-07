package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransferUiTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun wallet(amount:Long)=app.store.saveAccount(LedgerAccount(name="合成互转${UUID.randomUUID().toString().take(5)}",amountCents=amount))
    @Test fun manualTransferEditDeleteUndoAndSnapshotProjectionsUseOneRecord() {
        val a=wallet(100000);val b=wallet(20000);val marker="合成界面互转${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("beginManual();document.querySelector('#manual-type [data-type=transfer]').click();document.querySelector('#manual-amount').value='100';document.querySelector('#manual-note').value='$marker';document.querySelector('#manual-from').value='${a.uid}';document.querySelector('#manual-to').value='${b.uid}';document.querySelector('#manual-fee').value='1';document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("!manualOpen")
                val saved=app.store.all().single { it.note==marker }
                assertEquals(Direction.TRANSFER,saved.bill.direction);assertEquals(89900L,app.store.getAccount(a.id)!!.amountCents);assertEquals(30000L,app.store.getAccount(b.id)!!.amountCents)
                val detail=JSONObject(web.text("LedgerNative.recordDetails(${saved.id})")).getJSONObject("record")
                val legacy=JSONObject(web.text("LedgerNative.snapshot()")).getJSONArray("records")
                val legacyRow=(0 until legacy.length()).map { legacy.getJSONObject(it) }.single { it.getLong("id")==saved.id }
                val paged=JSONObject(web.text("LedgerNative.snapshotFirst('${java.time.YearMonth.now()}','','TRANSFER','')")).getJSONArray("records")
                val pagedRow=(0 until paged.length()).map { paged.getJSONObject(it) }.single { it.getLong("id")==saved.id }
                for(row in listOf(detail,legacyRow,pagedRow)) {
                    assertEquals(a.uid,row.getJSONObject("transfer").getString("fromAccountUid"));assertEquals(10100L,row.getJSONObject("transfer").getLong("debitCents"))
                    assertEquals(-10100L,row.getJSONObject("walletEffects").getLong(a.uid))
                }
                web.run("openRecord(${saved.id});document.querySelector('#record-fee-mode').value='DEDUCTED';document.querySelector('#record-fee-mode').dispatchEvent(new Event('input',{bubbles:true}));submitDraft()")
                web.await("!recordPanelOpen")
                val edited=app.store.get(saved.id)!!
                assertEquals(90000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(29900L,app.store.getAccount(b.id)!!.amountCents)
                assertTrue(edited.transfer!!.feeManualLocked)
                web.run("deleteEntry(${saved.id})");web.await("document.querySelector('#delete-undo')")
                assertEquals(100000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(20000L,app.store.getAccount(b.id)!!.amountCents)
                web.run("document.querySelector('#delete-undo button').click()")
                web.await("!document.querySelector('#delete-undo')")
                assertEquals(90000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(29900L,app.store.getAccount(b.id)!!.amountCents)
            }
        } finally {app.store.all().filter { it.note==marker }.forEach { app.store.delete(it.id) };app.changed()}
    }
    @Test fun completingExcludedTransferAndChangingTypesPreserveItsPermanentExclusion() {
        val a=wallet(100000);val b=wallet(20000);val uid=UUID.randomUUID().toString();val marker="合成不联动${UUID.randomUUID()}"
        val at=System.currentTimeMillis()
        val original=app.store.importTransactions(listOf(LedgerRecord(uid=uid,capture=Capture(Source.IMAGE,"csv","合成互转",at),
            bill=ParsedBill(10000,Direction.TRANSFER,"本人互转","转账",at,null,true,""),state=RecordState.POSTED,note=marker,
            transfer=RecordTransferDetails(uid,a.uid,b.uid,100,FeeMode.EXTRA)))).single()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${original.id})")
                assertTrue(web.text("document.querySelector('#wallet-explanation').textContent").contains("导入未联动"))
                web.run("document.querySelector('#direction').dataset.value='INCOME';submitDraft()")
                web.await("!recordPanelOpen")
                val changed=app.store.get(original.id)!!
                assertEquals(Direction.INCOME,changed.bill.direction);assertNull(changed.transfer);assertTrue(changed.walletEffects.isEmpty())
                assertEquals(WalletEligibility.IMPORT_EXCLUDED,changed.wallet!!.eligibility)
                assertEquals(100000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(20000L,app.store.getAccount(b.id)!!.amountCents)
            }
        } finally {app.store.delete(original.id);app.changed()}
    }
    @Test fun bridgeRejectsForgedEffectsMissingRevisionAndRefundToTransferConversion() {
        val a=wallet(100000);val b=wallet(20000);val at=System.currentTimeMillis();val uid=UUID.randomUUID().toString()
        val original=app.store.importTransactions(listOf(LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"manual","合成退款",at),
            bill=ParsedBill(7000,Direction.EXPENSE,"合成退款商户","餐饮",at,null,true,"",refund=RefundInfo("wechat",10000,3000)),state=RecordState.POSTED))).single()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val payload=JSONObject().put("id",original.id).put("amount","70.00").put("direction","TRANSFER").put("merchant","合成退款商户")
                    .put("category","餐饮").put("occurredAt",at).put("note","").put("expectedRevision",original.wallet!!.revision)
                    .put("submissionToken","synthetic-refund-${UUID.randomUUID()}").put("transfer",JSONObject().put("fromAccountUid",a.uid).put("toAccountUid",b.uid).put("fee","0").put("feeMode","EXTRA"))
                val response=JSONObject(web.text("LedgerNative.save(${JSONObject.quote(payload.toString())})"));assertFalse(response.getBoolean("ok"))
                val missingRevision=JSONObject(payload.toString()).put("direction","EXPENSE").also { it.remove("expectedRevision");it.remove("transfer") }
                assertFalse(JSONObject(web.text("LedgerNative.save(${JSONObject.quote(missingRevision.toString())})")).getBoolean("ok"))
                val forged=payload.put("walletEffects",JSONObject().put(a.uid,-1))
                assertFalse(JSONObject(web.text("LedgerNative.save(${JSONObject.quote(forged.toString())})")).getBoolean("ok"))
                assertEquals(7000L,app.store.get(original.id)!!.bill.amountCents);assertNotNull(app.store.get(original.id)!!.bill.refund)
                assertEquals(100000L,app.store.getAccount(a.id)!!.amountCents)
            }
        } finally {app.store.delete(original.id);app.changed()}
    }
    @Test fun pendingRecognitionRequiresExplicitFeeConfirmationThenPostsBothSelectedEndsTogether() {
        val a=app.store.saveAccount(LedgerAccount(name="合成待确认出",amountCents=100000,kind="bank",bankCode="icbc",cardLast4="8765"))
        val b=app.store.saveAccount(LedgerAccount(name="合成待确认入",amountCents=20000,kind="bank",bankCode="icbc",cardLast4="8766"))
        val at=System.currentTimeMillis()
        val evidence=TransferEvidence(TransferIntent.SELF_TRANSFER,TransferScenario.OWN_ACCOUNTS,TransferTerminalState.COMPLETED,
            PaymentMethodEvidence("BANK_CARD","DEBIT","icbc","8765"),PaymentMethodEvidence("BANK_CARD","CREDIT","icbc","8766"),
            amountCents=10000,reviewCodes=listOf("TRANSFER_FEE_AMBIGUOUS"))
        val bill=ParsedBill(10000,Direction.TRANSFER,"合成待确认互转","转账",at,"SYNTHETIC-FEE-${UUID.randomUUID()}",false,"请确认手续费",transfer=evidence)
        val pending=(app.store.ingest(Capture(Source.IMAGE,PaymentApps.WECHAT,"合成缺费",at),bill) as IngestResult.Saved).record
        try {
            assertEquals(RecordState.REVIEW,pending.state)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${pending.id})")
                assertEquals("",web.text("document.querySelector('#record-fee').value"))
                web.run("submitDraft()")
                assertEquals(RecordState.REVIEW,app.store.get(pending.id)!!.state)
                assertEquals(100000L,app.store.getAccount(a.id)!!.amountCents)
                web.run("document.querySelector('#record-fee').value='0';document.querySelector('#record-fee').dispatchEvent(new Event('input',{bubbles:true}));submitDraft()")
                web.await("!recordPanelOpen")
                val saved=app.store.get(pending.id)!!
                assertEquals(RecordState.POSTED,saved.state);assertEquals(0L,saved.transfer!!.feeCents)
                assertEquals(90000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(30000L,app.store.getAccount(b.id)!!.amountCents)
            }
        } finally {app.store.delete(pending.id);app.changed()}
    }

}
