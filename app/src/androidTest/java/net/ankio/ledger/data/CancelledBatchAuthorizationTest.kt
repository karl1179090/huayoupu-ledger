package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.PaymentRecognitionQueue
import net.ankio.ledger.capture.ShakeAuthorization
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Uses the installed synthetic test APK as capture origin; no external app or system permission. */
abstract class CancelledBatchAuthorizationScenario {
    protected val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    protected val pkg get()=InstrumentationRegistry.getInstrumentation().context.packageName
    protected data class Scenario(val task:Long,val key:String,val wallet:LedgerAccount,val oldDefault:String?,val oldEnabled:Boolean,
        val oldSelected:Boolean,val category:LedgerCategory,val rule:ClassificationRule,val authorization:ShakeAuthorization,
        val merchant:String,val doneMerchant:String,val snapshot:ClassificationSnapshot)

    protected suspend fun partial(shortcut:Boolean=false,revokeMasterDuringProcessing:Boolean=false):Scenario {
        app.taskRecovery.await()
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val oldEnabled=app.channels.enabled(ChannelSettings.Channel.OCR);val oldSelected=pkg in app.channels.selectedShakePackages()
        app.channels.setShakePackage(pkg,true);app.channels.set(ChannelSettings.Channel.OCR,true)
        val authorization=if(shortcut)app.channels.shortcutAuthorization(pkg)!! else app.channels.shakeAuthorization(pkg)!!
        val suffix=UUID.randomUUID().toString().take(8);val merchant="合成代次待完成$suffix";val doneMerchant="合成代次已完成$suffix"
        val wallet=app.store.saveAccount(LedgerAccount(name="合成代次钱包",amountCents=10000));app.store.setDefaultWallet(wallet.uid)
        val category=app.store.saveCategory(name="合成代次用途$suffix",direction=Direction.EXPENSE)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成代次规则",true,Direction.EXPENSE,category.id,0,
            listOf(ClassificationCondition(value=merchant))))
        val task=app.tasks.create(Source.OCR,authorization.origin,authorization);val key=app.tasks.operationKey(task)!!
        val snapshot=app.tasks.classificationSnapshot(task)!!
        val first=ParsedBill(600,Direction.EXPENSE,doneMerchant,"其他",1793491200000,null,true,"",true)
        val candidates=listOf(TransactionCandidate(CandidateId("authorization_done"),first),
            TransactionCandidate(CandidateId("authorization_pending"),first.copy(amountCents=800,merchant=merchant)))
        val processor=CaptureProcessor(app,OcrClient(app,RecognitionBackend { image,prepared,_,_,_,_ ->
            prepared(image);RecognitionResult("合成代次多卡",candidates)
        }))
        val error=runCatching { processor.processPrepared(syntheticBackupImage(142),Source.OCR,pkg,task) {
            if(app.store.recognitionBatch(key)?.counts?.added==1) {
                if(revokeMasterDuringProcessing)app.channels.set(ChannelSettings.Channel.OCR,false)
                false
            } else true
        } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException);assertEquals("CANCELLED",app.tasks.status(task))
        assertEquals(9400L,app.store.getAccount(wallet.id)!!.amountCents)
        assertEquals("其他",app.store.all().single { it.bill.merchant==doneMerchant }.bill.category)
        app.store.deleteClassificationRule(rule.id)
        return Scenario(task,key,wallet,oldDefault,oldEnabled,oldSelected,category,rule,authorization,merchant,doneMerchant,snapshot)
    }
    protected fun unchangedFinancialFacts(s:Scenario) {
        assertEquals(9400L,app.store.getAccount(s.wallet.id)!!.amountCents)
        val record=app.store.all().single { it.bill.merchant==s.doneMerchant }
        assertEquals(1,app.store.walletMovements(s.wallet.uid).count { it.recordUid==record.uid })
        assertTrue(app.store.all().none { it.bill.merchant==s.merchant })
        assertEquals(s.snapshot,app.tasks.classificationSnapshot(s.task))
    }
    protected fun cleanup(s:Scenario) {
        app.store.all().filter { it.bill.merchant in setOf(s.merchant,s.doneMerchant) }.forEach { app.store.delete(it.id) }
        app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(s.key));app.tasks.delete(s.task)
        listOf("authorization_done","authorization_pending").forEach { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(candidateOperationKey(s.key,CandidateId(it)))) }
        app.store.setDefaultWallet(s.oldDefault)
        app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(s.wallet.uid));app.store.deleteAccount(s.wallet.id)
        if(app.store.categoryDirectory().any { it.id==s.category.id })app.store.confirmCategoryDeletion(s.category.id,"cat:expense:other",app.store.previewCategoryDeletion(s.category.id).previewToken)
        app.channels.setShakePackage(pkg,s.oldSelected);app.channels.set(ChannelSettings.Channel.OCR,s.oldEnabled)
    }
}

