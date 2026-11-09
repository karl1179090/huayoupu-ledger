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

/** Public task creation and real recognition/coordinator/SQLite path; only OCR is synthetic. */
@RunWith(AndroidJUnit4::class)
class MerchantClassificationTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun queuedTaskKeepsItsCompleteRulesAcrossEditsAndHelperRecreation()=runBlocking {
        app.taskRecovery.await()
        val suffix=UUID.randomUUID().toString()
        val merchant="合成规则商家$suffix"
        val oldDefault=app.store.walletSettings().defaultAccountUid
        val wallet=app.store.saveAccount(LedgerAccount(name="合成规则资金",amountCents=10_000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid)
        val rule=app.store.saveClassificationRule(ClassificationRule("","合成首条$suffix",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(value=merchant))))
        val tasks=mutableListOf<Long>()
        try {
            val queued=app.tasks.create(Source.IMAGE,"image");tasks+=queued
            val version=app.tasks.classificationSnapshot(queued)!!.version
            app.store.saveClassificationRule(rule.copy(categoryId="cat:expense:shopping",enabled=false))
            RecognitionTasks(app).use { reopened ->
                val frozen=reopened.classificationSnapshot(queued)!!
                assertEquals(version,frozen.version)
                assertTrue(frozen.customRules.single { it.id==rule.id }.enabled)
                assertEquals("cat:expense:dining",frozen.customRules.single { it.id==rule.id }.categoryId)
            }
            val bill=ParsedBill(1250,Direction.EXPENSE,merchant,"其他",1_798_776_000_000,"SYNTHETIC-RULE-$suffix",true,"",true)
            val backend=RecognitionBackend { image,prepared,_,_,_,_ -> prepared(image);RecognitionResult("合成付款成功",bill,screenshot=image) }
            val result=CaptureProcessor(app,OcrClient(app,backend)).processPrepared("synthetic-$suffix".toByteArray(),Source.IMAGE,"image",queued) { true } as IngestResult.Saved
            assertEquals("餐饮",result.record.bill.category)
            assertEquals(CategorySource.CUSTOM_RULE,result.record.bill.categoryDecision!!.source)
            assertEquals(version,result.record.bill.categoryDecision!!.ruleVersion)
            assertEquals(8750L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.walletMovements(wallet.uid).count { it.recordUid==result.record.uid })
            assertEquals(IngestResult.DuplicateSkipped,CaptureProcessor(app,OcrClient(app,backend)).processPrepared("synthetic-$suffix".toByteArray(),Source.IMAGE,"image",queued) { true })
            assertEquals(8750L,app.store.getAccount(wallet.id)!!.amountCents)
            // A new historical receipt is a new task and uses the disabled current configuration.
            val newer=app.tasks.create(Source.IMAGE,"image");tasks+=newer
            val nextBill=bill.copy(transactionId="SYNTHETIC-RULE-NEW-$suffix",amountCents=800,occurredAt=bill.occurredAt-86_400_000)
            val nextBackend=RecognitionBackend { image,prepared,_,_,_,_ -> prepared(image);RecognitionResult("合成新的历史付款",nextBill,screenshot=image) }
            val next=CaptureProcessor(app,OcrClient(app,nextBackend)).processPrepared("synthetic-new-$suffix".toByteArray(),Source.IMAGE,"image",newer) { true } as IngestResult.Saved
            assertEquals("其他",next.record.bill.category)
            assertEquals(CategorySource.FALLBACK,next.record.bill.categoryDecision!!.source)
            assertEquals(7950L,app.store.getAccount(wallet.id)!!.amountCents)
            // Editing a bill does not create a rule; a later supplement keeps that manual choice.
            val edited=app.store.saveEdited(result.record.copy(bill=result.record.bill.copy(category="交通")))
            assertTrue(edited.bill.categoryDecision!!.manual)
            val supplement=app.tasks.create(Source.IMAGE,"image");tasks+=supplement
            CaptureProcessor(app,OcrClient(app,backend)).processPrepared("synthetic-supplement-$suffix".toByteArray(),Source.IMAGE,"image",supplement) { true }
            assertEquals("交通",app.store.get(edited.id)!!.bill.category)
            assertEquals(edited.bill.categoryDecision,app.store.get(edited.id)!!.bill.categoryDecision)
            assertEquals(7950L,app.store.getAccount(wallet.id)!!.amountCents)
            assertEquals(1,app.store.classificationRules().customRules.count { it.id==rule.id })
        } finally {
            app.store.all().filter { it.bill.merchant==merchant }.forEach { app.store.delete(it.id) }
            tasks.forEach { id -> app.tasks.operationKey(id)?.let { app.store.writableDatabase.delete("wallet_submissions","token=?",arrayOf(it)) };app.tasks.delete(id) }
            app.store.deleteClassificationRule(rule.id)
            app.store.setDefaultWallet(oldDefault)
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
        }
    }
    @Test fun backupRoundTripKeepsConfigSnapshotsAndManualProvenanceWithoutChangingFunds() {
        val sourceName="rules-source-${UUID.randomUUID()}.db";val destinationName="rules-dest-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,destinationName).use { destination ->
            val category=source.saveCategory(name="合成用途",direction=Direction.EXPENSE)
            val rule=source.saveClassificationRule(ClassificationRule("","合成备份规则",true,Direction.EXPENSE,category.id,0,listOf(ClassificationCondition(value="合成"))))
            val frozen=source.holdClassificationSnapshot("synthetic-backup-task")
            val wallet=source.saveAccount(LedgerAccount(name="合成备份钱包",amountCents=9000))
            val record=source.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual",""),
                bill=ParsedBill(500,Direction.EXPENSE,"合成商家",category.name,1_798_776_000_000,null,true,"",true),state=RecordState.POSTED),walletAccountUid=wallet.uid,manualWalletSelected=true)
            val bytes=LedgerBackup.encode(source,app.channels).toByteArray()
            val backup=LedgerBackup.decode(bytes.inputStream())
            assertNotNull(backup.classification)
            assertEquals(1,LedgerBackup.restore(destination,backup))
            assertEquals(rule.id,destination.classificationRules().customRules.single().id)
            assertEquals(frozen,destination.classificationBackup().snapshots.single().snapshot)
            assertFalse(destination.classificationBackup().snapshots.single().active)
            val restored=destination.all().single()
            assertEquals(record.bill.categoryDecision,restored.bill.categoryDecision)
            assertTrue(restored.bill.categoryDecision!!.manual)
            assertEquals(8500L,destination.accounts().single().amountCents)
            assertEquals(0,LedgerBackup.restore(destination,backup))
            assertEquals(8500L,destination.accounts().single().amountCents)
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(destinationName) }
    }

    @Test fun categoryTargetsStayStableAndOnlyActiveTasksPreventDeletion() {
        val ledgerName="rule-lifecycle-${UUID.randomUUID()}.db";val tasksName="rule-tasks-${UUID.randomUUID()}.db"
        try { LedgerStore(app,ledgerName).use { store -> RecognitionTasks(app,tasksName,classificationStore=store).use { tasks ->
            val category=store.saveCategory(name="合成待删除用途",direction=Direction.EXPENSE)
            val rule=store.saveClassificationRule(ClassificationRule("","合成活动引用",true,Direction.EXPENSE,category.id,0,listOf(ClassificationCondition(value="合成"))))
            val id=tasks.create(Source.IMAGE,"image")
            store.saveCategory(category.id,"合成改名用途",Direction.EXPENSE)
            assertEquals(category.id,tasks.classificationSnapshot(id)!!.customRules.single().categoryId)
            val bill=ParsedBill(100,Direction.EXPENSE,"合成商家","其他",1_798_776_000_000,null,true,"",true)
            assertEquals("合成改名用途",store.classify(bill,tasks.classificationSnapshot(id)!!).category)
            assertEquals("CATEGORY_REFERENCED",(runCatching { store.removeCategory(category.id) }.exceptionOrNull() as CategoryException).code)
            store.deleteClassificationRule(rule.id)
            assertEquals("CATEGORY_REFERENCED",(runCatching { store.removeCategory(category.id) }.exceptionOrNull() as CategoryException).code)
            tasks.update(id,"CANCELLED","合成取消")
            store.removeCategory(category.id)
            assertTrue(store.classificationBackup().snapshots.none { it.active })
            val second=store.saveCategory(name="合成失败创建用途",direction=Direction.EXPENSE)
            val secondRule=store.saveClassificationRule(rule.copy(id="",categoryId=second.id))
            tasks.writableDatabase.execSQL("CREATE TRIGGER synthetic_task_create_failure BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
            val before=store.classificationBackup().snapshots
            assertNotNull(runCatching { tasks.create(Source.IMAGE,"image") }.exceptionOrNull())
            assertEquals(before,store.classificationBackup().snapshots)
            tasks.writableDatabase.execSQL("DROP TRIGGER synthetic_task_create_failure")
            val completed=tasks.create(Source.IMAGE,"image")
            store.deleteClassificationRule(secondRule.id)
            tasks.update(completed,"SUCCEEDED","合成完成")
            store.removeCategory(second.id)
            assertTrue(store.classificationBackup().snapshots.none { it.active })
        } } } finally { app.deleteDatabase(ledgerName);app.deleteDatabase(tasksName) }
    }

    @Test fun oldTasksUseThePersistedUpgradeBaselineAfterNewRulesAreSaved() {
        val ledgerName="rule-legacy-${UUID.randomUUID()}.db";val tasksName="rule-legacy-tasks-${UUID.randomUUID()}.db"
        try { LedgerStore(app,ledgerName).use { store ->
            val baseline=store.classificationMigrationBaseline()
            store.saveClassificationRule(ClassificationRule("","合成未来规则",true,Direction.EXPENSE,"cat:expense:shopping",0,listOf(ClassificationCondition(value="咖啡"))))
            app.openOrCreateDatabase(tasksName,0,null).use { old ->
                old.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,origin TEXT NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,record_id INTEGER,image BLOB,diagnostic TEXT NOT NULL DEFAULT '{}',shake_authorization TEXT,operation_uid TEXT NOT NULL)")
                old.execSQL("CREATE UNIQUE INDEX tasks_operation_uid ON tasks(operation_uid)")
                old.execSQL("INSERT INTO tasks(source,origin,created_at,status,message,operation_uid) VALUES('IMAGE','image',1798776000000,'QUEUED','合成旧任务',?)",arrayOf(UUID.randomUUID().toString()))
                old.version=5
            }
            RecognitionTasks(app,tasksName,classificationStore=store).use { tasks ->
                assertEquals(baseline,tasks.classificationSnapshot(1))
                val bill=ParsedBill(100,Direction.EXPENSE,"合成咖啡","其他",1_798_776_000_000,null,true,"",true)
                assertEquals("餐饮",store.classify(bill,tasks.classificationSnapshot(1)!!).category)
                assertEquals("购物",store.classify(bill,store.classificationRules()).category)
            }
            RecognitionTasks(app,tasksName,classificationStore=store).use { assertEquals(baseline,it.classificationSnapshot(1)) }
        } } finally { app.deleteDatabase(ledgerName);app.deleteDatabase(tasksName) }
    }

    @Test fun conflictingBackupRulesAreDisabledInsteadOfReplacingLocalDefinitions() {
        val sourceName="rule-conflict-source-${UUID.randomUUID()}.db";val targetName="rule-conflict-target-${UUID.randomUUID()}.db"
        try { LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
            val original=source.saveClassificationRule(ClassificationRule("","合成同名规则",true,Direction.EXPENSE,"cat:expense:dining",0,listOf(ClassificationCondition(value="合成"))))
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
            val local=target.saveClassificationRule(original.copy(id="",categoryId="cat:expense:shopping"))
            assertEquals(0,LedgerBackup.restore(target,backup))
            val rules=target.classificationRules().customRules
            assertEquals(local,rules.single { it.id==local.id })
            val imported=rules.single { it.id!=local.id }
            assertFalse(imported.enabled);assertTrue(imported.conflictReason!!.contains("冲突"))
            LedgerBackup.restore(target,backup)
            assertEquals(rules,target.classificationRules().customRules)
        } } } finally { app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun taskCreationRacingWithRuleAndCategoryDeletionNeverKeepsAnInvalidTarget() {
        val ledgerName="rule-race-${UUID.randomUUID()}.db";val tasksName="rule-race-tasks-${UUID.randomUUID()}.db"
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
        try { LedgerStore(app,ledgerName).use { store -> RecognitionTasks(app,tasksName,classificationStore=store).use { tasks ->
            repeat(12) { index ->
                val category=store.saveCategory(name="合成竞争用途$index",direction=Direction.EXPENSE)
                val rule=store.saveClassificationRule(ClassificationRule("","合成竞争规则$index",true,Direction.EXPENSE,category.id,0,listOf(ClassificationCondition(value="合成"))))
                val start=java.util.concurrent.CountDownLatch(1)
                val creation=workers.submit<Long> { start.await();tasks.create(Source.IMAGE,"image") }
                val deletion=workers.submit<Boolean> { start.await();store.deleteClassificationRule(rule.id);runCatching { store.removeCategory(category.id) }.isSuccess }
                start.countDown()
                val task=creation.get(10,java.util.concurrent.TimeUnit.SECONDS)
                val removed=deletion.get(10,java.util.concurrent.TimeUnit.SECONDS)
                val reserved=tasks.classificationSnapshot(task)!!.customRules.any { it.categoryId==category.id }
                assertEquals(!removed,reserved)
                if(reserved)assertEquals(category.id,store.category(category.id,Direction.EXPENSE).id)
                tasks.update(task,"CANCELLED","合成竞争完成")
                if(!removed)store.removeCategory(category.id)
            }
        } } } finally { workers.shutdownNow();app.deleteDatabase(ledgerName);app.deleteDatabase(tasksName) }
    }

    @Test fun startupReconciliationReleasesOnlyOrphansAndCompletedTaskReservations() {
        val ledgerName="rule-orphan-${UUID.randomUUID()}.db";val tasksName="rule-orphan-tasks-${UUID.randomUUID()}.db"
        try { LedgerStore(app,ledgerName).use { store ->
            val category=store.saveCategory(name="合成孤儿用途",direction=Direction.EXPENSE)
            val rule=store.saveClassificationRule(ClassificationRule("","合成孤儿规则",true,Direction.EXPENSE,category.id,0,listOf(ClassificationCondition(value="合成"))))
            var actualTask=0L
            RecognitionTasks(app,tasksName,classificationStore=store).use { actualTask=it.create(Source.IMAGE,"image");it.queued(actualTask,"synthetic-queued".toByteArray()) }
            store.holdClassificationSnapshot("recognition:synthetic-orphan",taskDatabase=tasksName)
            store.deleteClassificationRule(rule.id)
            RecognitionTasks(app,tasksName,classificationStore=store).use { reopened ->
                reopened.recoverInterrupted()
                val snapshots=store.classificationBackup().snapshots
                assertFalse(snapshots.single { it.operationKey=="recognition:synthetic-orphan" }.active)
                assertTrue(snapshots.single { it.operationKey==reopened.operationKey(actualTask) }.active)
                assertEquals("CATEGORY_REFERENCED",(runCatching { store.removeCategory(category.id) }.exceptionOrNull() as CategoryException).code)
                reopened.delete(actualTask);store.removeCategory(category.id)
                assertTrue(store.classificationBackup().snapshots.none { it.active })
            }
        } } finally { app.deleteDatabase(ledgerName);app.deleteDatabase(tasksName) }
    }

    @Test fun genuineV12UpgradePreservesExistingBillCategoriesEvidenceAndFunds() {
        val name="rule-v12-${UUID.randomUUID()}.db"
        try {
            val file=app.getDatabasePath(name);file.parentFile!!.mkdirs()
            val sql=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("categories/ledger-v10.sql").bufferedReader().use { it.readText() }
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';').filter(String::isNotBlank).forEach(db::execSQL)
                db.execSQL("CREATE TABLE category_directory(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,direction TEXT NOT NULL CHECK(direction IN ('EXPENSE','INCOME')),normalized_name TEXT NOT NULL,icon TEXT NOT NULL,sort_order INTEGER NOT NULL,is_fallback INTEGER NOT NULL DEFAULT 0,UNIQUE(direction,name))")
                db.execSQL("CREATE INDEX categories_normalized ON category_directory(direction,normalized_name)")
                db.execSQL("ALTER TABLE records ADD COLUMN category_id TEXT")
                db.execSQL("CREATE INDEX records_category_id ON records(category_id)")
                db.execSQL("INSERT INTO category_directory VALUES('synthetic:kept','合成旧分类','EXPENSE','合成旧分类','tag',0,0)")
                db.execSQL("INSERT INTO category_directory VALUES('cat:expense:other','其他','EXPENSE','其他','ellipsis',1,1)")
                db.execSQL("INSERT INTO category_directory VALUES('cat:income:other','其他','INCOME','其他','ellipsis',0,1)")
                db.execSQL("UPDATE records SET category='合成旧分类',category_id='synthetic:kept' WHERE id=1")
                db.execSQL("ALTER TABLE records ADD COLUMN receipt_evidence TEXT")
                val receipt=ReceiptEvidence(merchant=ReceiptField("合成原商家","商家：合成原商家"))
                db.execSQL("UPDATE records SET receipt_evidence=? WHERE id=1",arrayOf(ReceiptEvidenceCodec.encode(receipt)))
                db.version=12
            }
            repeat(2) { LedgerStore(app,name).use { store ->
                assertEquals(16,store.readableDatabase.version)
                val original=store.get(1)!!
                assertEquals("合成旧分类",original.bill.category);assertNull(original.bill.categoryDecision)
                assertEquals("合成原商家",original.bill.receipt!!.merchant!!.value)
                assertEquals(10000L,original.bill.amountCents);assertEquals(1_793_937_600_000L,original.bill.occurredAt)
                assertEquals(listOf(100000L,20000L,-5000L,10000L),store.accounts().map { it.amountCents })
                assertEquals(2,store.walletSnapshot().movements.size)
            } }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun editingAnImportedNoteKeepsItsExplicitImportCategoryProvenance() {
        val name="rule-import-note-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val csv="时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片\n2026-11-01 10:00:00,合成导入用途,,支出,1.00,,,合成原备注,,0,0,,\n"
            val document=LedgerCsvImport.read(csv.byteInputStream())
            assertTrue(document.errors.toString(),document.errors.isEmpty())
            assertEquals(1,LedgerCsvImport.apply(store,document).imported)
            val original=store.all().single()
            assertEquals(CategorySource.IMPORT,original.bill.categoryDecision!!.source)
            val edited=store.saveEdited(original.copy(note="仅修改导入备注"))
            assertEquals(original.bill.categoryDecision,edited.bill.categoryDecision)
            assertEquals("合成导入用途",edited.bill.category)
            assertTrue(store.accounts().isEmpty())
        } } finally { app.deleteDatabase(name) }
    }

}
