package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Durable classification semantics are observed at the store/backup boundary using real SQLite. */
@RunWith(AndroidJUnit4::class)
class CategoryReplacementTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun rule(id:String)=ClassificationRule("","合成替代规则",true,Direction.EXPENSE,id,0,listOf(ClassificationCondition(value="合成")))
    private fun bill()=ParsedBill(700,Direction.EXPENSE,"合成替代商家","其他",1_798_776_000_000,"SYNTHETIC-REPLACE",true,"",true)
    private fun delete(store:LedgerStore,id:String,target:String?)=store.confirmCategoryDeletion(id,target,store.previewCategoryDeletion(id).previewToken)

    @Test fun chainedReplacementSurvivesReopenAndBackupWithoutChangingFrozenDefinitions() {
        val sourceName="replace-source-${UUID.randomUUID()}.db";val targetName="replace-target-${UUID.randomUUID()}.db"
        try {
            lateinit var frozen:ClassificationSnapshot;lateinit var first:LedgerCategory;lateinit var final:LedgerCategory
            lateinit var oldBackup:ValidatedBackup
            LedgerStore(app,sourceName).use { source ->
                first=source.saveCategory(name="合成原用途",direction=Direction.EXPENSE)
                val middle=source.saveCategory(name="合成中间用途",direction=Direction.EXPENSE)
                final=source.saveCategory(name="合成最终用途",direction=Direction.EXPENSE)
                val originalRule=source.saveClassificationRule(rule(first.id));frozen=source.holdClassificationSnapshot("synthetic-chain")
                val record=(source.ingest(Capture(Source.IMAGE,"image","合成历史规则凭证"),source.classify(bill(),frozen)) as IngestResult.Saved).record
                assertEquals(originalRule,record.bill.categoryDecision!!.rule)
                oldBackup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
                delete(source,first.id,middle.id);delete(source,middle.id,final.id)
                assertEquals(final.id,source.classify(bill(),frozen).categoryDecision!!.categoryId)
                assertEquals(frozen,source.classificationBackup().snapshots.single().snapshot)
                assertEquals(2,source.classificationBackup().replacements.size)
                val migrated=source.get(record.id)!!
                assertEquals(final.id,migrated.bill.categoryDecision!!.categoryId)
                assertEquals(originalRule,migrated.bill.categoryDecision!!.rule)
                assertEquals(record.bill.categoryDecision!!.explanation,migrated.bill.categoryDecision!!.explanation)
                assertEquals(record.bill.categoryDecision!!.ruleVersion,migrated.bill.categoryDecision!!.ruleVersion)
            }
            LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
                assertEquals(final.name,source.classify(bill(),frozen).category)
                val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
                val localFinal=target.saveCategory(name=final.name,direction=Direction.EXPENSE)
                LedgerBackup.restore(target,backup)
                assertEquals(localFinal.id,target.classify(bill(),frozen).categoryDecision!!.categoryId)
                assertEquals(frozen,target.classificationBackup().snapshots.single().snapshot)
                assertEquals(2,target.classificationBackup().replacements.size)
                val restored=target.all().single()
                assertEquals(localFinal.id,restored.bill.categoryDecision!!.categoryId)
                assertEquals(first.id,restored.bill.categoryDecision!!.rule!!.categoryId)
                assertEquals(frozen.customRules.single(),restored.bill.categoryDecision!!.rule)
                // A same-name fresh category cannot steal an old stable ID's explicit replacement.
                val recreated=target.saveCategory(name=first.name,direction=Direction.EXPENSE)
                assertNotEquals(first.id,recreated.id)
                LedgerBackup.restore(target,oldBackup)
                assertFalse(target.categoryDirectory().any { it.id==first.id })
                assertEquals(final.name,target.classify(bill(),frozen).category)
                assertTrue(target.classificationRules().customRules.all { it.categoryId==localFinal.id })
            } }
        } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }
    @Test fun migrationFailuresRollBackBillsRulesDirectoryMappingsAndFunds() {
        val name="replace-rollback-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val original=store.saveCategory(name="合成回滚原用途",direction=Direction.EXPENSE)
            val target=store.saveCategory(name="合成回滚替代",direction=Direction.EXPENSE)
            store.saveClassificationRule(rule(original.id));store.holdClassificationSnapshot("synthetic-rollback")
            val wallet=store.saveAccount(LedgerAccount(name="合成回滚钱包",amountCents=10_000))
            val record=store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),bill=bill().copy(category=original.name),state=RecordState.POSTED),walletAccountUid=wallet.uid,manualWalletSelected=true)
            val before=store.get(record.id)!!;val config=store.classificationBackup();val movements=store.walletMovements(wallet.uid)
            assertEquals(9300L,store.getAccount(wallet.id)!!.amountCents)
            val failures=listOf(
                "CREATE TRIGGER synthetic_migration_failure BEFORE DELETE ON category_directory BEGIN SELECT RAISE(ABORT,'synthetic directory failure'); END",
                "CREATE TRIGGER synthetic_migration_failure BEFORE UPDATE ON classification_configuration WHEN NEW.payload LIKE '%\"categoryReplacements\":[{%' BEGIN SELECT RAISE(ABORT,'synthetic mapping failure'); END")
            for(trigger in failures) {
                store.writableDatabase.execSQL(trigger)
                try { assertNotNull(runCatching { delete(store,original.id,target.id) }.exceptionOrNull()) }
                finally { store.writableDatabase.execSQL("DROP TRIGGER synthetic_migration_failure") }
                assertEquals(before,store.get(record.id));assertEquals(config,store.classificationBackup())
                assertEquals(original.id,store.category(original.id,Direction.EXPENSE).id)
                assertEquals(9300L,store.getAccount(wallet.id)!!.amountCents);assertEquals(movements,store.walletMovements(wallet.uid))
            }
            delete(store,original.id,target.id)
            val migrated=store.get(record.id)!!
            assertEquals(before.bill.copy(category=target.name,categoryDecision=before.bill.categoryDecision!!.copy(categoryId=target.id)),migrated.bill)
            assertEquals(before.wallet,migrated.wallet);assertEquals(9300L,store.getAccount(wallet.id)!!.amountCents)
            assertEquals(movements,store.walletMovements(wallet.uid))
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun backupRejectsCyclesCrossDirectionAndConflictingReplacementWithoutPartialRestore() {
        val sourceName="replace-invalid-source-${UUID.randomUUID()}.db";val targetName="replace-invalid-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
            val first=source.saveCategory(name="合成冲突原用途",direction=Direction.EXPENSE)
            val one=source.saveCategory(name="合成冲突替代一",direction=Direction.EXPENSE)
            val two=source.saveCategory(name="合成冲突替代二",direction=Direction.EXPENSE)
            source.saveClassificationRule(rule(first.id));source.holdClassificationSnapshot("synthetic-conflict")
            delete(source,first.id,one.id)
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            LedgerBackup.restore(target,backup)
            val initial=target.classificationBackup();val categories=target.categoryDirectory()
            val entry=backup.classification!!.replacements.single()
            val invalid=listOf(
                backup.copy(classification=backup.classification.copy(replacements=listOf(entry.copy(categoryName="")))),
                backup.copy(classification=backup.classification.copy(replacements=listOf(entry.copy(replacementId=first.id)))),
                backup.copy(classification=backup.classification.copy(replacements=listOf(entry.copy(replacementId="synthetic:loop"),CategoryReplacement("synthetic:loop","合成循环",Direction.EXPENSE,first.id)))) ,
                backup.copy(classification=backup.classification.copy(replacements=listOf(entry.copy(replacementId="cat:income:other")))),
                backup.copy(classification=backup.classification.copy(replacements=listOf(entry.copy(replacementId=two.id)))))
            for(input in invalid) {
                assertNotNull(runCatching { LedgerBackup.restore(target,input) }.exceptionOrNull())
                assertEquals(initial,target.classificationBackup());assertEquals(categories,target.categoryDirectory());assertTrue(target.all().isEmpty())
            }
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun futureCsvAndLegacyTextBackupKeepExplicitCategoryWithNewIdentity() {
        val name="replace-legacy-name-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val first=store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),bill=bill().copy(category="合成旧文本分类"),state=RecordState.POSTED))
            val original=store.categoryFor(first)!!
            store.delete(first.id)
            val target=store.saveCategory(name="合成有效替代",direction=Direction.EXPENSE)
            store.saveClassificationRule(rule(original.id));delete(store,original.id,target.id)
            val exported=LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),bill=bill().copy(category=original.name),state=RecordState.POSTED)
            val bytes=java.io.ByteArrayOutputStream().also { LedgerCsvExport.write(listOf(exported),LedgerCsvExport.Format.GENERAL,it) }.toByteArray()
            val document=LedgerCsvImport.read(bytes.inputStream())
            assertEquals(1,LedgerCsvImport.apply(store,document).imported)
            val imported=store.all().single()
            assertEquals(original.name,imported.bill.category)
            assertNotEquals(original.id,imported.bill.categoryDecision!!.categoryId)
            assertEquals(CategorySource.IMPORT,imported.bill.categoryDecision!!.source);assertTrue(imported.bill.categoryDecision!!.manual)
            assertFalse(store.categoryDirectory().any { it.id==original.id })
            // Text alone is an explicit category name, not proof of the old deleted identity.
            val legacy=exported.copy(uid=UUID.randomUUID().toString(),bill=exported.bill.copy(transactionId="SYNTHETIC-LEGACY-REPLACE"))
            assertEquals(1,LedgerBackup.restore(store,ValidatedBackup(listOf(legacy),emptyMap())))
            assertEquals(original.name,store.all().single { it.uid==legacy.uid }.bill.category)
            // Intentional creation of the old display name remains a distinct new category identity.
            val recreated=store.categoryDirectory().single { it.name==original.name }
            assertNotEquals(original.id,recreated.id)
            assertEquals(target.name,store.classify(bill(),store.classificationBackup().snapshots.firstOrNull()?.snapshot ?: store.classificationRules()).category)
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun oversizedReplacementRestoreCannotLeaveAnUnreadableConfigurationOrPartialBills() {
        val sourceName="replace-capacity-source-${UUID.randomUUID()}.db";val targetName="replace-capacity-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
            source.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),bill=bill(),state=RecordState.POSTED))
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            val initial=target.classificationBackup();val categories=target.categoryDirectory()
            val oversized=(0 until 2100).map { CategoryReplacement("synthetic:capacity:$it","合".repeat(1024),Direction.EXPENSE,"cat:expense:other") }
            assertNotNull(runCatching { LedgerBackup.restore(target,backup.copy(classification=backup.classification!!.copy(replacements=oversized))) }.exceptionOrNull())
            assertEquals(initial,target.classificationBackup());assertEquals(categories,target.categoryDirectory());assertTrue(target.all().isEmpty())
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun builtinReplacementPreservesHistoricalDefinitionEnableStateAndCopiedConditions() {
        val sourceName="replace-builtin-source-${UUID.randomUUID()}.db";val targetName="replace-builtin-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
            val original=source.classificationRules().builtinRules.single { it.conditions.single().value=="咖啡" }
            val frozen=source.holdClassificationSnapshot("synthetic-builtin-replacement")
            val replacement=source.saveCategory(name="合成内置替代",direction=Direction.EXPENSE)
            source.setBuiltinClassificationRuleEnabled(original.id,false)
            val preview=source.previewCategoryDeletion("cat:expense:dining")
            assertTrue(preview.ruleCount>0);assertEquals(1L,preview.queuedConfigurationCount)
            source.confirmCategoryDeletion("cat:expense:dining",replacement.id,preview.previewToken)
            val current=source.classificationRules().builtinRules.single { it.id==original.id }
            assertEquals(original.copy(enabled=false,categoryId=replacement.id),current)
            val copied=source.copyBuiltinClassificationRule(original.id)
            assertEquals(replacement.id,copied.categoryId);assertEquals(original.conditions,copied.conditions)
            assertTrue(copied.enabled);assertTrue(copied.id.isEmpty())
            val receipt=bill().copy(merchant="合成咖啡")
            val decision=source.classify(receipt,frozen).categoryDecision!!
            assertEquals(CategorySource.BUILTIN_RULE,decision.source);assertEquals(replacement.id,decision.categoryId);assertEquals(original,decision.rule)
            assertEquals(CategorySource.FALLBACK,source.classify(receipt,source.classificationRules()).categoryDecision!!.source)
            assertEquals(frozen,source.classificationBackup().snapshots.single().snapshot)
            LedgerBackup.restore(target,LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream()))
            assertEquals(decision,target.classify(receipt,frozen).categoryDecision)
            assertEquals(current,target.classificationRules().builtinRules.single { it.id==original.id })
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

}
