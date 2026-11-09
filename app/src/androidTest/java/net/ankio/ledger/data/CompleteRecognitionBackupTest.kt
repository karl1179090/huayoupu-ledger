package net.ankio.ledger.data

import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.UUID

class CompleteRecognitionBackupTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun backupBetweenRefundCommitAndTaskProjectionUsesTheRedirectedReceiptUid() {
        val prefix="refund-gap-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val tasks=RecognitionTasks(context,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(context,"$prefix-target.db",media);val restored=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成退款投影钱包",amountCents=20000));source.setDefaultWallet(wallet.uid)
            val at=1730611800000L;val capture=Capture(Source.OCR,"shake:${PaymentApps.WECHAT}","合成付款",at)
            val bill=ParsedBill(10000,Direction.EXPENSE,"合成退款商家","其他",at,null,true,"",true)
            val original=(source.ingest(capture,bill,screenshot=syntheticBackupImage(149)) as IngestResult.Saved).record
            val id=tasks.create(Source.IMAGE,"image");tasks.queued(id,syntheticBackupImage(150));val key=tasks.operationKey(id)!!
            val refund=bill.copy(amountCents=3654,transactionId="SYNTHETIC-REFUND",refund=RefundInfo("wechat",10000,6346))
            val pending=(source.ingestRecognition(capture.copy(text="合成成功退款",receivedAt=at+60000),refund,key,screenshot=syntheticBackupImage(150)) as IngestResult.Saved).record
            assertEquals(RecordState.REVIEW,pending.state);tasks.update(id,"REVIEW",pending.reason,pending.id)
            source.confirmRefund(pending.id,original.id) // Deliberately stop before tasks.reassignRecord.
            LedgerBackup.restore(target,LedgerBackup.decode(LedgerBackup.encode(source,ChannelSettings(context)).byteInputStream()))
            val row=restored.snapshot().getJSONObject(0)
            assertEquals(target.all().single().id,row.getLong("recordId"));assertEquals(original.uid,target.committedCapture(key)!!.recordUid)
            assertEquals(16346L,target.accounts().single().amountCents);assertEquals(3654L,target.all().single().bill.amountCents)
        } finally { tasks.close();restored.close();source.close();target.close();media.close() }
    }
    @Test fun repeatedOldBackupAfterLocalCategoryDeletionKeepsTheFrozenTaskAndReplacement() {
        val prefix="deleted-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val sourceTasks=RecognitionTasks(context,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(context,"$prefix-target.db",media);val targetTasks=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            val original=source.saveCategory(name="合成旧用途",direction=Direction.EXPENSE)
            val replacement=source.saveCategory(name="合成替代用途",direction=Direction.EXPENSE)
            source.saveClassificationRule(ClassificationRule("","合成规则",true,Direction.EXPENSE,original.id,0,listOf(ClassificationCondition(value="合成"))))
            val id=sourceTasks.create(Source.IMAGE,"image");sourceTasks.queued(id,syntheticBackupImage(147))
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,ChannelSettings(context)).byteInputStream())
            LedgerBackup.restore(target,backup)
            val task=targetTasks.pendingQueued().single().first;val frozen=targetTasks.classificationSnapshot(task)!!
            target.confirmCategoryDeletion(original.id,replacement.id,target.previewCategoryDeletion(original.id).previewToken)
            LedgerBackup.restore(target,backup)
            assertEquals(frozen,targetTasks.classificationSnapshot(task));assertFalse(target.categoryDirectory().any { it.id==original.id })
            val bill=ParsedBill(500,Direction.EXPENSE,"合成商家","其他",1730611800000L,null,true,"")
            assertEquals(replacement.name,target.classify(bill,frozen).category)
        } finally { sourceTasks.close();targetTasks.close();source.close();target.close();media.close() }
    }
    @Test fun sameOperationWithDifferentOriginalImageIsRejectedWithoutOverwritingLocalEvidence() {
        val prefix="conflict-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val sourceTasks=RecognitionTasks(context,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(context,"$prefix-target.db",media);val targetTasks=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            val id=sourceTasks.create(Source.IMAGE,"image");val original=syntheticBackupImage(1);sourceTasks.queued(id,original)
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,ChannelSettings(context)).byteInputStream())
            LedgerBackup.restore(target,backup)
            val changed=syntheticBackupImage(2);val rows=org.json.JSONArray(backup.recognitionTasks.toString())
            val row=rows.getJSONObject(0);row.put("imageHash",PortableCsv.digest(changed))
            val conflict=backup.copy(recognitionTasks=rows,taskImages=mapOf(row.getString("operation_uid") to changed))
            assertNotNull(runCatching { LedgerBackup.restore(target,conflict) }.exceptionOrNull())
            assertArrayEquals(original,targetTasks.image(targetTasks.pendingQueued().single().first))
        } finally { sourceTasks.close();targetTasks.close();source.close();target.close();media.close() }
    }
    @Test fun unavailableAndTimeoutNodesKeepPrivateEvidenceAndMissingImageWithoutRestoringAuthorization() {
        val prefix="node-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val sourceTasks=RecognitionTasks(context,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(context,"$prefix-target.db",media);val targetTasks=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            val time=RecognitionTimeContext(1730611800000L,"America/New_York")
            val page=QualifiedWechatNodeFixture.page(merchant="合成恢复无图商家")
            val route=NodeRecognitionRegistry.production.select(page,false) { time };assertNotNull(route.candidate)
            for(reason in listOf(net.ankio.ledger.capture.ScreenshotFailureReason.UNAVAILABLE,net.ankio.ledger.capture.ScreenshotFailureReason.TIMEOUT)) {
                val authorization=net.ankio.ledger.capture.ShakeAuthorization(PaymentApps.WECHAT,7,9)
                val id=sourceTasks.create(Source.OCR,"shake:${PaymentApps.WECHAT}",authorization)
                sourceTasks.beginRecognition(id) { time };sourceTasks.queuedWithoutScreenshot(id,page,route,reason)
            }
            val json=LedgerBackup.encode(source,ChannelSettings(context))
            LedgerBackup.restore(target,LedgerBackup.decode(json.byteInputStream()))
            val rows=targetTasks.pendingQueued();assertEquals(2,rows.size)
            rows.forEach { (id,_) ->
                assertTrue(targetTasks.hasMissingNodeEvidence(id));assertNull(targetTasks.image(id));assertNull(targetTasks.shakeAuthorization(id))
                assertEquals(time,targetTasks.recognitionTime(id));assertEquals(page.fingerprint,targetTasks.nodeSnapshot(id)!!.fingerprint);assertEquals(page.nodes,targetTasks.nodeSnapshot(id)!!.nodes);assertEquals(page.environment,targetTasks.nodeSnapshot(id)!!.environment)
            }
            assertEquals(setOf("UNAVAILABLE","TIMEOUT"),rows.map { targetTasks.recognitionEvidence(it.first)!!.getJSONObject("screenshot").getString("state") }.toSet())
            val forged=org.json.JSONObject(json)
            forged.getJSONArray("recognitionTasks").getJSONObject(0).put("source","IMAGE").put("origin","image")
            assertNotNull("Restored node evidence cannot impersonate an image import",runCatching { LedgerBackup.decode(forged.toString().byteInputStream()) }.exceptionOrNull())
        } finally { sourceTasks.close();targetTasks.close();source.close();target.close();media.close() }
    }
    @Test fun fullQueueBeyondUiLimitAndTaskFailureRollBackAllImportedFacts() {
        val prefix="full-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val sourceTasks=RecognitionTasks(context,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(context,"$prefix-target.db",media);val targetTasks=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            repeat(503) { sourceTasks.create(Source.IMAGE,"image").also { sourceTasks.queued(it,syntheticBackupImage(14)) } }
            val wallet=source.saveAccount(LedgerAccount(name="合成全队列钱包",amountCents=5000));source.setDefaultWallet(wallet.uid)
            source.ingestRecognition(Capture(Source.IMAGE,"image","合成全量凭证",1730611800000L),
                ParsedBill(500,Direction.EXPENSE,"合成全量","其他",1730611800000L,null,true,""),"recognition:${UUID.randomUUID()}")
            val bytes=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
            targetTasks.writableDatabase.execSQL("CREATE TRIGGER fail_task_restore BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT,'synthetic restore failure'); END")
            assertNotNull(runCatching { LedgerArchive.read(bytes.inputStream(),context.cacheDir).use { LedgerArchive.restoreDetailed(target,it) } }.exceptionOrNull())
            assertTrue(target.all().isEmpty());assertTrue(target.accounts().isEmpty());assertTrue(targetTasks.pendingQueued().isEmpty())
            targetTasks.writableDatabase.execSQL("DROP TRIGGER fail_task_restore")
            LedgerArchive.read(bytes.inputStream(),context.cacheDir).use { LedgerArchive.restoreDetailed(target,it) }
            assertEquals(503,targetTasks.pendingQueued().size);assertEquals(4500L,target.accounts().single().amountCents)
        } finally { sourceTasks.close();targetTasks.close();source.close();target.close();media.close() }
    }
    @Test fun deletedBillStillHasSingleAndEventReceiptsAfterJsonRestore() {
        val prefix="receipt-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val target=LedgerStore(context,"$prefix-target.db",media)
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成钱包",amountCents=10000))
            source.setDefaultWallet(wallet.uid)
            val capture=Capture(Source.IMAGE,"image","支付成功 合成商家 12.00",1730611800000L)
            val bill=ParsedBill(1200L,Direction.EXPENSE,"合成商家","其他",1730611800000L,null,true,"合成")
            val key="recognition:${UUID.randomUUID()}";val event="synthetic-${UUID.randomUUID()}"
            val record=(source.ingestRecognition(capture,bill,key,event) as IngestResult.Saved).record
            source.delete(record.id)
            val json=LedgerBackup.encode(source,ChannelSettings(context))
            repeat(2) { LedgerBackup.restore(target,LedgerBackup.decode(json.byteInputStream())) }
            assertEquals(record.uid,target.committedCapture(key)?.recordUid)
            assertNull(target.committedCapture(key)?.record)
            assertEquals(record.uid,target.committedPaymentEvent(event)?.recordUid)
            assertEquals(IngestResult.Skipped("账单已移除，未新增记账"),target.ingestRecognition(capture,bill,key,event))
            assertTrue(target.all().isEmpty())
            assertEquals(10000L,target.getAccountByUid(wallet.uid)!!.amountCents)
        } finally { source.close();target.close();media.close() }
    }
    @Test fun queuedImageKeepsOperationFrozenTimeAndEvidenceAcrossArchiveAndRepeatedMerge() {
        val prefix="backup-${UUID.randomUUID()}"
        val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media)
        val tasks=RecognitionTasks(context,"$prefix-source-tasks.db",media,source)
        val target=LedgerStore(context,"$prefix-target.db",media)
        val restored=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            val id=tasks.create(Source.IMAGE,"image")
            tasks.queued(id,syntheticBackupImage(14))
            tasks.phaseTiming(id,"screenshot",25)
            val key=tasks.operationKey(id)
            tasks.beginRecognition(id) { RecognitionTimeContext(1730611800000L,"America/New_York") }
            val output=ByteArrayOutputStream()
            LedgerArchive.write(source,ChannelSettings(context),output)
            repeat(2) {
                LedgerArchive.read(output.toByteArray().inputStream(),context.cacheDir).use { LedgerArchive.restoreDetailed(target,it) }
            }
            val queued=restored.pendingQueued()
            assertEquals(1,queued.size)
            val result=queued.single().first
            assertEquals(key,restored.operationKey(result))
            assertEquals(RecognitionTimeContext(1730611800000L,"America/New_York"),restored.recognitionTime(result))
            assertArrayEquals(tasks.image(id),restored.image(result))
            assertEquals(tasks.classificationSnapshot(id),restored.classificationSnapshot(result))
        } finally { tasks.close();restored.close();source.close();target.close();media.close() }
    }
}
