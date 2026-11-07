package net.ankio.ledger.capture

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import net.ankio.ledger.ledger
import org.json.JSONObject

/** Result feedback can open its saved bill; removed before every screenshot. */
class RecognitionBanner(private val service:Context) {
    private val handler=Handler(Looper.getMainLooper())
    private val windows=service.getSystemService(WindowManager::class.java)
    private var view:LinearLayout?=null
    private var hide:Runnable?=null
    @Volatile private var lastWindowType=0
    @Volatile private var lastFailure=""
    @Volatile private var delivery="not_shown"
    internal val attached get()=view?.isAttachedToWindow==true
    fun diagnostics()=JSONObject().put("delivery",delivery).put("windowType",lastWindowType).put("lastFailure",lastFailure).put("attached",attached)
    fun show(message:String,loading:Boolean=false,onRepair:(()->Unit)?=null,recordId:Long?=null) {
        handler.post {
            removeNow()
            val display=compactMessage(message)
            val density=service.resources.displayMetrics.density
            fun dp(n:Int)=(n*density).toInt()
            val row=LinearLayout(service).apply {
                gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12))
                background=GradientDrawable().apply { setColor(0xff5B3AA8.toInt());cornerRadius=dp(28).toFloat() }
                elevation=dp(8).toFloat();importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            val openIntent=recordId?.takeIf { it>0 }?.let { id ->
                android.content.Intent(service,net.ankio.ledger.ui.MainActivity::class.java).apply {
                    action=net.ankio.ledger.ui.MainActivity.OPEN_RECORD
                    putExtra("recordId",id)
                    flags=android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            }
            if(onRepair!=null)row.setOnClickListener { removeNow();onRepair() }
            else if(openIntent!=null)row.setOnClickListener { removeNow();runCatching { service.startActivity(openIntent) } }
            if(onRepair!=null || openIntent!=null)row.contentDescription=if(onRepair!=null)"$display，点击补齐设置" else "$display，点击查看账单"
            if(loading) row.addView(ProgressBar(service).apply {
                indeterminateTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
            },LinearLayout.LayoutParams(dp(20),dp(20)).apply { rightMargin=dp(10) })
            row.addView(TextView(service).apply { text=display;textSize=14f;setTextColor(Color.WHITE);setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=service.resources.displayMetrics.widthPixels-dp(104) },LinearLayout.LayoutParams(-2,-2))
            val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or if(onRepair==null && openIntent==null)WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=dp(16) }
            var added=false
            lastFailure="";lastWindowType=0
            try { windows.addView(row,params);view=row;lastWindowType=params.type;added=true }
            catch(error:Exception) {
                lastFailure=error.javaClass.simpleName
                if(row.isAttachedToWindow)runCatching { windows.removeViewImmediate(row) }
            }
            if(added) {
                delivery="capsule"
                if(android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    row.alpha=0f;row.translationY=dp(52).toFloat()
                    row.animate().alpha(1f).translationY(0f).setDuration(440).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()
                }
                if(!loading) { hide=Runnable { row.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(220).withEndAction { if(view===row) removeNow() }.start() };handler.postDelayed(hide!!,3600) }
            } else {
                view=null
                delivery="toast"
                android.widget.Toast.makeText(service,display,android.widget.Toast.LENGTH_LONG).show()
            }
            service.ledger.executionLog.event("ocr","feedback",delivery,diagnostics())
        }
    }
    private fun compactMessage(message:String):String=when {
        message.startsWith("已记账 ¥") -> message
        "全额退款" in message -> "已全额退款，不予记账"
        "这是退款记录" in message || message==net.ankio.ledger.core.StandaloneRefundReceipt.MESSAGE -> "退款记录，不予记账"
        "无法确认账单窗口" in message -> "请收起浮层后重试"
        "页面正在切换" in message || "页面已经切换" in message -> "页面已切换，请重试"
        "不是已支持的应用" in message -> "请进入支持的账单页面"
        "截图" in message && ("无法" in message || "不能" in message) -> "当前页面无法截图"
        "无障碍" in message || "页面识别权限" in message -> DevicePermissionGuidance.current.permissionMessage
        "未识别到" in message -> "未识别到金额，请重试"
        "正在识别" in message -> "正在识别…"
        message.length<=20 -> message
        "识别失败" in message -> "识别失败，请重试"
        "权限" in message -> "权限不足，请检查设置"
        else -> message.replace("\n"," ").take(19)+"…"
    }
    fun removeNow() { hide?.let(handler::removeCallbacks);hide=null;view?.let { it.animate().cancel();try { windows.removeViewImmediate(it) } catch(_:Exception) {} };view=null }
    fun close() { handler.post { removeNow() } }
}
