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

/** Storage capacity is observed through public saves, backups and durable task snapshots. */
@RunWith(AndroidJUnit4::class)
class ClassificationCapacityTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun bytes(text:String)=text.toByteArray(Charsets.UTF_8).size
    private fun textWithBytes(count:Int)="合".repeat(count/3)+(if(count%3==2)"é" else "x".repeat(count%3))
    private fun replacementJson(entry:CategoryReplacement)=JSONObject().put("categoryId",entry.categoryId)
        .put("categoryName",entry.categoryName).put("direction",entry.direction.name).put("replacementId",entry.replacementId)
    private fun payload(snapshot:ClassificationSnapshot,entries:List<CategoryReplacement>)=ClassificationCodec.snapshot(snapshot)
        .put("categoryReplacements",JSONArray().apply { entries.forEach { put(replacementJson(it)) } }).toString()

    /** Produce an exact wire-size fixture without bypassing restore or any field constraint. */
    private fun replacements(snapshot:ClassificationSnapshot,limit:Int,character:String="合"):List<CategoryReplacement> {
        val entries=mutableListOf<CategoryReplacement>()
        fun entry(index:Int,name:String)=CategoryReplacement("synthetic:capacity:$index",name,Direction.EXPENSE,"cat:expense:other")
        var size=bytes(payload(snapshot,emptyList()))
        while(true) {
            val candidate=entry(entries.size,character.repeat(1024/character.length))
            val added=bytes(replacementJson(candidate).toString())+(if(entries.isEmpty())0 else 1)
            if(size+added>limit)break
            entries+=candidate;size+=added
        }
        val removed=entries.removeAt(entries.lastIndex)
        size-=bytes(replacementJson(removed).toString())+1
        val first=entry(entries.size,"");val second=entry(entries.size+1,"")
        val budget=limit-size-bytes(replacementJson(first).toString())-bytes(replacementJson(second).toString())-2
        val firstBytes=minOf(3072,budget-1)
        entries+=first.copy(categoryName=textWithBytes(firstBytes));entries+=second.copy(categoryName=textWithBytes(budget-firstBytes))
        assertEquals(limit,bytes(payload(snapshot,entries)))
        assertTrue(entries.all { it.categoryName.length in 1..1024 })
        return entries
    }

    private fun snapshotWithBytes(baseline:ClassificationSnapshot,limit:Int,prefix:String="synthetic"):ClassificationSnapshot {
        fun rule(index:Int,full:Boolean)=ClassificationRule("synthetic:capacity-rule:$prefix:$index","合成容量快照$prefix$index",true,Direction.EXPENSE,
            "cat:expense:other",index,List(20) { ClassificationCondition(value=if(full)"合".repeat(200) else "x") })
        val rules=mutableListOf<ClassificationRule>()
        var size=bytes(ClassificationCodec.snapshot(baseline.copy(customRules=emptyList())).toString())
        while(true) {
            val candidate=rule(rules.size,true)
            val added=bytes(ClassificationCodec.rule(candidate).toString())+(if(rules.isEmpty())0 else 1)
            if(size+added>limit)break
            rules+=candidate;size+=added
        }
        size-=bytes(ClassificationCodec.rule(rules.removeAt(rules.lastIndex)).toString())+1
        repeat(2) {
            val candidate=rule(rules.size,false)
            rules+=candidate;size+=bytes(ClassificationCodec.rule(candidate).toString())+1
        }
        var remaining=limit-size
        for(index in rules.size-2 until rules.size)rules[index]=rules[index].copy(conditions=rules[index].conditions.map {
            val added=minOf(599,remaining);remaining-=added;it.copy(value=textWithBytes(added+1))
        })
        assertEquals(0,remaining)
        return baseline.copy(customRules=rules).also { assertEquals(limit,bytes(ClassificationCodec.snapshot(it).toString())) }
    }

    private fun assertCapacityFailure(action:()->Unit) {
        val failure=runCatching(action).exceptionOrNull()
        assertTrue("Oversized UTF-8 payload must report a normal rule validation error",failure is ClassificationException)
        assertEquals("RULE_INVALID",(failure as ClassificationException).code)
    }

    @Test fun saveCannotOutgrowReadableFullConfigurationAndLeavesEveryPublicStateUnchanged() {
        val name="classification-capacity-save-${UUID.randomUUID()}.db"
        try {
            lateinit var before:ValidatedBackup
            LedgerStore(app,name).use { store ->
                val seed=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                val configuration=seed.classification!!.configuration
                val mappings=replacements(configuration,2_000_000)
                LedgerBackup.restore(store,seed.copy(classification=seed.classification.copy(replacements=mappings)))
                assertEquals(configuration,store.classificationRules())
                store.holdClassificationSnapshot("synthetic-capacity-existing")
                before=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                val draft=ClassificationRule("","合成容量规则",true,Direction.EXPENSE,"cat:expense:other",0,
                    List(20) { ClassificationCondition(value="合".repeat(200)) })
                val failure=runCatching { store.saveClassificationRule(draft) }.exceptionOrNull()
                assertTrue("Save must reject the oversized complete UTF-8 configuration",failure is ClassificationException)
                assertEquals("RULE_INVALID",(failure as ClassificationException).code)
                assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream()))
                assertEquals(configuration,store.holdClassificationSnapshot("synthetic-capacity-future"))
                store.forgetClassificationSnapshot("synthetic-capacity-future")
            }
            LedgerStore(app,name).use { reopened ->
                assertEquals(before.classification!!.configuration,reopened.classificationRules())
                assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(reopened,app.channels).byteInputStream()))
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun asciiChineseAndEmojiOversizeBackupsRejectBeforeAnyLedgerOrWalletMutation() {
        val sourceName="classification-capacity-source-${UUID.randomUUID()}.db"
        val targetName="classification-capacity-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
            val wallet=source.saveAccount(LedgerAccount(name="合成容量恢复资金",amountCents=8000))
            source.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),
                bill=ParsedBill(100,Direction.EXPENSE,"合成容量商家","其他",1_798_776_000_000,null,true,"",true),
                state=RecordState.POSTED),walletAccountUid=wallet.uid,manualWalletSelected=true)
            val seed=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            val before=LedgerBackup.decode(LedgerBackup.encode(target,app.channels).byteInputStream())
            val classification=seed.classification!!
            for((character,limit) in listOf("x" to 2_000_001,"合" to 2_000_001,"😀" to 2_000_001,"合" to 5_576_620)) {
                val mappings=replacements(classification.configuration,limit,character)
                val oversized=classification.copy(replacements=mappings)
                assertCapacityFailure { ClassificationCodec.backup(oversized) }
                val wire=JSONObject().put("configuration",ClassificationCodec.snapshot(classification.configuration))
                    .put("categoryReplacements",JSONArray().apply { mappings.forEach { put(replacementJson(it)) } }).put("snapshots",JSONArray())
                assertCapacityFailure { ClassificationCodec.readBackup(wire) }
                assertCapacityFailure { ClassificationCodec.readSnapshot(payload(classification.configuration,mappings)) }
                var mediaRestored=false
                assertCapacityFailure { LedgerBackup.restore(target,seed.copy(classification=oversized)) { mediaRestored=true } }
                assertFalse(mediaRestored)
                assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(target,app.channels).byteInputStream()))
                assertTrue(target.all().isEmpty());assertTrue(target.accounts().isEmpty())
            }
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun maximumConfigurationSurvivesActualQueuedTaskAndReopenWhileOversizedHoldIsAtomic() {
        val name="classification-capacity-queue-${UUID.randomUUID()}.db"
        val taskName="classification-capacity-tasks-${UUID.randomUUID()}.db"
        val nodeEvidence=NodeSnapshot("com.tencent.mm",7,"com.tencent.mm.SyntheticDetail",1,100,110,1_799_400_000_000,
            NodeEnvironment("synthetic",1,"synthetic-api34","synthetic-device"),List(200) { index ->
                CaptureNode(index,if(index==0)null else if(index<=3)0 else index%3+1,if(index==0)0 else if(index<=3)1 else 2,
                    NodeBounds(0,index*10,100,index*10+10),"合".repeat(70),className="c".repeat(256),resourceId="r".repeat(256))
            })
        assertTrue(bytes(NodeSnapshotCodec.json(nodeEvidence).toString())>100_000)
        try {
            lateinit var expected:ClassificationSnapshot
            var taskId=0L
            LedgerStore(app,name).use { store ->
                val seed=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                val baseline=seed.classification!!.configuration
                val overhead=bytes(payload(baseline,emptyList()))-bytes(ClassificationCodec.snapshot(baseline).toString())
                expected=snapshotWithBytes(baseline,2_000_000-overhead)
                assertEquals(2_000_000,bytes(payload(expected,emptyList())))
                LedgerBackup.restore(store,seed.copy(classification=ClassificationBackup(expected,emptyList())))
                assertEquals(expected,store.classificationRules())
                RecognitionTasks(app,taskName,classificationStore=store).use { tasks ->
                    taskId=tasks.create(Source.OCR,"shake:com.tencent.mm")
                    assertEquals(expected,tasks.classificationSnapshot(taskId))
                    assertTrue(tasks.queued(taskId,"synthetic-capacity-image".toByteArray(),nodeEvidence))
                    assertEquals(nodeEvidence.fingerprint,tasks.nodeSnapshot(taskId)!!.fingerprint)
                }
                val before=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                val oversized=expected.copy(version=expected.version+"x".repeat(overhead+1))
                assertCapacityFailure { store.holdClassificationSnapshot("synthetic-capacity-rejected",oversized) }
                assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream()))
            }
            LedgerStore(app,name).use { reopened -> RecognitionTasks(app,taskName,classificationStore=reopened).use { tasks ->
                assertEquals(expected,reopened.classificationRules())
                assertEquals(expected,tasks.classificationSnapshot(taskId))
                assertEquals(listOf(taskId to "shake:com.tencent.mm"),tasks.pendingQueued())
                assertEquals(nodeEvidence.fingerprint,tasks.nodeSnapshot(taskId)!!.fingerprint)
                assertEquals(1,tasks.snapshot().length())
                assertEquals(expected,reopened.classificationBackup().snapshots.single().snapshot)
                tasks.delete(taskId)
            } }
        } finally { app.deleteDatabase(taskName);app.deleteDatabase(name) }
    }

    @Test fun replacementOverheadCannotPartiallyDeleteCategoryOrMoveExistingBillAndFunds() {
        val name="classification-capacity-delete-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val category=store.saveCategory(name="合成容量删除分类",direction=Direction.EXPENSE)
            val wallet=store.saveAccount(LedgerAccount(name="合成容量删除资金",amountCents=9000))
            val record=store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),
                bill=ParsedBill(300,Direction.EXPENSE,"合成容量删除商家",category.name,1_798_776_000_000,null,true,"",true),
                state=RecordState.POSTED),walletAccountUid=wallet.uid,manualWalletSelected=true)
            val seed=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
            LedgerBackup.restore(store,seed.copy(classification=seed.classification!!.copy(
                replacements=replacements(seed.classification.configuration,2_000_000))))
            store.holdClassificationSnapshot("synthetic-capacity-deletion")
            val before=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
            val movements=store.walletMovements(wallet.uid)
            val preview=store.previewCategoryDeletion(category.id)
            assertEquals(1L,preview.billCount)
            assertCapacityFailure { store.confirmCategoryDeletion(category.id,"cat:expense:other",preview.previewToken) }
            assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream()))
            assertEquals(category.id,store.get(record.id)!!.bill.categoryDecision!!.categoryId)
            assertEquals(8700L,store.getAccount(wallet.id)!!.amountCents)
            assertEquals(movements,store.walletMovements(wallet.uid))
            LedgerStore(app,name).use { reopened ->
                assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(reopened,app.channels).byteInputStream()))
            }
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun individuallySafeBackupsCannotMergeIntoUnreadableRulesOrPartiallyImportFunds() {
        val sourceName="classification-capacity-merge-source-${UUID.randomUUID()}.db"
        val targetName="classification-capacity-merge-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
            for((store,prefix) in listOf(source to "incoming",target to "local")) {
                val seed=LedgerBackup.decode(LedgerBackup.encode(store,app.channels).byteInputStream())
                val snapshot=snapshotWithBytes(seed.classification!!.configuration,1_100_000,prefix)
                LedgerBackup.restore(store,seed.copy(classification=ClassificationBackup(snapshot,emptyList())))
                store.holdClassificationSnapshot("synthetic-capacity-merge-$prefix")
            }
            val wallet=source.saveAccount(LedgerAccount(name="合成容量合并资金",amountCents=7000))
            source.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),
                bill=ParsedBill(200,Direction.EXPENSE,"合成容量合并商家","其他",1_798_776_000_000,null,true,"",true),
                state=RecordState.POSTED),walletAccountUid=wallet.uid,manualWalletSelected=true)
            val incoming=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            val before=LedgerBackup.decode(LedgerBackup.encode(target,app.channels).byteInputStream())
            assertCapacityFailure { LedgerBackup.restore(target,incoming) }
            assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(target,app.channels).byteInputStream()))
            assertTrue(target.accounts().isEmpty());assertTrue(target.all().isEmpty())
            LedgerStore(app,targetName).use { reopened ->
                assertEquals(before,LedgerBackup.decode(LedgerBackup.encode(reopened,app.channels).byteInputStream()))
            }
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }
}
