package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClassificationPreviewTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun draftsUseSaveOrderingAndValidationWhileLongOrMissingBusinessFieldsStayEditableAndUnsaved() {
        val name="classification-preview-boundaries-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val first=store.saveClassificationRule(ClassificationRule("","合成先行",true,Direction.EXPENSE,"cat:expense:dining",0,
                listOf(ClassificationCondition(value="合成平台"))))
            val second=store.saveClassificationRule(first.copy(id="",name="合成后置",categoryId="cat:expense:shopping"))
            val before=store.classificationBackup()
            val input=ClassificationInput.hypothetical(Direction.EXPENSE,"合成平台",emptyList(),"IMAGE")
            val changed=store.previewClassification(input,first.copy(categoryId="cat:expense:transport",order=999))
            assertEquals(first.id,changed.preview.decision.ruleId)
            assertEquals("交通",changed.category.name)
            assertEquals(first.order,changed.preview.rules.single { it.rule.id==first.id }.rule.order)
            val appended=store.previewClassification(input,second.copy(id="",name="合成未保存"))
            assertEquals(first.id,appended.preview.decision.ruleId)
            assertEquals(first.id,appended.preview.rules.single { it.rule.id==appended.draftRuleId }.interceptedByRuleId)
            val invalid=listOf(first.copy(categoryId="cat:income:salary") to "RULE_TARGET",
                first.copy(id="missing-synthetic-rule") to "RULE_NOT_FOUND",first.copy(conditions=emptyList()) to "RULE_INVALID")
            invalid.forEach { (draft,code) ->
                val failure=runCatching { store.previewClassification(input,draft) }.exceptionOrNull()
                assertEquals(code,(failure as ClassificationException).code)
            }
            assertEquals(before,store.classificationBackup())
            val longRecord=store.saveEdited(LedgerRecord(capture=Capture(Source.IMAGE,"com.tencent.mm","商品说明：午餐\n微信支付"),
                bill=ParsedBill(100,Direction.EXPENSE,"合".repeat(201),"购物",1_798_776_000_000,null,true,"",true),
                state=RecordState.POSTED,note="午餐"))
            val draft=store.classificationRuleDraftFromBill(longRecord.id)
            assertTrue(draft.rule.conditions.isEmpty())
            assertEquals("IMAGE",draft.rule.source)
            assertNull(draft.input.platform);assertTrue(draft.input.orderContent.isEmpty())
            assertEquals(before,store.classificationBackup())
            assertEquals(CategorySource.FALLBACK,store.previewClassification(longRecord.id).preview.decision.source)
            assertTrue(runCatching { store.previewClassification(input.copy(direction=Direction.TRANSFER)) }.isFailure)
            assertTrue(runCatching { store.classificationRuleDraftFromBill(-1) }.isFailure)
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun billPrefillAndDraftTestsStayUnsavedUntilExplicitSaveAndKeepQueuedConfigurationFrozen() {
        val suffix=UUID.randomUUID().toString();val name="classification-draft-$suffix.db";val taskName="classification-draft-tasks-$suffix.db"
        try { LedgerStore(app,name).use { store -> RecognitionTasks(app,taskName,classificationStore=store).use { tasks ->
            val record=store.saveEdited(LedgerRecord(capture=Capture(Source.OCR,"shake:com.tencent.mm","广告：购物"),
                bill=ParsedBill(500,Direction.EXPENSE,"合成平台","餐饮",1_798_776_000_000,null,true,"",true,
                    receipt=ReceiptEvidence(productDescription=ReceiptField("午餐","商品说明：午餐"))),state=RecordState.POSTED))
            val queued=tasks.create(Source.OCR,"shake:com.tencent.mm")
            val frozen=tasks.classificationSnapshot(queued)
            val configuration=store.classificationBackup();val records=store.all();val funds=store.walletSnapshot()
            val draft=store.classificationRuleDraftFromBill(record.id)
            assertEquals("",draft.rule.id);assertEquals("cat:expense:dining",draft.rule.categoryId)
            assertEquals("LIVE:com.tencent.mm",draft.rule.source)
            assertEquals(listOf("合成平台","午餐"),draft.rule.conditions.map { it.value })
            assertTrue(draft.rule.conditions.all { it.operator==ClassificationOperator.EQUALS })
            val tested=store.previewClassification(record.id,draft.rule)
            assertEquals(tested.draftRuleId,tested.preview.decision.ruleId)
            assertEquals("餐饮",tested.category.name)
            assertEquals(configuration,store.classificationBackup());assertEquals(records,store.all());assertEquals(funds,store.walletSnapshot())
            assertEquals(frozen,tasks.classificationSnapshot(queued))
            // Changing a bill category is independent of the unsaved draft.
            store.saveEdited(record.copy(bill=record.bill.copy(category="购物")),manualCategorySelected=true)
            assertEquals(configuration,store.classificationBackup())
            val saved=store.saveClassificationRule(draft.rule)
            assertEquals(frozen,tasks.classificationSnapshot(queued))
            val future=tasks.create(Source.OCR,"shake:com.tencent.mm")
            assertEquals(saved,tasks.classificationSnapshot(future)!!.customRules.single { it.id==saved.id })
            assertEquals(CategorySource.FALLBACK,ClassificationEngine.classify(frozen!!,draft.input).source)
            assertEquals(saved.id,store.previewClassification(record.id).preview.decision.ruleId)
            assertEquals("购物",store.get(record.id)!!.bill.category)
            RecognitionTasks(app,taskName,classificationStore=store).use { reopened -> assertEquals(frozen,reopened.classificationSnapshot(queued)) }
        } } } finally { app.deleteDatabase(taskName);app.deleteDatabase(name) }
    }

    @Test fun existingManualBillIsOnlyHypotheticalInputAndPreviewPreservesEveryStoredState() {
        val name="classification-preview-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val wallet=store.saveAccount(LedgerAccount(name="合成预览资金",amountCents=20_000))
            val rule=store.saveClassificationRule(ClassificationRule("","合成午餐",true,Direction.EXPENSE,"cat:expense:dining",0,
                listOf(ClassificationCondition(value="合成平台"),
                    ClassificationCondition(ClassificationField.ORDER_CONTENT,ClassificationOperator.EQUALS,"午餐")),source="IMAGE:wechat"))
            store.holdClassificationSnapshot("synthetic-preview-held")
            val receipt=ReceiptEvidence(productDescription=ReceiptField("午餐","商品说明：午餐"),
                receiptIssuer=ReceiptField("微信支付","支付机构：微信支付"))
            val bill=ParsedBill(500,Direction.EXPENSE,"合成平台","购物",1_798_776_000_000,null,true,"",true,receipt=receipt)
            val record=store.saveEdited(LedgerRecord(capture=Capture(Source.IMAGE,"com.tencent.mm","广告：打车"),bill=bill,
                state=RecordState.POSTED,note="打车"),walletAccountUid=wallet.uid,manualWalletSelected=true)
            assertTrue(record.bill.categoryDecision!!.manual)
            val records=store.all();val accounts=store.accounts();val funds=store.walletSnapshot()
            val categories=store.categoryDirectory();val config=store.classificationBackup();val history=store.captureHistory(record.id)
            val result=store.previewClassification(record.id)
            assertEquals(rule.id,result.preview.decision.ruleId)
            assertEquals("餐饮",result.category.name)
            assertEquals(listOf("午餐"),result.preview.input.orderContent)
            assertEquals(Source.IMAGE,result.preview.input.captureSource)
            assertNull(result.preview.input.sourcePackage)
            assertEquals("wechat",result.preview.input.platform)
            assertFalse(result.preview.decision.manual)
            assertEquals(listOf(true,true),result.preview.rules.single { it.rule.id==rule.id }.conditions.map { it.matched })
            fun assertUnchanged(reader:LedgerStore) {
                assertEquals(records,reader.all());assertEquals(accounts,reader.accounts());assertEquals(funds,reader.walletSnapshot())
                assertEquals(categories,reader.categoryDirectory());assertEquals(config,reader.classificationBackup())
                assertEquals(history,reader.captureHistory(record.id))
                assertEquals("购物",reader.get(record.id)!!.bill.category)
                assertTrue(reader.get(record.id)!!.bill.categoryDecision!!.manual)
            }
            assertUnchanged(store)
            LedgerStore(app,name).use(::assertUnchanged)
        } } finally { app.deleteDatabase(name) }
    }
}
