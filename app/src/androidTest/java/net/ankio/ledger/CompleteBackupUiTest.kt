package net.ankio.ledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real packaged Activity result, confirmation dialog and WebView feedback with synthetic data. */
class CompleteBackupUiTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun clickRestoreAndReadDialog():String {
        val deadline=SystemClock.elapsedRealtime()+5000
        while(SystemClock.elapsedRealtime()<deadline) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(root!=null) {
                val texts=mutableListOf<String>();var button:AccessibilityNodeInfo?=null
                fun visit(node:AccessibilityNodeInfo) { node.text?.toString()?.let { texts+=it;if(it=="恢复" && node.isClickable)button=node }
                    for(i in 0 until node.childCount)node.getChild(i)?.let(::visit) }
                visit(root)
                if(button!=null) { assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));return texts.joinToString("\n") }
            }
            SystemClock.sleep(20)
        }
        error("Restore confirmation button unavailable")
    }
    private fun execute(kind:String) {
        kotlinx.coroutines.runBlocking { app.taskRecovery.await() }
        val prefix="ui-backup-${UUID.randomUUID()}";val source=LedgerStore(app,"$prefix.db")
        val file=File(app.cacheDir,"$prefix.json");val previous=app.store.walletSettings().defaultAccountUid
        var walletUid:String?=null;var recordUid:String?=null
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成界面恢复钱包",amountCents=5000));walletUid=wallet.uid;source.setDefaultWallet(wallet.uid)
            val saved=(source.ingestRecognition(Capture(Source.IMAGE,"image","合成界面备份",1730611800000L),
                ParsedBill(500,Direction.EXPENSE,prefix,"其他",1730611800000L,null,true,""),"recognition:${UUID.randomUUID()}") as IngestResult.Saved).record
            recordUid=saved.uid;file.writeText(if(kind=="invalid")"invalid synthetic backup" else LedgerBackup.encode(source,app.channels))
            AtomicBackupTransaction.processProbe={ phase ->
                if(kind=="uncertain" && phase=="afterEndTransaction") { app.store.writableDatabase.close();error("synthetic unavailable commit connection") }
                if(kind=="cleanup" && phase=="committed")app.store.writableDatabase.execSQL("DETACH DATABASE media")
                if(kind=="applicationCleanup" && phase=="applicationCleanup")error("synthetic post-commit application cleanup failure")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val taskDatabase=app.getDatabasePath("recognition.db");val preserved=File(taskDatabase.parentFile,"recognition.db.t14-ui-preserved")
                if(kind=="pause") { app.tasks.close();check(!preserved.exists() && taskDatabase.renameTo(preserved));check(taskDatabase.mkdir()) }
                try {
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }.invoke(activity,MainActivity.RESTORE_REQUEST,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file))) }
                val dialog=clickRestoreAndReadDialog();assertTrue(dialog.contains("v1～v10"))
                val expected=when(kind) { "invalid"->"现有账单未修改";"uncertain"->"需要核对";"pause"->"暂停采集失败";else->"新增 1 笔" }
                web.await("document.querySelector('.toast')?.textContent.includes('${expected}')")
                web.await("!document.querySelector('#sheet').open && document.querySelector('.toast').classList.contains('show') && getComputedStyle(document.querySelector('.toast')).display!=='none'")
                web.run("window.__t14Painted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>{window.__t14Painted=true}))")
                web.await("window.__t14Painted===true")
                val message=web.text("document.querySelector('.toast').textContent")
                if(kind !in setOf("invalid","pause"))assertFalse(message.contains("现有账单未修改"))
                val output=File(app.filesDir,"t14-ui").apply { mkdirs() }
                File(output,"$kind.txt").writeText(dialog+"\nRESULT: "+message)
                instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(output,"$kind.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
                if(kind in setOf("invalid","pause"))assertNull(app.store.all().find { it.uid==saved.uid })
                else { assertNotNull(app.store.all().find { it.uid==saved.uid });assertEquals(4500L,app.store.getAccountByUid(wallet.uid)!!.amountCents) }
                } finally { if(kind=="pause") { app.tasks.close();check(taskDatabase.delete());check(preserved.renameTo(taskDatabase)) } }
            }
        } finally {
            AtomicBackupTransaction.processProbe=null
            recordUid?.let { uid -> app.store.all().find { it.uid==uid }?.let { app.store.delete(it.id) } }
            app.store.setDefaultWallet(previous)
            walletUid?.let { uid -> app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(uid));app.store.getAccountByUid(uid)?.let { app.store.deleteAccount(it.id) } }
            file.delete();source.close()
        }
    }
    @Test fun initialPauseFailureDoesNotStartRestore()=execute("pause")
    @Test fun postCommitApplicationCleanupRetainsTheImportedSummary()=execute("applicationCleanup")
    @Test fun invalidBackupShowsUnmodifiedMessage()=execute("invalid")
    @Test fun unknownCommitOutcomeNeverClaimsExistingBillsUnmodified()=execute("uncertain")
    @Test fun committedRestoreWithCleanupFailureStillShowsSuccess()=execute("cleanup")
}
