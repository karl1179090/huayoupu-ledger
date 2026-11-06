package net.ankio.ledger

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.*
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.BackgroundBatteryPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Live storage is authoritative, including the short deletion undo grace period. */
@RunWith(AndroidJUnit4::class)
class BackgroundRecognition056Test {
    @get:org.junit.Rule val wallet=CaptureWalletFixture()
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun fixture(state:RecordState=RecordState.POSTED)=LedgerRecord(capture=Capture(Source.OCR,"com.tencent.mm","合成测试"),
        bill=ParsedBill(1234,Direction.EXPENSE,"后台回归${UUID.randomUUID()}","其他",System.currentTimeMillis(),null,true,"",true),state=state)
    private fun withRecord(state:RecordState=RecordState.POSTED,block:(LedgerRecord,Long,PaymentRecognitionQueue)->Unit) {
        val model=fixture(state);app.store.merge(listOf(model));val record=app.store.all().first { it.uid==model.uid }
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm")
        app.tasks.update(task,if(state==RecordState.POSTED)"SUCCEEDED" else "REVIEW","stale",record.id)
        try { block(record,task,PaymentRecognitionQueue(app){true}) }
        finally { app.tasks.delete(task);app.store.delete(record.id) }
    }
    @Test fun savedFeedbackUsesCurrentStoredAmountInsteadOfStaleResponse()=withRecord { record,task,queue ->
        app.store.saveEdited(record.copy(bill=record.bill.copy(amountCents=4567)))
        assertEquals("已记账 ¥45.67",queue.completedMessage(task,IngestResult.Saved(record)))
    }
    @Test fun removedSavedBillAndDuplicateNeverReportSuccess()=withRecord { record,task,queue ->
        app.store.delete(record.id)
        assertFalse(queue.completedMessage(task,IngestResult.Saved(record)).startsWith("已记账"))
        assertFalse(queue.completedMessage(task,IngestResult.DuplicateSkipped).startsWith("已记账"))
        assertFalse(app.captureHealth.successful(record.id,"com.tencent.mm"))
    }
    @Test fun duplicateRequiresTaskLinkRatherThanStoredSuccessText() {
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm")
        try {
            app.tasks.update(task,"DUPLICATE","已记账 ¥99.99")
            assertFalse(PaymentRecognitionQueue(app){true}.completedMessage(task,IngestResult.DuplicateSkipped).startsWith("已记账"))
        } finally { app.tasks.delete(task) }
    }
    @Test fun reviewRecordDoesNotReportPostedSuccess()=withRecord(RecordState.REVIEW) { record,task,queue ->
        assertEquals("金额待核对，请打开账单",queue.completedMessage(task,IngestResult.Saved(record)))
        assertFalse(app.captureHealth.successful(record.id,"com.tencent.mm"))
    }
    @Test fun completedRefundReportsAnAdjustmentInsteadOfAZeroAmountPayment() {
        val model=fixture().let { it.copy(bill=it.bill.copy(amountCents=0,refund=RefundInfo("wechat",1234,1234))) }
        app.store.merge(listOf(model))
        val record=app.store.all().first { it.uid==model.uid }
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm")
        try {
            app.tasks.update(task,"SUCCEEDED","退款已更新",record.id)
            assertEquals("退款已更新",PaymentRecognitionQueue(app){true}.completedMessage(task,IngestResult.Saved(record,updated=true)))
        } finally { app.tasks.delete(task);app.store.delete(record.id) }
    }
    @Test fun walletRequiredFeedbackAsksForSelectionWithoutClaimingAnAmountError() {
        val previous=app.store.walletSettings().defaultAccountUid
        val merchant="待选钱包${UUID.randomUUID()}"
        app.store.setDefaultWallet(null)
        var record:LedgerRecord?=null
        val task=app.tasks.create(Source.OCR,"shake:com.example.synthetic")
        try {
            record=(app.store.ingest(Capture(Source.OCR,"com.example.synthetic","合成账单"),
                ParsedBill(1234,Direction.EXPENSE,merchant,"其他",System.currentTimeMillis(),UUID.randomUUID().toString(),true,"",true)) as IngestResult.Saved).record
            app.tasks.update(task,"REVIEW","请选择钱包",record.id)
            assertEquals("WALLET_REQUIRED",record.wallet!!.reviewCode)
            assertEquals("请选择钱包后入账",PaymentRecognitionQueue(app){true}.completedMessage(task,IngestResult.Saved(record)))
        } finally { record?.let { app.store.delete(it.id) };app.tasks.delete(task);app.store.setDefaultWallet(previous) }
    }
    @Test fun publicBatteryRequestTargetsOnlyThisApplication() {
        val intent=BackgroundBatteryPolicy.request(app.packageName)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,intent.action)
        assertEquals("package:${app.packageName}",intent.data.toString())
        assertNull(intent.component)
    }
    @Test fun duplicateAliasLinksTaskToOriginalWithDifferentPrimaryOrderNumber()=runBlocking {
        assumeTrue(Build.MODEL.contains("sdk_gphone"))
        val prefs=app.getSharedPreferences("remote_ai",Context.MODE_PRIVATE);val previous=prefs.all.toMap()
        val seed=UUID.randomUUID().toString().replace("-","")
        val merchant="订单别名回归$seed"
        val alipay="alipay-$seed";val taobao="taobao-$seed"
        val capture=Capture(Source.OCR,"com.taobao.taobao","淘宝订单号$taobao 支付宝交易号$alipay 实付款12.34元")
        val bill=ParsedBill(1234,Direction.EXPENSE,merchant,"其他",System.currentTimeMillis(),taobao,true,"",true)
        val original=app.store.ingest(capture,bill) as IngestResult.Saved
        val task=app.tasks.create(Source.IMAGE,"image")
        val repeatTask=app.tasks.create(Source.IMAGE,"image")
        val bitmap=Bitmap.createBitmap(80,160,Bitmap.Config.ARGB_8888)
        try {

            val client=OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ ->
                val remote=JSONObject().put("is_bill",true).put("amount","12.34").put("direction","expense")
                    .put("merchant",merchant).put("category","其他").put("occurred_at",JSONObject.NULL)
                    .put("transaction_id",alipay).put("reliable",true).put("raw_text","$merchant 交易单号$alipay 支付金额12.34元").put("merchant_icon",JSONObject.NULL)
                JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",remote.toString()))))
            })
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val processor=CaptureProcessor(app,client)
            processor.process(bitmap,Source.IMAGE,"com.eg.android.AlipayGphone",task) { true }
            val repeated=processor.process(bitmap,Source.IMAGE,"com.eg.android.AlipayGphone",repeatTask) { true }
            assertEquals(IngestResult.DuplicateSkipped,repeated)
            assertEquals("DUPLICATE",app.tasks.status(repeatTask))
            assertEquals(original.record.id,app.tasks.recordId(task))
            assertEquals(original.record.id,app.tasks.recordId(repeatTask))
            assertEquals(taobao,app.store.get(original.record.id)!!.bill.transactionId)
            assertEquals(1,app.store.all().count { it.bill.merchant==merchant })
            assertEquals("已记账 ¥12.34",PaymentRecognitionQueue(app){true}.completedMessage(repeatTask,IngestResult.DuplicateSkipped))
        } finally {
            bitmap.recycle();app.tasks.delete(task);app.tasks.delete(repeatTask)
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            restore(prefs,previous);app.changed()
        }
    }
    @Test fun queuedRestartFinishesWithVerifiedFeedbackWithoutOpeningActivity()=runBlocking {
        assumeTrue(Build.MODEL.contains("sdk_gphone"))
        assumeTrue(app.captureQueue.size()==0 && app.tasks.pendingQueued().isEmpty())
        val aiPrefs=app.getSharedPreferences("remote_ai",Context.MODE_PRIVATE);val aiBefore=aiPrefs.all.toMap()
        val healthPrefs=app.getSharedPreferences("capture_health",Context.MODE_PRIVATE);val healthBefore=healthPrefs.all.toMap()
        val shake=app.channels.enabled(ChannelSettings.Channel.OCR)
        app.channels.set(ChannelSettings.Channel.OCR,true)
        val task=app.tasks.create(Source.OCR,"shake:${whitelist.packageName}",app.channels.shakeAuthorization(whitelist.packageName))
        val merchant="恢复队列${UUID.randomUUID()}"
        val feedback=CopyOnWriteArrayList<String>()
        val permission=java.util.concurrent.atomic.AtomicBoolean(false)
        val queue=PaymentRecognitionQueue(app){permission.get()}
        try {
            app.channels.set(ChannelSettings.Channel.OCR,true)

            val bitmap=Bitmap.createBitmap(80,160,Bitmap.Config.ARGB_8888)
            val image=try { bitmap.eraseColor(android.graphics.Color.WHITE);app.recognizer.prepareScreenshot(bitmap) } finally { bitmap.recycle() }
            app.tasks.prepared(task,image);app.tasks.recoverInterrupted()
            assertEquals("QUEUED",app.tasks.status(task))
            val factory={CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ ->
                val order=UUID.randomUUID().toString()
                val bill=JSONObject().put("is_bill",true).put("amount","12.34").put("direction","expense")
                    .put("merchant",merchant).put("category","其他").put("occurred_at",JSONObject.NULL)
                    .put("transaction_id",order).put("reliable",true)
                    .put("raw_text","支出12.34元 $merchant 交易单号$order").put("merchant_icon",JSONObject.NULL)
                JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",bill.toString()))))
            }))}
            queue.resume(factory)
            assertEquals("A binding permission must retain the disk-backed queue",0,queue.size())
            assertEquals("QUEUED",app.tasks.status(task))
            permission.set(true)
            queue.resume(factory) { id,message,loading -> if(!loading) { assertEquals(task,id);feedback+=message } }
            withTimeout(10_000) { while(queue.size()!=0)delay(25) }
            assertEquals(listOf("已记账 ¥12.34"),feedback.toList())
            val id=app.tasks.recordId(task)!!;assertEquals(RecordState.POSTED,app.store.get(id)!!.state)
            val health=app.captureHealth.snapshot()
            assertEquals(id,health.getLong("lastSuccessfulRecordId"));assertTrue(health.getLong("lastSuccessfulAt")>0)
            assertEquals(whitelist.packageName,health.getString("lastSuccessfulOrigin"))
        } finally {
            permission.set(false);queue.cancelAll();withTimeout(10_000){while(queue.size()!=0)delay(25)}
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            app.tasks.delete(task);app.channels.set(ChannelSettings.Channel.OCR,shake)
            restore(aiPrefs,aiBefore);restore(healthPrefs,healthBefore);app.changed()
        }
    }
    private fun restore(prefs:android.content.SharedPreferences,values:Map<String,*>) {
        val edit=prefs.edit().clear();values.forEach { (key,value)->when(value) {
            is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Long->edit.putLong(key,value)
            is Int->edit.putInt(key,value);is Float->edit.putFloat(key,value);is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
        } };assertTrue(edit.commit())
    }
}
