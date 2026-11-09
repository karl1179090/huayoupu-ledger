package net.ankio.ledger

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ocr.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.UUID

/** Run a/b separately around force-stop with expectProcessRestart=true for genuine process proof. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MixedReceiptProcessRestartTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val state get()=app.getSharedPreferences("synthetic-mixed-restart",0)
    private val frozen=RecognitionTimeContext(1794110400000,"Asia/Shanghai")

    @Test fun aPersistMixedReceiptBeforeProcessDeath()=runBlocking {
        app.taskRecovery.await();assertFalse(state.contains("task"))
        val suffix=UUID.randomUUID().toString().take(8)
        val settings=app.store.walletSettings()
        val wechat=app.store.saveAccount(LedgerAccount(name="合成重开零钱$suffix",amountCents=50000,kind="wechat"))
        val cash=app.store.saveAccount(LedgerAccount(name="合成重开默认$suffix",amountCents=50000,kind="cash"))
        app.store.setDefaultWallet(cash.uid);app.store.setAppWallet(PaymentApps.WECHAT,wechat.uid)
        val task=app.tasks.create(Source.IMAGE,"image")
        val page=listOf("服务开通通知","优先从零钱支付","交易已关闭","扣款成功","交易金额：12.34元",
            "商户名称：合成重开咖啡$suffix","扣费服务商：合成扣费平台","银行作者：招商银行","商品说明：午餐","通过微信零钱扣款")
            .mapIndexed { i,text -> LocalOcrLine(text,60,100+i*55,1000,140+i*55) }
        val bitmap=android.graphics.Bitmap.createBitmap(1080,1800,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK;textSize=28f }
        page.forEach { canvas.drawText(it.text,it.left.toFloat(),it.top+28f,paint) }
        val bytes=java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray() }
        val backend=object:RecognitionBackend {
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult=error("context required")
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->RecognitionTimeContext):RecognitionResult {
                onPrepared(image);return RecognitionResult(page.joinToString("\n") { it.text },
                    TransactionPageParser.parse(page,1080,1800,recognitionTime(),livePaymentAt,policy,origin),screenshot=image)
            }
        }
        val result=(CaptureProcessor(app,OcrClient(app,backend),recognitionClock={frozen})
            .processPrepared(bytes,Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
        assertEquals(BatchCounts(1,0,0,0),result.counts)
        val saved=app.store.get(result.items.single().recordId!!)!!
        assertEquals(mapOf(wechat.uid to -1234L),saved.walletEffects)
        assertTrue(state.edit().putLong("task",task).putLong("record",saved.id).putString("uid",saved.uid)
            .putString("wechat",wechat.uid).putString("cash",cash.uid).putString("oldDefault",settings.defaultAccountUid)
            .putString("oldBinding",settings.appBindings[PaymentApps.WECHAT]).putInt("pid",Process.myPid())
            .putString("receipt",ReceiptEvidenceCodec.encode(saved.bill.receipt)).putString("text",saved.capture.text)
            .putString("ruleVersion",saved.bill.categoryDecision!!.ruleVersion).commit())
    }

    @Test fun bReopenSavedEvidenceWithoutOcrOrAnotherDebitAndPreservePostedBackup()=runBlocking {
        app.taskRecovery.await()
        val task=state.getLong("task",0);assertTrue("Run phase a first",task>0)
        val firstPid=state.getInt("pid",0)
        if(InstrumentationRegistry.getArguments().getString("expectProcessRestart")=="true")assertNotEquals(firstPid,Process.myPid())
        val key=app.tasks.operationKey(task)!!
        val wechat=state.getString("wechat",null)!!;val cash=state.getString("cash",null)!!
        try {
            var calls=0
            val backend=RecognitionBackend { _,_,_,_,_,_ -> calls++;error("Saved receipt must not reread the current page or run OCR") }
            val processor=CaptureProcessor(app,OcrClient(app,backend),recognitionClock={RecognitionTimeContext(frozen.recognitionAt+172800000,"America/New_York")})
            repeat(2) {
                val result=(processor.processPrepared(app.tasks.image(task)!!,Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
                assertEquals(BatchCounts(1,0,0,0),result.counts)
            }
            val saved=app.store.get(state.getLong("record",0))!!
            val receipt=ReceiptEvidenceCodec.decode(state.getString("receipt",null)!!)
            assertEquals(state.getString("uid",null),saved.uid);assertEquals(receipt,saved.bill.receipt)
            assertEquals(state.getString("text",null),saved.capture.text)
            assertEquals(frozen,app.tasks.recognitionTime(task));assertEquals(frozen.recognitionAt,saved.bill.occurredAt)
            assertEquals(state.getString("ruleVersion",null),saved.bill.categoryDecision!!.ruleVersion)
            assertEquals("cat:expense:dining",saved.bill.categoryDecision!!.categoryId)
            assertEquals(0,calls);assertEquals(48766L,app.store.getAccountByUid(wechat)!!.amountCents)
            assertEquals(50000L,app.store.getAccountByUid(cash)!!.amountCents)
            assertEquals(1,app.store.walletMovements(wechat).count { it.recordUid==saved.uid })
            val balances=app.store.accounts().associate { it.uid to it.amountCents }
            val json=LedgerBackup.encode(app.store,ChannelSettings(app)).toByteArray()
            val zip=java.io.ByteArrayOutputStream().use { LedgerArchive.write(app.store,ChannelSettings(app),it);it.toByteArray() }
            for(bytes in listOf(json,zip)) {
                val name="synthetic-mixed-posted-${UUID.randomUUID()}.db"
                try { LedgerStore(app,name).use { target -> LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                    LedgerArchive.restore(target,prepared)
                    val restored=target.all().single { it.uid==saved.uid }
                    assertEquals(receipt,restored.bill.receipt);assertEquals(saved.walletEffects,restored.walletEffects)
                    assertEquals(saved.bill.categoryDecision,restored.bill.categoryDecision)
                    assertEquals(balances,target.accounts().associate { it.uid to it.amountCents })
                    val movements=target.walletSnapshot().movements
                    assertEquals(0,LedgerArchive.restore(target,prepared));assertEquals(movements,target.walletSnapshot().movements)
                    assertEquals(balances,target.accounts().associate { it.uid to it.amountCents })
                } } } finally { app.deleteDatabase(name) }
            }
            val directory=java.io.File(app.cacheDir,"synthetic-t11-evidence").apply { mkdirs() }
            java.io.File(directory,"process.json").writeText(JSONObject().put("firstPid",firstPid).put("secondPid",Process.myPid())
                .put("ocrCallsAfterReopen",calls).put("receipt",ReceiptEvidenceCodec.json(receipt)).put("walletBalance",48766)
                .put("defaultBalance",50000).put("onceOnlyMovements",1).toString(2))
        } finally {
            app.store.get(state.getLong("record",0))?.let { app.store.delete(it.id) }
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(key));app.tasks.delete(task)
            app.store.setDefaultWallet(state.getString("oldDefault",null));app.store.setAppWallet(PaymentApps.WECHAT,state.getString("oldBinding",null))
            for(uid in listOf(wechat,cash)) {
                app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(uid))
                app.store.getAccountByUid(uid)?.let { app.store.deleteAccount(it.id) }
            }
            assertTrue(state.edit().clear().commit())
        }
    }
}
