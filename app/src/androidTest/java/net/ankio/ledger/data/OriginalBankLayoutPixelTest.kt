package net.ankio.ledger.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real bundled pixel OCR. Only layout structure is retained; every financial value is synthetic. */
@RunWith(AndroidJUnit4::class)
class OriginalBankLayoutPixelTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun inlineCardTailsAndYearlessTransactionTimesStayLocalAcrossNotificationCards()=runBlocking {
        app.taskRecovery.await()
        val previousIds=app.store.all().mapTo(mutableSetOf()) { it.id }
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val accounts=mutableListOf<LedgerAccount>()
        val prefix="合成银行形状${UUID.randomUUID()}"
        val fixed=RecognitionTimeContext(Instant.parse("2026-11-09T04:00:00Z").toEpochMilli(),"Asia/Shanghai")
        fun account(name:String,amount:Long,tail:String?=null)=app.store.saveAccount(LedgerAccount(name=name,amountCents=amount,
            kind=if(tail==null)"wechat" else "bank",bankCode=if(tail==null)null else "cmb",cardLast4=tail)).also(accounts::add)
        val a=account("合成银行甲",100000,"0007")
        val b=account("合成银行乙",200000,"0008")
        val wechat=account("合成承载应用钱包",300000)
        app.store.setDefaultWallet(wechat.uid)
        val history=(app.store.ingest(Capture(Source.MANUAL,"manual","合成日金额历史"),
            ParsedBill(2371,Direction.INCOME,"$prefix-历史","其他",Instant.parse("2026-11-01T11:50:00Z").toEpochMilli(),null,true,"",true)) as IngestResult.Saved).record
        val task=app.tasks.create(Source.IMAGE,"image");val operation=app.tasks.operationKey(task)!!
        val fixture=File(app.cacheDir,"t10-original4").apply { check(isDirectory || mkdirs()) }
        val bitmap=Bitmap.createBitmap(1080,1720,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(238,238,238))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value:String,x:Float,y:Float,size:Float=38f,color:Int=Color.BLACK) {
            paint.textSize=size;paint.color=color;canvas.drawText(value,x,y,paint)
        }
        text("12:45",50f,52f,32f);text("通知",495f,60f,42f)
        fun card(top:Float,date:String,amount:String,tail:String,balance:String) {
            paint.color=Color.WHITE;canvas.drawRoundRect(35f,top,1045f,top+720f,18f,18f,paint)
            text("招商银行微银行",85f,top+55f,40f)
            text("交易成功提醒",85f,top+135f,42f)
            listOf("交易时间：" to date,"交易类型：" to "网上快捷支付（尾号$tail）",
                "交易金额：" to "人民币$amount","账户余额：" to "${balance}元",
                "交易说明：" to "点击查看更多详情").forEachIndexed { i,(label,value) ->
                val y=top+235f+i*72f;text(label,85f,y,color=Color.rgb(110,110,110));text(value,350f,y)
            }
            text("查看详情",85f,top+645f,36f)
        }
        card(90f,"11月01日 09:12","23.71","0007","88,888.88")
        text("昨天 20:30",438f,865f,30f,Color.rgb(130,130,130))
        card(935f,"11月02日 17:24","68.42","0008","77,777.77")
        val image=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() };bitmap.recycle()
        File(fixture,"fixture.png").writeBytes(image)
        try {
            val processor=CaptureProcessor(app,recognitionClock={fixed})
            val started=android.os.SystemClock.elapsedRealtimeNanos()
            val outcome=processor.processPrepared(image,Source.IMAGE,"image",task) { true }
            val processorElapsedMs=(android.os.SystemClock.elapsedRealtimeNanos()-started)/1_000_000
            File(fixture,"observed.json").writeText(JSONObject().put("outcome",outcome.javaClass.simpleName)
                .put("taskStatus",app.tasks.status(task)).put("taskMessage",app.tasks.message(task))
                .put("batch",app.store.recognitionBatch(operation)?.json() ?: JSONObject.NULL)
                .put("frozen",app.store.recognitionBatchBackup()).toString(2))
            assertTrue("Expected a two-card batch; inspect synthetic observed.json",outcome is IngestResult.Batch)
            val result=(outcome as IngestResult.Batch).result
            assertEquals(BatchCounts(1,1,0,0),result.counts)
            assertEquals(listOf(2371L,6842L),result.items.map { it.amountCents })
            assertEquals(listOf(Direction.EXPENSE,Direction.EXPENSE),result.items.map { it.direction })
            assertEquals(listOf("2026-11-01","2026-11-02"),result.items.map { it.accountingDate })
            assertEquals(history.uid,result.items[0].recordUID);assertEquals(history.id,result.items[0].recordId)
            val frozen=app.store.recognitionBatchBackup().getJSONObject(0).getJSONArray("items")
            for(i in 0..1) {
                val bill=frozen.getJSONObject(i).getJSONObject("bill")
                assertEquals(if(i==0)"0007" else "0008",bill.getJSONObject("paymentMethod").getString("cardLast4"))
                assertEquals("cmb",bill.getJSONObject("paymentMethod").getString("bankCode"))
                assertEquals("DEBIT",bill.getJSONObject("paymentMethod").getString("role"))
                assertEquals("",bill.getString("merchant"))
                val receipt=bill.getJSONObject("receipt");assertEquals("招商银行",receipt.getJSONObject("receiptIssuer").getString("value"))
                val time=receipt.getJSONObject("time")
                assertEquals("YEAR_INFERRED",time.getString("source"));assertEquals("MINUTE",time.getString("precision"))
                assertEquals("RECOGNITION",time.getString("reference"));assertFalse(time.getString("rawText").contains("20:30"))
                assertEquals(fixed.recognitionAt,time.getLong("recognitionAt"))
            }
            val posted=app.store.get(result.items[1].recordId!!)!!
            assertEquals(RecordState.POSTED,posted.state);assertEquals(b.uid,posted.wallet!!.accountUid)
            assertEquals(Instant.parse("2026-11-02T09:24:00Z").toEpochMilli(),posted.bill.occurredAt)
            assertEquals(100000L,app.store.getAccount(a.id)!!.amountCents)
            assertEquals(193158L,app.store.getAccount(b.id)!!.amountCents)
            assertEquals(302371L,app.store.getAccount(wechat.id)!!.amountCents)
            assertEquals(fixed,app.tasks.recognitionTime(task))
            val movements=app.store.walletMovements(b.uid)
            val replayStarted=android.os.SystemClock.elapsedRealtimeNanos()
            val replay=(processor.processPrepared(image,Source.IMAGE,"image",task) { true } as IngestResult.Batch).result
            val replayElapsedMs=(android.os.SystemClock.elapsedRealtimeNanos()-replayStarted)/1_000_000
            assertEquals(result,replay);assertEquals(movements,app.store.walletMovements(b.uid))
            File(fixture,"verified.json").writeText(result.json().put("bankA",app.store.getAccount(a.id)!!.amountCents)
                .put("bankB",app.store.getAccount(b.id)!!.amountCents).put("wechat",app.store.getAccount(wechat.id)!!.amountCents)
                .put("processorElapsedMs",processorElapsedMs).put("replayElapsedMs",replayElapsedMs).toString(2))
            synchronized(app.store) {
                for((name,db) in listOf("ledger.db" to app.store.readableDatabase,"recognition.db" to app.tasks.readableDatabase)) {
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use { if(it.moveToFirst())check(it.getInt(0)==0) }
                    app.getDatabasePath(name).copyTo(File(fixture,name),overwrite=true)
                    for(suffix in listOf("-wal","-shm"))app.getDatabasePath(name+suffix).takeIf { it.exists() }
                        ?.copyTo(File(fixture,name+suffix),overwrite=true)
                }
            }
        } finally {
            app.store.all().filter { it.id !in previousIds }.forEach {
                app.store.writableDatabase.delete("wallet_submissions","result_uid=?",arrayOf(it.uid));app.store.delete(it.id)
            }
            app.store.writableDatabase.delete("recognition_batches","operation_key=?",arrayOf(operation));app.tasks.delete(task)
            app.store.setDefaultWallet(oldDefault)
            accounts.forEach { account -> app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(account.uid));app.store.deleteAccount(account.id) }
        }
    }
}
