package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ocr.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

/** Synthetic geometry at the public processor/coordinator/SQLite boundary. Pixel OCR is explicit below. */
@RunWith(AndroidJUnit4::class)
class MixedReceiptAcceptanceTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val time=RecognitionTimeContext(1794110400000,"Asia/Shanghai")
    private val tasks=mutableListOf<Long>()
    private val wallets=mutableListOf<LedgerAccount>()
    private var baseline=setOf<Long>()
    private lateinit var oldSettings:WalletSettings
    private lateinit var wechat:LedgerAccount
    private lateinit var cash:LedgerAccount
    @Before fun prepare():Unit=runBlocking {
        app.taskRecovery.await();baseline=app.store.all().map { it.id }.toSet();oldSettings=app.store.walletSettings()
        wechat=wallet("wechat");cash=wallet("cash")
        app.store.setDefaultWallet(cash.uid);app.store.setAppWallet(PaymentApps.WECHAT,wechat.uid)
    }
    private fun wallet(kind:String,tail:String?=null)=app.store.saveAccount(LedgerAccount(
        name="合成混排${kind}${wallets.size}",amountCents=50000,kind=kind,
        bankCode=if(kind=="bank")"cmb" else null,cardLast4=tail)).also { wallets+=it }
    @After fun clean() {
        app.store.all().filter { it.id !in baseline }.forEach { app.store.delete(it.id) }
        tasks.forEach { id -> app.tasks.operationKey(id)?.let { key ->
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(key))
        };app.tasks.delete(id) }
        app.store.setDefaultWallet(oldSettings.defaultAccountUid)
        app.store.setAppWallet(PaymentApps.WECHAT,oldSettings.appBindings[PaymentApps.WECHAT])
        wallets.forEach { wallet ->
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }
    private fun lines(vararg text:String,top:Int=100)=text.mapIndexed { i,s -> LocalOcrLine(s,100,top+i*50,900,top+i*50+35) }
    private fun image(page:List<LocalOcrLine>):ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(1080,1800,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK;textSize=28f }
        page.forEach { canvas.drawText(it.text,it.left.toFloat(),it.top+28f,paint) }
        return java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();out.toByteArray()
        }
    }
    private fun mixed(method:String="通过零钱扣款")=lines("服务开通通知","支付方式：优先从零钱支付","交易已关闭","交易金额：888.00元")+
        lines("扣款成功","交易金额：12.34元","商户名称：合成咖啡","扣费服务商：合成扣费平台",
            "银行作者：招商银行","商品说明：午餐",method,top=600)+lines("为你推荐","商品说明：地铁优惠广告",top=1200)
    private suspend fun recognize(page:List<LocalOcrLine>,origin:String="shake:com.tencent.mm"):Pair<Long,IngestResult> {
        val source=if(origin=="image")Source.IMAGE else Source.OCR
        val task=app.tasks.create(source,origin);tasks+=task
        val backend=object:RecognitionBackend {
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult=error("frozen time required")
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->RecognitionTimeContext):RecognitionResult {
                onPrepared(image)
                return RecognitionResult(page.joinToString("\n") { it.text },
                    TransactionPageParser.parse(page,1080,1800,recognitionTime(),livePaymentAt,policy,origin),screenshot=image)
            }
        }
        return task to CaptureProcessor(app,OcrClient(app,backend),recognitionClock={time})
            .processPrepared(image(page),source,origin,task) { true }
    }
    private fun record(result:IngestResult):LedgerRecord {
        val batch=(result as IngestResult.Batch).result
        assertEquals(1,batch.items.size)
        return app.store.get(requireNotNull(batch.items.single().recordId))!!
    }

    @Test fun mixedActualDebitKeepsItsOwnEvidenceAndOriginalPositionAfterSqliteReopen()=runBlocking {
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val (task,result)=recognize(mixed())
        val saved=record(result)
        assertEquals(ReceiptBlockOrigin("MIXED_DEBIT",1080,1800,100,600,900,935),saved.bill.receipt!!.blockOrigin)
        assertEquals(1,app.store.all().count { it.id !in baseline })
        assertEquals(RecordState.POSTED,saved.state);assertEquals(1234L,saved.bill.amountCents)
        assertEquals("合成咖啡",saved.bill.merchant)
        assertEquals("合成扣费平台",saved.bill.receipt!!.serviceProvider!!.value)
        assertEquals("招商银行",saved.bill.receipt!!.receiptIssuer!!.value)
        assertEquals("午餐",saved.bill.receipt!!.productDescription!!.value)
        assertEquals("WECHAT_BALANCE",saved.bill.paymentMethod!!.medium)
        assertEquals(time.recognitionAt,saved.bill.occurredAt)
        assertEquals(time,app.tasks.recognitionTime(task))
        assertFalse(saved.capture.text.contains("888.00"));assertFalse(saved.capture.text.contains("交易已关闭"))
        assertFalse(saved.capture.text.contains("广告"))
        assertEquals(mapOf(wechat.uid to -1234L),saved.walletEffects)
        assertEquals(before+(wechat.uid to 48766L),app.store.accounts().associate { it.uid to it.amountCents })
        LedgerStore(app).use { reopened -> assertEquals(saved,reopened.get(saved.id)) }
    }

    @Test fun malformedBlockOriginsRejectTheEntireBackupBeforeWritingAnyRecordOrWallet()=runBlocking {
        recognize(mixed())
        val encoded=LedgerBackup.encode(app.store,net.ankio.ledger.capture.ChannelSettings(app))
        val mutations=listOf<(JSONObject)->Unit>(
            { it.put("kind","GUESS") },{ it.put("pageWidth","1080") },{ it.put("left",100.5) },
            { it.put("top",-1) },{ it.put("right",100) },{ it.put("bottom",1801) },{ it.remove("top") },
            { it.put("extra",1) },{ it.put("pageWidth",Long.MAX_VALUE) },
            { it.put("pageWidth",Int.MAX_VALUE).put("left",Int.MAX_VALUE).put("right",-1) })
        val name="synthetic-mixed-invalid-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { target ->
            for((index,mutation) in mutations.withIndex()) {
                val json=JSONObject(encoded)
                mutation(json.getJSONArray("records").getJSONObject(0).getJSONObject("receipt").getJSONObject("blockOrigin"))
                assertNotNull("malformed block $index",runCatching {
                    LedgerBackup.restore(target,LedgerBackup.decode(json.toString().byteInputStream()))
                }.exceptionOrNull())
                assertTrue(target.all().isEmpty());assertTrue(target.accounts().isEmpty())
            }
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun legacyReceiptWithoutBlockOriginKeepsItsJsonShapeAndBusinessEvidence() {
        val page=lines("招商银行","交易成功提醒","交易类型：网上快捷支付","交易金额：23.45元",
            "交易卡号：尾号0007","交易日期：2026-11-01")
        val original=LocalOcrBillParser.parse(page,1080,1800,time.recognitionAt,origin="shake:com.tencent.mm",timeContext=time)!!.receipt!!
        val json=ReceiptEvidenceCodec.json(original) as JSONObject
        json.remove("blockOrigin")
        val restored=ReceiptEvidenceCodec.decode(json)
        assertNull(restored.blockOrigin)
        assertEquals(original,restored)
        assertFalse((ReceiptEvidenceCodec.json(restored) as JSONObject).has("blockOrigin"))
        assertEquals("cmb",restored.bank!!.bankCode)
        assertEquals("2026-11-01",restored.time!!.localDate)
        assertEquals("capture",restored.sourceApp!!.source)
        assertEquals("ocr",restored.bank!!.transactionType!!.source)
    }
}
