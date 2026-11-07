package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ShakeRuntime
import net.ankio.ledger.capture.ShakeTrigger
import net.ankio.ledger.capture.screenshotCooldownRemaining
import net.ankio.ledger.data.RecognitionDiagnostics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShakeTimingTest {
    @Test fun legacyScreenshotWaitsPastItsSystemLimitWhileAFirstFrameNeedsNoDelay() {
        assertEquals(0L,screenshotCooldownRemaining(30,0,1_000))
        assertEquals(700L,screenshotCooldownRemaining(30,1_000,1_400))
        assertTrue(screenshotCooldownRemaining(30,1_000,2_000)>0)
        assertEquals(0L,screenshotCooldownRemaining(30,1_000,2_100))
        assertEquals(0L,screenshotCooldownRemaining(31,1_000,1_400))
        assertEquals(0L,screenshotCooldownRemaining(34,1_000,1_400))
        val trigger=ShakeTrigger(1_200,1_200)
        assertTrue(trigger.freshAt(1_400+screenshotCooldownRemaining(30,1_000,1_400)))
        assertFalse(ShakeTrigger(500,500).freshAt(1_400+screenshotCooldownRemaining(30,1_000,1_400)))
    }
    @Test fun signalMustStillBeFreshWhenCaptureActuallyStarts() {
        val trigger=ShakeTrigger(10_000,10_020)
        assertTrue(trigger.freshAt(10_100))
        assertFalse(trigger.freshAt(12_000))
        assertFalse(trigger.freshAt(9_000))
        assertEquals("sensor_age_ms=20; dispatch_age_ms=100; queue_ms=80",trigger.details(10_100))
    }

    @Test fun delayedSignalAndBackgroundFlagsExportWithoutSensorVectorsOrFinancialFields() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val runtime=ShakeRuntime(app)
        runtime.sample(1000,9000)
        val state=runtime.snapshot()
        assertEquals(8000,state.getLong("lastDelayedSampleAgeMs"))
        assertTrue(state.has("backgroundRestricted"));assertTrue(state.has("batteryExempt"))
        assertFalse(state.has("values"));assertFalse(state.has("x"));assertFalse(state.has("screenshot"))
        val report=JSONObject(RecognitionDiagnostics.encode(app.tasks,state))
        assertEquals(8000,report.getJSONObject("shakeRuntime").getLong("lastDelayedSampleAgeMs"))
        assertFalse(report.has("rawText"));assertFalse(report.has("apiKey"))
        // Isolate synthetic timing from any later manual device probe.
        app.getSharedPreferences("shake_runtime",android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
}
