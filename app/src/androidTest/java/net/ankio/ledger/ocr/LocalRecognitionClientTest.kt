package net.ankio.ledger.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import net.ankio.ledger.ocr.MerchantIconEncoding
import net.ankio.ledger.ocr.OcrClient
import net.ankio.ledger.ocr.SyntheticRecognitionBackend
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.data.LedgerAccount
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRecognitionClientTest {
    private var previousDefault: String? = null
    private var testWallet: LedgerAccount? = null
    @Before fun prepareSyntheticWallet() {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        previousDefault = app.store.walletSettings().defaultAccountUid
        testWallet = app.store.saveAccount(LedgerAccount(name = "本地OCR合成钱包", amountCents = 100000))
        app.store.setDefaultWallet(testWallet!!.uid)
    }
    @After fun restoreWalletSetting() {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        app.store.setDefaultWallet(previousDefault)
        testWallet?.let { app.store.archiveAccount(it.id) }
    }
    @Test fun realOcrBankEvidenceAndFullRefundReachTheSameWalletTransaction() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        val before = app.store.all().map { it.id }.toSet()
        val card = app.store.saveAccount(LedgerAccount(name = "合成招商卡", kind = "bank", bankCode = "cmb", cardLast4 = "0007", amountCents = 20000))
        val wechat = app.store.saveAccount(LedgerAccount(name = "合成微信", kind = "wechat", amountCents = 20000))
        val taskIds = mutableListOf<Long>()
        suspend fun recognize(refund: Boolean): IngestResult {
            val bitmap = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
            val bytes = try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
                fun text(value: String, x: Float, y: Float, size: Float = 40f) { paint.textSize = size; canvas.drawText(value, x, y, paint) }
                text("微信支付", 390f, 160f, 60f)
                text(if (refund) "已全额退款" else "支付成功", 370f, 300f, 60f)
                text("合成咖啡店", 360f, 430f, 58f)
                if (!refund) text("-100.00", 330f, 620f, 100f)
                else { text("原付款金额", 70f, 600f); text("100.00元", 600f, 600f); text("累计退款", 70f, 730f); text("100.00元", 600f, 730f) }
                text(if (refund) "原支付时间" else "支付时间", 70f, 950f)
                text("2026-11-05 12:00:00", 440f, 950f)
                text(if (refund) "原交易单号" else "交易单号", 70f, 1100f)
                text("202611051234567890123456", 380f, 1100f, 35f)
                text("付款方式", 70f, 1270f); text("招商银行 (0007)", 440f, 1270f)
                encode(bitmap)
            } finally { bitmap.recycle() }
            val task = app.tasks.create(Source.IMAGE, "image").also(taskIds::add)
            return CaptureProcessor(app).processPrepared(bytes, Source.IMAGE, "com.tencent.mm", task) { true }
        }
        try {
            val paid = recognize(false) as IngestResult.Saved
            assertEquals(RecordState.POSTED, paid.record.state)
            assertEquals(card.uid, paid.record.wallet!!.accountUid)
            assertEquals(10000L, app.store.getAccount(card.id)!!.amountCents)
            assertEquals(20000L, app.store.getAccount(wechat.id)!!.amountCents)
            val refunded = recognize(true) as IngestResult.Saved
            assertEquals(paid.record.id, refunded.record.id)
            assertEquals(0L, refunded.record.bill.amountCents)
            assertEquals(card.uid, refunded.record.wallet!!.accountUid)
            assertEquals(20000L, app.store.getAccount(card.id)!!.amountCents)
            assertEquals("SUCCEEDED", app.tasks.status(taskIds.last()))
            assertEquals("退款已更新", app.tasks.snapshot().let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }
                .single { it.getLong("id") == taskIds.last() }.getString("message") })
            recognize(true)
            assertEquals(20000L, app.store.getAccount(card.id)!!.amountCents)
        } finally {
            taskIds.forEach(app.tasks::delete)
            app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
            app.store.archiveAccount(card.id); app.store.archiveAccount(wechat.id)
        }
    }
    @Test fun cancelledQueuedImageNeverDecodesPixels()=runBlocking {
        val image=Bitmap.createBitmap(1800,1800,Bitmap.Config.ARGB_8888)
        val truncated=try {
            ByteArrayOutputStream().use { out ->
                check(image.compress(Bitmap.CompressFormat.PNG,100,out))
                out.toByteArray().also { bytes ->
                    val idat=bytes.indices.first { it+4<bytes.size && bytes.copyOfRange(it,it+4).contentEquals(byteArrayOf(73,68,65,84)) }
                    bytes[idat+4]=0 // Invalid zlib stream, with a valid image header.
                }
            }
        } finally { image.recycle() }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(truncated,0,truncated.size,bounds)
        assertTrue(bounds.outWidth>0 && bounds.outHeight>0)
        assertNull(BitmapFactory.decodeByteArray(truncated,0,truncated.size))
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val prepared=java.util.concurrent.CountDownLatch(1)
        val holder=launch(Dispatchers.IO) {
            LocalInferenceGate.shared.run { entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val waiting=async(Dispatchers.IO) {
                runCatching { LocalRecognitionClient(ApplicationProvider.getApplicationContext())
                    .recognizePrepared(truncated,onPrepared={ prepared.countDown() }) }
            }
            assertTrue(prepared.await(5,java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.delay(200)
            assertFalse("Queued image must wait before allocating/decoding pixels",waiting.isCompleted)
            waiting.cancel();waiting.join()
        } finally { release.countDown();holder.join() }
        val failure=runCatching { LocalRecognitionClient(ApplicationProvider.getApplicationContext())
            .recognizePrepared(truncated) }.exceptionOrNull()
        assertTrue(failure is RecognitionException)
        assertEquals(42,LocalInferenceGate.shared.run { 42 })
    }
    @Test fun anUnseenPaleWordmarkPassesRealOcrOnLightAndDarkPages()=runBlocking {
        for((background,ink) in listOf(Color.rgb(235,235,235) to Color.rgb(205,205,205),
            Color.rgb(28,30,35) to Color.rgb(60,62,67))) {
            val bitmap=Bitmap.createBitmap(900,1600,Bitmap.Config.ARGB_8888)
            val image=try {
                val canvas=Canvas(bitmap);canvas.drawColor(background)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ink }
                for(i in 0..5)canvas.drawRect(380f+i*24,280f,390f+i*24,310f,p)
                p.color=if(Color.red(background)<100)Color.WHITE else Color.BLACK
                p.typeface=Typeface.create("sans-serif",Typeface.NORMAL);p.textSize=58f
                canvas.drawText("知余咖啡店",280f,430f,p)
                p.textSize=96f;canvas.drawText("-28.50",280f,600f,p)
                p.textSize=48f;canvas.drawText("支付成功",340f,800f,p)
                encode(bitmap)
            } finally { bitmap.recycle() }
            val result=LocalRecognitionClient(ApplicationProvider.getApplicationContext()).recognizePrepared(image,origin="com.eg.android.AlipayGphone")
            assertEquals(result.rawText,"知余咖啡店",result.bill?.merchant)
            assertEquals(2850L,result.bill?.amountCents)
            if(result.merchantIcon==null) {
                val decoded=BitmapFactory.decodeByteArray(image,0,image.size)
                try {
                    val evidence=withContext(Dispatchers.IO) { LocalOcr(ApplicationProvider.getApplicationContext()).use { it.recognize(decoded) } }
                    fail("Actual OCR/encoded image: ${result.iconDetails}; ${evidence.json()}")
                } finally { decoded.recycle() }
            }
        }
    }
    @Test fun merchantIconExtractionIsSharedAcrossManualApplicationOrigins()=runBlocking {
        val image=paymentImage()
        for(origin in listOf("com.tencent.mm","com.eg.android.AlipayGphone","com.taobao.taobao",
                "com.xunmeng.pinduoduo","com.sankuai.meituan","com.ss.android.ugc.aweme","com.taobao.idlefish")) {
            val result=LocalRecognitionClient(ApplicationProvider.getApplicationContext()).recognizePrepared(image,origin=origin)
            assertEquals("$origin merchant", "知余咖啡店",result.bill?.merchant)
            assertEquals("$origin amount",2850L,result.bill?.amountCents)
            assertNotNull("$origin icon: ${result.iconDetails}",result.merchantIcon)
        }
    }
    @Test fun nativeVerifiedWechatAndAlipayScanResultsPostWithoutSuccessText()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val image=Bitmap.createBitmap(900,1600,Bitmap.Config.ARGB_8888)
        val submitted=try {
            val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            paint.textSize=44f;canvas.drawText("合成自助餐厅",260f,360f,paint)
            paint.textSize=110f;canvas.drawText("15.58",300f,510f,paint)
            paint.textSize=36f;canvas.drawText("摇一摇，有优惠",260f,750f,paint)
            canvas.drawText("完成",400f,1450f,paint)
            encode(image)
        } finally { image.recycle() }
        for(origin in listOf("com.tencent.mm","com.eg.android.AlipayGphone")) {
            val before=app.store.all().map { it.id }.toSet()
            app.recognitionSession.invalidate()
            val task=app.tasks.create(Source.OCR,"payment_auto:$origin")
            app.tasks.diagnose(task,"capture_context","payment_success_result:${System.currentTimeMillis()}")
            try {
                val result=CaptureProcessor(app).processPrepared(submitted,Source.OCR,origin,task) { true }
                assertTrue("$origin must save a verified result: $result",result is IngestResult.Saved)
                val record=(result as IngestResult.Saved).record
                assertEquals(RecordState.POSTED,record.state);assertEquals(1558L,record.bill.amountCents)
                assertEquals("合成自助餐厅",record.bill.merchant);assertEquals("SUCCEEDED",app.tasks.status(task))
                assertArrayEquals(submitted,app.store.screenshot(record.id))
            } finally {
                app.tasks.delete(task);app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
                app.recognitionSession.invalidate();app.changed()
            }
        }
    }
    @Test fun amountOnlyManualScreenshotPostsImmediatelyUsingRecognitionTime()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val bitmap=Bitmap.createBitmap(900,1600,Bitmap.Config.ARGB_8888)
        val bytes=try {
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=100f;typeface=Typeface.create("sans-serif",Typeface.BOLD) }
            canvas.drawText("¥ 28.50",240f,600f,paint)
            encode(bitmap)
        } finally { bitmap.recycle() }
        val before=app.store.all().map { it.id }.toSet()
        app.recognitionSession.invalidate()
        val task=app.tasks.create(Source.OCR,"shake:com.taobao.taobao")
        val started=System.currentTimeMillis()
        try {
            val result=CaptureProcessor(app).processPrepared(bytes,Source.OCR,"com.taobao.taobao",task) { true }
            assertTrue(result is IngestResult.Saved)
            val record=(result as IngestResult.Saved).record
            assertEquals(RecordState.POSTED,record.state)
            assertEquals(2850L,record.bill.amountCents)
            assertTrue(record.bill.merchant.isNotBlank());assertFalse(record.bill.timeExplicit)
            assertTrue(record.bill.occurredAt in started..System.currentTimeMillis())
        } finally {
            app.tasks.delete(task);app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
            app.recognitionSession.invalidate();app.changed()
        }
    }
    @Test fun bundledEngineReadsShippedOrderWithoutMistakingDeliveryForPaymentTime()=runBlocking {
        val bitmap=Bitmap.createBitmap(1080,1800,Bitmap.Config.ARGB_8888)
        val submitted=try {
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            fun text(value:String,x:Float,y:Float,size:Float=36f) { paint.textSize=size;canvas.drawText(value,x,y,paint) }
            text("已发货",440f,160f,56f)
            text("知余咖啡店",180f,530f,58f);text("进店逛逛",800f,530f)
            text("商品价格：99.00元",180f,700f)
            text("实付价 ¥26.90",180f,800f)
            text("实付款 ¥26.90",700f,1000f,42f)
            text("订单信息",60f,1150f)
            text("支付宝交易号",60f,1250f);text("202610171234567890123456",450f,1250f,28f)
            text("创建时间",60f,1360f);text("2026-10-17 13:11:50",550f,1360f,32f)
            text("付款时间",60f,1470f);text("2026-10-17 13:11:55",550f,1470f,32f)
            text("发货时间",60f,1580f);text("2026-10-18 10:00:00",550f,1580f,32f)
            encode(bitmap)
        } finally { bitmap.recycle() }
        val capturedAt=LocalDateTime.of(2026,10,19,0,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result=LocalRecognitionClient(ApplicationProvider.getApplicationContext()).recognizePrepared(submitted,capturedAt,origin="com.taobao.taobao")
        val bill=requireNotNull(result.bill) { result.rawText }
        assertTrue("${bill.reason}; ${result.rawText}",bill.reliable)
        assertEquals(2690L,bill.amountCents)
        assertEquals("知余咖啡店",bill.merchant)
        assertEquals(LocalDateTime.of(2026,10,17,13,11,55).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),bill.occurredAt)
    }
    @Test fun defaultLocalPipelinePostsWithoutConfiguredKeyOrAnyTransportCall() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        app.recognitionSession.invalidate()
        val before = app.store.all().map { it.id }.toSet()
        val submitted = paymentImage()
        writeSyntheticOcrEvidence(submitted)
        val taskId = app.tasks.create(Source.OCR, "shake")
        try {
            val result = CaptureProcessor(app)
                .processPrepared(submitted, Source.OCR, "com.tencent.mm", taskId) { true }
            assertTrue("Expected local payment persistence", result is IngestResult.Saved)
            val saved = (result as IngestResult.Saved).record
            assertEquals(RecordState.POSTED, saved.state)
            assertEquals(2850L, saved.bill.amountCents)
            assertEquals("SUCCEEDED", app.tasks.status(taskId))
            assertArrayEquals(submitted, app.store.screenshot(saved.id))
            assertFalse(app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        } finally {
            app.tasks.delete(taskId)
            app.store.all().filter { it.id !in before }.forEach { app.store.delete(it.id) }
            app.recognitionSession.invalidate()
            app.changed()
        }
    }

    @Test fun bundledEngineExtractsPaymentAndMerchantIconFromExactSubmittedImage() = runBlocking {
        val submitted = paymentImage()
        var prepared: ByteArray? = null
        val capturedAt = LocalDateTime.of(2026, 10, 18, 11, 50).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = LocalRecognitionClient(ApplicationProvider.getApplicationContext()).recognizePrepared(submitted, capturedAt, { prepared = it },
            livePaymentAt = capturedAt, expenseOnly = true, origin = "com.tencent.mm")
        assertArrayEquals(submitted, prepared)
        assertArrayEquals(submitted, result.screenshot)
        val bill = requireNotNull(result.bill) { "Generated successful payment not recognized: ${result.rawText}" }
        assertEquals(2850L, bill.amountCents)
        assertEquals(Direction.EXPENSE, bill.direction)
        assertEquals("知余咖啡店", bill.merchant)
        assertTrue("Unreliable generated payment: ${bill.reason}", bill.reliable)
        assertNotNull("Merchant icon missing: ${result.iconDetails}", result.merchantIcon)
        assertNotNull(MerchantIconEncoding.mime(result.merchantIcon!!))
        assertTrue("Icons must stay compact", result.merchantIcon.size < 32 * 1024)
    }

    @Test fun blankImageProducesNoBillAndNeverInventsIcon() = runBlocking {
        val blank = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes = try { encode(blank) } finally { blank.recycle() }
        val result = LocalRecognitionClient(ApplicationProvider.getApplicationContext()).recognizePrepared(bytes)
        assertNull(result.bill)
        assertNull(result.merchantIcon)
        assertArrayEquals(bytes, result.screenshot)
    }

    @Test fun withdrawnPermissionStopsBeforeImageIsPreparedOrRecognized() = runBlocking {
        var prepared = false
        try {
            LocalRecognitionClient(ApplicationProvider.getApplicationContext()).recognizePrepared(paymentImage(), onPrepared = { prepared = true }, stillAllowed = { false })
            fail("Withdrawn permission must cancel recognition")
        } catch (_: CancellationException) {
            assertFalse(prepared)
        }
    }

    private fun paymentImage(): ByteArray {
        val image = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun text(value: String, x: Float, y: Float, size: Float, bold: Boolean = false) {
                paint.color = Color.rgb(25, 30, 35)
                paint.textSize = size
                paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                canvas.drawText(value, x, y, paint)
            }
            text("支付成功", 390f, 160f, 64f, true)
            paint.color = Color.rgb(20, 155, 175)
            canvas.drawCircle(540f, 290f, 65f, paint)
            paint.color = Color.WHITE
            paint.textSize = 75f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("M", 510f, 318f, paint)
            text("知余咖啡店", 395f, 435f, 58f)
            text("- ¥ 28.50", 300f, 625f, 112f, true)
            text("支付时间", 85f, 1040f, 45f)
            text("2026-10-18 11:48:30", 420f, 1040f, 42f)
            text("支付方式", 85f, 1160f, 45f)
            text("零钱", 420f, 1160f, 45f)
            text("账户余额", 85f, 1280f, 45f)
            text("999.00元", 420f, 1280f, 45f)
            return encode(image)
        } finally { image.recycle() }
    }

    private fun encode(bitmap: Bitmap) = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
        it.toByteArray()
    }

    private suspend fun writeSyntheticOcrEvidence(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val ocr = LocalOcr(ApplicationProvider.getApplicationContext()).use { it.recognize(bitmap) }
            File(app.cacheDir, "synthetic-local-ocr-evidence.json").writeText(ocr.json().toString(2))
        } finally { bitmap.recycle() }
    }
}
