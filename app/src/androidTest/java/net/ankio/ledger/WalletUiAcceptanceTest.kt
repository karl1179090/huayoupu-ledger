package net.ankio.ledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.data.LedgerArchive
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Independent checks through the real WebView bridge and document-result confirmation. */
@RunWith(AndroidJUnit4::class)
class WalletUiAcceptanceTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get() = ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun dirtyEditorRejectsAStaleRevisionAndRetainsUnsavedInputWithoutAnotherDebit() {
        val at=System.currentTimeMillis()
        val saved=(app.store.ingest(Capture(Source.MANUAL,"manual","合成版本冲突",at),
            ParsedBill(3000,Direction.EXPENSE,"版本冲突${UUID.randomUUID()}","餐饮",at,null,true,"",true)) as IngestResult.Saved).record
        val account=app.store.accounts().single { it.uid==saved.wallet!!.accountUid }
        val balance=account.amountCents
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${saved.id});document.querySelector('#note').value='尚未保存的人工备注'")
                val current=app.store.saveEdited(saved.copy(note="另一入口已保存的备注"))
                app.changed()
                web.await("draft.wallet.revision===${current.wallet!!.revision}")
                web.run("window.__acceptanceMessage='';window.__oldNotify=notify;notify=message=>{window.__acceptanceMessage=message;window.__oldNotify(message)};submitDraft()")
                web.await("window.__acceptanceMessage.includes('账单已更新')")
                assertEquals("尚未保存的人工备注",web.text("document.querySelector('#note').value"))
                assertEquals("true",web.value("recordPanelOpen"))
                assertEquals("另一入口已保存的备注",app.store.get(saved.id)!!.note)
                assertEquals(balance,app.store.getAccount(account.id)!!.amountCents)
            }
        } finally { app.store.delete(saved.id);app.changed() }
    }

    @Test fun manualRotationRetainsItsSubmissionTokenAndDefaultWalletWithoutManualLock() {
        val marker="合成旋转提交${UUID.randomUUID()}"
        val default=app.store.walletSettings().defaultAccountUid
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("beginManual();document.querySelector('#manual-amount').value='3.21';document.querySelector('#manual-note').value='$marker';document.querySelector('#manual-note').dispatchEvent(new Event('input',{bubbles:true}))")
                val token=web.text("LedgerUI.getState().manual.submissionToken")
                assertTrue(token.isNotBlank())
                scenario.recreate();web.ready()
                web.await("manualOpen && document.querySelector('#manual-note').value==='$marker'")
                assertEquals(token,web.text("LedgerUI.getState().manual.submissionToken"))
                web.run("document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("!manualOpen")
                val posted=app.store.all().single { it.note==marker }
                assertEquals(RecordState.POSTED,posted.state)
                assertEquals(default,posted.wallet!!.accountUid)
                assertFalse(posted.wallet.manualLocked)
                assertEquals(-321L,posted.wallet.appliedCents)
                web.run("beginManual()")
                assertNotEquals(token,web.text("LedgerUI.getState().manual.submissionToken"))
            }
        } finally { app.store.all().filter { it.note==marker }.forEach { app.store.delete(it.id) };app.changed() }
    }

    @Test fun backupConfirmationPausesBothChannelsAndRequiresFreshShakeSelection() {
        verifyRestorePause(false)
    }

    @Test fun openManualFormShowsTheCurrentDefaultAndKeepsAnExplicitChoiceAfterSettingsChange() {
        val initialDefault=app.store.walletSettings().defaultAccountUid!!
        val initialAccount=app.store.accounts().single { it.uid==initialDefault }
        val alternative=app.store.saveAccount(LedgerAccount(name="合成新默认${UUID.randomUUID().toString().take(6)}",amountCents=0))
        val marker="合成默认变更${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("beginManual()")
                app.store.setDefaultWallet(alternative.uid);app.changed()
                web.await("document.querySelector('#manual-wallet').textContent.includes('${alternative.name}')")
                web.run("chooseWallet('manual')")
                assertEquals("true",web.text("document.querySelector('[data-wallet-uid=\"${alternative.uid}\"]').getAttribute('aria-pressed')"))
                web.run("document.querySelector('[data-wallet-uid=\"$initialDefault\"]').click()")
                app.store.setDefaultWallet(null);app.changed()
                web.await("document.querySelector('#manual-wallet').textContent.includes('${initialAccount.name}')")
                web.run("document.querySelector('#manual-amount').value='5.00';document.querySelector('#manual-note').value='$marker';document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))")
                web.await("!manualOpen")
                val record=app.store.all().single { it.note==marker }
                assertEquals(RecordState.POSTED,record.state)
                assertEquals(initialDefault,record.wallet!!.accountUid)
                assertTrue(record.wallet.manualLocked)
                assertEquals(-500L,record.wallet.appliedCents)
                assertEquals(0L,app.store.getAccount(alternative.id)!!.amountCents)
            }
        } finally {
            app.store.all().filter { it.note==marker }.forEach { app.store.delete(it.id) }
            app.store.setDefaultWallet(initialDefault);app.store.deleteAccount(alternative.id);app.changed()
        }
    }

    @Test fun failedBackupConfirmationAlsoLeavesBothChannelsPaused() {
        verifyRestorePause(true)
    }

    private fun verifyRestorePause(corrupt:Boolean) {
        runBlocking { withTimeout(10_000) { app.taskRecovery.await() } }
        val channels=app.channels
        val previousShake=channels.enabled(ChannelSettings.Channel.OCR)
        val previousAutomatic=channels.automaticEnabled()
        val previousApps=channels.selectedAutoPackages()
        val previousSelected=channels.selectedShakePackages()
        val fixturePackage=InstrumentationRegistry.getInstrumentation().context.packageName
        val file=File(app.cacheDir,"acceptance-restore-${UUID.randomUUID()}.zip")
        var task=0L
        try {
            if(corrupt)file.writeText("synthetic damaged archive")
            else file.outputStream().use { LedgerArchive.write(app.store,channels,it) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("window.__restoreMessage='';window.__oldNotify=notify;notify=message=>{window.__restoreMessage=message;window.__oldNotify(message)}")
                channels.setAutoPackage("com.tencent.mm",true);channels.setAutomaticEnabled(true)
                channels.setShakePackage(fixturePackage,true);channels.set(ChannelSettings.Channel.OCR,true)
                val oldAuthorization=channels.shakeAuthorization(fixturePackage)!!
                task=app.tasks.create(Source.OCR,"shake:$fixturePackage",oldAuthorization)
                val session=app.recognitionSession.generation
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                        .apply { isAccessible=true }.invoke(activity,MainActivity.RESTORE_REQUEST,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))
                    WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<Button>()
                        .single { it.text.toString()=="恢复" }.performClick()
                }
                // AlertController dispatches the positive listener through its Handler;
                // performClick returning does not mean the listener has run yet.
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertFalse("The restore confirmation callback must pause automatic capture",channels.automaticEnabled())
                assertFalse(channels.enabled(ChannelSettings.Channel.OCR))
                assertTrue(app.recognitionSession.generation>session)
                web.await(if(corrupt)"window.__restoreMessage.includes('恢复失败')" else "window.__restoreMessage.includes('请重新选择摇一摇应用')")
                assertFalse(channels.automaticEnabled());assertFalse(channels.captureEnabled())
                assertEquals(setOf("com.tencent.mm"),channels.selectedAutoPackages().intersect(setOf("com.tencent.mm")))
                assertFalse(channels.shakeAuthorized(oldAuthorization))
                assertEquals("CANCELLED",app.tasks.status(task))
                if(!corrupt)assertTrue(channels.selectedShakePackages().isEmpty())
                // A user may later reselect the same app; the pre-restore task stays revoked.
                channels.setShakePackage(fixturePackage,true);channels.set(ChannelSettings.Channel.OCR,true)
                assertFalse(channels.shakeAuthorized(oldAuthorization))
            }
        } finally {
            channels.set(ChannelSettings.Channel.OCR,false);channels.setAutomaticEnabled(false)
            if(task>0)app.tasks.delete(task)
            channels.selectedShakePackages().filter { it !in previousSelected }.forEach { channels.setShakePackage(it,false) }
            previousSelected.forEach { channels.setShakePackage(it,true) }
            PaymentApps.entries.forEach { channels.setAutoPackage(it.packageName,it.packageName in previousApps) }
            channels.set(ChannelSettings.Channel.OCR,previousShake);channels.setAutomaticEnabled(previousAutomatic)
            file.delete();app.changed()
        }
    }

    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
}
