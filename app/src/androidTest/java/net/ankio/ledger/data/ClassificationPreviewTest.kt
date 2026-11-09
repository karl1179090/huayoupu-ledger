package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClassificationPreviewTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()

    private fun utf8(text:String)=text.toByteArray(Charsets.UTF_8).size
    private fun capacityWire(snapshot:ClassificationSnapshot,replacements:List<CategoryReplacement>)=ClassificationCodec.snapshot(snapshot)
        .put("categoryReplacements",JSONArray().apply { replacements.forEach { put(JSONObject().put("categoryId",it.categoryId)
            .put("categoryName",it.categoryName).put("direction",it.direction.name).put("replacementId",it.replacementId)) } }).toString()

    /** Exact independent wire-size fixture; long Chinese names keep byte/character counts distinct. */
    private fun capacityReplacements(snapshot:ClassificationSnapshot,bytes:Int):List<CategoryReplacement> {
        fun entry(index:Int,name:String)=CategoryReplacement("synthetic:preview-capacity:$index",name,Direction.EXPENSE,"cat:expense:other")
        fun json(entry:CategoryReplacement)=JSONObject().put("categoryId",entry.categoryId).put("categoryName",entry.categoryName)
            .put("direction",entry.direction.name).put("replacementId",entry.replacementId).toString()
        fun text(count:Int)="合".repeat(count/3)+(if(count%3==2)"é" else "x".repeat(count%3))
        val entries=mutableListOf<CategoryReplacement>()
        var size=utf8(capacityWire(snapshot,emptyList()))
        while(true) {
            val candidate=entry(entries.size,"合".repeat(1024))
            val added=utf8(json(candidate))+(if(entries.isEmpty())0 else 1)
            if(size+added>bytes)break
            entries+=candidate;size+=added
        }
        size-=utf8(json(entries.removeAt(entries.lastIndex)))+1
        val first=entry(entries.size,"");val second=entry(entries.size+1,"")
        val budget=bytes-size-utf8(json(first))-utf8(json(second))-2
        val firstBytes=minOf(3072,budget-1)
        entries+=first.copy(categoryName=text(firstBytes));entries+=second.copy(categoryName=text(budget-firstBytes))
        assertEquals(bytes,utf8(capacityWire(snapshot,entries)))
        assertTrue(entries.all { it.categoryName.length in 1..1024 })
        return entries
    }

    @Test fun chineseDraftOneByteBeyondFinalConfigurationRejectsPreviewAndSaveWithoutAnyMutation() =
        previewAndSaveCapacity(1)

    @Test fun chineseDraftAtExactFinalConfigurationLimitPreviewsAndSavesAndRemainsReadable() =
        previewAndSaveCapacity(0)

    private fun previewAndSaveCapacity(excess:Int) {
        val suffix=UUID.randomUUID().toString();val name="classification-preview-capacity-$suffix.db";val taskName="classification-preview-capacity-tasks-$suffix.db"
        try {
            lateinit var expected:ValidatedBackup
            lateinit var expectedTask:String
            LedgerStore(app,name).use { store ->
                val wallet=store.saveAccount(LedgerAccount(name="合成预览容量资金",amountCents=10_000))
                store.setDefaultWallet(wallet.uid)
                store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","合成凭证"),
                    bill=ParsedBill(100,Direction.EXPENSE,"合成容量账单","其他",1_798_776_000_000,null,true,"",true),
                    state=RecordState.POSTED),walletAccountUid=wallet.uid,manualWalletSelected=true)
                val seed=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                val baseline=seed.classification!!.configuration.copy(version="seed")
                val draft=ClassificationRule("","合成中文容量草稿",true,Direction.EXPENSE,"cat:expense:other",0,
                    List(20) { ClassificationCondition(value="合".repeat(200)) })
                // New persisted rule IDs and versions are UUIDs, each36 ASCII bytes.
                val future=baseline.copy(version="x".repeat(36),customRules=listOf(draft.copy(id="x".repeat(36))))
                val replacements=capacityReplacements(future,2_000_000+excess)
                val baselineBytes=utf8(capacityWire(baseline,replacements))
                assertTrue(baselineBytes<2_000_000)
                assertTrue(capacityWire(baseline,replacements).length<baselineBytes)
                LedgerBackup.restore(store,seed.copy(classification=ClassificationBackup(baseline,emptyList(),replacements)))
                RecognitionTasks(app,taskName,classificationStore=store).use { tasks ->
                    val queued=tasks.create(Source.IMAGE,"image")
                    val frozen=tasks.classificationSnapshot(queued)
                    val before=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                    val beforeTask=tasks.snapshot().toString()
                    val input=ClassificationInput.hypothetical(Direction.EXPENSE,"合".repeat(200),emptyList(),"IMAGE")
                    if(excess>0) {
                        for(action in listOf<()->Any?>({ store.previewClassification(input,draft) },{ store.saveClassificationRule(draft) })) {
                            val failure=runCatching(action).exceptionOrNull()
                            assertTrue("Preview and Save must reject a final2000001-byte configuration",failure is ClassificationException)
                            assertEquals("RULE_INVALID",(failure as ClassificationException).code)
                            assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream()))
                            assertEquals(beforeTask,tasks.snapshot().toString())
                            assertEquals(frozen,tasks.classificationSnapshot(queued))
                        }
                        expected=before
                    } else {
                        val preview=store.previewClassification(input,draft)
                        assertEquals(CategorySource.CUSTOM_RULE,preview.preview.decision.source)
                        assertTrue(preview.preview.rules.single { it.selected }.conditions.all { it.matched })
                        assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream()))
                        assertEquals(beforeTask,tasks.snapshot().toString())
                        val saved=store.saveClassificationRule(draft)
                        expected=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                        val afterClassification=requireNotNull(expected.classification)
                        assertEquals(2_000_000,utf8(capacityWire(afterClassification.configuration,replacements)))
                        assertEquals(saved,afterClassification.configuration.customRules.single())
                        assertEquals(before,expected.copy(classification=before.classification))
                        assertEquals(before.classification!!.snapshots,afterClassification.snapshots)
                        assertEquals(beforeTask,tasks.snapshot().toString())
                        assertEquals(frozen,tasks.classificationSnapshot(queued))
                    }
                    expectedTask=beforeTask
                }
            }
            LedgerStore(app,name).use { reopened -> RecognitionTasks(app,taskName,classificationStore=reopened).use { tasks ->
                assertEquals(expected,LedgerBackup.decode(LedgerBackup.encode(reopened,app.channels).byteInputStream()))
                assertEquals(expected.classification!!.configuration,reopened.classificationRules())
                assertEquals(expectedTask,tasks.snapshot().toString())
            } }
        } finally { app.deleteDatabase(taskName);app.deleteDatabase(name) }
    }

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