class CancelledBatchAuthorizationTest:CancelledBatchAuthorizationScenario() {
    @Test fun revokingApplicationGenerationReleasesAnAlreadyCancelledBatchBeforeDeletion()=runBlocking {
        val s=partial()
        try {
            assertEquals(1L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            app.channels.setShakePackage(pkg,false)
            assertFalse(app.channels.shakeAuthorized(s.authorization))
            assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(s.task))
            val preview=app.store.previewCategoryDeletion(s.category.id)
            assertEquals(0L,preview.queuedConfigurationCount);assertFalse(preview.requiresReplacement)
            app.store.confirmCategoryDeletion(s.category.id,null,preview.previewToken)
            unchangedFinancialFacts(s)
        } finally { cleanup(s) }
    }
    @Test fun revokingMasterDuringProcessingReleasesTheCancelledBatchReservation()=runBlocking {
        val s=partial(revokeMasterDuringProcessing=true)
        try {
            assertFalse(app.channels.shakeAuthorized(s.authorization))
            assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(s.task))
            assertEquals(0L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            unchangedFinancialFacts(s)
        } finally { cleanup(s) }
    }
    @Test fun shortcutGenerationRevocationReleasesTheReservationAndReconciliationDoesNotReactivateIt()=runBlocking {
        val s=partial(shortcut=true)
        try {
            app.channels.set(ChannelSettings.Channel.OCR,false)
            assertTrue(app.channels.shakeAuthorized(s.authorization))
            assertEquals(1L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            app.channels.invalidateShortcuts()
            assertFalse(app.channels.shakeAuthorized(s.authorization))
            assertEquals(0L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            app.tasks.recoverInterrupted()
            assertEquals("CANCELLED",app.tasks.status(s.task))
            assertEquals(0L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(s.task))
            unchangedFinancialFacts(s)
        } finally { cleanup(s) }
    }
    @Test fun temporaryCapturePermissionLossKeepsTheValidTokenReservedUntilExplicitRetry()=runBlocking {
        val s=partial();var permission=false
        val queue=PaymentRecognitionQueue(app,hasCapturePermission={permission})
        try {
            assertTrue(app.channels.shakeAuthorized(s.authorization))
            assertFalse(queue.retryBatch(s.task))
            app.tasks.recoverInterrupted()
            assertEquals(1L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
            unchangedFinancialFacts(s)
            permission=true;assertTrue(queue.retryBatch(s.task))
            kotlinx.coroutines.withTimeout(10000) { while(app.tasks.status(s.task)!="SUCCEEDED" || queue.size()!=0)kotlinx.coroutines.delay(25) }
            assertEquals(BatchCounts(2,0,0,0),app.store.recognitionBatch(s.key)!!.counts)
            val done=app.store.all().single { it.bill.merchant==s.doneMerchant }
            val pending=app.store.all().single { it.bill.merchant==s.merchant }
            assertEquals("其他",done.bill.category);assertEquals(s.category.name,pending.bill.category)
            assertEquals(s.category.id,pending.bill.categoryDecision!!.categoryId)
            assertEquals(s.rule,pending.bill.categoryDecision!!.rule);assertEquals(s.snapshot.version,pending.bill.categoryDecision!!.ruleVersion)
            assertEquals(8600L,app.store.getAccount(s.wallet.id)!!.amountCents)
            listOf(done,pending).forEach { record -> assertEquals(1,app.store.walletMovements(s.wallet.uid).count { it.recordUid==record.uid }) }
            assertEquals(0L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
        } finally { cleanup(s) }
    }
}

/** Host-only phases, excluded from the ordinary registry and separated by a real force-stop. */
class CancelledBatchAuthorizationProcessRestartTest:CancelledBatchAuthorizationScenario() {
    private val state get()=app.getSharedPreferences("synthetic-revoked-batch-restart",0)
    @Test fun aCreateRevokedShortcutBatchBeforeProcessDeath()=runBlocking {
        assertFalse(state.contains("task"))
        val s=partial(shortcut=true)
        app.channels.invalidateShortcuts()
        assertEquals(0L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
        assertTrue(state.edit().putLong("task",s.task).putString("key",s.key).putString("wallet",s.wallet.uid)
            .putString("oldDefault",s.oldDefault).putBoolean("oldEnabled",s.oldEnabled).putBoolean("oldSelected",s.oldSelected)
            .putString("category",s.category.id).putString("rule",s.rule.id).putString("authorization",s.authorization.encode())
            .putString("merchant",s.merchant).putString("doneMerchant",s.doneMerchant)
            .putString("snapshot",ClassificationCodec.snapshot(s.snapshot).toString()).putInt("pid",android.os.Process.myPid()).commit())
    }
    @Test fun bRevokedShortcutBatchRemainsInactiveAfterProcessDeath()=runBlocking {
        app.taskRecovery.await()
        assertEquals("true",InstrumentationRegistry.getArguments().getString("expectProcessRestart"))
        assertNotEquals(state.getInt("pid",0),android.os.Process.myPid())
        val snapshot=ClassificationCodec.readSnapshot(state.getString("snapshot",null)!!)
        val s=Scenario(state.getLong("task",0),state.getString("key",null)!!,
            app.store.getAccountByUid(state.getString("wallet",null)!!)!!,state.getString("oldDefault",null),
            state.getBoolean("oldEnabled",false),state.getBoolean("oldSelected",false),
            app.store.categoryDirectory().single { it.id==state.getString("category",null) },
            snapshot.customRules.single { it.id==state.getString("rule",null) },ShakeAuthorization.decode(state.getString("authorization",null))!!,
            state.getString("merchant",null)!!,state.getString("doneMerchant",null)!!,snapshot)
        try {
            assertEquals("CANCELLED",app.tasks.status(s.task))
            assertNotEquals(s.authorization,app.channels.shortcutAuthorization(pkg))
            assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(s.task))
            val preview=app.store.previewCategoryDeletion(s.category.id)
            assertEquals(0L,preview.queuedConfigurationCount);assertFalse(preview.requiresReplacement)
            app.store.confirmCategoryDeletion(s.category.id,null,preview.previewToken)
            unchangedFinancialFacts(s)
        } finally { cleanup(s);assertTrue(state.edit().clear().commit()) }
    }
}
