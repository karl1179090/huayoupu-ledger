package net.ankio.ledger.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inputmethod.InputMethodManager
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.CapturePrivacy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.WeakHashMap

/** Current-user PackageManager catalog. Call page on the bridge/IO thread, never on the UI thread. */
class InstalledAppsRepository private constructor(private val context:Context) {
    private val packages=context.packageManager
    private data class Application(val packageName:String,val label:String,val system:Boolean,val problem:String?,val iconKey:String)
    @Volatile private var catalog:List<Application>?=null
    @Volatile private var version=System.currentTimeMillis().coerceAtLeast(1)
    private val icons=File(context.cacheDir,"installed-app-icons")

    /** Installation time survives updates, but an uninstall/reinstall requires fresh consent. */
    fun installationFingerprint(packageName:String):String? {
        if(!ShakeAuthorization.validPackage(packageName))return null
        return runCatching { packages.getPackageInfo(packageName,0).firstInstallTime.toString() }.getOrNull()
    }
    private fun excludedPackages():Set<String> {
        val homes=runCatching { packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        val keyboards=runCatching { context.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName } }.getOrDefault(emptyList())
        val permissionControllers=listOf("android.intent.action.MANAGE_PERMISSIONS","android.content.pm.action.REQUEST_PERMISSIONS").flatMap { action ->
            runCatching { packages.queryIntentActivities(Intent(action),PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        }
        return (homes+keyboards+permissionControllers+context.packageName).toSet()
    }
    private fun problem(info:ApplicationInfo,excluded:Set<String>):String?=when {
        info.packageName==context.packageName -> "本应用不可采集"
        !CapturePrivacy.manualPackageAllowed(info.packageName) || info.packageName in excluded -> "系统或输入组件不可采集"
        info.uid%100_000<android.os.Process.FIRST_APPLICATION_UID -> "系统组件不可采集"
        info.flags and ApplicationInfo.FLAG_SYSTEM!=0 && runCatching {
            packages.getPackageInfo(info.packageName,PackageManager.GET_ACTIVITIES).activities.orEmpty().none { it.enabled }
        }.getOrDefault(true) -> "系统组件不可采集"
        !info.enabled || info.flags and ApplicationInfo.FLAG_INSTALLED==0 -> "应用未启用"
        else -> null
    }
    fun selectionProblem(packageName:String):String? {
        if(!ShakeAuthorization.validPackage(packageName))return "应用包名无效"
        val info=runCatching { packages.getApplicationInfo(packageName,0) }.getOrNull()?:return "应用未安装"
        return problem(info,excludedPackages())
    }
    fun selectable(packageName:String)=selectionProblem(packageName)==null
    @Synchronized fun invalidate() { catalog=null;version=Math.addExact(version,1) }
    /** A new catalog is built from all installed applications, including applications without a launcher. */
    @Synchronized private fun applications(refresh:Boolean):List<Application> {
        if(!refresh)catalog?.let { return it }
        val excluded=excludedPackages()
        val values=packages.getInstalledApplications(0).map { info ->
            val stamp=runCatching { packages.getPackageInfo(info.packageName,0).lastUpdateTime }.getOrDefault(0)
            val key=MessageDigest.getInstance("SHA-256").digest("${info.packageName}:$stamp:${info.icon}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            Application(info.packageName,runCatching { packages.getApplicationLabel(info).toString().take(200) }.getOrDefault(info.packageName),
                info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0,problem(info,excluded),key)
        }
        version=Math.addExact(version,1)
        catalog=values
        val current=values.map { it.iconKey+".png" }.toSet()
        icons.listFiles()?.filter { it.name !in current }?.forEach(File::delete)
        return values
    }
    fun page(search:String="",offset:Int=0,limit:Int=60,refresh:Boolean=false):JSONObject {
        val values=applications(refresh)
        val app=context.applicationContext as? LedgerApp
        app?.channels?.reconcileShakePackages()
        val selected=app?.channels?.selectedShakePackages().orEmpty()
        val needle=search.take(200).trim().lowercase(Locale.ROOT)
        val ordered=values.asSequence().filter { needle.isBlank() || it.label.lowercase(Locale.ROOT).contains(needle) || it.packageName.lowercase(Locale.ROOT).contains(needle) }
            .sortedWith(compareByDescending<Application> { it.packageName in selected }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName }).toList()
        val start=offset.coerceAtLeast(0).coerceAtMost(ordered.size)
        val end=(start+limit.coerceIn(1,200)).coerceAtMost(ordered.size)
        val rows=JSONArray()
        for(value in ordered.subList(start,end)) {
            cacheIcon(value)
            rows.put(JSONObject().put("packageName",value.packageName).put("label",value.label).put("system",value.system)
                .put("selectable",value.problem==null).put("reason",value.problem.orEmpty()).put("selected",value.packageName in selected)
                .put("iconUrl","/installed-app-icon/${value.iconKey}.png"))
        }
        return JSONObject().put("version",version).put("total",ordered.size).put("offset",start).put("hasMore",end<ordered.size)
            .put("selectedCount",selected.size).put("applications",rows)
    }
    private fun cacheIcon(application:Application) {
        val file=File(icons,application.iconKey+".png")
        if(file.isFile)return
        runCatching {
            val drawable=packages.getApplicationIcon(application.packageName)
            val bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888)
            val bytes=try {
                drawable.setBounds(0,0,72,72);drawable.draw(Canvas(bitmap))
                ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG,100,output);output.toByteArray() }
            } finally { bitmap.recycle() }
            synchronized(this) { icons.mkdirs();if(catalog?.any { it.iconKey==application.iconKey }==true)file.writeBytes(bytes) }
        }
    }
    fun iconAsset(key:String):ByteArray? {
        if(!Regex("[a-f0-9]{64}").matches(key) || catalog?.none { it.iconKey==key }!=false)return null
        val file=File(icons,"$key.png")
        if(!file.isFile || file.length() !in 1..131_072)return null
        return runCatching { file.readBytes() }.getOrNull()
    }
    companion object {
        private val instances=WeakHashMap<Context,InstalledAppsRepository>()
        @Synchronized fun get(context:Context):InstalledAppsRepository {
            val application=context.applicationContext
            return instances.getOrPut(application) { InstalledAppsRepository(application) }
        }
    }
}
