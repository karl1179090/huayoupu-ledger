package net.ankio.ledger.ui

import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ledger
import org.json.JSONObject
import org.json.JSONArray

/** Native authority for the reference HTML permission sheets. No credentials are exposed to JS. */
internal class PermissionSetupController(private val activity:MainActivity,
    private val guidance:net.ankio.ledger.core.PermissionGuidance=net.ankio.ledger.capture.DevicePermissionGuidance.current) {
    private val app get()=activity.ledger
    private val background get()=activity.getSharedPreferences("background_setup",android.content.Context.MODE_PRIVATE)
    private var channel:ChannelSettings.Channel?=null
    private var kind=""
    private var coreOpened=false
    private var message=""
    private var enableAfter=true
    private var automatic=false
    private var pendingPackage:String?=null
    private var focusStep:String?=null
    private val backgroundKeys=listOf("battery","autostart","task_lock")
    val active get()=channel!=null
    fun start(value:ChannelSettings.Channel) {
        if(value!=ChannelSettings.Channel.OCR)return
        begin(false,true,null)
    }
    fun startAutomatic(packageName:String?=null) {
        if(packageName==null && app.channels.selectedAutoPackages().isEmpty()) {
            activity.message("请先选择允许自动识别的应用");return
        }
        begin(true,true,packageName)
    }
    private fun begin(auto:Boolean,enable:Boolean,pkg:String?) {
        channel=ChannelSettings.Channel.OCR;enableAfter=enable;coreOpened=false;message=""
        automatic=auto;pendingPackage=pkg;focusStep=null
        nextFeature()
    }
    fun openShake(enable:Boolean)=begin(false,enable,null)
    private fun nextFeature() {
        if(channel!=ChannelSettings.Channel.OCR)return
        kind="shake";render()
    }
    fun saveState():JSONObject? = if(active)JSONObject().put("channel",channel!!.key).put("kind",kind)
        .put("coreOpened",coreOpened).put("enableAfter",enableAfter).put("message",message).put("automatic",automatic)
        .put("pendingPackage",pendingPackage).put("focusStep",focusStep) else null
    fun restoreState(state:JSONObject) {
        val restored=ChannelSettings.Channel.entries.firstOrNull { it.key==state.optString("channel") }?:return
        if(restored!=ChannelSettings.Channel.OCR)return
        val restoredKind=state.optString("kind")
        if(restoredKind !in setOf("core","shake","done"))return
        channel=restored;kind=restoredKind;coreOpened=state.optBoolean("coreOpened")
        enableAfter=state.optBoolean("enableAfter",true);message=state.optString("message")
        automatic=state.optBoolean("automatic",false)
        pendingPackage=state.optString("pendingPackage").takeIf { pkg->net.ankio.ledger.core.PaymentApps.entries.any { it.packageName==pkg } }
        focusStep=state.optString("focusStep").takeIf { it in backgroundKeys }
    }
    fun cancel() { channel=null;kind="";message="";pendingPackage=null;focusStep=null;activity.showSetup(JSONObject().put("kind","closed")) }
    fun resume() { if(active)render() }
    private fun addressed(key:String)=(if(key=="battery")batteryExempt() else background.getBoolean(key,false)) || background.getBoolean(key+"_skipped",false)
    fun backgroundNeedsReview()=backgroundKeys.any { !addressed(it) }
    private fun syncBattery() {
        val allowed=batteryExempt()
        if(background.getBoolean("battery",false)!=allowed || allowed && background.getBoolean("battery_skipped",false)) {
            background.edit().putBoolean("battery",allowed).apply { if(allowed)remove("battery_skipped") else putBoolean("completed",false) }.apply()
        }
    }
    private fun batteryExempt()=activity.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
    private fun currentStep():String? = when {
        !automatic && app.channels.selectedShakePackages().isEmpty() -> "applications"
        !app.channels.permitted(ChannelSettings.Channel.OCR) -> "accessibility"
        net.ankio.ledger.capture.ScreenCaptureService.current==null -> "connection"
        else -> backgroundKeys.firstOrNull { !addressed(it) }
    }
    private fun selectedStep()=currentStep().let { if(it in setOf("accessibility","connection"))it else focusStep?:it }
    fun render() {
        if(!active)return
        syncBattery()
        val rows=JSONArray()
        backgroundKeys.forEach { key -> rows.put(JSONObject().put("key",key).put("done",background.getBoolean(key,false))
            .put("skipped",background.getBoolean(key+"_skipped",false)).put("manual",key!="battery")
            .put("systemBatteryExempt",if(key=="battery")batteryExempt() else JSONObject.NULL)) }
        val next=currentStep()
        if(kind=="done" && next!=null)kind="shake"
        val stage=when { next=="applications"->"applications";kind=="core"->"restricted";next in setOf("accessibility","connection")->"accessibility";focusStep!=null || next!=null->"background";kind=="done"->"done";else->"review" }
        val target=pendingPackage?.let { pkg->net.ankio.ledger.core.PaymentApps.entries.firstOrNull { it.packageName==pkg }?.label }
        activity.showSetup(JSONObject().put("kind",kind).put("stage",stage).put("channel",channel!!.key).put("coreOpened",coreOpened)
            .put("guide",net.ankio.ledger.capture.DevicePermissionGuidance.payload(guidance))
            .put("rows",rows).put("next",next?:JSONObject.NULL).put("focusStep",focusStep?.takeIf { stage=="background" }?:next?:JSONObject.NULL)
            .put("message",message.ifBlank { when(next) { "applications"->"请选择允许摇一摇记账的应用";"connection"->"已授权，服务连接中，请稍候";else->"" } }).put("enableAfter",enableAfter).put("automatic",automatic)
            .put("target",if(automatic)target?.plus("自动记账")?:"支付自动识别" else "摇一摇识别")
            .put("permitted",app.channels.permitted(ChannelSettings.Channel.OCR))
            .put("batteryExempt",batteryExempt()))
    }
    private fun complete() {
        if(!automatic && app.channels.selectedShakePackages().isEmpty()) { kind="shake";render();return }
        if(!app.channels.permitted(ChannelSettings.Channel.OCR)) { kind="shake";render();return }
        background.edit().putBoolean("completed",batteryExempt() && backgroundKeys.drop(1).all { background.getBoolean(it,false) }).apply()
        if(automatic) {
            val before=app.channels.selectedAutoPackages()
            val wasEnabled=app.channels.automaticEnabled()
            pendingPackage?.let { app.channels.enablePaymentApplication(it) }
            if(app.channels.selectedAutoPackages().isEmpty()) { message="请选择允许自动识别的应用";render();return }
            app.channels.setAutomaticEnabled(true)
            try { net.ankio.ledger.capture.CaptureRuntime.refreshHealth() }
            catch(_:Exception) {
                net.ankio.ledger.core.PaymentApps.entries.forEach { app.channels.setAutoPackage(it.packageName,it.packageName in before) }
                app.channels.setAutomaticEnabled(wasEnabled);message="无法启动自动识别，请返回应用重试";app.changed();render();return
            }
            net.ankio.ledger.capture.ScreenCaptureService.current?.checkPaymentPages()
        } else if(enableAfter) activity.setupEnableChannel(ChannelSettings.Channel.OCR)
        app.changed();kind="done";message="";render()
    }
    fun action(action:String) {
        if(!active)return
        app.executionLog.event("system","permission_guide",action,JSONObject().put("channel",channel!!.key).put("sheet",kind))
        message=""
        when {
            action=="close" -> { cancel();return }
            action=="restricted" -> { kind="core";render();return }
            action=="back" -> { kind="shake";focusStep=null;render();return }
            action.startsWith("view:") -> { focusStep=action.substringAfter(':').takeIf { it in backgroundKeys };kind="shake";render();return }
            action.startsWith("reset:") -> {
                val key=action.substringAfter(':').takeIf { it in setOf("autostart","task_lock") }?:return
                background.edit().putBoolean(key,false).remove(key+"_skipped").putBoolean("completed",false).apply()
                focusStep=key;kind="shake";render();return
            }
            action.startsWith("open:") -> {
                when(val key=action.substringAfter(':')) {
                    "accessibility","connection"->activity.setupRequestPermission(ChannelSettings.Channel.OCR)
                    "battery_oem"->openAppInfo()
                    in backgroundKeys->{ focusStep=key;kind="shake";openBackground(key) }
                    else->return
                };render();return
            }
            action=="skip" -> {
                when(val next=selectedStep()) {
                    in backgroundKeys->{val key=focusStep?:next!!;background.edit().putBoolean(key,false).putBoolean(key+"_skipped",true).apply();focusStep=null}
                    else->return
                };render();return
            }
            action=="open" -> when(kind) {
                "core"->{coreOpened=openAppInfo()}
                "shake"->when(val next=selectedStep()) {
                    "accessibility","connection"->activity.setupRequestPermission(ChannelSettings.Channel.OCR)
                    in backgroundKeys->{ focusStep=next;openBackground(next!!) }
                    else->Unit
                }
            }
            action=="check" -> when(kind) {
                "done"->{cancel();return}
                "core"->{kind="shake";render();return}
                "shake"->when(val next=selectedStep()) {
                    null->{complete();return}
                    "accessibility"->message=guidance.permissionMessage
                    "connection"->message="页面识别未连接，请重新开启"
                    else->{
                        val key=next
                        if(key=="battery" && !batteryExempt())message="电池优化仍未允许，请去系统设置补齐，或暂时跳过"
                        else { background.edit().putBoolean(key,true).putBoolean(key+"_skipped",false).apply();focusStep=null }
                    }
                }
            }
        }
        if(active)render()
    }
    private fun openAppInfo():Boolean=try {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",activity.packageName,null)));true
    } catch(_:Exception) { message="请长按花有谱图标，进入应用信息，检查受限设置。";false }
    private fun openBackground(key:String):Boolean {
        if(key=="task_lock") {
            message="请在最近任务中找到花有谱，按本机指引检查加锁；无此项可跳过"
            if(net.ankio.ledger.capture.ScreenCaptureService.current?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)==true)return true
        }
        val intent=when {
            key=="battery" -> if(batteryExempt())Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else BackgroundBatteryPolicy.request(activity.packageName)
            key=="task_lock" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            guidance.family=="xiaomi" && key=="autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"))
            // Use the public application-info entry instead of a version-specific
            // PowerKeeper activity, which can silently redirect to the launcher.
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",activity.packageName,null))
        }
        message=when(key) {
            "battery" -> if(!batteryExempt())"请允许后台运行，返回继续检查" else "电池优化已豁免，仍需检查后台策略"
            "task_lock" -> "请按本机指引检查，无此项可跳过"
            else -> "请检查${guidance.autostartSearch}，无此项可跳过"
        }
        return try { activity.startActivity(intent);true } catch(_:Exception) { openAppInfo() }
    }
}

/** Public Android request only; no setting is changed until the user approves the system dialog. */
internal object BackgroundBatteryPolicy {
    fun request(packageName:String)=Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.fromParts("package",packageName,null))
}
