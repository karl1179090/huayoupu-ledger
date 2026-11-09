package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Controlled host probe sends an actual ordered package broadcast to this app only. */
class ReceiverCompletionProbeTest {
    @Test fun packageBroadcastCompletesWhenWorkIsRejectedOrFinishes()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("receiverProbe")=="true")
        val app=ApplicationProvider.getApplicationContext<LedgerApp>();app.taskRecovery.await();app.channels
        val ready=File(app.filesDir,"t14-receiver-ready.json");val release=File(app.filesDir,"t14-receiver-release.json")
        ready.delete();release.delete()
        val closed=InstrumentationRegistry.getArguments().getString("closed")=="true"
        if(closed)app.recognitionWork.closeAndDrain()
        try {
            ready.writeText(org.json.JSONObject().put("closed",closed).put("pid",android.os.Process.myPid()).toString())
            withTimeout(30000) { while(!release.isFile)delay(20) }
            val result=org.json.JSONObject(release.readText())
            assertTrue("Actual ordered package broadcast did not complete before timeout: $result",result.getBoolean("completed"))
        } finally { if(closed)app.recognitionWork.reopen();ready.delete();release.delete() }
    }
}
