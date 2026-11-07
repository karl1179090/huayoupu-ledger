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
import java.time.YearMonth
import java.util.UUID

/** Independent checks use the packaged WebView bridge and real ledger transactions. */
@RunWith(AndroidJUnit4::class)
class TransferUiAcceptanceTest {
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun wallet(kind: String, tail: String? = null) = app.store.saveAccount(LedgerAccount(
        name = "合成独立$kind", amountCents = 100_000, kind = kind, bankCode = if(kind=="bank") "cmb" else null, cardLast4 = tail))
    private fun payload(record: LedgerRecord, from: String, to: String, fee: String, token: String) = JSONObject()
        .put("id",record.id).put("amount","100.00").put("direction","TRANSFER").put("merchant",record.bill.merchant)
        .put("category","转账").put("occurredAt",record.bill.occurredAt).put("note",record.note)
        .put("expectedRevision",record.wallet!!.revision).put("submissionToken",token)
        .put("transfer",JSONObject().put("fromAccountUid",from).put("toAccountUid",to).put("fee",fee).put("feeMode","EXTRA"))
    private fun save(web: WebUiProbe, data: JSONObject) = JSONObject(web.text("LedgerNative.save(${JSONObject.quote(data.toString())})"))
    private fun cleanup(id: Long, wallets: List<LedgerAccount>) {
        app.store.delete(id)
        for(wallet in wallets) {
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid))
            app.store.deleteAccount(wallet.id)
        }
        app.changed()
    }

    @Test fun ambiguousReviewHasTheSameExtensionInEveryProjectionAndRequiresExplicitFee() {
        val a=wallet("bank","0007");val b=wallet("wechat");val at=System.currentTimeMillis()
        val evidence=TransferEvidence(TransferIntent.AMBIGUOUS,terminalState=TransferTerminalState.COMPLETED,
            from=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007"),
            to=PaymentMethodEvidence("WECHAT_BALANCE","CREDIT",platform="wechat"),amountCents=10000,
            reviewCodes=listOf("TRANSFER_IDENTITY_CONFLICT","TRANSFER_FEE_AMBIGUOUS"))
        val original=(app.store.ingest(Capture(Source.IMAGE,PaymentApps.WECHAT,"合成独立待确认",at),
            ParsedBill(10000,null,"合成独立待确认","其他",at,"SYNTHETIC-UI-${UUID.randomUUID()}",false,"请确认交易类型",transfer=evidence)) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val detail=JSONObject(web.text("LedgerNative.recordDetails(${original.id})")).getJSONObject("record")
                val legacy=JSONObject(web.text("LedgerNative.snapshot()")).getJSONArray("records")
                val paged=JSONObject(web.text("LedgerNative.snapshotFirst('${YearMonth.now()}','','','')")).getJSONArray("records")
                val rows=listOf(detail,(0 until legacy.length()).map { legacy.getJSONObject(it) }.single { it.getLong("id")==original.id },
                    (0 until paged.length()).map { paged.getJSONObject(it) }.single { it.getLong("id")==original.id })
                for(row in rows) {
                    assertTrue(row.isNull("direction"));assertTrue(row.get("transfer") is JSONObject)
                    assertTrue(row.getJSONObject("transfer").isNull("feeCents"));assertEquals(0,row.getJSONObject("walletEffects").length())
                }
                web.run("openRecord(${original.id})")
                assertTrue(web.text("document.querySelector('#direction').textContent").contains("待确认"))
                val data=payload(original,a.uid,b.uid,"","SYNTHETIC-CONFIRM-${UUID.randomUUID()}")
                assertFalse(save(web,data).getBoolean("ok"))
                assertEquals(RecordState.REVIEW,app.store.get(original.id)!!.state)
                data.getJSONObject("transfer").put("fee","0")
                assertTrue(save(web,data).getBoolean("ok"))
                assertEquals(RecordState.POSTED,app.store.get(original.id)!!.state)
                assertEquals(90_000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(110_000L,app.store.getAccount(b.id)!!.amountCents)
            }
        } finally {cleanup(original.id,listOf(a,b))}
    }

    @Test fun aSingleChangedEndLocksOnlyThatEndAndBridgeTokensAndRevisionsPreventReplay() {
        val used=app.store.accounts().filter { it.bankCode=="cmb" }.map { it.cardLast4 }.toSet()
        val tail=(0..9999).map { it.toString().padStart(4,'0') }.first { it !in used }
        val a=wallet("bank",tail);val b=wallet("wechat");val c=wallet("cash");val at=System.currentTimeMillis()
        val previousBinding=app.store.walletSettings().appBindings[PaymentApps.WECHAT]
        app.store.setAppWallet(PaymentApps.WECHAT,b.uid)
        val lines=listOf("充值成功","充值金额100.00元","付款方式招商银行储蓄卡（$tail）","到账账户微信零钱","手续费0.00元","微信转账单号SYNTHETIC-${UUID.randomUUID()}")
        val bill=LocalOcrBillParser.parse(lines.map(::LocalOcrLine),0,0,at,origin=PaymentApps.WECHAT)!!
        val original=(app.store.ingest(Capture(Source.IMAGE,PaymentApps.WECHAT,lines.joinToString("\n"),at),bill) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val data=payload(original,c.uid,b.uid,"0","SYNTHETIC-RETRY-${UUID.randomUUID()}")
                assertTrue(save(web,data).getBoolean("ok"))
                val updated=app.store.get(original.id)!!
                assertTrue(updated.transfer!!.fromManualLocked);assertFalse(updated.transfer.toManualLocked);assertFalse(updated.transfer.feeManualLocked)
                val movements=app.store.walletSnapshot().movements.size
                assertEquals(original.id,save(web,data).getLong("id"))
                assertEquals(movements,app.store.walletSnapshot().movements.size)
                data.put("submissionToken","SYNTHETIC-STALE-${UUID.randomUUID()}").getJSONObject("transfer").put("fee","1")
                val stale=save(web,data)
                assertFalse(stale.getBoolean("ok"));assertEquals("STALE_RECORD_REVISION",stale.getString("code"))
                assertEquals(100_000L,app.store.getAccount(a.id)!!.amountCents);assertEquals(90_000L,app.store.getAccount(c.id)!!.amountCents)
                assertEquals(110_000L,app.store.getAccount(b.id)!!.amountCents)
            }
        } finally {app.store.setAppWallet(PaymentApps.WECHAT,previousBinding);cleanup(original.id,listOf(a,b,c))}
    }
}
