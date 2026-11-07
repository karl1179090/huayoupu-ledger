package net.ankio.ledger.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*

/** Shared native sheet styling; credentials never need to enter the WebView. */
class MintDialog(val context: Context, title: String, subtitle: String = "") {
    private var embedded: EmbeddedModelPage? = null
    private var embeddedDismiss: (() -> Unit)? = null
    val dialog = object:Dialog(context) {
        override fun dismiss() {
            if(!isShowing || window?.decorView?.isAttachedToWindow!=true)return
            super.dismiss()
        }
    }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22),dp(8),dp(22),dp(20)) }
    private var keyboardInset = 0
    private val root = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screen = resources.displayMetrics.heightPixels
            val available = if(embedded!=null) minOf((screen*.92f).toInt(),MeasureSpec.getSize(heightMeasureSpec)) else minOf((screen*.88f).toInt(),screen-keyboardInset-dp(64)).coerceAtLeast(dp(140))
            super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(heightMeasureSpec),available),MeasureSpec.AT_MOST))
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.WHITE, 26)
        setPadding(0,dp(12),0,0)
    }
    init {
        val heading = LinearLayout(context).apply { tag="mint-heading";gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22),0,dp(12),0) }
        heading.addView(text(title, 21, INK, true), LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(button("关闭", false).apply { setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(64),dp(48)))
        root.addView(heading)
        if (subtitle.isNotEmpty()) root.addView(text(subtitle,13,MUTED).apply { setPadding(dp(22),0,dp(22),dp(12)) })
        val scroll = ScrollView(context).apply { isFillViewport = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,-2,1f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if(embedded!=null)return@setOnApplyWindowInsetsListener insets
            val next = insets.getInsets(WindowInsets.Type.ime()).bottom
            if(next != keyboardInset) { keyboardInset=next; root.requestLayout() }
            insets
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.35f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    /** Secondary privacy actions stay reachable while the disclosure body scrolls. */
    fun fixedActions(vararg actions:View) {
        val footer=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;setPadding(dp(22),dp(8),dp(22),dp(16)) }
        actions.forEachIndexed { index,action ->
            (action.parent as? android.view.ViewGroup)?.removeView(action)
            footer.addView(action,LinearLayout.LayoutParams(0,dp(48),1f).apply { if(index>0)leftMargin=dp(8) })
        }
        root.addView(footer,LinearLayout.LayoutParams(-1,-2))
    }
    fun dismiss() { embedded?.dismiss() ?: dialog.dismiss() }
    fun onPageDismiss(listener:()->Unit) { embeddedDismiss=listener }
    fun showEmbedded(activity:MainActivity,origin:RectF?,originRadius:Float?=null,pageTag:String="embedded-panel-page") {
        (root.parent as? android.view.ViewGroup)?.removeView(root)
        root.tag=pageTag
        root.addView(View(context).apply { background=shape(Color.rgb(214,206,232),2);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },0,LinearLayout.LayoutParams(dp(30),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(8) })
        (root.background as GradientDrawable).cornerRadii=floatArrayOf(dp(30).toFloat(),dp(30).toFloat(),dp(30).toFloat(),dp(30).toFloat(),0f,0f,0f,0f)
        root.setOnApplyWindowInsetsListener(null)
        embedded=EmbeddedModelPage(activity,root,origin,originRadius) { embedded=null;embeddedDismiss?.invoke() }
        activity.modelPage=embedded
        embedded!!.show()
    }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 12, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1),it) }
    }
    fun text(value: String, size: Int = 14, color: Int = INK, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    fun label(value: String, parent: LinearLayout = content) {
        parent.addView(text(value,13,MUTED,true).apply { setPadding(0,dp(14),0,dp(7)) })
    }
    fun button(value: String, primary: Boolean = false) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(12),dp(8),dp(12),dp(8)); setTextColor(if(primary)Color.WHITE else GREEN)
        background = RippleDrawable(ColorStateList.valueOf(0x25308060),shape(if(primary)GREEN else PALE,if(primary)20 else 12),shape(Color.WHITE,if(primary)20 else 12))
        backgroundTintList = null
        stateListAnimator = null
    }
    fun action(value: String, primary: Boolean = false, click: () -> Unit): Button = button(value,primary).apply {
        content.addView(this, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        setOnClickListener { click() }
    }
    fun show() {
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            val width = minOf(context.resources.displayMetrics.widthPixels - dp(16),dp(480))
            setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { y=dp(8) }
            decorView.post {
                val max = (context.resources.displayMetrics.heightPixels*.88f).toInt()
                if (decorView.height > max) setLayout(width,max)
            }
        }
    }
    companion object {
        val GREEN = Color.rgb(125,91,226)
        val PALE = Color.rgb(239,233,252)
        val INK = Color.rgb(55,48,75)
        val MUTED = Color.rgb(117,111,138)
        val LINE = Color.rgb(227,223,240)
        fun confirm(context: Context, title: String, message: String, positive: String, onConfirm: () -> Unit) {
            val sheet = MintDialog(context,title)
            sheet.content.addView(sheet.text(message,14,MUTED))
            sheet.action(positive,true) { sheet.dialog.dismiss(); onConfirm() }
            sheet.action("取消") { sheet.dialog.dismiss() }
            sheet.show()
        }
    }
}
