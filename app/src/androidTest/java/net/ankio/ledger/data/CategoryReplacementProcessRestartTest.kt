package net.ankio.ledger.data

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.*
import net.ankio.ledger.ocr.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.UUID

/** Run a and b as separate instrumentation invocations around force-stop for process-death acceptance. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CategoryReplacementProcessRestartTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val state get()=app.getSharedPreferences("synthetic-category-replacement-restart",0)
    @Test fun aCreateQueuedSnapshotBeforeProcessDeath()=runBlocking {
        app.taskRecovery.await();assertFalse("Previous synthetic restart case must be completed",state.contains("task"))
        val merchant="合成替代恢复${UUID.randomUUID()}"
        val old=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成替代恢复钱包",amountCents=6000))
        app.store.setDefaultWallet(wallet.uid)
        val original=app.store.saveCategory(name="合成恢复原用途${UUID.randomUUID().toString().take(8)}",direction=Direction.EXPENSE)
        val middle=app.store.saveCategory(name="合成恢复中间${UUID.randomUUID().toString().take(8)}",direction=Direction.EXPENSE)
        val final=app.store.saveCategory(name="合成恢复替代${UUID.randomUUID().toString().take(8)}",direction=Direction.EXPENSE)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成替代恢复规则",true,Direction.EXPENSE,original.id,0,listOf(ClassificationCondition(value=merchant),ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.NOT_CONTAINS,"打车")),source="IMAGE:wechat"))
        val task=app.tasks.create(Source.IMAGE,"image");assertTrue(app.tasks.queued(task,"synthetic-restart".toByteArray()))
        val version=app.tasks.classificationSnapshot(task)!!.version
        app.store.confirmCategoryDeletion(original.id,middle.id,app.store.previewCategoryDeletion(original.id).previewToken)
        val second=app.store.previewCategoryDeletion(middle.id)
        assertEquals(1L,second.queuedConfigurationCount)
        app.store.confirmCategoryDeletion(middle.id,final.id,second.previewToken)
        // Future mutable rules cannot affect the still-frozen queued task.
        app.store.saveClassificationRule(rule.copy(enabled=false,categoryId="cat:expense:shopping",matchMode=ClassificationMatchMode.ANY,source="IMAGE:alipay",
            conditions=listOf(ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.EQUALS,"未来条件"))))
        assertTrue(state.edit().putLong("task",task).putString("rule",rule.id).putString("merchant",merchant).putString("version",version)
            .putString("original",original.id).putString("target",final.id).putString("targetName",final.name).putString("wallet",wallet.uid).putString("oldDefault",old).putInt("pid",Process.myPid()).commit())
    }
    @Test fun bResumeQueuedSnapshotAfterProcessDeath()=runBlocking {
        app.taskRecovery.await()
        val task=state.getLong("task",0);assertTrue("Run phase a first",task>0)
        if(InstrumentationRegistry.getArguments().getString("expectProcessRestart")=="true")assertNotEquals(state.getInt("pid",0),Process.myPid())
        val merchant=state.getString("merchant",null)!!;val wallet=app.store.getAccountByUid(state.getString("wallet",null)!!)!!
        val key=app.tasks.operationKey(task)!!
        try {
            assertEquals("QUEUED",app.tasks.status(task))
            val backend=RecognitionBackend { image,prepared,_,_,_,_ -> prepared(image);RecognitionResult("合成替代恢复支付",ParsedBill(700,Direction.EXPENSE,merchant,"其他",1_798_776_000_000,"SYNTHETIC-REPLACEMENT-$task",true,"",true,
                receipt=ReceiptEvidence(productDescription=ReceiptField("合成午餐","商品说明：合成午餐"),receiptIssuer=ReceiptField("微信支付","支付机构：微信支付"))),screenshot=image) }
            val result=CaptureProcessor(app,OcrClient(app,backend)).processPrepared(app.tasks.image(task)!!,Source.IMAGE,"image",task) { true } as IngestResult.Saved
            assertEquals(state.getString("targetName",null),result.record.bill.category)
            assertEquals(state.getString("target",null),result.record.bill.categoryDecision!!.categoryId)
            assertEquals(CategorySource.CUSTOM_RULE,result.record.bill.categoryDecision!!.source)
            assertEquals(state.getString("original",null),app.tasks.classificationSnapshot(task)!!.customRules.single { it.id==state.getString("rule",null) }.categoryId)
            val frozen=app.tasks.classificationSnapshot(task)!!.customRules.single { it.id==state.getString("rule",null) }
            assertEquals(frozen,result.record.bill.categoryDecision!!.rule)
            assertEquals("IMAGE:wechat",frozen.source);assertEquals(ClassificationMatchMode.ALL,frozen.matchMode)
            assertEquals(2,frozen.conditions.size)
            assertEquals(state.getString("version",null),result.record.bill.categoryDecision!!.ruleVersion)
            assertEquals(5300L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(IngestResult.DuplicateSkipped,CaptureProcessor(app,OcrClient(app,backend)).processPrepared("synthetic-retry".toByteArray(),Source.IMAGE,"image",task) { true })
            assertEquals(5300L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==result.record.uid })
        } finally {
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(key));app.tasks.delete(task)
            app.store.deleteClassificationRule(state.getString("rule",null)!!);app.store.setDefaultWallet(state.getString("oldDefault",null))
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
            val target=state.getString("target",null)!!
            app.store.confirmCategoryDeletion(target,"cat:expense:other",app.store.previewCategoryDeletion(target).previewToken)
            assertTrue(state.edit().clear().commit())
        }
    }
}
