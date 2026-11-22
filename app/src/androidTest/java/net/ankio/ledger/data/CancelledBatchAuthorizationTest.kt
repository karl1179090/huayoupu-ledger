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

    protected suspend fun partial(shortcut:Boolean=false,revokeMasterDuringProcessing:Boolean=false,failed:Boolean=false):Scenario {
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
        if(failed) {
            val pendingKey=candidateOperationKey(key,candidates.last().id)
            app.store.writableDatabase.execSQL("""CREATE TRIGGER reject_authorization_child BEFORE INSERT ON wallet_submissions
                WHEN NEW.kind='CAPTURE_RESULT' AND NEW.token='$pendingKey' BEGIN SELECT RAISE(ABORT,'synthetic batch failure'); END""")
            try { processor.processPrepared(syntheticBackupImage(142),Source.OCR,pkg,task) { true } }
            finally { app.store.writableDatabase.execSQL("DROP TRIGGER reject_authorization_child") }
        } else {
            val error=runCatching { processor.processPrepared(syntheticBackupImage(142),Source.OCR,pkg,task) {
                if(app.store.recognitionBatch(key)?.counts?.added==1) {
                    if(revokeMasterDuringProcessing)app.channels.set(ChannelSettings.Channel.OCR,false)
                    false
                } else true
            } }.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
        }
        assertEquals(if(failed)"FAILED" else "CANCELLED",app.tasks.status(task))
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
    protected fun reserved(s:Scenario)=app.store.classificationBackup().snapshots.single { it.operationKey==s.key }.active
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
    @Test fun deletionRecheckRejectsOldInstallationBeforeAsynchronousRevocation() = runBlocking {
        val scenario = partial(failed = true)
        val preferences = app.getSharedPreferences("channels", 0)
        val key = "shake_installation_v1:$pkg"
        val original = requireNotNull(preferences.getString(key, null))
        try {
            val preview = app.store.previewCategoryDeletion(scenario.category.id)
            assertEquals(1L, preview.queuedConfigurationCount)
            assertTrue(preferences.edit().putString(key, "old-installation").commit())
            val stale = runCatching {
                app.store.confirmCategoryDeletion(scenario.category.id, "cat:expense:other", preview.previewToken)
            }.exceptionOrNull() as CategoryPreviewStale
            assertEquals(0L, stale.preview.queuedConfigurationCount)
            assertFalse(reserved(scenario))
            assertEquals("FAILED", app.tasks.status(scenario.task))
            unchangedFinancialFacts(scenario)
        } finally {
            assertTrue(preferences.edit().putString(key, original).commit())
            cleanup(scenario)
        }
    }

    @Test fun revokingApplicationGenerationReleasesFailedAndCancelledBatchesBeforeDeletion()=runBlocking {
        for(failed in listOf(false,true)) {
            val s=partial(failed=failed)
            try {
                assertTrue(reserved(s))
                app.channels.setShakePackage(pkg,false)
                assertFalse(app.channels.shakeAuthorized(s.authorization));assertFalse(reserved(s))
                app.channels.setShakePackage(pkg,true)
                assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(s.task))
                val preview=app.store.previewCategoryDeletion(s.category.id)
                assertEquals(0L,preview.queuedConfigurationCount);assertFalse(preview.requiresReplacement)
                app.store.confirmCategoryDeletion(s.category.id,null,preview.previewToken)
                unchangedFinancialFacts(s)
            } finally { cleanup(s) }
        }
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
        for(failed in listOf(false,true)) {
            val s=partial(failed=failed);var permission=false
            val queue=PaymentRecognitionQueue(app,hasCapturePermission={permission})
            try {
            assertTrue(app.channels.shakeAuthorized(s.authorization))
            assertFalse(queue.retryBatch(s.task))
            app.tasks.recoverInterrupted()
            assertTrue(reserved(s));assertEquals(1L,app.store.previewCategoryDeletion(s.category.id).queuedConfigurationCount)
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
    @Test fun lostScreenshotAndCompletedCandidatesReleaseFailedAndCancelledReservationsOnStartup()=runBlocking {
        for(failed in listOf(false,true)) for(missingScreenshot in listOf(true,false)) {
            val s=partial(failed=failed)
            try {
                assertTrue(reserved(s))
                if(missingScreenshot) app.screenshots.release("task:${app.tasks.databaseName}:${s.task}")
                else assertTrue(app.store.submitBatchCandidate(s.key,CandidateId("authorization_pending"),app.tasks.image(s.task)).complete)
                app.tasks.recoverInterrupted()
                assertFalse(reserved(s))
                assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(s.task))
                assertEquals(if(failed)"FAILED" else "CANCELLED",app.tasks.status(s.task))
                val preview=app.store.previewCategoryDeletion(s.category.id)
                assertEquals(0L,preview.queuedConfigurationCount)
                assertEquals(if(missingScreenshot)0L else 1L,preview.billCount)
                if(missingScreenshot)unchangedFinancialFacts(s)
            } finally { cleanup(s) }
        }
    }

    @Test fun automaticBatchUsesItsCapturedGenerationAndCannotRetryAfterReenabling()=runBlocking {
        app.taskRecovery.await()
        val pkg=PaymentApps.WECHAT
        val wasEnabled=app.channels.automaticEnabled();val wasSelected=pkg in app.channels.selectedAutoPackages()
        val suffix=UUID.randomUUID().toString().take(8)
        val category=app.store.saveCategory(name="合成自动代次用途$suffix",direction=Direction.EXPENSE)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成自动代次规则$suffix",true,Direction.EXPENSE,category.id,0,
            listOf(ClassificationCondition(value="合成自动代次商家$suffix"))))
        val tasks=mutableListOf<Long>()
        val keys=mutableListOf<String>()
        try {
            app.channels.setAutoPackage(pkg,true);app.channels.setAutomaticEnabled(true)
            val generation=app.channels.automaticGeneration(pkg)
            assertEquals(generation,ChannelSettings(app).automaticGeneration(pkg))
            val origin="payment_auto:$pkg"
            val bill=ParsedBill(347,Direction.EXPENSE,"合成自动代次商家$suffix","其他",1793491200000,null,true,"",true)
            for(grant in listOf(generation,null)) {
                val id=app.tasks.create(Source.OCR,origin,automaticGeneration=grant).also(tasks::add)
                app.tasks.queued(id,syntheticBackupImage(161))
                val key=app.tasks.operationKey(id)!!.also(keys::add)
                val batch=app.store.freezeRecognitionBatch(Capture(Source.OCR,origin,"合成自动待处理"),
                    listOf(TransactionCandidate(CandidateId("automatic_pending"),bill)),key,"Asia/Shanghai",app.tasks.classificationSnapshot(id)!!,RecognitionPolicy.USER_INITIATED)
                assertFalse(batch.complete);assertTrue(app.tasks.update(id,"FAILED","合成自动待重试"))
                val reservation=app.store.classificationBackup().snapshots.single { it.operationKey==key }
                assertEquals(grant!=null,reservation.active)
            }
            app.store.deleteClassificationRule(rule.id)
            assertEquals(1L,app.store.previewCategoryDeletion(category.id).queuedConfigurationCount)
            assertFalse(PaymentRecognitionQueue(app,hasCapturePermission={false}).retryBatch(tasks.first()))
            app.channels.setAutomaticEnabled(false)
            assertTrue(app.channels.automaticGeneration(pkg)>generation)
            assertTrue(app.store.classificationBackup().snapshots.filter { it.operationKey in keys }.none { it.active })
            app.channels.setAutomaticEnabled(true)
            val reenabled=app.channels.automaticGeneration(pkg)
            assertTrue(reenabled>generation);assertEquals(reenabled,ChannelSettings(app).automaticGeneration(pkg))
            app.tasks.recoverInterrupted()
            assertTrue(tasks.none { PaymentRecognitionQueue(app,hasCapturePermission={true}).retryBatch(it) })
            assertEquals(0L,app.store.previewCategoryDeletion(category.id).queuedConfigurationCount)
            app.channels.setAutoPackage(pkg,false)
            assertTrue(app.channels.automaticGeneration(pkg)>reenabled)
        } finally {
            if(app.store.classificationRules().customRules.any { it.id==rule.id })app.store.deleteClassificationRule(rule.id)
            tasks.forEach(app.tasks::delete)
            keys.forEach { app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(it)) }
            app.store.removeCategory(category.id)
            app.channels.setAutoPackage(pkg,wasSelected);app.channels.setAutomaticEnabled(wasEnabled)
        }
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
