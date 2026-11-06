package net.ankio.ledger.widget

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil

/** Measure the actual Android line breaker, including CJK fallback; exact labels never shrink below 12dp. */
internal object ExpenseWidgetTypography {
    fun textHeight(text:String,sizeDp:Float,widthDp:Float):Float {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=sizeDp;typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)}
        return StaticLayout.Builder.obtain(text,0,text.length,paint,widthDp.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setUseLineSpacingFromFallbacks(true).build().height.toFloat()
    }
    fun gridSize(texts:List<String>,columns:Int,widthDp:Float,availableHeightDp:Float,fontScale:Float):Float {
        val cell=(widthDp/columns-8).coerceAtLeast(30f)
        var size=(12f*fontScale).coerceIn(12f,20f)
        while(size>12f) {
            val rows=texts.chunked(columns).sumOf {row->row.maxOfOrNull {text->maxOf(32.0,ceil(textHeight(text,size,cell).toDouble()+8))}?:0.0}
            if(rows<=availableHeightDp)break
            size-=0.5f
        }
        return size.coerceAtLeast(12f)
    }
}
