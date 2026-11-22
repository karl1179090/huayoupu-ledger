package net.ankio.ledger.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.launch

class ChannelSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
    init {
        val edit=preferences.edit().remove("page").remove("page_consent_v1").remove("sms").remove("notification")
        edit.remove("shake_permission_feedback")
        if(!preferences.contains("auto_apps_v1")) {
            // Preserve only the previously explicit WeChat grant. New applications are opt-in.
            val previous=preferences.getBoolean("wechat_auto_consent_v1",false)
            edit.putStringSet("auto_apps_v1",if(previous)setOf("com.tencent.mm") else emptySet())
                .putBoolean("automatic_enabled",previous && preferences.getBoolean("ocr",false))
        }
        edit.apply()
        (context.applicationContext as? net.ankio.ledger.LedgerApp)?.let { app ->
            if(registerPackages(app))app.recognitionWork.launch { app.channels.reconcileShakePackages() }
        }
    }
    private val generations = java.util.concurrent.ConcurrentHashMap<Channel, Long>()
    // Retired keys remain only for decoding old backups; none can request permissions or run.
    enum class Channel(val key: String) { OCR("ocr"), PAGE("page"), SMS("sms"), NOTIFICATION("notification"); val capture get()=this==OCR }
    fun selectedShakePackages():Set<String> = preferences.getStringSet("shake_apps_v1",emptySet()).orEmpty().toSet()
    fun shakeMasterGeneration()=preferences.getLong("shake_master_generation_v1",0)
    fun shakeApplicationGeneration(packageName:String)=preferences.getLong("shake_package_generation_v1:$packageName",0)
    @Synchronized fun shakeAllowed(packageName:String):Boolean {
        return enabled(Channel.OCR) && shortcutAllowed(packageName)
    }
    /** Explicit shortcut requests share app selection, but never depend on the motion switch. */
    @Synchronized fun shortcutAllowed(packageName:String):Boolean {
        if(packageName !in selectedShakePackages())return false
        val installed=InstalledAppsRepository.get(context)
        return installed.selectable(packageName) && installed.installationFingerprint(packageName)==preferences.getString("shake_installation_v1:$packageName",null)
    }
    @Synchronized fun shakeAuthorization(packageName:String):ShakeAuthorization? = if(shakeAllowed(packageName))
        ShakeAuthorization(packageName,shakeMasterGeneration(),shakeApplicationGeneration(packageName)) else null
    @Synchronized fun shakeAuthorized(authorization:ShakeAuthorization):Boolean =
        (if(authorization.shortcut)shortcutAllowed(authorization.packageName) && shortcutGeneration()==authorization.masterGeneration
            else shakeAllowed(authorization.packageName) && shakeMasterGeneration()==authorization.masterGeneration) &&
            shakeApplicationGeneration(authorization.packageName)==authorization.applicationGeneration
    /** Snapshot persisted grants without acquiring the channels monitor inside a ledger transaction. */
    internal fun captureGrants() = CaptureGrants(preferences.all) { packageName ->
        InstalledAppsRepository.get(context).installationFingerprint(packageName)
    }
    internal class CaptureGrants(private val values: Map<String, *>, private val fingerprint: (String) -> String?) {
        private val installationMatches = mutableMapOf<String, Boolean>()

        fun allows(authorization:ShakeAuthorization):Boolean {
            val masterKey=if(authorization.shortcut)"shortcut_generation_v1" else "shake_master_generation_v1"
            val packageName = authorization.packageName
            return packageName in (values["shake_apps_v1"] as? Set<*>).orEmpty() &&
                (authorization.shortcut || values[Channel.OCR.key]==true) &&
                (values[masterKey] ?: 0L)==authorization.masterGeneration &&
                (values["shake_package_generation_v1:$packageName"] ?: 0L)==authorization.applicationGeneration &&
                installationMatches.getOrPut(packageName) {
                    val original = values["shake_installation_v1:$packageName"] as? String
                    original != null && fingerprint(packageName) == original
                }
        }
        fun allowsAutomatic(packageName:String,generation:Long):Boolean = values["automatic_enabled"]==true &&
            packageName in (values["auto_apps_v1"] as? Set<*>).orEmpty() &&
            (values[automaticGenerationKey(packageName)] ?: 0L)==generation
    }
    fun shortcutGeneration()=preferences.getLong("shortcut_generation_v1",0)
    @Synchronized fun shortcutAuthorization(packageName:String):ShakeAuthorization?=if(shortcutAllowed(packageName))
        ShakeAuthorization(packageName,shortcutGeneration(),shakeApplicationGeneration(packageName),shortcut=true) else null
    fun invalidateShortcuts() {
        synchronized(this) {
            check(preferences.edit().putLong("shortcut_generation_v1",Math.addExact(shortcutGeneration(),1)).commit())
        }
        (context.applicationContext as? net.ankio.ledger.LedgerApp)?.captureQueue?.cancelShortcut()
    }
    /** Commit consent before cancellation; queue locks are never acquired while holding this lock. */
    fun setShakePackage(packageName:String,enabled:Boolean) {
        require(ShakeAuthorization.validPackage(packageName)) { "应用包名无效" }
        val changed=synchronized(this) {
            val installed=InstalledAppsRepository.get(context)
            val fingerprint=if(enabled) {
                require(installed.selectable(packageName)) { installed.selectionProblem(packageName)?:"应用不可采集" }
                requireNotNull(installed.installationFingerprint(packageName)) { "应用未安装" }
            } else null
            val selected=selectedShakePackages().toMutableSet()
            if((packageName in selected)==enabled && (!enabled || preferences.getString("shake_installation_v1:$packageName",null)==fingerprint))false
            else {
                if(enabled)selected.add(packageName) else selected.remove(packageName)
                val edit=preferences.edit().putStringSet("shake_apps_v1",selected)
                    .putLong("shake_package_generation_v1:$packageName",Math.addExact(shakeApplicationGeneration(packageName),1))
                if(enabled)edit.putString("shake_installation_v1:$packageName",fingerprint) else edit.remove("shake_installation_v1:$packageName")
                check(edit.commit()) { "应用授权未保存，请重试" };true
            }
        }
        if(changed)(context.applicationContext as? net.ankio.ledger.LedgerApp)?.let { app ->
            if(!enabled) {
                ScreenCaptureService.current?.cancelShakeRecognition(packageName)
                app.captureQueue.cancelShake(packageName)
                app.captureQueue.cancelShortcut(packageName)
            }
            CaptureRuntime.refreshHealth();app.changed()
        }
    }
    /** Also catches uninstalls missed while the process was stopped, including a later reinstall. */
    fun reconcileShakePackages() {
        val installed=InstalledAppsRepository.get(context)
        selectedShakePackages().filter { pkg -> !installed.selectable(pkg) ||
            installed.installationFingerprint(pkg)!=preferences.getString("shake_installation_v1:$pkg",null) }.forEach { setShakePackage(it,false) }
    }
    /** Durable local pause; a backup never imports consent or a live generation. */
    @Synchronized fun pauseForRestore(clearSelections:Boolean=true) {
        val edit=preferences.edit().putBoolean("automatic_enabled",false).putBoolean(Channel.OCR.key,false)
            .putLong("shake_master_generation_v1",Math.addExact(shakeMasterGeneration(),1))
            .putLong("shortcut_generation_v1",Math.addExact(shortcutGeneration(),1))
        if(clearSelections)edit.putStringSet("shake_apps_v1",emptySet()).putStringSet("auto_apps_v1",emptySet())
        net.ankio.ledger.core.PaymentApps.entries.forEach { bumpAutomaticGeneration(edit,it.packageName) }
        check(edit.commit())
    }
    fun resetShakeSelectionForRestore() {
        set(Channel.OCR,false)
        selectedShakePackages().forEach { setShakePackage(it,false) }
    }
    fun captureEnabled()=enabled(Channel.OCR) && selectedShakePackages().isNotEmpty() || automaticEnabled()
    fun automaticEnabled()=preferences.getBoolean("automatic_enabled",false)
    fun selectedAutoPackages():Set<String> = preferences.getStringSet("auto_apps_v1",emptySet()).orEmpty().toSet()
        .intersect(net.ankio.ledger.core.PaymentApps.entries.map { it.packageName }.toSet())
    fun autoAllowed(packageName:String)=automaticEnabled() && packageName in selectedAutoPackages()
    /** A new individual enable must not reactivate stale selections from an old master switch. */
    @Synchronized fun enablePaymentApplication(packageName:String) {
        require(net.ankio.ledger.core.PaymentApps.entries.any { it.packageName==packageName })
        if(!automaticEnabled())selectedAutoPackages().filter { it!=packageName }.forEach { changeAutoPackage(it,false) }
        changeAutoPackage(packageName,true)
        changeAutomaticEnabled(true)
    }
    fun pauseForMissingPermission() {
        invalidateShortcuts()
        set(Channel.OCR,false)
        setAutomaticEnabled(false)
        selectedAutoPackages().forEach { setAutoPackage(it,false) }
    }
    fun automaticGeneration(packageName:String)=preferences.getLong(automaticGenerationKey(packageName),0)
    private fun bumpAutomaticGeneration(edit:android.content.SharedPreferences.Editor,packageName:String) {
        edit.putLong(automaticGenerationKey(packageName),Math.addExact(automaticGeneration(packageName),1))
    }
    fun setAutomaticEnabled(enabled:Boolean) {
        val changed=synchronized(this) { changeAutomaticEnabled(enabled) }
        if(changed && !enabled)reconcileAutomaticReservations()
    }
    private fun changeAutomaticEnabled(enabled:Boolean):Boolean {
        if(enabled==automaticEnabled())return false
        val edit=preferences.edit().putBoolean("automatic_enabled",enabled)
        net.ankio.ledger.core.PaymentApps.entries.forEach { bumpAutomaticGeneration(edit,it.packageName) }
        check(edit.commit()) { "自动识别设置未保存，请重试" }
        return true
    }
    fun setAutoPackage(packageName:String,enabled:Boolean) {
        val changed=synchronized(this) { changeAutoPackage(packageName,enabled) }
        if(changed && !enabled)reconcileAutomaticReservations(packageName)
    }
    private fun changeAutoPackage(packageName:String,enabled:Boolean):Boolean {
        require(net.ankio.ledger.core.PaymentApps.entries.any { it.packageName==packageName })
        val selected=selectedAutoPackages().toMutableSet()
        if((packageName in selected)==enabled)return false
        if(enabled)selected.add(packageName) else selected.remove(packageName)
        val edit=preferences.edit().putStringSet("auto_apps_v1",selected)
        bumpAutomaticGeneration(edit,packageName)
        check(edit.commit()) { "自动识别应用授权未保存，请重试" }
        return true
    }
    private fun reconcileAutomaticReservations(packageName:String?=null) {
        (context.applicationContext as? net.ankio.ledger.LedgerApp)?.tasks?.reconcileRevokedBatchReservations("payment_auto:",packageName)
    }
    fun wechatAutoConsented()="com.tencent.mm" in selectedAutoPackages() && automaticEnabled()
    fun wechatAutoEnabled()=autoAllowed("com.tencent.mm")
    fun setWechatAuto(enabled:Boolean) {
        synchronized(this) {
            changeAutoPackage("com.tencent.mm",enabled)
            changeAutomaticEnabled(enabled)
        }
        if(!enabled)reconcileAutomaticReservations()
    }
    fun enabled(channel: Channel): Boolean = channel == Channel.OCR && preferences.getBoolean(channel.key, false)
    fun set(channel: Channel, enabled: Boolean) {
        if(channel!=Channel.OCR) { preferences.edit().remove(channel.key).apply();return }
        val changed=synchronized(this) {
            if(enabled==enabled(channel))false else {
                val generation=Math.addExact(shakeMasterGeneration(),1)
                check(preferences.edit().putBoolean(channel.key,enabled).putLong("shake_master_generation_v1",generation).commit()) { "摇一摇设置未保存，请重试" }
                generations[channel]=generation;true
            }
        }
        if(!changed)return
        (context.applicationContext as? net.ankio.ledger.LedgerApp)?.executionLog?.event(channel.key,"switch",if(enabled)"enabled" else "disabled")
        if(!enabled)(context.applicationContext as? net.ankio.ledger.LedgerApp)?.captureQueue?.cancelShake()
    }
    fun generation(channel: Channel): Long = if(channel==Channel.OCR)shakeMasterGeneration() else generations[channel] ?: 0L
    fun status(message: String) { preferences.edit().putString("last_status", message).apply() }
    fun lastStatus(): String = preferences.getString("last_status", "") ?: ""
    fun permitted(channel: Channel): Boolean = when (channel) {
        Channel.SMS, Channel.NOTIFICATION, Channel.PAGE -> false
        Channel.OCR -> (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) ==
                captureServiceComponent(context) }
    }
    companion object {
        private fun automaticGenerationKey(packageName:String)="automatic_generation_v1:$packageName"
        private val registeredApplications=java.util.WeakHashMap<Context,Boolean>()
        @Synchronized private fun registerPackages(context:Context):Boolean {
            if(registeredApplications[context]==true)return false
            val filter=android.content.IntentFilter().apply { InstalledAppsReceiver.ACTIONS.forEach(::addAction);addDataScheme("package") }
            androidx.core.content.ContextCompat.registerReceiver(context,InstalledAppsReceiver(),filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            registeredApplications[context]=true
            return true
        }
    }
}
