package net.ankio.ledger.capture

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import net.ankio.ledger.ledger

/** Timing and system flags only. No motion vectors, bill data or screen content. */
class ShakeRuntime(private val context:Context) {
    private val preferences=context.getSharedPreferences("shake_runtime",Context.MODE_PRIVATE)
    @Volatile private var lastSampleAgeMs=-1L
    @Volatile private var lastSampleAtMs=-1L
    @Volatile private var deliveryThread="not_received"
    @Volatile private var lastPersistedDelayAtMs=-3000L
    fun started() { preferences.edit().putLong("startedAt",System.currentTimeMillis()).putString("sensorThread","dedicated").apply() }
    fun sample(eventAtMs:Long,receivedAtMs:Long) {
        val age=receivedAtMs-eventAtMs
        lastSampleAgeMs=age;lastSampleAtMs=receivedAtMs
        deliveryThread=Thread.currentThread().name
        if(age !in 0..1500L && receivedAtMs-lastPersistedDelayAtMs>=3000) {
            lastPersistedDelayAtMs=receivedAtMs
            preferences.edit().putLong("lastDelayedSampleAgeMs",age).putLong("lastDelayedSampleAt",System.currentTimeMillis()).apply()
        }
    }
    fun trigger(ageMs:Long) { preferences.edit().putLong("lastTriggerAgeMs",ageMs).putLong("lastTriggerAt",System.currentTimeMillis()).apply() }
    fun snapshot():JSONObject {
        val power=context.getSystemService(PowerManager::class.java)
        val activity=context.getSystemService(ActivityManager::class.java)
        val selected=context.ledger.channels.selectedShakePackages().size
        return JSONObject().put("running",CaptureRuntime.running && CaptureRuntime.sensorRunning && context.ledger.channels.enabled(ChannelSettings.Channel.OCR))
            .put("sensorRunning",CaptureRuntime.sensorRunning).put("shakeSelectedCount",selected)
            .put("shakeSelectionRequired",selected==0)
            .put("accessibilityConnected",ScreenCaptureService.current!=null)
            .put("backgroundRestricted",activity.isBackgroundRestricted)
            .put("batteryExempt",power.isIgnoringBatteryOptimizations(context.packageName)).put("powerSave",power.isPowerSaveMode)
            .put("sensorThread",preferences.getString("sensorThread","not_started"))
            .put("deliveryThread",deliveryThread)
            .put("startedAt",preferences.getLong("startedAt",0)).put("lastSampleAgeMs",lastSampleAgeMs)
            .put("lastSampleReceivedAgoMs",if(lastSampleAtMs>=0)SystemClock.elapsedRealtime()-lastSampleAtMs else -1)
            .put("lastDelayedSampleAgeMs",preferences.getLong("lastDelayedSampleAgeMs",-1))
            .put("lastDelayedSampleAt",preferences.getLong("lastDelayedSampleAt",0))
            .put("lastTriggerAgeMs",preferences.getLong("lastTriggerAgeMs",-1)).put("lastTriggerAt",preferences.getLong("lastTriggerAt",0))
    }
}

internal data class ShakeTrigger(val eventAtMs:Long,val receivedAtMs:Long) {
    fun freshAt(nowMs:Long)=nowMs-eventAtMs in 0..1500L
    fun details(nowMs:Long)="sensor_age_ms=${receivedAtMs-eventAtMs}; dispatch_age_ms=${nowMs-eventAtMs}; queue_ms=${nowMs-receivedAtMs}"
    companion object { fun now()=SystemClock.elapsedRealtime().let { ShakeTrigger(it,it) } }
}
