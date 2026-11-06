package net.ankio.ledger.capture

import android.app.KeyguardManager
import android.hardware.*
import android.os.*
import net.ankio.ledger.core.ShakeDetector
import net.ankio.ledger.ledger

/** Sensor listening shares the system-bound accessibility connection; no second service. */
class CaptureRuntime private constructor(private val service:ScreenCaptureService):SensorEventListener,AutoCloseable {
    private val sensors=service.getSystemService(SensorManager::class.java)
    private val detector=ShakeDetector()
    private val mainHandler=Handler(Looper.getMainLooper())
    private var sensorThread:HandlerThread?=null
    @Volatile private var registered=false
    private val permissionObserver=object:android.database.ContentObserver(mainHandler) {
        override fun onChange(selfChange:Boolean) { refreshReadiness() }
    }
    init {
        service.contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor("enabled_accessibility_services"),false,permissionObserver)
    }
    private fun refreshReadiness() {
        if(instance!==this || ScreenCaptureService.current!==service)return
        val app=service.ledger
        app.captureHealth.suspendForMissingPermission()
        if(!app.channels.captureEnabled() || !app.channels.permitted(ChannelSettings.Channel.OCR)) { stop();return }
        val listen=app.channels.enabled(ChannelSettings.Channel.OCR) && app.channels.selectedShakePackages().isNotEmpty()
        if(!listen)stopSensor()
        else if(!registered) {
            val sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val thread=HandlerThread("ledger-shake-sensor").also { it.start();sensorThread=it }
            registered=sensor!=null && sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,0,Handler(thread.looper))
            if(registered)app.shakeRuntime.started()
            else {
                stopSensor();app.channels.set(ChannelSettings.Channel.OCR,false)
                app.channels.status("此设备无法监听摇动，请使用截图导入")
            }
        }
        val wasRunning=running
        running=app.channels.captureEnabled()
        if(!running)stop()
        else {
            if(!wasRunning)app.executionLog.event("system","capture_service","accessibility_runtime_started")
            app.captureHealth.clearNotice();service.checkPaymentPages();app.captureQueue.resume()
        }
        app.changed()
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(!running || !registered)return
        val at=event.timestamp/1_000_000
        val received=SystemClock.elapsedRealtime()
        service.ledger.shakeRuntime.sample(at,received)
        if(!service.getSystemService(PowerManager::class.java).isInteractive || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked)return
        if(detector.sample(at,event.values[0],event.values[1],event.values[2],received)) {
            service.ledger.shakeRuntime.trigger(received-at)
            processShakeTrigger(ShakeTrigger(at,received))
        }
    }
    internal fun processShakeTrigger(trigger:ShakeTrigger) {
        if(!running || !registered || !service.ledger.channels.enabled(ChannelSettings.Channel.OCR) || service.ledger.channels.selectedShakePackages().isEmpty() || !trigger.freshAt(SystemClock.elapsedRealtime()))return
        service.ledger.executionLog.event("ocr","shake","received",service.ledger.shakeRuntime.snapshot())
        service.captureOnShake(trigger)
    }
    private fun stopSensor() {
        sensors.unregisterListener(this);registered=false;sensorThread?.quitSafely();sensorThread=null
    }
    private fun stop() {
        val wasRunning=running
        running=false;stopSensor()
        if(wasRunning) { service.captureServiceStopped();service.ledger.changed() }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    override fun close() {
        stop();service.contentResolver.unregisterContentObserver(permissionObserver)
        mainHandler.removeCallbacksAndMessages(null)
        if(instance===this)instance=null
    }
    companion object {
        @Volatile var running=false;private set
        @Volatile internal var instance:CaptureRuntime?=null;private set
        internal val sensorRunning get()=instance?.registered==true
        internal fun attach(service:ScreenCaptureService) {
            instance?.close();instance=CaptureRuntime(service);refreshHealth()
        }
        internal fun detach(service:ScreenCaptureService) { instance?.takeIf { it.service===service }?.close() }
        fun refreshHealth() { instance?.let { runtime->runtime.mainHandler.post { runtime.refreshReadiness() } } }
        fun stopSafely() { instance?.let { runtime->runtime.mainHandler.post { if(instance===runtime)runtime.stop() } } }
    }
}
