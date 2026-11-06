package net.ankio.ledger.widget

import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.min

/** Native labels occupy the space immediately above their own bars, never a separate amount grid. */
internal object ExpenseWidgetBarLayout {
    data class Plan(val columns:Int,val fontDp:Float,val labelHeights:List<Float>,val reservedLabelDp:Float,val plotDp:Float,val rowDp:Float,val rows:Int)
    fun create(labels:List<String>,widthDp:Float,heightDp:Float,fontScale:Float,year:Boolean):Plan {
        val preferred=if(year)listOf(if(widthDp>=260)12 else 6,6) else listOf(labels.size,4,3)
        var best:Plan?=null;var bestScore=Float.NEGATIVE_INFINITY
        for(columns in preferred.distinct().filter {it in listOf(3,4,5,6,7,12) && it<=labels.size}) {
            val cell=widthDp/columns
            var font=(12f*fontScale).coerceIn(12f,20f)
            while(font>=12f) {
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=font;typeface=android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD)}
                val groupsFit=labels.all {label->label.split('\u200b').all {paint.measureText(it)<=cell}}
                val heights=labels.map {ExpenseWidgetTypography.textHeight(it,font,cell)}
                val reserved=heights.maxOrNull()?:0f
                val rows=ceil(labels.size.toFloat()/columns).toInt()
                val plot=(heightDp/rows-reserved-22f)
                if(groupsFit && plot>=20f) {
                    val score=font-rows*1.5f
                    if(score>bestScore) {bestScore=score;best=Plan(columns,font,heights,reserved,plot,reserved+plot+8f,rows)}
                    break
                }
                font-=0.5f
            }
        }
        return best?:run {
            val columns=preferred.first().coerceAtMost(labels.size)
            val heights=labels.map {ExpenseWidgetTypography.textHeight(it,12f,widthDp/columns)}
            val reserved=heights.maxOrNull()?:0f;val rows=ceil(labels.size.toFloat()/columns).toInt()
            Plan(columns,12f,heights,reserved,4f,reserved+8f,rows)
        }
    }
}
