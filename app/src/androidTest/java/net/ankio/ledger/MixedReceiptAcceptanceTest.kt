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
    private fun image(page:List<LocalOcrLine>,textSize:Float=28f):ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(1080,1800,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK;this.textSize=textSize }
        page.forEach { canvas.drawText(it.text,it.left.toFloat(),it.top+textSize,paint) }
        return java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();out.toByteArray()
        }
    }
    private fun mixed(method:String="通过零钱扣款")=lines("服务开通通知","支付方式：优先从零钱支付","交易已关闭","待支付","交易金额：888.00元")+
        lines("扣款成功","交易金额：12.34元","商户名称：合成咖啡","扣费服务商：合成扣费平台",
            "银行作者：招商银行","商品说明：午餐",method,top=600)+lines("为你推荐","商品说明：地铁优惠广告",top=1200)
    private fun nested()=lines("图片查看器","交易时间：2020-01-01 08:00","账单记录时间：2019-01-01 09:00",top=30)+
        lines("招商银行","交易金额：23.45元","账户余额：9999.00元","积分：12345",top=400)+
        lines("为你推荐","商户名称：合成火锅广告","商品说明：午餐广告","交易金额：888.00元","保存图片",top=1000)
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
        androidx.test.core.app.ActivityScenario.launch(net.ankio.ledger.ui.MainActivity::class.java).use { scenario ->
            lateinit var bridge:net.ankio.ledger.ui.LedgerWebBridge
            scenario.onActivity { bridge=net.ankio.ledger.ui.LedgerWebBridge(it) }
            val detail=JSONObject(bridge.recordDetails(saved.id)).getJSONObject("record")
            assertEquals(saved.bill.receipt,ReceiptEvidenceCodec.decode(detail.getJSONObject("receipt")))
            val rows=JSONObject(bridge.snapshotFirst("2026-11","","","")).getJSONArray("records")
            val row=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getLong("id")==saved.id }
            assertEquals(saved.bill.receipt,ReceiptEvidenceCodec.decode(row.getJSONObject("receipt")))
        }
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

    @Test fun preferenceOnlyDebitRemainsReviewWithNoFallbackUntilExplicitManualAccountConfirmation()=runBlocking {
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val (task,result)=recognize(mixed("支付方式：优先从零钱支付"))
        assertEquals(BatchCounts(0,0,1,0),(result as IngestResult.Batch).result.counts)
        val saved=record(result)
        assertEquals(RecordState.REVIEW,saved.state);assertNull(saved.wallet!!.accountUid)
        assertEquals("ACTUAL_ACCOUNT_REQUIRED",saved.wallet!!.reviewCode)
        assertEquals("ACTUAL_ACCOUNT_REQUIRED",saved.bill.paymentMethod!!.reason)
        assertTrue(saved.walletEffects.isEmpty());assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
        assertEquals("REVIEW",app.tasks.status(task))
        assertEquals("cat:expense:dining",saved.bill.categoryDecision!!.categoryId)
        val token="synthetic-mixed-confirm-${UUID.randomUUID()}"
        val confirmed=app.store.saveEdited(saved,cash.uid,true,submissionToken=token)
        assertEquals(RecordState.POSTED,confirmed.state);assertTrue(confirmed.wallet!!.manualLocked)
        assertEquals(mapOf(cash.uid to -1234L),confirmed.walletEffects)
        assertEquals(before+(cash.uid to 48766L),app.store.accounts().associate { it.uid to it.amountCents })
        app.store.saveEdited(saved,cash.uid,true,submissionToken=token)
        assertEquals(before+(cash.uid to 48766L),app.store.accounts().associate { it.uid to it.amountCents })
        assertEquals(saved.bill.receipt,app.store.get(saved.id)!!.bill.receipt)
    }

    @Test fun nestedBankAmountOnlyUsesFrozenTimeAndStaysReviewThroughJsonAndZipRestoreWithoutMoney()=runBlocking {
        wallet("bank","0007");wallet("bank","0008")
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val (task,result)=recognize(nested().reversed(),"image")
        val saved=record(result);val receipt=saved.bill.receipt!!
        assertEquals(BatchCounts(0,0,1,0),(result as IngestResult.Batch).result.counts)
        assertEquals(1,app.store.all().count { it.id !in baseline })
        assertEquals(RecordState.REVIEW,saved.state);assertEquals(2345L,saved.bill.amountCents)
        assertNull(saved.bill.direction);assertEquals("",saved.bill.merchant)
        assertNull(receipt.merchant);assertNull(receipt.productDescription);assertNull(receipt.sourceApp)
        assertEquals("招商银行",receipt.receiptIssuer!!.value)
        assertEquals("cmb",receipt.bank!!.bankCode);assertNull(receipt.bank!!.cardLast4)
        assertEquals(time.recognitionAt,saved.bill.occurredAt)
        assertEquals("RECOGNITION_FALLBACK",receipt.time!!.source);assertEquals("2026-11-08",receipt.time!!.localDate)
        assertEquals(ReceiptBlockOrigin("NESTED_BANK",1080,1800,100,400,900,485),receipt.blockOrigin)
        assertEquals("招商银行\n交易金额：23.45元",saved.capture.text)
        assertNull(saved.wallet!!.accountUid);assertTrue(saved.walletEffects.isEmpty())
        assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
        val json=LedgerBackup.encode(app.store,net.ankio.ledger.capture.ChannelSettings(app)).toByteArray()
        val zip=java.io.ByteArrayOutputStream().use { LedgerArchive.write(app.store,net.ankio.ledger.capture.ChannelSettings(app),it);it.toByteArray() }
        for(bytes in listOf(json,zip)) {
            val name="synthetic-mixed-roundtrip-${UUID.randomUUID()}.db"
            try { LedgerStore(app,name).use { target -> LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                assertEquals(1,LedgerArchive.restore(target,prepared))
                val restored=target.all().single { it.uid==saved.uid }
                assertEquals(receipt,restored.bill.receipt);assertEquals(saved.bill.occurredAt,restored.bill.occurredAt)
                assertEquals(saved.bill.category,restored.bill.category);assertEquals(saved.capture,restored.capture)
                assertEquals(RecordState.REVIEW,restored.state);assertTrue(restored.walletEffects.isEmpty())
                assertEquals(before,target.accounts().associate { it.uid to it.amountCents })
                assertEquals(BatchCounts(0,0,1,0),target.recognitionBatch(app.tasks.operationKey(task)!!)!!.counts)
                val frozen=target.recognitionBatchBackup().getJSONObject(0).getJSONArray("items").getJSONObject(0)
                    .getJSONObject("bill").getJSONObject("receipt")
                assertEquals(receipt,ReceiptEvidenceCodec.decode(frozen))
                val movements=target.walletSnapshot().movements
                assertEquals(0,LedgerArchive.restore(target,prepared));assertEquals(movements,target.walletSnapshot().movements)
                assertEquals(before,target.accounts().associate { it.uid to it.amountCents })
            } } } finally { app.deleteDatabase(name) }
        }
    }

    @Test fun preferenceReviewRoundtripsWithoutAccountButPostedManualAndNonzeroEffectsAreRejected()=runBlocking {
        val (_,result)=recognize(mixed("支付方式：优先从零钱支付"),"image")
        val saved=record(result)
        val encoded=LedgerBackup.encode(app.store,net.ankio.ledger.capture.ChannelSettings(app))
        val zipped=java.io.ByteArrayOutputStream().use { LedgerArchive.write(app.store,net.ankio.ledger.capture.ChannelSettings(app),it);it.toByteArray() }
        val name="synthetic-preference-backup-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { target ->
            val mutations=listOf<(JSONObject)->Unit>(
                { it.getJSONArray("records").getJSONObject(0).put("state","POSTED") },
                { it.getJSONObject("wallets").getJSONArray("links").getJSONObject(0)
                    .put("selectionSource","MANUAL").put("manualLocked",true) },
                { it.getJSONObject("wallets").getJSONArray("effects").put(JSONObject().put("recordUid",saved.uid)
                    .put("accountUid",wechat.uid).put("appliedCents",-1234)) })
            for((index,mutation) in mutations.withIndex()) {
                val invalid=JSONObject(encoded);mutation(invalid)
                assertNotNull("invalid unresolved wallet $index",runCatching {
                    LedgerBackup.restore(target,LedgerBackup.decode(invalid.toString().byteInputStream()))
                }.exceptionOrNull())
                assertTrue(target.all().isEmpty());assertTrue(target.accounts().isEmpty())
            }
            for(bytes in listOf(encoded.toByteArray(),zipped)) {
                LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                    LedgerArchive.restore(target,prepared)
                    val restored=target.all().single { it.uid==saved.uid }
                    assertEquals(RecordState.REVIEW,restored.state);assertNull(restored.wallet!!.accountUid)
                    assertEquals("ACTUAL_ACCOUNT_REQUIRED",restored.wallet!!.reviewCode)
                    assertTrue(restored.walletEffects.isEmpty());assertEquals(saved.bill.receipt,restored.bill.receipt)
                    assertEquals(50000L,target.getAccountByUid(wechat.uid)!!.amountCents)
                    assertEquals(50000L,target.getAccountByUid(cash.uid)!!.amountCents)
                }
            }
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun unmatchedBankAccountRoundtripsAsReviewWithNoFallbackOrWalletEffect()=runBlocking {
        val page=lines("图片查看器",top=30)+lines("招商银行","交易金额：34.56元","交易类型：网上快捷支付",
            "交易卡号：尾号9999",top=400)
        val (_,result)=recognize(page,"image")
        val saved=record(result)
        assertEquals(Direction.EXPENSE,saved.bill.direction);assertEquals(RecordState.REVIEW,saved.state)
        assertNull(saved.wallet!!.accountUid);assertEquals("PAYMENT_METHOD_UNMATCHED",saved.wallet!!.reviewCode)
        assertTrue(saved.walletEffects.isEmpty())
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val bytes=LedgerBackup.encode(app.store,net.ankio.ledger.capture.ChannelSettings(app)).toByteArray()
        val name="synthetic-unmatched-bank-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { target ->
            LedgerBackup.restore(target,LedgerBackup.decode(bytes.inputStream()))
            val restored=target.all().single { it.uid==saved.uid }
            assertEquals(RecordState.REVIEW,restored.state);assertNull(restored.wallet!!.accountUid)
            assertTrue(restored.walletEffects.isEmpty());assertEquals(saved.bill.receipt,restored.bill.receipt)
            assertEquals(before,target.accounts().associate { it.uid to it.amountCents })
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun serviceOnlyPagesSaveNothingAndExplicitShortcutCanReadTheNestedSummary()=runBlocking {
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val page=lines("先享后付服务通知","优先从零钱支付","服务费用：99.00元")+
            lines("为你推荐","支付成功","交易金额：888.00元","通过微信零钱扣款",top=900)
        for(origin in listOf("image","shake:com.tencent.mm")) {
            val (_,result)=recognize(page,origin)
            assertTrue(result==IngestResult.Ignored || result is IngestResult.Skipped)
            assertEquals(baseline,app.store.all().map { it.id }.toSet())
            assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
        }
        val (_,result)=recognize(nested(),"shortcut:com.tencent.mm")
        val saved=record(result)
        assertEquals(2345L,saved.bill.amountCents);assertEquals(RecordState.REVIEW,saved.state)
        assertEquals("com.tencent.mm",saved.bill.receipt!!.sourceApp!!.value)
        assertEquals("NESTED_BANK",saved.bill.receipt!!.blockOrigin!!.kind)
        assertEquals(time.recognitionAt,saved.bill.occurredAt)
        assertTrue(saved.walletEffects.isEmpty());assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
    }

    @Test fun sensitiveOuterPageStillStopsBeforeInferenceAndCannotUseTheIsolatedDebitCard()=runBlocking {
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val page=PaymentPage(PaymentApps.WECHAT,7,"synthetic.readonly.Viewer",mixed().map { it.text }+"请输入支付密码")
        assertFalse(CapturePrivacy.manualAllowed(page))
        val task=app.tasks.create(Source.OCR,"shake:com.tencent.mm");tasks+=task
        val error=runCatching { CaptureProcessor(app).processPrepared(image(mixed()),Source.OCR,"shake:com.tencent.mm",task) {
            CapturePrivacy.manualAllowed(page)
        } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertNull(app.tasks.recognitionTime(task));assertNull(app.tasks.image(task))
        assertEquals(baseline,app.store.all().map { it.id }.toSet())
        assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
    }

    private fun pixelRows(top:Int,vararg text:String)=text.mapIndexed { i,s -> LocalOcrLine(s,60,top+i*65,1020,top+i*65+55) }
    private fun pixelEvidence(name:String,task:Long,saved:LedgerRecord,bytes:ByteArray) {
        val directory=java.io.File(app.cacheDir,"synthetic-t11-evidence").apply { mkdirs() }
        java.io.File(directory,"$name.png").writeBytes(bytes)
        val batches=app.store.recognitionBatchBackup()
        val frozen=(0 until batches.length()).map { batches.getJSONObject(it) }.single { it.getString("operationKey")==app.tasks.operationKey(task) }
        java.io.File(directory,"$name.json").writeText(JSONObject().put("ocrPageText",frozen.getString("rawText"))
            .put("selectedText",saved.capture.text).put("state",saved.state.name).put("amountCents",saved.bill.amountCents)
            .put("merchant",saved.bill.merchant).put("direction",saved.bill.direction?.name ?: JSONObject.NULL)
            .put("occurredAt",saved.bill.occurredAt).put("receipt",ReceiptEvidenceCodec.json(saved.bill.receipt))
            .put("categoryId",saved.bill.categoryDecision?.categoryId ?: JSONObject.NULL)
            .put("categoryRuleId",saved.bill.categoryDecision?.ruleId ?: JSONObject.NULL)
            .put("walletEffects",JSONObject(saved.walletEffects)).toString(2))
    }

    @Test fun bundledPixelOcrKeepsTheRealDebitSeparateFromServicePendingBalanceAndMarketingText()=runBlocking {
        val ruleId=app.store.saveClassificationRule(ClassificationRule("","合成广告隔离",true,Direction.EXPENSE,"cat:expense:transport",0,
            listOf(ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"地铁")))).id
        val page=pixelRows(100,"先享后付服务通知","支付方式：优先从零钱支付","交易已关闭","待支付","交易金额：888.00元")+
            pixelRows(650,"扣款成功","交易金额：12.34元","商户名称：合成咖啡","扣费服务商：合成扣费平台",
                "银行作者：招商银行","商品说明：午餐","通过微信零钱扣款","账户余额：9999.00元","积分：12345")+
            pixelRows(1400,"为你推荐","商品说明：地铁优惠广告","交易金额：777.00元")
        val bytes=image(page,42f)
        val task=app.tasks.create(Source.IMAGE,"image");tasks+=task
        try {
            val result=CaptureProcessor(app,recognitionClock={time}).processPrepared(bytes,Source.IMAGE,"image",task) { true }
            val saved=record(result)
            assertEquals(BatchCounts(1,0,0,0),(result as IngestResult.Batch).result.counts)
            assertEquals(1,app.store.all().count { it.id !in baseline })
            assertEquals(1234L,saved.bill.amountCents);assertEquals("合成咖啡",saved.bill.merchant)
            assertEquals("合成扣费平台",saved.bill.receipt!!.serviceProvider!!.value)
            assertEquals("招商银行",saved.bill.receipt!!.receiptIssuer!!.value)
            assertEquals("午餐",saved.bill.receipt!!.productDescription!!.value)
            assertEquals("MIXED_DEBIT",saved.bill.receipt!!.blockOrigin!!.kind)
            assertEquals(time.recognitionAt,saved.bill.occurredAt)
            assertEquals("cat:expense:dining",saved.bill.categoryDecision!!.categoryId)
            assertNotEquals(ruleId,saved.bill.categoryDecision!!.ruleId)
            assertEquals(mapOf(wechat.uid to -1234L),saved.walletEffects)
            assertEquals(48766L,app.store.getAccount(wechat.id)!!.amountCents)
            assertEquals(50000L,app.store.getAccount(cash.id)!!.amountCents)
            assertFalse(saved.capture.text.contains("888.00"));assertFalse(saved.capture.text.contains("9999.00"))
            assertFalse(saved.capture.text.contains("广告"));assertFalse(saved.capture.text.contains("待支付"))
            assertArrayEquals(bytes,app.store.screenshot(saved.id))
            pixelEvidence("mixed",task,saved,bytes)
        } finally { app.store.deleteClassificationRule(ruleId) }
    }

    @Test fun bundledPixelOcrNestedBankAmountIgnoresOuterDatesAndKeepsUnknownDirectionAndAccountReview()=runBlocking {
        val page=pixelRows(30,"图片查看器","交易时间：2020-01-01 08:00","账单记录时间：2019-01-01 09:00")+
            pixelRows(450,"招商银行","交易金额：23.45元","账户余额：9999.00元","积分：12345")+
            pixelRows(1100,"为你推荐","商户名称：合成火锅广告","商品说明：地铁优惠","交易金额：888.00元")
        val bytes=image(page,42f)
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val task=app.tasks.create(Source.IMAGE,"image");tasks+=task
        val result=CaptureProcessor(app,recognitionClock={time}).processPrepared(bytes,Source.IMAGE,"image",task) { true }
        val saved=record(result)
        assertEquals(BatchCounts(0,0,1,0),(result as IngestResult.Batch).result.counts)
        assertEquals(1,app.store.all().count { it.id !in baseline })
        assertEquals(2345L,saved.bill.amountCents);assertNull(saved.bill.direction);assertEquals("",saved.bill.merchant)
        assertNull(saved.bill.receipt!!.merchant);assertNull(saved.bill.receipt!!.productDescription)
        assertEquals("招商银行",saved.bill.receipt!!.receiptIssuer!!.value)
        assertEquals("cmb",saved.bill.receipt!!.bank!!.bankCode);assertNull(saved.bill.receipt!!.sourceApp)
        assertEquals("NESTED_BANK",saved.bill.receipt!!.blockOrigin!!.kind)
        assertTrue(saved.bill.receipt!!.blockOrigin!!.top>300);assertTrue(saved.bill.receipt!!.blockOrigin!!.bottom<800)
        assertEquals(time.recognitionAt,saved.bill.occurredAt)
        assertEquals("RECOGNITION_FALLBACK",saved.bill.receipt!!.time!!.source)
        assertNull(saved.wallet!!.accountUid);assertTrue(saved.walletEffects.isEmpty())
        assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
        for(unrelated in listOf("2020","2019","9999","888","广告","积分"))assertFalse(unrelated,saved.capture.text.contains(unrelated))
        assertArrayEquals(bytes,app.store.screenshot(saved.id))
        pixelEvidence("nested",task,saved,bytes)
    }

    private fun userLayoutImage(nested:Boolean):ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(1080,1800,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun text(value:String,x:Float,y:Float,size:Float,color:Int=android.graphics.Color.BLACK,center:Boolean=false) {
            paint.color=color;paint.textSize=size;paint.textAlign=if(center)android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
            canvas.drawText(value,x,y,paint)
        }
        if(nested) {
            text("付款截图 · 1 / 1",540f,90f,40f,center=true)
            paint.color=android.graphics.Color.rgb(35,38,43);canvas.drawRect(50f,260f,1030f,820f,paint)
            val white=android.graphics.Color.WHITE
            text("服务号",540f,320f,36f,white,true);text("1条通知",90f,375f,30f,white)
            text("招商银行微银行: 交易成功提醒",90f,455f,36f,white)
            text("4分钟前",850f,455f,28f,android.graphics.Color.LTGRAY)
            text("交易金额:",90f,545f,36f,white);text("人民币23.45",500f,545f,42f,white)
            text("全部通知 >",540f,705f,32f,white,true)
            text("2021/03/07 09:20",540f,940f,30f,center=true)
            text("贴图",90f,1120f,38f);text("推荐",740f,1120f,32f);text("关注",900f,1120f,32f)
            text("商户名称：合成推荐餐厅",90f,1220f,36f);text("交易说明：地铁广告",90f,1290f,36f)
            text("交易金额：777.00元",90f,1360f,36f)
        } else {
            text("「先享后付」服务使用通知",80f,110f,38f)
            text("商户名称：合成上卡商家",90f,180f,32f);text("服务商：合成上卡服务",90f,240f,32f)
            text("优先从零钱支付",90f,300f,32f);text("交易金额：888.00元",90f,360f,32f)
            text("交易时间：2020-01-01 08:00",90f,420f,32f);text("交易已关闭",90f,480f,32f)
            text("合成下卡咖啡",540f,690f,42f,center=true);text("通过零钱扣款",540f,780f,42f,center=true)
            text("￥",355f,930f,48f);text("12.34",430f,930f,84f)
            text("扣费服务",90f,1100f,36f);text("合成地铁服务方",490f,1100f,36f)
            text("扣费内容",90f,1180f,36f);text("午餐套餐",490f,1180f,36f)
            text("查看订单详情 >",540f,1300f,32f,center=true)
        }
        return java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray() }
    }

    /** Intercepts only observability; the production LocalRecognitionClient still performs OCR and parsing. */
    private suspend fun realUserLayout(name:String,bytes:ByteArray):Pair<Long,IngestResult> {
        val directory=java.io.File(app.cacheDir,"synthetic-t11-evidence").apply { mkdirs() }
        java.io.File(directory,"$name.png").writeBytes(bytes)
        val task=app.tasks.create(Source.IMAGE,"image");tasks+=task
        val local=LocalRecognitionClient(app)
        var actual:RecognitionResult?=null
        val backend=object:RecognitionBackend {
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String):RecognitionResult=error("frozen context required")
            override suspend fun recognize(image:ByteArray,onPrepared:(ByteArray)->Unit,stillAllowed:()->Boolean,
                livePaymentAt:Long?,policy:RecognitionPolicy,origin:String,recognitionTime:()->RecognitionTimeContext):RecognitionResult =
                local.recognizePrepared(image,onPrepared=onPrepared,stillAllowed=stillAllowed,livePaymentAt=livePaymentAt,
                    policy=policy,origin=origin,recognitionTime=recognitionTime).also { actual=it }
        }
        val result=CaptureProcessor(app,OcrClient(app,backend),recognitionClock={time}).processPrepared(bytes,Source.IMAGE,"image",task) { true }
        java.io.File(directory,"$name-ocr.json").writeText(JSONObject().put("rawText",actual!!.rawText)
            .put("candidateCount",actual!!.candidates.size).put("phaseTimingsMs",JSONObject(actual!!.phaseTimingsMs))
            .put("taskEvidence",app.tasks.recognitionEvidence(task)).toString(2))
        return task to result
    }

    @Test fun actualServiceUseNoticeAndTitlelessBalanceCardPixelKeepLowerMerchantProviderContentAndMoney()=runBlocking {
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成图三服务方隔离",true,Direction.EXPENSE,"cat:expense:transport",0,
            listOf(ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"地铁"))))
        val bytes=userLayoutImage(false)
        try {
            val (task,result)=realUserLayout("user-image3",bytes)
            val saved=record(result)
            assertEquals(BatchCounts(1,0,0,0),(result as IngestResult.Batch).result.counts)
            assertEquals(1,app.store.all().count { it.id !in baseline })
            assertEquals(1234L,saved.bill.amountCents);assertEquals("合成下卡咖啡",saved.bill.merchant)
            assertEquals("合成地铁服务方",saved.bill.receipt!!.serviceProvider!!.value)
            assertEquals("午餐套餐",saved.bill.receipt!!.transactionDescription!!.value)
            assertNull(saved.bill.receipt!!.receiptIssuer);assertNull(saved.bill.receipt!!.sourceApp)
            assertEquals("MIXED_DEBIT",saved.bill.receipt!!.blockOrigin!!.kind)
            assertTrue(saved.bill.paymentMethod!!.executed);assertEquals("WECHAT_BALANCE",saved.bill.paymentMethod!!.medium)
            assertEquals("cat:expense:dining",saved.bill.categoryDecision!!.categoryId)
            assertNotEquals(rule.id,saved.bill.categoryDecision!!.ruleId)
            assertEquals(time.recognitionAt,saved.bill.occurredAt)
            assertEquals(mapOf(wechat.uid to -1234L),saved.walletEffects)
            assertEquals(48766L,app.store.getAccount(wechat.id)!!.amountCents);assertEquals(50000L,app.store.getAccount(cash.id)!!.amountCents)
            for(unrelated in listOf("上卡","888","2020","查看订单详情"))assertFalse(unrelated,saved.capture.text.contains(unrelated))
            assertTrue(app.tasks.recognitionEvidence(task)!!.getJSONObject("phaseTimingsMs").has("save"))
            LedgerStore(app).use { assertEquals(saved,it.get(saved.id)) }
            val name="synthetic-image3-roundtrip-${UUID.randomUUID()}.db"
            try { LedgerStore(app,name).use { target ->
                LedgerBackup.restore(target,LedgerBackup.decode(LedgerBackup.encode(app.store,net.ankio.ledger.capture.ChannelSettings(app)).byteInputStream()))
                val restored=target.all().single { it.uid==saved.uid }
                assertEquals(saved.bill.receipt,restored.bill.receipt);assertTrue(restored.bill.paymentMethod!!.executed)
                assertEquals(saved.walletEffects,restored.walletEffects);assertEquals(48766L,target.getAccountByUid(wechat.uid)!!.amountCents)
            } } finally { app.deleteDatabase(name) }
            pixelEvidence("user-image3",task,saved,bytes)
        } finally { app.store.deleteClassificationRule(rule.id) }
    }

    @Test fun paymentScreenshotSheetMicroBankPixelIgnoresOuterUnlabelledDateAndRecommendationFeed()=runBlocking {
        val before=app.store.accounts().associate { it.uid to it.amountCents }
        val bytes=userLayoutImage(true)
        val (task,result)=realUserLayout("user-image1",bytes)
        val saved=record(result)
        assertEquals(BatchCounts(0,0,1,0),(result as IngestResult.Batch).result.counts)
        assertEquals(1,app.store.all().count { it.id !in baseline })
        assertEquals(2345L,saved.bill.amountCents);assertNull(saved.bill.direction);assertEquals("",saved.bill.merchant)
        assertEquals("招商银行",saved.bill.receipt!!.receiptIssuer!!.value)
        assertEquals("cmb",saved.bill.receipt!!.bank!!.bankCode);assertNull(saved.bill.receipt!!.bank!!.cardLast4)
        assertNull(saved.bill.receipt!!.merchant);assertNull(saved.bill.receipt!!.transactionDescription)
        assertEquals(time.recognitionAt,saved.bill.occurredAt);assertEquals("RECOGNITION_FALLBACK",saved.bill.receipt!!.time!!.source)
        assertEquals("NESTED_BANK",saved.bill.receipt!!.blockOrigin!!.kind)
        assertNull(saved.wallet!!.accountUid);assertTrue(saved.walletEffects.isEmpty())
        assertEquals(before,app.store.accounts().associate { it.uid to it.amountCents })
        for(unrelated in listOf("2021","777","地铁","推荐","关注"))assertFalse(unrelated,saved.capture.text.contains(unrelated))
        assertArrayEquals(bytes,app.store.screenshot(saved.id))
        pixelEvidence("user-image1",task,saved,bytes)
    }
}
