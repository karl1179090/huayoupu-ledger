package net.ankio.ledger

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual native sheets, from a file-picker result, using only disposable synthetic data. */
@RunWith(AndroidJUnit4::class)
class NativeImportPreviewTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun waitFor(text:String):AccessibilityNodeInfo {
        val end=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<end) {
            val node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            if(node!=null)return node
            Thread.sleep(100)
        }
        throw AssertionError("Native text not visible: $text")
    }
    private fun click(text:String) { var node:AccessibilityNodeInfo?=waitFor(text)
        while(node!=null && !node.isClickable)node=node.parent
        assertTrue("Native action not clickable: $text",node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name:String) {
        instrumentation.waitForIdleSync();Thread.sleep(200)
        val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { val file=File(app.getExternalFilesDir(null),"s5-evidence/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { image.recycle() }
        // AGP uninstalls test applications after connected tests; keep synthetic evidence in shell temp.
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/huayoupu-s5-$name.png").use { descriptor->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
    private fun deliver(activity:MainActivity,request:Int,file:File) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }
            .invoke(activity,request,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))
    }
    @Test fun pickerPreviewDefaultsClosedRemapsAtomicallyAndWalletOverwriteNeedsExplicitChoice() {
        val store=app.store;val suffix=UUID.randomUUID().toString().take(5)
        val a=store.saveAccount(LedgerAccount(name="合成转出$suffix",amountCents=100000))
        val b=store.saveAccount(LedgerAccount(name="合成目标$suffix",amountCents=20000))
        val snapshot=store.saveAccount(LedgerAccount(name="合成快照$suffix",amountCents=50000))
        val sourceB=b.copy(id=0,uid=UUID.randomUUID().toString());val uid=UUID.randomUUID().toString();val now=1_793_937_600_000L
        val transfer=LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv","",now),bill=ParsedBill(10000,Direction.TRANSFER,"合成原生预览","转账",now,null,true,"",true),state=RecordState.POSTED,
            transfer=RecordTransferDetails(uid,a.uid,sourceB.uid,100,FeeMode.EXTRA,fromReference=WalletFileReference.of(a).json(),toReference=WalletFileReference.of(sourceB).json()))
        val billFile=File(app.cacheDir,"synthetic-native-bill-$suffix.csv").apply { outputStream().use { LedgerCsvExport.write(listOf(transfer),LedgerCsvExport.Format.GENERAL,it) } }
        val walletFile=File(app.cacheDir,"synthetic-native-wallet-$suffix.csv").apply { outputStream().use { WalletCsvExport.write(listOf(snapshot.copy(id=0,revision=0,amountCents=30000)),it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                scenario.onActivity { deliver(it,MainActivity.CSV_IMPORT_REQUEST,billFile) }
                val switch=waitFor("联动钱包余额（新文件默认关闭）")
                assertTrue(switch.isCheckable);assertFalse(switch.isChecked);waitFor("钱包余额变化 ¥0.00")
                screenshot("01-bills-default-closed")
                click("钱包映射（")
                click("${b.name} → 未关联")
                click("${b.name} · ${b.uid.take(8)}")
                waitFor("导入账单预览");click("联动钱包余额（新文件默认关闭）")
                waitFor("${a.name}：¥1000.00 → ¥899.00");waitFor("${b.name}：¥200.00 → ¥300.00")
                screenshot("02-bills-remapped-linked-preview")
                assertNull(store.all().firstOrNull { it.uid==uid });assertEquals(100000L,store.getAccount(a.id)!!.amountCents);assertEquals(20000L,store.getAccount(b.id)!!.amountCents)
                scenario.onActivity { deliver(it,17,walletFile) }
                waitFor("${snapshot.name}：本地 ¥500.00 · 文件 ¥300.00 → ¥500.00")
                screenshot("03-wallet-default-retain-500")
                click("账户匹配／新建／合并／跳过")
                click("${snapshot.name} · ${snapshot.uid.take(8)}")
                click("合并到 ${snapshot.name} · ${snapshot.uid.take(8)}")
                click("用文件余额覆盖本地余额")
                click("重算预览")
                waitFor("${snapshot.name}：本地 ¥500.00 · 文件 ¥300.00 → ¥300.00")
                screenshot("04-wallet-explicit-overwrite-300")
                assertEquals(50000L,store.getAccount(snapshot.id)!!.amountCents)
            }
        } finally {
            billFile.delete();walletFile.delete()
            synchronized(store) { val db=store.writableDatabase;db.beginTransaction();try {
                listOf(a,b,snapshot).forEach { account->db.delete("wallet_movements","account_uid=?",arrayOf(account.uid));db.delete("accounts","uid=?",arrayOf(account.uid)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() } }
            app.changed()
        }
    }
}
