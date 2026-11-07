package net.ankio.ledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Independent restore checks use real synthetic images and private temporary databases. */
@RunWith(AndroidJUnit4::class)
class TransferBackupAcceptanceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val stores=mutableListOf<Pair<String,LedgerStore>>()
    private val cache=File(context.cacheDir,"backup-independent-${UUID.randomUUID()}").apply {mkdirs()}
    private val now=1_793_937_600_000L
    private fun store():LedgerStore {val name="backup-independent-${UUID.randomUUID()}.db";return LedgerStore(context,name).also {stores.add(name to it)}}
    private fun wallet(s:LedgerStore,name:String="合成资产",uid:String=UUID.randomUUID().toString(),kind:String="cash",tail:String?=null)=
        s.saveAccount(LedgerAccount(uid=uid,name=name,amountCents=50000,kind=kind,bankCode=if(kind=="bank")"cmb" else null,cardLast4=tail))
    private fun bill(direction:Direction=Direction.EXPENSE,amount:Long=1000)=LedgerRecord(capture=Capture(Source.MANUAL,"synthetic","合成独立证据",now),
        bill=ParsedBill(amount,direction,"合成独立备份商户","其他",now,null,true,"",true),state=RecordState.POSTED)
    private fun transfer(s:LedgerStore,a:LedgerAccount,b:LedgerAccount)=bill(Direction.TRANSFER).let {s.saveEdited(it.copy(
        transfer=RecordTransferDetails(it.uid,a.uid,b.uid,100,FeeMode.EXTRA)))}
    private fun encoded(s:LedgerStore)=LedgerBackup.encode(s,ChannelSettings(context))
    private fun decoded(s:LedgerStore)=LedgerBackup.decode(encoded(s).byteInputStream())
    private fun archive(s:LedgerStore)=ByteArrayOutputStream().also {LedgerArchive.write(s,ChannelSettings(context),it)}.toByteArray()
    private fun replaceManifestUid(bytes:ByteArray,old:String,new:String)=ByteArrayOutputStream().also {output->
        ZipInputStream(bytes.inputStream()).use {input->ZipOutputStream(output).use {zip->while(true){
            val entry=input.nextEntry ?: break;val raw=input.readBytes()
            zip.putNextEntry(ZipEntry(entry.name));zip.write(if(entry.name=="manifest.json")raw.toString(Charsets.UTF_8).replace(old,new).toByteArray() else raw);zip.closeEntry()
        }}}
    }.toByteArray()
    private fun image(color:Int)=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).let {bitmap->bitmap.eraseColor(color)
        ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle()}.toByteArray()}
    private fun evidence(intent:TransferIntent=TransferIntent.SELF_TRANSFER,to:PaymentMethodEvidence?=null)=TransferEvidence(intent,
        scenario=TransferScenario.OWN_ACCOUNTS,terminalState=TransferTerminalState.COMPLETED,
        from=PaymentMethodEvidence("BANK_CARD","DEBIT","cmb","0007"),
        to=to ?: PaymentMethodEvidence("BANK_CARD","CREDIT","cmb","0007"),amountCents=1000,feeCents=0,feeMode=FeeMode.EXTRA,
        debitCents=1000,creditCents=1000,amountLabels=listOf(TransferAmountEvidence("AMOUNT","转账金额",1000)))
    private fun recognized(s:LedgerStore,e:TransferEvidence):LedgerRecord {
        val b=bill(Direction.TRANSFER).bill.copy(amountCents=e.amountCents,direction=if(e.intent==TransferIntent.AMBIGUOUS)null else Direction.TRANSFER,transfer=e,
            transactionId="SYNTHETIC-${UUID.randomUUID()}")
        return (s.ingest(Capture(Source.IMAGE,"synthetic.bank","本人账户合成详情",now),b) as IngestResult.Saved).record
    }
    private fun rejected(block:()->Unit){assertNotNull("Invalid backup was accepted",runCatching(block).exceptionOrNull())}
    private fun assertTransferEquals(expected:RecordTransferDetails,actual:RecordTransferDetails) {
        // JSON null members may be normalized; compare the complete evidence values, not member order.
        fun plain(d:RecordTransferDetails)=d.copy(fromEvidence=null,toEvidence=null,fromReference=null,toReference=null,recognitionEvidence=null)
        assertEquals(plain(expected),plain(actual))
        for((left,right) in listOf(expected.fromEvidence to actual.fromEvidence,expected.toEvidence to actual.toEvidence))
            assertEquals(left?.let {WalletBackupCodec.evidence(JSONObject(it))},right?.let {WalletBackupCodec.evidence(JSONObject(it))})
        assertEquals(WalletFileReference.from(expected.fromReference),WalletFileReference.from(actual.fromReference))
        assertEquals(WalletFileReference.from(expected.toReference),WalletFileReference.from(actual.toReference))
        assertEquals(expected.recognitionEvidence?.let(TransferEvidenceCodec::decode),actual.recognitionEvidence?.let(TransferEvidenceCodec::decode))
    }
    @After fun cleanup(){stores.forEach {(name,s)->s.purgeExpiredDeletes(Long.MAX_VALUE);s.all().forEach {s.delete(it.id)};s.close();context.deleteDatabase(name)};cache.deleteRecursively()}

    @Test fun actualSameWalletRecognitionReviewSurvivesJsonAndZipWithoutFunding() {
        val source=store();val a=wallet(source,kind="bank",tail="0007");val review=recognized(source,evidence())
        assertEquals(RecordState.REVIEW,review.state);assertEquals("TRANSFER_SAME_WALLET",review.transfer!!.reviewCode)
        assertEquals(a.uid,review.transfer!!.fromAccountUid);assertEquals(a.uid,review.transfer!!.toAccountUid);assertTrue(review.walletEffects.isEmpty())
        val jsonTarget=store();LedgerBackup.restore(jsonTarget,decoded(source))
        LedgerArchive.read(archive(source).inputStream(),cache).use {prepared->val zipTarget=store();LedgerArchive.restore(zipTarget,prepared)
            for(target in listOf(jsonTarget,zipTarget)) {val restored=target.all().single()
                assertTransferEquals(review.transfer!!,restored.transfer!!);assertEquals(review.bill.transfer,restored.bill.transfer)
                assertEquals(RecordState.REVIEW,restored.state);assertTrue(restored.walletEffects.isEmpty());assertEquals(50000L,target.getAccountByUid(a.uid)!!.amountCents)
            }
        }
    }

    @Test fun ambiguousReviewAndManuallyCorrectedCurrentFactsKeepIndependentOriginalEvidence() {
        val source=store();val a=wallet(source,kind="bank",tail="0007");val b=wallet(source,"合成微信",kind="wechat")
        val ambiguous=recognized(source,evidence(TransferIntent.AMBIGUOUS))
        assertNull(ambiguous.bill.direction);assertNull(ambiguous.transfer!!.fromAccountUid);assertNull(ambiguous.transfer!!.toAccountUid)
        val zero=recognized(source,evidence().copy(amountCents=0,debitCents=0,creditCents=0))
        assertEquals(RecordState.REVIEW,zero.state);assertTrue(zero.walletEffects.isEmpty())
        val original=evidence(to=PaymentMethodEvidence("WECHAT_BALANCE","CREDIT",platform="wechat"))
        val posted=recognized(source,original);assertEquals(RecordState.POSTED,posted.state)
        val edited=source.saveEdited(posted.copy(bill=posted.bill.copy(amountCents=10000),transfer=posted.transfer!!.copy(feeCents=100)))
        val target=store();LedgerBackup.restore(target,decoded(source));val restored=target.all().associateBy {it.uid}
        assertTransferEquals(ambiguous.transfer!!,restored.getValue(ambiguous.uid).transfer!!);assertTrue(restored.getValue(ambiguous.uid).walletEffects.isEmpty())
        assertEquals(0L,restored.getValue(zero.uid).bill.amountCents);assertEquals(RecordState.REVIEW,restored.getValue(zero.uid).state)
        assertTrue(restored.getValue(zero.uid).walletEffects.isEmpty())
        val current=restored.getValue(edited.uid);assertEquals(10000L,current.bill.amountCents);assertEquals(100L,current.transfer!!.feeCents)
        assertEquals(original,current.bill.transfer);assertEquals(edited.walletEffects,current.walletEffects)
        assertEquals(source.getAccountByUid(a.uid)!!.amountCents,target.getAccountByUid(a.uid)!!.amountCents)
        assertEquals(source.getAccountByUid(b.uid)!!.amountCents,target.getAccountByUid(b.uid)!!.amountCents)
    }

    @Test fun pendingRefundWithIncompleteNetRemainsReviewAndDoesNotBreakCompleteBackup() {
        val source=store();val a=wallet(source);source.setDefaultWallet(a.uid)
        val incoming=bill().bill.copy(amountCents=4000,refund=RefundInfo("wechat",10000,5000),reliable=false)
        val review=(source.ingest(Capture(Source.IMAGE,PaymentApps.WECHAT,"合成退款金额冲突",now),incoming) as IngestResult.Saved).record
        assertEquals(RecordState.REVIEW,review.state);assertTrue(review.walletEffects.isEmpty())
        val target=store();LedgerBackup.restore(target,decoded(source));val restored=target.all().single()
        assertEquals(review.bill.refund,restored.bill.refund);assertEquals(4000L,restored.bill.amountCents)
        assertEquals(RecordState.REVIEW,restored.state);assertTrue(restored.walletEffects.isEmpty())
    }

    @Test fun mixedExistingAndNewTransferEndsAndZeroEffectReferencesAreExcludedAsWholeRecords() {
        val source=store();val a=wallet(source,"合成A");val b=wallet(source,"合成B");val row=transfer(source,a,b)
        // The zero-effect reference is constructed from a legal complete refund with an explicit existing endpoint.
        val zeroRecord=bill(amount=0).let {it.copy(bill=it.bill.copy(refund=RefundInfo("wechat",1000,1000)),
            wallet=RecordWalletLink(it.uid,WalletEligibility.ACTIVE,a.uid),walletEffects=emptyMap())}
        val full=decoded(source);val zeroBackup=full.copy(records=full.records+zeroRecord,wallets=full.wallets!!.let {it.copy(
            links=it.links+zeroRecord.wallet!!,effects=it.effects+(zeroRecord.uid to emptyMap()))})
        val target=store();val existing=wallet(target,"合成本机A",uid=a.uid);val before=target.getAccountByUid(a.uid)!!.amountCents
        LedgerBackup.restore(target,zeroBackup)
        for(uid in listOf(row.uid,zeroRecord.uid)) {val restored=target.all().single {it.uid==uid}
            assertEquals(WalletEligibility.RESTORE_EXCLUDED,restored.wallet!!.eligibility);assertTrue(restored.walletEffects.isEmpty())
            target.softDelete(restored.id,now);target.undoDelete(restored.id,now+1)
        }
        assertEquals(before,target.getAccount(existing.id)!!.amountCents)
        assertEquals(source.getAccountByUid(b.uid)!!.amountCents,target.getAccountByUid(b.uid)!!.amountCents)
    }

    @Test fun uidAndStrongIdentitySkipsPreserveLocalScreenshotHistoryIconAndMoney() {
        val source=store();val a=wallet(source);source.setDefaultWallet(a.uid)
        val local=source.saveEdited(bill().copy(identities=listOf(TransactionIdentity("wechat","SYNTHETIC-BACKUP-ORDER"))))
        val old=image(Color.RED);source.attachScreenshot(local.id,old);source.restoreMerchantIcon(local.id,old)
        val bytes=archive(source);source.saveEdited(local.copy(note="合成本机新备注"));val current=image(Color.BLUE)
        source.attachScreenshot(local.id,current)
        source.writableDatabase.execSQL("UPDATE merchant_icon_assets SET image=?",arrayOf<Any>(current))
        val before=source.walletSnapshot();val history=source.captureHistory(local.id)
        LedgerArchive.read(bytes.inputStream(),cache).use {assertEquals(0,LedgerArchive.restore(source,it))}
        assertEquals("合成本机新备注",source.get(local.id)!!.note);assertArrayEquals(current,source.screenshot(local.id))
        assertArrayEquals(current,source.merchantIcon(local.id));assertEquals(history,source.captureHistory(local.id));assertEquals(before,source.walletSnapshot())
        LedgerArchive.read(replaceManifestUid(bytes,local.uid,UUID.randomUUID().toString()).inputStream(),cache).use {duplicate->
            val summary=LedgerArchive.restoreDetailed(source,duplicate)
            assertEquals(0,summary.imported);assertEquals(1,summary.identityConflicts);assertEquals(1,source.all().size)
        }
        assertArrayEquals(current,source.screenshot(local.id));assertArrayEquals(current,source.merchantIcon(local.id));assertEquals(before,source.walletSnapshot())
    }

    @Test fun strictFinancialNumbersEffectsAndForeignKeysRejectBeforeChangingLocalState() {
        val source=store();val a=wallet(source);val b=wallet(source);transfer(source,a,b);val json=encoded(source)
        val target=store();wallet(target);val before=target.walletSnapshot()
        val mutations=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("wallets").getJSONArray("effects").getJSONObject(0).put("appliedCents",1.5)},
            {it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("feeCents",0.5)},
            {it.getJSONObject("wallets").getJSONObject("settings").put("revision",1.1)},
            {it.getJSONObject("wallets").getJSONArray("effects").let {a->a.put(JSONObject(a.getJSONObject(0).toString()))}},
            {it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("fromAccountUid",UUID.randomUUID().toString())},
            {it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0).put("eligibility","IMPORT_EXCLUDED")},
            {it.getJSONObject("wallets").getJSONArray("transfers").getJSONObject(0).put("feeCents",Money.MAX_CENTS)})
        for(mutate in mutations) {val bad=JSONObject(json);mutate(bad)
            rejected {LedgerBackup.restore(target,LedgerBackup.decode(bad.toString().byteInputStream()))}
            assertTrue(target.all().isEmpty());assertEquals(before,target.walletSnapshot())
        }
    }

    @Test fun stagedZipAndJsonMediaAreValidatedBeforeAnyDatabaseMutation() {
        val source=store();val a=wallet(source);source.setDefaultWallet(a.uid);val record=source.saveEdited(bill())
        source.attachScreenshot(record.id,image(Color.RED));val target=store();wallet(target);val before=target.walletSnapshot()
        LedgerArchive.read(archive(source).inputStream(),cache).use {prepared->
            prepared.files.values.first().writeBytes(byteArrayOf(1,2,3))
            rejected {LedgerArchive.restore(target,prepared)}
        }
        val json=JSONObject(encoded(source));json.getJSONObject("histories").getJSONArray(record.uid).getJSONObject(0)
            .put("image",android.util.Base64.encodeToString(byteArrayOf(1,2,3),android.util.Base64.NO_WRAP))
        rejected {LedgerBackup.restore(target,LedgerBackup.decode(json.toString().byteInputStream()))}
        assertTrue(target.all().isEmpty());assertEquals(before,target.walletSnapshot())
    }

    @Test fun backupStreamingReleasesLedgerLockAndKeepsOriginalMediaAfterConcurrentReplacement() {
        val source=store();val a=wallet(source);source.setDefaultWallet(a.uid);val record=source.saveEdited(bill())
        val old=image(Color.RED);source.attachScreenshot(record.id,old);val oldBalance=source.getAccount(a.id)!!.amountCents
        val executor=Executors.newSingleThreadExecutor();var changed=false
        fun mutateOnce(){if(!changed){changed=true;executor.submit {
                source.saveEdited(source.get(record.id)!!.copy(bill=record.bill.copy(amountCents=2000)))
                source.attachScreenshot(record.id,image(Color.BLUE))
            }.get(3,TimeUnit.SECONDS)}}
        val output=object:ByteArrayOutputStream(){
            override fun write(value:Int){mutateOnce();super.write(value)}
            override fun write(bytes:ByteArray,offset:Int,length:Int){mutateOnce();super.write(bytes,offset,length)}
        }
        try {LedgerArchive.write(source,ChannelSettings(context),output)} finally {executor.shutdownNow()}
        assertTrue(changed);assertEquals(2000L,source.get(record.id)!!.bill.amountCents)
        LedgerArchive.read(output.toByteArray().inputStream(),cache).use {prepared->val target=store();LedgerArchive.restore(target,prepared)
            val restored=target.all().single();assertEquals(1000L,restored.bill.amountCents);assertArrayEquals(old,target.screenshot(restored.id))
            assertEquals(oldBalance,target.getAccountByUid(a.uid)!!.amountCents)
        }
    }

    @Test fun restoredAliasesKeepLocalConflictsAndAuditsNeverRestoreAnExecutableToken() {
        val source=store();val a=wallet(source);val b=wallet(source);val alias=UUID.randomUUID().toString();source.saveWalletImportAlias(alias,a.uid)
        val doc=LedgerCsvImport.read(ByteArrayOutputStream().also {LedgerCsvExport.write(listOf(bill()),LedgerCsvExport.Format.GENERAL,it)}.toByteArray().inputStream())
        val token="SYNTHETIC-S6-AUDIT-${UUID.randomUUID()}";val preview=LedgerCsvImport.prepare(source,doc,submissionToken=token)
        LedgerCsvImport.applyPreview(source,doc,preview)
        val target=store();wallet(target,uid=b.uid);target.saveWalletImportAlias(alias,b.uid)
        val result=LedgerBackup.restoreDetailed(target,decoded(source));assertEquals(1,result.aliasConflicts)
        assertEquals(b.uid,target.walletImportAliases()[alias]);assertEquals(1,target.walletSnapshot().audits.size)
        assertNull(target.importReceipt(token));assertEquals(0L,target.readableDatabase.rawQuery("SELECT COUNT(*) FROM import_batches",null).use {it.moveToFirst();it.getLong(0)})
        val imported=target.all().single();assertEquals(WalletEligibility.IMPORT_EXCLUDED,imported.wallet!!.eligibility)
        assertTrue(imported.walletEffects.isEmpty())
    }

    @Test fun failureAfterImportingMediaRollsBackNewOwnersAndKeepsExistingLocalMedia() {
        val source=store();val a=wallet(source);source.setDefaultWallet(a.uid);val row=source.saveEdited(bill())
        source.attachScreenshot(row.id,image(Color.RED));val backup=decoded(source)
        val target=store();val b=wallet(target);target.setDefaultWallet(b.uid);val local=target.saveEdited(bill())
        val blue=image(Color.BLUE);target.attachScreenshot(local.id,blue);target.restoreMerchantIcon(local.id,blue)
        val before=target.walletSnapshot();val beforeOwners=ScreenshotStore.get(context).availableOwners()
        rejected {LedgerBackup.restoreDetailed(target,backup) {record->
            target.restoreHistory(record.id,backup.histories.getValue(record.uid))
            target.restoreMerchantIcon(record.id,image(Color.RED))
            throw IllegalStateException("synthetic failure after external media owners were created")
        }}
        assertEquals(listOf(local.uid),target.all().map {it.uid});assertEquals(before,target.walletSnapshot())
        assertArrayEquals(blue,target.screenshot(local.id));assertArrayEquals(blue,target.merchantIcon(local.id))
        assertEquals(beforeOwners,ScreenshotStore.get(context).availableOwners())
    }
}
