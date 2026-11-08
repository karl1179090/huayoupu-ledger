package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Only external OCR is synthetic; task snapshots, coordinator, funds and ledger queries are real. */
@RunWith(AndroidJUnit4::class)
class CompoundClassificationTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun queuedCompoundRulesUseOnlyReceiptFieldsAndPreserveFundsAndOriginalText()=runBlocking {
        app.taskRecovery.await()
        val suffix=UUID.randomUUID().toString();val merchant="合成综合平台$suffix"
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成订单资金",amountCents=10_000))
        app.store.setDefaultWallet(wallet.uid)
        val ride=app.store.saveClassificationRule(ClassificationRule("","合成出行$suffix",true,Direction.EXPENSE,"cat:expense:transport",0,
            listOf(ClassificationCondition(value=merchant),ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"打车")),source="IMAGE:wechat"))
        val meal=app.store.saveClassificationRule(ride.copy(id="",name="合成餐饮$suffix",categoryId="cat:expense:dining",
            conditions=listOf(ClassificationCondition(value=merchant),ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.NOT_CONTAINS,"打车"))))
        val tasks=mutableListOf<Long>()
        try {
            val queued=app.tasks.create(Source.IMAGE,"image").also(tasks::add)
            val frozen=app.tasks.classificationSnapshot(queued)!!
            app.store.saveClassificationRule(meal.copy(enabled=false,categoryId="cat:expense:shopping"))
            RecognitionTasks(app).use { assertEquals(frozen,it.classificationSnapshot(queued)) }
            val receipt=ReceiptEvidence(productDescription=ReceiptField("Ａ＋Ｂ\t午餐","商品说明：Ａ＋Ｂ\t午餐"),
                receiptIssuer=ReceiptField("微信支付","支付机构：微信支付"))
            val base=ParsedBill(1250,Direction.EXPENSE,merchant,"其他",1_798_776_000_000,"SYNTHETIC-COMPOUND-$suffix",true,"",true,receipt=receipt)
            suspend fun recognize(bill:ParsedBill,task:Long,origin:String="image"):LedgerRecord {
                val backend=RecognitionBackend { image,prepared,_,_,_,_ -> prepared(image)
                    RecognitionResult("合成付款成功\n广告：打车\n手写记账备注：打车",bill,screenshot=image) }
                return (CaptureProcessor(app,OcrClient(app,backend)).processPrepared("synthetic-$task".toByteArray(),Source.IMAGE,origin,task) { true } as IngestResult.Saved).record
            }
            val first=recognize(base,queued)
            assertEquals("餐饮",first.bill.category);assertEquals(meal,frozen.customRules.single { it.id==meal.id })
            assertEquals(meal,first.bill.categoryDecision!!.rule)
            assertEquals("Ａ＋Ｂ\t午餐",app.store.get(first.id)!!.bill.receipt!!.productDescription!!.value)
            assertEquals(Direction.EXPENSE,first.bill.direction);assertEquals(wallet.uid,first.wallet!!.accountUid)
            assertEquals(8750L,app.store.getAccount(wallet.id)!!.amountCents)
            // Same merchant, another structured purpose, same source: a different category.
            val rideTask=app.tasks.create(Source.IMAGE,"image").also(tasks::add)
            val second=recognize(base.copy(amountCents=700,transactionId="SYNTHETIC-RIDE-$suffix",
                receipt=receipt.copy(productDescription=ReceiptField("打车","商品说明：打车"))),rideTask)
            assertEquals("交通",second.bill.category);assertEquals(ride.id,second.bill.categoryDecision!!.ruleId)
            assertEquals(8050L,app.store.getAccount(wallet.id)!!.amountCents)
            // No content: negative condition cannot match. An image origin cannot become a live app.
            val missingTask=app.tasks.create(Source.IMAGE,"com.tencent.mm").also(tasks::add)
            val third=recognize(base.copy(amountCents=300,transactionId="SYNTHETIC-MISSING-$suffix",receipt=null),missingTask,"com.tencent.mm")
            assertEquals(CategorySource.FALLBACK,third.bill.categoryDecision!!.source)
            assertEquals(7750L,app.store.getAccount(wallet.id)!!.amountCents)
            // A note edit remains outside matching and does not replace category provenance.
            val edited=app.store.saveEdited(first.copy(note="打车"))
            assertEquals(first.bill.categoryDecision,edited.bill.categoryDecision)
            assertEquals(7750L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(3,app.store.walletMovements(wallet.uid).count { it.recordUid in setOf(first.uid,second.uid,third.uid) })
        } finally {
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            tasks.forEach { id -> app.tasks.operationKey(id)?.let { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) };app.tasks.delete(id) }
            app.store.deleteClassificationRule(ride.id);app.store.deleteClassificationRule(meal.id)
            app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }

    @Test fun builtinCopiesAreUnsavedEditableDraftsAndEnableStateIsFrozenAndBackedUp() {
        val name="compound-source-${UUID.randomUUID()}.db";val destination="compound-dest-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { source -> LedgerStore(app,destination).use { target ->
            val original=source.classificationRules();val builtin=original.builtinRules.first()
            val frozen=source.holdClassificationSnapshot("synthetic-compound-backup")
            source.setBuiltinClassificationRuleEnabled(builtin.id,false)
            val draft=source.copyBuiltinClassificationRule(builtin.id)
            assertTrue(draft.id.isBlank());assertTrue(draft.enabled)
            assertEquals(builtin.conditions,draft.conditions);assertEquals(builtin.source,draft.source)
            assertTrue(source.classificationRules().customRules.isEmpty())
            val copied=source.saveClassificationRule(draft.copy(conditions=draft.conditions+ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.NOT_CONTAINS,"广告"),
                matchMode=ClassificationMatchMode.ANY,source="IMAGE:alipay"))
            val snapshot=source.classificationRules()
            assertFalse(snapshot.builtinRules.single { it.id==builtin.id }.enabled)
            assertEquals(frozen,source.classificationBackup().snapshots.single().snapshot)
            assertTrue(frozen.builtinRules.single { it.id==builtin.id }.enabled)
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            LedgerBackup.restore(target,backup)
            assertEquals(snapshot,target.classificationRules())
            assertEquals(copied,target.classificationRules().customRules.single())
            assertEquals(frozen,target.classificationBackup().snapshots.single().snapshot)
            assertEquals(0,LedgerBackup.restore(target,backup))
            assertEquals(snapshot,target.classificationRules())
            val legacy=ClassificationCodec.snapshot(original)
            assertEquals(original,ClassificationCodec.readSnapshot(legacy))
            assertTrue(source.accounts().isEmpty());assertTrue(target.all().isEmpty())
        } } } finally { app.deleteDatabase(name);app.deleteDatabase(destination) }
    }
    @Test fun oversizedRestoredConfigurationIsRejectedWithoutChangingTheLedger() {
        val name="compound-limit-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val original=store.classificationRules()
            val backup=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
            val conditions=List(20) { ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.CONTAINS,"合".repeat(200)) }
            val rules=List(450) { index -> ClassificationRule("synthetic-limit-$index","合成容量$index",true,Direction.EXPENSE,"cat:expense:dining",index,conditions) }
            val oversized=backup.copy(classification=ClassificationBackup(original.copy(customRules=rules),emptyList()))
            assertTrue("An unreadable configuration must not be committed",runCatching { LedgerBackup.restore(store,oversized) }.isFailure)
            assertEquals(original,store.classificationRules())
            assertTrue(store.all().isEmpty());assertTrue(store.accounts().isEmpty())
        } } finally { app.deleteDatabase(name) }
    }

}
