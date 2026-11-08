package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TransferBackupTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val stores=mutableListOf<Pair<String,LedgerStore>>();private val now=1_793_937_600_000L
    private fun store():LedgerStore { val name="transfer-backup-${UUID.randomUUID()}.db";return LedgerStore(context,name).also { stores.add(name to it) } }
    @After fun cleanup() { stores.forEach { (name,s)->
        val ids=s.readableDatabase.rawQuery("SELECT id FROM records",null).use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } }
        s.releaseImportedMedia(ids);s.close();context.deleteDatabase(name)
    } }
    private fun asset(s:LedgerStore,balance:Long=100000,name:String="合成钱包",kind:String="cash")=s.saveAccount(LedgerAccount(name=name,amountCents=balance,kind=kind,
        bankCode=if(kind=="bank")"cmb" else null,cardLast4=if(kind=="bank")"0007" else null))
    private fun bill(amount:Long=10000,direction:Direction=Direction.EXPENSE,id:String?=null)=LedgerRecord(capture=Capture(Source.MANUAL,"manual","合成备份",now),
        bill=ParsedBill(amount,direction,"合成商户","购物",now,id,true,"",true),state=RecordState.POSTED)
    private fun transfer(s:LedgerStore,a:LedgerAccount,b:LedgerAccount,fee:Long=100,mode:FeeMode=FeeMode.EXTRA)=bill(direction=Direction.TRANSFER).let { r->
        s.saveEdited(r.copy(transfer=RecordTransferDetails(r.uid,a.uid,b.uid,fee,mode,fromReference=WalletFileReference.of(a).json(),toReference=WalletFileReference.of(b).json()))) }
    private fun encoded(s:LedgerStore)=LedgerBackup.encode(s,ChannelSettings(context))
    private fun backup(s:LedgerStore)=LedgerBackup.decode(encoded(s).byteInputStream())
    private fun zip(s:LedgerStore)=ByteArrayOutputStream().also { LedgerArchive.write(s,ChannelSettings(context),it) }.toByteArray()
    private fun balance(s:LedgerStore,a:LedgerAccount)=s.getAccountByUid(a.uid)!!.amountCents
    private fun reject(block:()->Unit) { var failed=false;try { block() } catch(_:Exception) { failed=true };assertTrue("invalid backup must fail",failed) }
    private fun recognition(amount:Long=10000,fee:Long=100,from:PaymentMethodEvidence=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007"),
        to:PaymentMethodEvidence=PaymentMethodEvidence("WECHAT_BALANCE","CREDIT",platform="wechat"),intent:TransferIntent=TransferIntent.SELF_TRANSFER)=
        ParsedBill(amount,if(intent==TransferIntent.SELF_TRANSFER)Direction.TRANSFER else null,"合成充值","转账",now,"SYNTHETIC-BACKUP-${UUID.randomUUID()}",true,"",true,
            transfer=TransferEvidence(intent,TransferScenario.TOP_UP,TransferTerminalState.COMPLETED,from,to,amount,fee,FeeMode.EXTRA,
                amountLabels=listOf(TransferAmountEvidence("AMOUNT","充值金额",amount),TransferAmountEvidence("FEE","手续费",fee))))
    private fun ingest(s:LedgerStore,b:ParsedBill)=(s.ingest(Capture(Source.OCR,PaymentApps.WECHAT,"合成充值完成页面",now),b) as IngestResult.Saved).record

    @Test fun B04_CompleteV5CopiesAbsoluteBalancesEffectsAndOriginalAuditWithoutReplay() {
        val source=store();val a=asset(source,-5000);val b=asset(source,20000);val saved=transfer(source,a,b,mode=FeeMode.DEDUCTED)
        val json=JSONObject(encoded(source));assertEquals(6,json.getInt("version"));assertEquals(2,json.getJSONObject("wallets").getJSONArray("effects").length())
        assertFalse(json.getJSONObject("wallets").getJSONArray("links").getJSONObject(0).has("appliedCents"))
        val target=store();val summary=LedgerBackup.restoreDetailed(target,LedgerBackup.decode(json.toString().byteInputStream()))
        assertEquals(1,summary.imported);assertEquals(balance(source,a),balance(target,a));assertEquals(balance(source,b),balance(target,b))
        val restored=target.all().single();assertEquals(saved.transfer,restored.transfer);assertEquals(saved.walletEffects,restored.walletEffects)
        val count=target.walletSnapshot().movements.size;assertEquals(0,LedgerBackup.restore(target,backup(source)));assertEquals(count,target.walletSnapshot().movements.size)
        assertEquals(2,target.walletSnapshot().movements.count { it.kind=="RESTORE_SNAPSHOT" && it.deltaCents==0L })
        target.delete(restored.id);assertEquals(-5000L,balance(target,a));assertEquals(20000L,balance(target,b))
    }
    @Test fun B04_S4OverflowReviewRoundTripsThroughJsonAndZipWithCompleteEvidence() {
        val source=store();asset(source,kind="bank");asset(source,kind="wechat")
        val pending=ingest(source,recognition(Money.MAX_CENTS,1));assertEquals(RecordState.REVIEW,pending.state);assertTrue(pending.walletEffects.isEmpty())
        val target=store();LedgerBackup.restore(target,backup(source));val restored=target.all().single()
        assertEquals(pending.bill.transfer,restored.bill.transfer);assertEquals(pending.transfer,restored.transfer);assertTrue(restored.walletEffects.isEmpty())
        val zipTarget=store();LedgerArchive.read(zip(source).inputStream(),context.cacheDir).use { LedgerArchive.restore(zipTarget,it) }
        assertEquals(pending.bill.transfer,zipTarget.all().single().bill.transfer);assertTrue(zipTarget.all().single().walletEffects.isEmpty())
    }
    @Test fun B04_AmbiguousSameWalletAndIncompleteRefundReviewsRemainReviewAndHaveNoEffects() {
        val source=store();asset(source,kind="bank");asset(source,kind="wechat")
        val ambiguous=ingest(source,recognition(intent=TransferIntent.AMBIGUOUS));assertNull(ambiguous.bill.direction)
        val same=ingest(source,recognition(from=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007"),to=PaymentMethodEvidence("BANK_CARD","CREDIT","cmb","0007")))
        assertEquals(RecordState.REVIEW,same.state);assertEquals(same.transfer!!.fromAccountUid,same.transfer.toAccountUid)
        val refund=ParsedBill(4000,Direction.EXPENSE,"合成退款","购物",now,"SYNTHETIC-REVIEW-REFUND",true,"",true,RefundInfo("wechat",10000,5000))
        val refundPending=(source.ingest(Capture(Source.OCR,PaymentApps.WECHAT,"合成退款成功",now),refund) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,refundPending.state)
        val target=store();LedgerBackup.restore(target,backup(source));assertEquals(3,target.pending().size)
        assertTrue(target.all().all { it.walletEffects.isEmpty() });assertEquals(ambiguous.bill.transfer,target.all().single { it.uid==ambiguous.uid }.bill.transfer)
    }
    @Test fun B04_ManualCorrectionKeepsOriginalRecognitionEvidenceIndependentOfCurrentFacts() {
        val source=store();asset(source,kind="bank");asset(source,kind="wechat");val original=ingest(source,recognition())
        val edited=source.saveEdited(original.copy(bill=original.bill.copy(amountCents=12000),transfer=original.transfer!!.copy(feeCents=200)))
        assertEquals(100L,edited.bill.transfer!!.feeCents);assertEquals(200L,edited.transfer!!.feeCents);assertTrue(edited.transfer.feeManualLocked)
        val target=store();LedgerBackup.restore(target,backup(source));val restored=target.all().single()
        assertEquals(edited.bill.transfer,restored.bill.transfer);assertEquals(edited.transfer,restored.transfer);assertEquals(edited.walletEffects,restored.walletEffects)
    }
    @Test fun B06_NewMixedAndExistingWalletGroupsQualifyAsWholeTransfers() {
        val source=store();val a=asset(source);val b=asset(source,20000);val original=transfer(source,a,b);val prepared=backup(source)
        for(existing in 0..2) { val target=store()
            if(existing>=1)target.saveAccount(a.copy(id=0,amountCents=50000,revision=0))
            if(existing==2)target.saveAccount(b.copy(id=0,amountCents=70000,revision=0))
            LedgerBackup.restore(target,prepared);val restored=target.all().single()
            assertEquals(if(existing==0)WalletEligibility.ACTIVE else WalletEligibility.RESTORE_EXCLUDED,restored.wallet!!.eligibility)
            assertEquals(if(existing==0)original.walletEffects else emptyMap<String,Long>(),restored.walletEffects)
            val before=target.accounts().associate { it.uid to it.amountCents }
            if(existing>0) { target.saveEdited(restored.copy(bill=restored.bill.copy(amountCents=12000)));target.delete(restored.id);assertEquals(before,target.accounts().associate { it.uid to it.amountCents }) }
        }
    }
    @Test fun B06_ZeroEffectAndPartialReviewReferencesStillPreventPartialActivation() {
        val source=store();val a=asset(source,kind="bank")
        val pending=ingest(source,recognition());assertEquals(RecordState.REVIEW,pending.state);assertEquals(a.uid,pending.transfer!!.fromAccountUid)
        val target=store();target.saveAccount(a.copy(id=0,amountCents=50000,revision=0));LedgerBackup.restore(target,backup(source))
        val restored=target.all().single();assertEquals(WalletEligibility.RESTORE_EXCLUDED,restored.wallet!!.eligibility)
        val b=asset(target,20000,kind="wechat");target.saveEdited(restored.copy(transfer=restored.transfer!!.copy(toAccountUid=b.uid)))
        assertEquals(50000L,balance(target,a));assertEquals(20000L,balance(target,b));assertTrue(target.all().single().walletEffects.isEmpty())
    }
    @Test fun B07_StrongIdentityAcrossDifferentUidsAndDirectionsNeverAddsAnotherRecordOrEffect() {
        val source=store();val a=asset(source);val b=asset(source,20000);val seed=bill(direction=Direction.TRANSFER,id="SYNTHETIC-SHARED-ORDER").copy(capture=Capture(Source.OCR,PaymentApps.WECHAT,"合成强身份",now))
        source.saveEdited(seed.copy(transfer=RecordTransferDetails(seed.uid,a.uid,b.uid,100,FeeMode.EXTRA)))
        val target=store();val local=asset(target,50000);val existing=target.saveEdited(bill(id="SYNTHETIC-SHARED-ORDER").copy(capture=seed.capture),local.uid,true)
        val before=balance(target,local);val summary=LedgerBackup.restoreDetailed(target,backup(source))
        assertEquals(0,summary.imported);assertEquals(1,summary.identityConflicts);assertEquals(listOf(existing.uid),target.all().map { it.uid });assertEquals(before,balance(target,local))
        assertTrue(target.readableDatabase.rawQuery("SELECT 1 FROM record_wallet_effects WHERE record_uid=?",arrayOf(seed.uid)).use { !it.moveToFirst() })
    }
    @Test fun B04_B06_ExcludedReferencesAliasesAndCommittedAuditRestoreWithoutExecutableTokens() {
        val source=store();val a=asset(source);val foreign=LedgerAccount(name="合成历史未匹配端",amountCents=0);val row=bill(direction=Direction.TRANSFER).let { r->r.copy(transfer=RecordTransferDetails(r.uid,a.uid,foreign.uid,100,FeeMode.EXTRA,
            fromReference=WalletFileReference.of(a).json(),toReference=WalletFileReference.of(foreign).json())) }
        val document=LedgerCsvImport.read(ByteArrayOutputStream().also { LedgerCsvExport.write(listOf(row),LedgerCsvExport.Format.GENERAL,it) }.toByteArray().inputStream())
        val preview=LedgerCsvImport.prepare(source,document,submissionToken="SYNTHETIC-NON-MIGRATABLE-TOKEN");val result=LedgerCsvImport.applyPreview(source,document,preview)
        val alias=UUID.randomUUID().toString();source.saveWalletImportAlias(alias,a.uid)
        val target=store();LedgerBackup.restore(target,backup(source));val restored=target.all().single()
        assertEquals(WalletEligibility.IMPORT_EXCLUDED,restored.wallet!!.eligibility);assertNull(restored.transfer!!.toAccountUid);assertNotNull(restored.transfer.toReference)
        assertTrue(restored.walletEffects.isEmpty());assertEquals(a.uid,target.walletImportAliases()[alias]);assertNull(target.importReceipt(preview.plan!!.submissionToken))
        assertEquals(0L,target.readableDatabase.rawQuery("SELECT COUNT(*) FROM import_batches",null).use { it.moveToFirst();it.getLong(0) })
        val audit=target.walletSnapshot().audits.single();assertEquals(result,LedgerCsvImport.Result.decode(audit.summary));assertEquals(result.recordUids.toSet(),audit.recordUids.toSet())
        val conflicted=store();conflicted.saveAccount(a.copy(id=0,amountCents=50000,revision=0));val other=asset(conflicted,70000);conflicted.saveWalletImportAlias(alias,other.uid)
        val summary=LedgerBackup.restoreDetailed(conflicted,backup(source));assertEquals(1,summary.aliasConflicts);assertEquals(other.uid,conflicted.walletImportAliases()[alias])
    }
    @Test fun B05_V4AppliedFactsConvertToEffectsAndOlderVersionsRemainExcluded() {
        val source=store();val a=asset(source);val original=source.saveEdited(bill(30000),a.uid,true)
        fun legacy(version:Int):JSONObject {
            val j=JSONObject(encoded(source));j.put("version",version)
            if(version==4) { val w=j.getJSONObject("wallets");val snapshot=source.walletSnapshot();val links=w.getJSONArray("links")
                for(i in 0 until links.length()) { val link=links.getJSONObject(i);val old=snapshot.links.single { it.recordUid==link.getString("recordUid") }
                    link.put("appliedAccountUid",old.appliedAccountUid ?: JSONObject.NULL).put("appliedCents",old.appliedCents);link.remove("accountReference") }
                listOf("version","effects","transfers","aliases","audits").forEach(w::remove)
            } else { j.remove("wallets");if(version==1) { j.remove("accounts");j.remove("customCategories") } }
            return j
        }
        for(version in 1..4) { val target=store();val decoded=LedgerBackup.decode(legacy(version).toString().byteInputStream());LedgerBackup.restore(target,decoded)
            val r=target.all().single();assertEquals(original.uid,r.uid)
            if(version==4) { assertEquals(mapOf(a.uid to -30000L),r.walletEffects);target.delete(r.id);assertEquals(100000L,balance(target,a)) }
            else { assertEquals(WalletEligibility.LEGACY_EXCLUDED,r.wallet!!.eligibility);assertTrue(r.walletEffects.isEmpty());if(version>1)assertEquals(70000L,balance(target,a)) }
        }
    }
    @Test fun B08_InvalidFeesTypesDuplicateEffectsAndMissingAccountsRejectBeforeChangingLocalLedger() {
        val source=store();val a=asset(source);val b=asset(source,20000);transfer(source,a,b);val json=encoded(source)
        val target=store();val local=asset(target,77777);val variants=listOf<(JSONObject)->Unit>(
            { it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("feeCents",-1) },
            { it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("feeCents",1.5) },
            { it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("feeMode","UNKNOWN") },
            { val e=it.getJSONObject("wallets").getJSONArray("effects");e.put(e.getJSONObject(0)) },
            { it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0).put("eligibility","IMPORT_EXCLUDED") },
            { it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("toAccountUid",UUID.randomUUID().toString()) })
        variants.forEach { mutation->val altered=JSONObject(json);mutation(altered);reject { LedgerBackup.restore(target,LedgerBackup.decode(altered.toString().byteInputStream())) }
            assertTrue(target.all().isEmpty());assertEquals(77777L,balance(target,local)) }
    }
    @Test fun B08_MediaTamperingAndDatabaseFailuresRollBackAllFinancialAndMediaFacts() {
        val source=store();val a=asset(source);val b=asset(source,20000);val r=transfer(source,a,b);source.attachScreenshot(r.id,syntheticBackupImage(1))
        val target=store();val local=asset(target,77777);val before=ScreenshotStore.get(context).stats().getInt("count")
        LedgerArchive.read(zip(source).inputStream(),context.cacheDir).use { prepared->prepared.files.values.first().writeBytes(byteArrayOf(1,2,3));reject { LedgerArchive.restore(target,prepared) } }
        assertEquals(before,ScreenshotStore.get(context).stats().getInt("count"));assertTrue(target.all().isEmpty());assertEquals(77777L,balance(target,local))
        target.writableDatabase.execSQL("CREATE TRIGGER backup_reject_effect BEFORE INSERT ON record_wallet_effects BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        reject { LedgerBackup.restore(target,backup(source)) };assertTrue(target.all().isEmpty());assertEquals(listOf(local),target.accounts());assertEquals(before,ScreenshotStore.get(context).stats().getInt("count"))
    }
    @Test fun B04_ZipIsAnImmutableFinancialAndMediaSnapshotWhileAnotherThreadWrites() {
        val source=store();val a=asset(source);val b=asset(source,20000);val r=transfer(source,a,b);val red=syntheticBackupImage(1);source.attachScreenshot(r.id,red)
        var changed=false;val pool=Executors.newSingleThreadExecutor()
        try { val output=object:ByteArrayOutputStream() {
            private fun mutate() { if(!changed) { changed=true;pool.submit { source.saveEdited(source.get(r.id)!!.copy(bill=r.bill.copy(amountCents=12000)));source.attachScreenshot(r.id,syntheticBackupImage(2)) }.get(5,TimeUnit.SECONDS) } }
            override fun write(value:Int) { mutate();super.write(value) }
            override fun write(bytes:ByteArray,off:Int,len:Int) { mutate();super.write(bytes,off,len) }
        }
            LedgerArchive.write(source,ChannelSettings(context),output)
            val target=store();LedgerArchive.read(output.toByteArray().inputStream(),context.cacheDir).use { LedgerArchive.restore(target,it) }
            val restored=target.all().single();assertEquals(10000L,restored.bill.amountCents);assertArrayEquals(red,target.screenshot(restored.id));assertEquals(89900L,balance(target,a));assertEquals(30000L,balance(target,b))
            assertEquals(12000L,source.get(r.id)!!.bill.amountCents)
        } finally { pool.shutdownNow() }
    }
}
