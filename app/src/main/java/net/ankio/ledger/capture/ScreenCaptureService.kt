package net.ankio.ledger.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.RecognitionException
import net.ankio.ledger.core.*
import net.ankio.ledger.ledger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** Acquire a validated frame first; local recognition continues independently of the foreground page. */
open class ScreenCaptureService:AccessibilityService() {
    private val busy=AtomicBoolean(false)
    private var job:Job?=null
    private var connectionRestore:Job?=null
    private var shortcutCapture:Job?=null
    @Volatile private var requestId=0L
    private val banner by lazy { RecognitionBanner(this) }
    private val knownWindows=java.util.concurrent.ConcurrentHashMap<Int,String>()
    private val knownWindowTypes=java.util.concurrent.ConcurrentHashMap<Int,Int>()
    private data class ForegroundMetadata(val windowId:Int?,val generation:Long)
    @Volatile private var foregroundMetadata:ForegroundMetadata?=null
    private val foregroundSwitch=java.util.concurrent.atomic.AtomicLong()
    private val windowProbe=CaptureProbe()
    private val rootProbe=CaptureProbe()
    private val paymentMonitor by lazy { PaymentPageMonitor(this,::captureAutomatic) }
    internal var processorFactory:()->CaptureProcessor={ CaptureProcessor(ledger) }
    internal var beforeScreenshot:suspend ()->Unit={}
    // Provider checks the context at actual frame delivery, before resuming the caller.
    // Once delivered, navigation cannot invalidate the retained frame/snapshot pair.
    internal var screenshotProvider:suspend (Int?,()->Boolean)->Bitmap={ window,allowed -> screenshot(window,allowed) }
    private val contentGenerations=java.util.concurrent.ConcurrentHashMap<Int,Long>()
    private val contentSerial=java.util.concurrent.atomic.AtomicLong()
    internal fun captureContentGeneration(window:Int)=contentGenerations[window] ?: contentSerial.get()
    internal var beforeShakeOwner:suspend ()->Unit={}
    @Volatile private var activeAutomatic:Boolean?=null
    @Volatile private var activeShortcut=false
    @Volatile private var activePackage:String?=null
    @Volatile private var cacheMode="platform_default"
    @Volatile private var runtimeRestoreAt=-1L
    @Volatile private var runtimeRestoreNotBefore=0L
    @Volatile private var runtimeRestoreAttempts=0L
    @Volatile private var runtimeRestoreOutcome="not_requested"
    @Volatile private var lastScreenshotAt=0L
    private val paymentBoot by lazy { runCatching {
        android.provider.Settings.Global.getString(contentResolver,android.provider.Settings.Global.BOOT_COUNT)
            ?.toLongOrNull()?.takeIf { it>=0 }?.toString()
    }.getOrNull()?:java.util.UUID.randomUUID().toString() }
    @Volatile internal var screenshotRequests=0L;private set
    @Volatile internal var lastScreenshotWindowId:Int?=null;private set
    private val shakeAttempts=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var shakeDecision="{}"
    private class PrivacyBlocked(val userMessage:String="页面暂不可读，请稍后再试"):Exception()

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event==null)return
        if(event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) || event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                event.windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_REMOVED or AccessibilityEvent.WINDOWS_CHANGE_BOUNDS)!=0) {
            if(contentGenerations.size>128)contentGenerations.clear()
            val serial=contentSerial.incrementAndGet()
            if(event.windowId>=0)contentGenerations[event.windowId]=serial
            else {
                // Missing identity never authorizes an in-flight frame. Conservatively
                // invalidate this package's known windows (all when package is missing).
                val pkg=event.packageName?.toString()
                knownWindows.filterValues { pkg==null || it==pkg }.keys.forEach { contentGenerations[it]=serial }
            }
        }
        val foreground=foregroundMetadata?.takeIf { it.generation==foregroundSwitch.get() }?.windowId
        if(shakeWindowEventChangesTarget(event.eventType,event.windowChanges,event.windowId,
                foreground,knownWindowTypes[event.windowId]))foregroundSwitch.incrementAndGet()
        if(event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED!=0) {
            knownWindows.remove(event.windowId)
            knownWindowTypes.remove(event.windowId)
        }
        if(event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            val pkg=event.packageName?.toString()
            if(CapturePrivacy.manualPackageAllowed(pkg) && event.windowId>=0) {
                if(knownWindows.size>64)knownWindows.clear()
                knownWindows[event.windowId]=pkg!!
            }
        }
        // Restore only an already enabled capability after a paused runtime.
        val eventPackage=event.packageName?.toString()
        if(CapturePrivacy.manualPackageAllowed(eventPackage) &&
            (ledger.channels.autoAllowed(eventPackage!!) || ledger.channels.enabled(ChannelSettings.Channel.OCR)))
            restoreEnabledRuntime("payment_event")
        paymentMonitor.event(event)
    }
    private fun restoreEnabledRuntime(reason:String) {
        if(current!==this || CaptureRuntime.running || !ledger.channels.captureEnabled())return
        val now=SystemClock.elapsedRealtime()
        if(now<runtimeRestoreNotBefore)return
        if(!ledger.channels.permitted(ChannelSettings.Channel.OCR))return
        runtimeRestoreAt=now;runtimeRestoreNotBefore=now+2_000;runtimeRestoreAttempts++
        runCatching { CaptureRuntime.refreshHealth() }
            .onSuccess {
                runtimeRestoreOutcome="requested:$reason"
                ledger.executionLog.event("system","capture_service","runtime_restore_requested",
                    org.json.JSONObject().put("reason",reason).put("attempt",runtimeRestoreAttempts))
            }.onFailure {
                runtimeRestoreOutcome="failed:${it.javaClass.simpleName}"
                ledger.executionLog.event("system","capture_service","runtime_restore_failed",
                    org.json.JSONObject().put("reason",reason).put("errorType",it.javaClass.simpleName))
                ledger.channels.status("识别运行服务未恢复，请检查后台运行设置");ledger.changed()
            }
    }
    override fun onServiceConnected() {
        knownWindows.clear()
        knownWindowTypes.clear()
        foregroundMetadata=null
        val configuration=serviceInfo?:return
        serviceInfo=configuration.apply {
            // Alipay groups the result description and hides its individual status/amount
            // nodes from the default tree. Read those nodes before the existing privacy gate.
            flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        current=this
        refreshScope()
        if(Build.VERSION.SDK_INT>=33)cacheMode=if(runCatching { setCacheEnabled(false) }.getOrDefault(false))"disabled" else "disable_failed"
        CaptureRuntime.attach(this)
        CaptureRuntime.refreshHealth()
        // The OS may rebind accessibility while our Activity is closed after reclaiming
        // the process. Restore only previously enabled capture, without requiring an app visit.
        connectionRestore?.cancel()
        connectionRestore=ledger.scope.launch(Dispatchers.Main.immediate) {
            repeat(12) {
                if(current!==this@ScreenCaptureService || !ledger.channels.captureEnabled())return@launch
                if(ledger.channels.permitted(ChannelSettings.Channel.OCR)) {
                    restoreEnabledRuntime("accessibility_reconnected")
                    // The permission list can lag service binding. The immediate
                    // resume below may have refused it before the permission list caught up.
                    ledger.captureQueue.resume()
                    ledger.executionLog.event("system","capture_service","accessibility_reconnected")
                    return@launch
                }
                delay(250)
            }
        }
        ledger.captureQueue.resume()
        ledger.changed()
    }
    private suspend fun windowSnapshot(timeout:Long=180):CaptureProbeResult<List<CaptureWindow>> {
        val generation=foregroundSwitch.get()
        val result=windowProbe.readResult(timeout) {
            val available=windows
            try { available.map {
                knownWindowTypes[it.id]=it.type
                CaptureWindow(it.id,it.type,it.isActive,it.isFocused,it.layer,null,it.isInPictureInPictureMode)
            }.also { if(knownWindowTypes.size>128)knownWindowTypes.keys.retainAll(it.map { value -> value.id }.toSet()) } }
            finally { available.forEach { it.recycle() } }
        }
        // Use only an on-time metadata result. A late Binder response must not
        // replace the foreground identity used to classify subsequent events.
        foregroundMetadata=result.value?.takeIf { foregroundSwitch.get()==generation }?.let { ForegroundMetadata(selectCaptureWindow(it)?.id,generation) }
        return result
    }
    private suspend fun foregroundObservation(trace:(String)->Unit):ShakeWindowObservation {
        val switch=foregroundSwitch.get()
        val snapshot=windowSnapshot(700)
        val metadata=snapshot.value
        if(metadata==null) { trace("windows=${snapshot.outcome}; cache=$cacheMode");return ShakeWindowObservation(null,switch,null) }
        if(metadata.isNotEmpty())knownWindows.keys.retainAll(metadata.map { it.id }.toSet())
        val selected=selectCaptureWindow(metadata)
        var detail="windows=${metadata.size}; selected=${selected?.id}; type=${selected?.type}; focused=${selected?.focused}; active=${selected?.active}; event=${selected?.id?.let { knownWindows[it] }}; cache=$cacheMode"
        val owner=lookupCaptureOwner(metadata,knownWindows.toMap()) { windowId ->
            val result=rootProbe.readResult(700) {
                val node=if(windowId==null) {
                    if(Build.VERSION.SDK_INT>=33)getRootInActiveWindow(0) else rootInActiveWindow
                } else {
                    val available=windows
                    try {
                        available.firstOrNull { it.id==windowId }?.let { if(Build.VERSION.SDK_INT>=33)it.getRoot(0) else it.root }
                    } finally { available.forEach { it.recycle() } }
                }
                node?.let { try { CaptureRoot(it.windowId,it.packageName?.toString()) } finally { it.recycle() } }
            }
            detail+="; root=${result.outcome}; rootWindow=${result.value?.windowId}"
            result.value
        }
        // lookupCaptureOwner validates root/window identity before accepting an owner. Remember
        // that proof for the immediate screenshot check, including the first shake after binding.
        if(selected?.type==AccessibilityWindowInfo.TYPE_APPLICATION && CapturePrivacy.manualPackageAllowed(owner))
            knownWindows[selected.id]=owner!!
        trace("$detail; owner=${owner?:"unavailable"}")
        return if(foregroundSwitch.get()!=switch)ShakeWindowObservation(null,foregroundSwitch.get(),null)
            else ShakeWindowObservation(selected?.id,switch,owner)
    }
    /** Metadata-only observation for native readiness checks; never traverses page content. */
    internal suspend fun observeShakeWindow():ShakeWindowObservation=foregroundObservation {}
    private suspend fun currentTarget(pkg:String,expectedWindow:Int?=null,expectedSwitch:Long?=null):CaptureWindow? {
        if(expectedSwitch!=null && foregroundSwitch.get()!=expectedSwitch)return null
        val metadata=windowSnapshot().value?:return null
        val selected=selectCaptureWindow(metadata)?:return null
        if(selected.type!=AccessibilityWindowInfo.TYPE_APPLICATION || !selected.active && !selected.focused)return null
        if(expectedWindow!=null && selected.id!=expectedWindow)return null
        return selected.takeIf { knownWindows[it.id]==pkg && (expectedSwitch==null || foregroundSwitch.get()==expectedSwitch) }
    }

    private fun cancelAcquisition() {
        requestId++;job?.cancel();job=null;busy.set(false);activeAutomatic=null;activeShortcut=false;activePackage=null;banner.close()
    }
    fun cancelRecognition() { shortcutCapture?.cancel();shortcutCapture=null;cancelAcquisition();ledger.captureQueue.cancelAll() }
    fun cancelShakeRecognition(packageName:String?=null) {
        shortcutCapture?.cancel();shortcutCapture=null
        if(activeAutomatic==false && (packageName==null || activePackage==packageName))cancelAcquisition()
        ledger.captureQueue.cancelShake(packageName)
        ledger.captureQueue.cancelShortcut(packageName)
        refreshScope()
    }
    fun cancelAutomaticRecognition(packageName:String?=null) {
        if(activeAutomatic==true && (packageName==null || activePackage==packageName))cancelAcquisition()
        ledger.captureQueue.cancelAutomatic(packageName)
        paymentMonitor.stop()
        refreshScope()
    }
    fun cancelChannel(channel:ChannelSettings.Channel) {
        if(channel==ChannelSettings.Channel.OCR) {
            if(activeAutomatic==false && !activeShortcut)cancelAcquisition()
            ledger.captureQueue.cancelShake()
            refreshScope()
        }
    }
    /** Connection teardown must not discard already acquired, queued frames. */
    fun captureServiceStopped() {
        // A completed runtime being stopped is a new recovery opportunity, not
        // another duplicate start request from the previous event burst.
        runtimeRestoreNotBefore=0L
        if(!activeShortcut)cancelAcquisition()
        paymentMonitor.stop()
    }
    fun refreshScope() {
        // The OS can clear the connection before onUnbind clears current. Consent
        // cancellation still runs then; an unbound service has no scope to update.
        if(current!==this)return
        val configuration=serviceInfo?:return
        serviceInfo=configuration.apply {
            // Android filters package-less TYPE_WINDOWS_CHANGED out when packageNames
            // is set. Those system events are needed when an activity is announced
            // before its window can be queried. Receive their metadata, then enforce
            // application selection in the monitor BEFORE reading a view tree or pixels.
            // Keep the XML filter empty too so that the first service connection
            // can receive system-window events before this runtime update.
            packageNames=null
        }
    }
    fun checkPaymentPages() { refreshScope();paymentMonitor.schedule() }
    internal fun automaticCaptureAvailable()=!busy.get() && ledger.captureQueue.hasCapacity()
    fun paymentDiagnostics()=paymentMonitor.diagnostics()
        .put("feedback",banner.diagnostics())
        .put("shake",org.json.JSONObject(shakeDecision))
        .put("captureBusy",busy.get()).put("queueSize",ledger.captureQueue.size())
        .put("runtimeRestore",org.json.JSONObject().put("attempts",runtimeRestoreAttempts)
            .put("outcome",runtimeRestoreOutcome)
            .put("lastAttemptAgoMs",if(runtimeRestoreAt<0)-1 else SystemClock.elapsedRealtime()-runtimeRestoreAt))
    /** ADB can inspect the running listener without bringing MainActivity forward. */
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>?) {
        super.dump(fd,writer,args)
        writer.println("ZhiyuCapture="+paymentDiagnostics().toString())
    }
    fun revoke() {
        ledger.channels.invalidateShortcuts()
        ledger.channels.setAutomaticEnabled(false)
        ledger.channels.set(ChannelSettings.Channel.OCR,false)
        cancelRecognition();CaptureRuntime.stopSafely();disableSelf();ledger.changed()
    }
    fun captureOnShake()=captureOnShake(ShakeTrigger.now())
    internal fun captureOnShake(trigger:ShakeTrigger)=capture(trigger)
    /** Allow only the shortcut/gesture window to disappear; never renew a stale trigger. */
    internal fun captureFromShortcut() {
        shortcutCapture?.cancel()
        val trigger=ShakeTrigger.now()
        val consent=synchronized(ledger.channels) {
            ledger.channels.shortcutGeneration() to ledger.channels.selectedShakePackages()
                .associateWith(ledger.channels::shakeApplicationGeneration)
        }
        shortcutCapture=ledger.scope.launch(Dispatchers.Main) {
            delay(180)
            while(trigger.freshAt(SystemClock.elapsedRealtime())) {
                if(current!==this@ScreenCaptureService || !screenUnlocked() ||
                    !ledger.channels.permitted(ChannelSettings.Channel.OCR) ||
                    ledger.channels.shortcutGeneration()!=consent.first)return@launch
                val observed=observeShakeWindow()
                if(!trigger.freshAt(SystemClock.elapsedRealtime()))break
                val pkg=observed.packageName
                if(pkg!=null && pkg!=packageName) {
                    if(!ledger.channels.shortcutAllowed(pkg) || consent.second[pkg]!=ledger.channels.shakeApplicationGeneration(pkg)) {
                        feedback("请在已选应用的单笔账单详情页触发快捷方式");return@launch
                    }
                    if(foregroundSwitch.get()==observed.switchGeneration) {
                        capture(trigger,expectedWindow=observed,shortcut=true);return@launch
                    }
                }
                delay(60)
            }
            feedback("页面尚未就绪，请打开账单详情后重试快捷方式")
        }
    }
    internal fun captureAutomatic(page:PaymentMatch)=capture(ShakeTrigger.now(),page)
    private fun capture(trigger:ShakeTrigger,page:PaymentMatch?=null,expectedWindow:ShakeWindowObservation?=null,shortcut:Boolean=false) {
        // Diagnostic application acquires only explicit comparison pairs, never financial tasks.
        if(net.ankio.ledger.BuildConfig.NODE_DIAGNOSTIC)return
        val automatic=page!=null
        val channel=ChannelSettings.Channel.OCR
        val attempt=if(automatic)0 else shakeAttempts.incrementAndGet()
        fun note(outcome:String,window:Int?=null,detail:String="") {
            if(!automatic && shakeAttempts.get()==attempt)shakeDecision=org.json.JSONObject().put("attempt",attempt)
                .put("outcome",outcome).put("window",window?:org.json.JSONObject.NULL)
                .put("switchGeneration",foregroundSwitch.get()).put("eventAgeMs",SystemClock.elapsedRealtime()-trigger.eventAtMs)
                .put("detail",detail.take(200)).toString()
        }
        note("received")
        if(!trigger.freshAt(SystemClock.elapsedRealtime()))return
        val enabled=if(automatic)ledger.channels.autoAllowed(page!!.packageName) else shortcut || ledger.channels.enabled(channel)
        if((!shortcut && !CaptureRuntime.running) || !enabled || shortcut && (current!==this || !ledger.channels.permitted(channel)))return
        if(!automatic && (ledger.channels.selectedShakePackages().isEmpty() || !screenUnlocked()))return
        val consent=if(automatic)null else synchronized(ledger.channels) {
            (if(shortcut)ledger.channels.shortcutGeneration() else ledger.channels.shakeMasterGeneration()) to ledger.channels.selectedShakePackages().associateWith(ledger.channels::shakeApplicationGeneration)
        }
        val generation=if(automatic)ledger.channels.automaticGeneration(page!!.packageName) else consent!!.first
        val recognitionGeneration=ledger.recognitionSession.generation
        val initialSwitch=foregroundSwitch.get()
        val initialRequest=requestId
        // Bind to metadata observed during THIS trigger, before dispatch to the application
        // scope. The bounded Binder worker keeps an unavailable window from blocking sensors.
        val initialObservation=if(automatic)null else {
            val result=runBlocking { windowSnapshot(120) }
            val metadata=result.value?:run { note("initial_metadata_unavailable",detail=result.outcome);return }
            val selected=selectCaptureWindow(metadata)?:run { note("initial_window_unavailable");return }
            if(expectedWindow!=null && (selected.id!=expectedWindow.windowId || initialSwitch!=expectedWindow.switchGeneration)) {
                note("shortcut_window_changed",selected.id);return
            }
            if(selected.type!=AccessibilityWindowInfo.TYPE_APPLICATION || foregroundSwitch.get()!=initialSwitch || !trigger.freshAt(SystemClock.elapsedRealtime())) {
                note("initial_window_changed",selected.id,"type=${selected.type}; expectedSwitch=$initialSwitch");return
            }
            val owner=captureForegroundPackage(metadata,null,knownWindows.toMap())
            if(owner!=null && (owner !in consent!!.second || !(if(shortcut)ledger.channels.shortcutAllowed(owner) else ledger.channels.shakeAllowed(owner)))) { note("application_not_selected",selected.id);return }
            note("initial_window_bound",selected.id)
            ShakeWindowObservation(selected.id,initialSwitch,owner)
        }
        ledger.scope.launch {
            val observations=linkedSetOf<String>()
            val windowDetails=linkedSetOf<String>()
            if(!automatic)beforeShakeOwner()
            note("owner_check_started",initialObservation?.windowId)
            // Resolve the foreground BEFORE queue/busy feedback or any content read. A known
            // unselected owner returns immediately; an unknown owner is tied to one window.
            var firstObservation=true
            val observed=if(automatic)null else resolveShakeTarget({
                if(foregroundSwitch.get()!=initialSwitch || requestId!=initialRequest || !trigger.freshAt(SystemClock.elapsedRealtime()))
                    ShakeWindowObservation(null,foregroundSwitch.get(),null)
                else if(firstObservation) { firstObservation=false;initialObservation!! }
                else foregroundObservation { windowDetails.add(it) }.also { observations.add(it.packageName?:"unavailable") }
            }) { pkg -> pkg in consent!!.second && (if(shortcut)ledger.channels.shortcutAllowed(pkg) else ledger.channels.shakeAllowed(pkg)) }?:if(!automatic) {
                note("owner_rejected",initialObservation?.windowId);return@launch
            } else null
            val pkg=page?.packageName?:observed?.packageName?:return@launch
            val authorization=if(automatic)null else (if(shortcut)ledger.channels.shortcutAuthorization(pkg) else ledger.channels.shakeAuthorization(pkg))?:return@launch
            if(!automatic && (authorization!!.masterGeneration!=generation || authorization.applicationGeneration!=consent!!.second[pkg] ||
                    observed!!.switchGeneration!=initialSwitch || !screenUnlocked()))return@launch
            if(!trigger.freshAt(SystemClock.elapsedRealtime()) || requestId!=initialRequest || ledger.recognitionSession.generation!=recognitionGeneration)return@launch
            if(!ledger.captureQueue.hasCapacity()) { if(!automatic)feedback("待识别账单较多，请稍候再摇一摇");return@launch }
            if(!busy.compareAndSet(false,true)) { if(!automatic)feedback("正在获取上一张账单，请稍候");return@launch }
            activeAutomatic=automatic;activeShortcut=shortcut;activePackage=pkg
            val id=++requestId
            job=coroutineContext[Job]
            fun allowed()=requestId==id && (if(shortcut)current===this@ScreenCaptureService else CaptureRuntime.running) && ledger.channels.permitted(channel) && ledger.recognitionSession.generation==recognitionGeneration &&
                if(automatic)ledger.channels.autoAllowed(pkg) && ledger.channels.automaticGeneration(pkg)==generation
                else authorization!=null && ledger.channels.shakeAuthorized(authorization)
            var task:Long?=null
            var bitmap:Bitmap?=null
            var enqueued=false
            try {
                val startAtMs=SystemClock.elapsedRealtime()
                if(!trigger.freshAt(startAtMs) || !allowed())throw CancellationException()
                // No acquisition banner or artificial delay before the frame: a result can vanish
                // as soon as the user taps Done. Any progress banner is shown after persistence.
                withContext(Dispatchers.Main) { banner.removeNow() }
                while(true) {
                    val now=SystemClock.elapsedRealtime()
                    val remaining=screenshotCooldownRemaining(Build.VERSION.SDK_INT,lastScreenshotAt,now)
                    if(!allowed() || !trigger.freshAt(now) || !trigger.freshAt(now+remaining))throw CancellationException("本次摇动已失效")
                    if(remaining==0L)break
                    note("waiting_for_screenshot_interval",observed?.windowId)
                    delay(remaining)
                }
                val target=currentTarget(pkg,page?.windowId?:observed?.windowId,observed?.switchGeneration)
                    ?:throw RecognitionException("页面正在切换，未获取本次截图，可打开账单详情摇一摇补录")
                // A screenshot may NEVER be used as the probe that decides whether a page
                // contains a password. Unknown/unreadable shared containers fail closed.
                val manual=if(automatic)null else paymentMonitor.manualCheck(pkg,target.id,shortcut)
                note("page_checked",target.id,manual?.outcome.orEmpty())
                if(manual?.value?.let(net.ankio.ledger.core.CapturePrivacy::sensitive)==true)
                    throw PrivacyBlocked("支付验证页，暂不截图")
                val validatedPage=if(automatic)paymentMonitor.confirmedPage(page!!) else manual?.value
                if(validatedPage?.let(net.ankio.ledger.core.CapturePrivacy::manualAllowed)!=true)throw PrivacyBlocked()
                val snapshot=validatedPage.snapshot ?: throw PrivacyBlocked()
                if(!automatic)beforeScreenshot()
                if(!allowed() || !trigger.freshAt(SystemClock.elapsedRealtime()) || currentTarget(pkg,target.id,observed?.switchGeneration)==null)throw CancellationException("采集窗口已经改变")
                // A stable window can replace its transaction in place. Re-read the exact semantics
                // before the pixel request; an adapter/fallback may never authorize a new sensitive page.
                val revalidated=if(automatic)paymentMonitor.confirmedPage(page!!) else paymentMonitor.manualCheck(pkg,target.id,shortcut).value
                if(revalidated?.snapshot?.sameTransaction(snapshot)!=true)throw ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"页面内容已变化，未获取本次截图")
                if(!allowed() || currentTarget(pkg,target.id,observed?.switchGeneration)==null)throw CancellationException("采集窗口已经改变")
                note("screenshot_authorized",target.id)
                task=ledger.tasks.create(Source.OCR,if(automatic)"payment_auto:$pkg" else authorization!!.origin,authorization)
                if(automatic)ledger.tasks.diagnose(task,"payment_identity","payment_page:$paymentBoot:${page!!.eventIdentity}")
                ledger.tasks.diagnose(task,"trigger",(if(automatic)"payment_auto; " else "")+trigger.details(startAtMs))
                ledger.changed()
                ledger.tasks.diagnose(task,"stage","screenshot")
                ledger.tasks.diagnose(task,"window","$pkg; id=${target.id}; automatic=$automatic")
                val processor=processorFactory()
                val fallback=processor.prepareNodeFallback(task,snapshot,pkg)
                var missingReason:ScreenshotFailureReason?=null
                val screenshotAt=SystemClock.elapsedRealtime()
                bitmap=try { withTimeoutOrNull(4_000) { screenshotProvider(if(Build.VERSION.SDK_INT>=34)target.id else null) {
                    allowed() && captureContentGeneration(target.id)==snapshot.generation &&
                        (automatic || screenUnlocked() && foregroundSwitch.get()==observed?.switchGeneration)
                } }
                    ?:throw ScreenshotAcquisitionException(ScreenshotFailureReason.TIMEOUT,"系统截图响应超时，请检查无障碍状态后重试")
                } catch(error:ScreenshotAcquisitionException) {
                    // Keep the existing protected-page privacy refusal: no financial attempt,
                    // silent automatic probes, and an explanation for an explicit request.
                    if(error.reason==ScreenshotFailureReason.PROTECTED)throw PrivacyBlocked(error.userMessage)
                    if(!error.ordinary || fallback==null)throw error
                    // A failed pixel request grants no evidence delivery: all current gates still apply.
                    if(!allowed() || !screenUnlocked())throw CancellationException()
                    if(currentTarget(pkg,target.id,observed?.switchGeneration)==null || captureContentGeneration(target.id)!=snapshot.generation)
                        throw ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"采集窗口已经改变")
                    val afterFailure=if(automatic)paymentMonitor.confirmedPage(page!!) else paymentMonitor.manualCheck(pkg,target.id,shortcut).value
                    if(afterFailure?.snapshot?.sameTransaction(snapshot)!=true || !allowed() || !screenUnlocked() ||
                        captureContentGeneration(target.id)!=snapshot.generation || currentTarget(pkg,target.id,observed?.switchGeneration)==null)
                        throw ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"采集窗口已经改变")
                    missingReason=error.reason
                    null
                }
                ledger.tasks.phaseTiming(task,"screenshot",SystemClock.elapsedRealtime()-screenshotAt)
                if(!allowed())throw CancellationException()
                // A specified-window frame belongs to the validated target even if the user has
                // returned since acquisition. Legacy full-display capture needs a second owner check.
                if(Build.VERSION.SDK_INT<34 && currentTarget(pkg,target.id,observed?.switchGeneration)==null)
                    throw RecognitionException("页面已经切换，本次截图未发送，可打开详情摇一摇补录")
                ledger.tasks.recordOriginalCaptureDay(task,System.currentTimeMillis(),java.time.ZoneId.systemDefault().id)
                if(automatic)ledger.tasks.diagnose(task,"capture_context","payment_success_result:${System.currentTimeMillis()}")
                ledger.tasks.diagnose(task,"timing","captured_age_ms=${SystemClock.elapsedRealtime()-trigger.eventAtMs}")
                val image=bitmap?.let { pixels -> withContext(Dispatchers.Default) { ledger.recognizer.prepareScreenshot(pixels) } }
                if(!allowed())throw CancellationException()
                val queued=if(image!=null)ledger.tasks.queued(task,image,snapshot)
                    else ledger.tasks.queuedWithoutScreenshot(task,snapshot,requireNotNull(fallback),requireNotNull(missingReason))
                if(!queued)throw CancellationException("识别记录已删除")
                ledger.tasks.diagnose(task,"window_details",windowDetails.toList().takeLast(2).joinToString(" | "))
                val feedbackTask=task
                enqueued=ledger.captureQueue.enqueue(task,pkg,automatic,{processor}) { message,loading ->
                    feedback(message,loading,if(!loading && (message.startsWith("已记账") || message=="退款已更新"))ledger.tasks.recordId(feedbackTask) else null)
                }
                if(!enqueued)throw RecognitionException("识别任务较多，请稍后重新摇一摇补录")
                note("queued",target.id)
                ledger.changed()
            } catch(blocked:PrivacyBlocked) {
                note("privacy_blocked",observed?.windowId)
                if(page!=null)paymentMonitor.rejectFrame(page)
                task?.let(ledger.tasks::delete)
                ledger.executionLog.event(if(automatic)"payment_auto" else "ocr","capture","privacy_skipped")
                // Automatic probes stay silent. An explicit shake must explain its refusal,
                // without acquiring pixels or leaving a failed financial record behind.
                if(!automatic && allowed())feedback(blocked.userMessage)
            } catch(cancelled:CancellationException) {
                note("cancelled",observed?.windowId)
                if(page!=null && !enqueued)paymentMonitor.rejectFrame(page)
                if(!enqueued)task?.let { ledger.tasks.update(it,"CANCELLED","识别已停止，未新增账单");ledger.changed() }
            } catch(error:Exception) {
                note("failed",observed?.windowId,(error as? RecognitionException)?.userMessage?:error.javaClass.simpleName)
                if(page!=null && !enqueued)paymentMonitor.rejectFrame(page)
                val message=(error as? RecognitionException)?.userMessage?:"获取截图失败，请在账单详情页重试"
                ledger.executionLog.event(if(automatic)"payment_auto" else channel.key,"capture","failed",org.json.JSONObject().put("message",message).put("errorType",error.javaClass.simpleName))
                task?.let {
                    ledger.tasks.diagnose(it,"window",observations.joinToString(" → "))
                    ledger.tasks.diagnose(it,"window_details",windowDetails.toList().takeLast(2).joinToString(" | "))
                    ledger.tasks.update(it,"FAILED",message);ledger.changed()
                }
                if(allowed())feedback(message)
            } finally {
                bitmap?.recycle()
                if(requestId==id) { busy.set(false);activeAutomatic=null;activeShortcut=false;activePackage=null;job=null;paymentMonitor.schedule() }
            }
        }
    }
    /** Debug diagnostic receiver only. Reuses the production target, tree, privacy and pixel gates. */
    internal suspend fun collectNodeComparison(pkg:String):org.json.JSONObject {
        check(net.ankio.ledger.BuildConfig.DEBUG && net.ankio.ledger.BuildConfig.NODE_DIAGNOSTIC && packageName.endsWith(".nodediagnostic"))
        check(busy.compareAndSet(false,true))
        var image:Bitmap?=null
        try {
            val authorization=ledger.channels.shakeAuthorization(pkg) ?: throw CancellationException("application_not_selected")
            val recognitionGeneration=ledger.recognitionSession.generation
            val observed=observeShakeWindow()
            check(observed.packageName==pkg && observed.windowId!=null)
            val target=currentTarget(pkg,observed.windowId,observed.switchGeneration) ?: throw CancellationException("window_changed")
            fun allowed()=current===this && CaptureRuntime.running && screenUnlocked() && ledger.channels.permitted(ChannelSettings.Channel.OCR) &&
                ledger.channels.shakeAuthorized(authorization) && ledger.recognitionSession.generation==recognitionGeneration &&
                foregroundSwitch.get()==observed.switchGeneration
            if(!allowed())throw CancellationException("authorization_changed")
            withContext(Dispatchers.Main) { banner.removeNow() }
            val first=paymentMonitor.manualCheck(pkg,target.id).value?.snapshot ?: error("snapshot_unavailable")
            if(!CapturePrivacy.manualAllowed(first.page()) || !allowed())throw CancellationException("privacy_or_authorization")
            val confirmed=paymentMonitor.manualCheck(pkg,target.id).value?.snapshot ?: error("snapshot_unavailable")
            if(!first.sameTransaction(confirmed) || currentTarget(pkg,target.id,observed.switchGeneration)==null)throw CancellationException("semantic_identity_changed")
            val requestedAt=SystemClock.elapsedRealtime()
            image=withTimeoutOrNull(4_000) { screenshotProvider(if(Build.VERSION.SDK_INT>=34)target.id else null) {
                allowed() && captureContentGeneration(target.id)==confirmed.generation
            } } ?: throw ScreenshotAcquisitionException(ScreenshotFailureReason.TIMEOUT,"comparison_timeout")
            val deliveredAt=SystemClock.elapsedRealtime()
            val after=paymentMonitor.manualCheck(pkg,target.id).value?.snapshot ?: error("snapshot_unavailable")
            if(!allowed() || !confirmed.sameTransaction(after) || currentTarget(pkg,target.id,observed.switchGeneration)==null)
                throw CancellationException("comparison_context_changed")
            val snapshot=net.ankio.ledger.data.NodeSnapshotCodec.json(confirmed).toString().toByteArray(Charsets.UTF_8)
            val png=java.io.ByteArrayOutputStream().use { stream -> check(image!!.compress(Bitmap.CompressFormat.PNG,100,stream));stream.toByteArray() }
            fun sha(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val comparisonId=java.util.UUID.randomUUID().toString()
            val root=java.io.File(filesDir,"node-comparisons").apply { mkdirs();android.system.Os.chmod(absolutePath,448) }
            val directory=java.io.File(root,comparisonId).apply { mkdir();android.system.Os.chmod(absolutePath,448) }
            fun write(name:String,bytes:ByteArray) { java.io.File(directory,name).also { it.writeBytes(bytes);android.system.Os.chmod(it.absolutePath,384) } }
            val manifest=org.json.JSONObject().put("version",1).put("comparisonId",comparisonId).put("package",pkg).put("windowId",target.id)
                .put("foregroundSwitchGeneration",observed.switchGeneration).put("snapshotFingerprint",confirmed.fingerprint)
                .put("snapshotSha256",sha(snapshot)).put("screenshotSha256",sha(png)).put("width",image!!.width).put("height",image!!.height)
                .put("screenshotTarget",if(Build.VERSION.SDK_INT>=34)"WINDOW" else "DISPLAY").put("screenshotRequestedMs",requestedAt).put("screenshotDeliveredMs",deliveredAt)
                .put("secondSnapshotFingerprint",after.fingerprint).put("sameTransaction",true).put("nodeReadMs",confirmed.readCompletedMs-confirmed.readStartedMs)
                .put("screenshotMs",deliveredAt-requestedAt).put("appVersion",confirmed.environment.appVersion).put("appVersionCode",confirmed.environment.appVersionCode)
                .put("system",confirmed.environment.system).put("device",confirmed.environment.device).put("scope","manual detail comparison; no layout enabled")
            write("snapshot.json",snapshot);write("screenshot.png",png);write("manifest.json",manifest.toString().toByteArray(Charsets.UTF_8))
            return org.json.JSONObject().put("ok",true).put("comparisonId",comparisonId)
        } finally { image?.recycle();busy.set(false) }
    }
    private fun screenUnlocked()=getSystemService(android.os.PowerManager::class.java).isInteractive &&
        !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    private suspend fun screenshot(windowId:Int?,allowed:()->Boolean):Bitmap=suspendCancellableCoroutine { continuation ->
        val callback=object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                lastScreenshotAt=SystemClock.elapsedRealtime()
                val buffer=result.hardwareBuffer
                if(!allowed()) {
                    buffer.close()
                    if(continuation.isActive)continuation.resumeWithException(ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"采集上下文已改变，未获取本次截图"))
                    return
                }
                val image=try { Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)?.let { hardware ->
                    try { hardware.copy(Bitmap.Config.ARGB_8888,false) } finally { hardware.recycle() }
                } } catch(_:Exception) { null } finally { buffer.close() }
                if(!continuation.isActive) { image?.recycle();return }
                if(image==null)continuation.resumeWithException(ScreenshotAcquisitionException(ScreenshotFailureReason.UNAVAILABLE,"无法读取截图，请尝试导入截图"))
                else continuation.resume(image) { _,value,_ -> value.recycle() }
            }
            override fun onFailure(errorCode:Int) {
                lastScreenshotAt=SystemClock.elapsedRealtime()
                if(continuation.isActive)continuation.resumeWithException(
                    if(errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)ScreenshotAcquisitionException(ScreenshotFailureReason.PROTECTED,"当前页面受系统保护，未获取截图；请使用账单截图导入")
                    else if(errorCode==ERROR_TAKE_SCREENSHOT_INVALID_WINDOW || errorCode==ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY)ScreenshotAcquisitionException(ScreenshotFailureReason.WINDOW_CHANGED,"截图窗口已失效，未新增账单")
                    else if(errorCode==ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)ScreenshotAcquisitionException(ScreenshotFailureReason.AUTHORIZATION_CHANGED,"截图授权已改变，未新增账单")
                    else ScreenshotAcquisitionException(ScreenshotFailureReason.UNAVAILABLE,"当前页面无法截图（系统代码 $errorCode），请尝试导入截图"))
            }
        }
        try {
            synchronized(ledger.channels) {
                if(!allowed())continuation.resumeWithException(ScreenshotAcquisitionException(ScreenshotFailureReason.AUTHORIZATION_CHANGED,"截图授权已改变，未新增账单"))
                else {
                    lastScreenshotAt=SystemClock.elapsedRealtime()
                    screenshotRequests++
                    lastScreenshotWindowId=windowId
                    if(Build.VERSION.SDK_INT>=34 && windowId!=null)takeScreenshotOfWindow(windowId,mainExecutor,callback)
                    else takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,callback)
                }
            }
        } catch(error:Exception) {
            if(continuation.isActive)continuation.resumeWithException(ScreenshotAcquisitionException(
                if(error is SecurityException)ScreenshotFailureReason.AUTHORIZATION_CHANGED else ScreenshotFailureReason.UNAVAILABLE,"无法获取截图，请检查无障碍状态"))
        }
    }
    private fun feedback(message:String,loading:Boolean=false,recordId:Long?=null) {
        ledger.channels.status(message);ledger.changed()
        if(current===this)banner.show(message,loading,recordId=recordId)
    }
    internal fun showReadinessFeedback(message:String) { if(current===this)banner.show(message) }
    /** Also used by disk-backed jobs resumed without a new foreground capture callback. */
    internal fun showQueuedFeedback(message:String,loading:Boolean,recordId:Long?)=feedback(message,loading,recordId)
    override fun onInterrupt() { shortcutCapture?.cancel();shortcutCapture=null;cancelAcquisition() }
    private fun releaseConnection() {
        shortcutCapture?.cancel();shortcutCapture=null
        cancelAcquisition()
        connectionRestore?.cancel();connectionRestore=null
        captureServiceStopped();knownWindows.clear();knownWindowTypes.clear();foregroundMetadata=null
        ledger.captureHealth.suspendForMissingPermission()
        CaptureRuntime.detach(this)
        if(current===this)current=null
        ledger.changed()
    }
    override fun onUnbind(intent:Intent?):Boolean { releaseConnection();return super.onUnbind(intent) }
    override fun onDestroy() { releaseConnection();paymentMonitor.close();windowProbe.close();rootProbe.close();super.onDestroy() }
    companion object {
        val PACKAGES=PaymentApps.entries.map { it.packageName }.toSet()
        @Volatile var current:ScreenCaptureService?=null;private set
    }
}

/** Removal of an already-background window cannot replace the confirmed foreground. */
internal fun shakeWindowEventChangesTarget(eventType:Int,changes:Int,windowId:Int,foregroundWindow:Int?,windowType:Int?):Boolean {
    if(windowType==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)return false
    if(eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return true
    if(eventType!=AccessibilityEvent.TYPE_WINDOWS_CHANGED)return false
    if(changes and (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or AccessibilityEvent.WINDOWS_CHANGE_ADDED)!=0)return true
    return changes and AccessibilityEvent.WINDOWS_CHANGE_REMOVED!=0 && (foregroundWindow==null || windowId==foregroundWindow)
}

/** AOSP rejects elapsed <=1000ms on API 30, and <=333ms from API 31 onward.
 * https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java
 * https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java
 * Keep a margin and measure from the callback, after the system received the last request. */
internal fun screenshotCooldownRemaining(sdk:Int,lastAt:Long,now:Long):Long =
    if(lastAt==0L)0L else ((if(sdk==30)1_100L else 400L)-(now-lastAt)).coerceAtLeast(0)
