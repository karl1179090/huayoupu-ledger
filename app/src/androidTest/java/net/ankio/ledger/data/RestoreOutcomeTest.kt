package net.ankio.ledger.data

import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RestoreOutcomeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun scenario(probe:(LedgerStore,String)->Unit,verify:(LedgerStore,()->RestoreSummary)->Unit) {
        val prefix="outcome-${UUID.randomUUID()}";val media=ScreenshotStore(context,prefix)
        val source=LedgerStore(context,"$prefix-source.db",media);val target=LedgerStore(context,"$prefix-target.db",media)
        val tasks=RecognitionTasks(context,"$prefix-target-tasks.db",media,target)
        try {
            val wallet=source.saveAccount(LedgerAccount(name="合成提交结果钱包",amountCents=5000));source.setDefaultWallet(wallet.uid)
            source.ingestRecognition(Capture(Source.IMAGE,"image","合成提交结果凭证",1730611800000L),
                ParsedBill(500,Direction.EXPENSE,"合成结果","其他",1730611800000L,null,true,""),"recognition:${UUID.randomUUID()}")
            val backup=LedgerBackup.decode(LedgerBackup.encode(source,ChannelSettings(context)).byteInputStream())
            AtomicBackupTransaction.processProbe={ probe(target,it) }
            verify(target) { LedgerBackup.restoreDetailed(target,backup) }
        } finally { AtomicBackupTransaction.processProbe=null;tasks.close();source.close();target.close();media.close() }
    }
    @Test fun committedRestoreStillReturnsSuccessWhenDetachCleanupFails()=scenario({ store,phase ->
        if(phase=="committed")store.writableDatabase.execSQL("DETACH DATABASE media")
    }) { store,restore ->
        assertEquals(1,restore().imported);assertEquals(4500L,store.accounts().single().amountCents);assertEquals(1,store.all().size)
    }
    @Test fun lostConnectionAfterCommitReportsUncertainOutcomeAndCleanupPreservesThatType()=scenario({ store,phase ->
        if(phase=="afterEndTransaction") { store.writableDatabase.close();error("synthetic lost commit connection") }
    }) { store,restore ->
        val error=runCatching { restore() }.exceptionOrNull()
        assertTrue("Expected explicit uncertain result, got $error",error is RestoreOutcomeUncertainException)
        assertEquals(1,store.all().size);assertEquals(4500L,store.accounts().single().amountCents)
    }
}
