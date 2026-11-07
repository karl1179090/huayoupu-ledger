package net.ankio.ledger.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import net.ankio.ledger.core.Money
import net.ankio.ledger.data.LedgerStatistics
import kotlin.math.min

/** Only bars/date marks become pixels; every precise amount also has a native TextView. */
object ExpenseChartRenderer {
    const val MAX_PIXELS=120_000
    const val MAX_BITMAP_BYTES=MAX_PIXELS*4
    data class Chart(val bitmap:Bitmap,val description:String,val renderNanos:Long)
    // Lavender widget palette (2026-11-06 reference): quiet lilac bars, one deep-grape current bar.
    fun render(snapshot:LedgerStatistics.Snapshot,widthPixels:Int,heightPixels:Int,dark:Boolean,barColor:Int=0):Chart {
        require(widthPixels>0 && heightPixels>0)
        val started=System.nanoTime()
        val scale=min(1.0,kotlin.math.sqrt(MAX_PIXELS.toDouble()/(widthPixels.toLong()*heightPixels)))
        val rawWidth=(widthPixels*scale).toInt().coerceIn(1,1200)
        val width=if(rawWidth>1)rawWidth/2*2 else rawWidth
        val height=(heightPixels*scale).toInt().coerceIn(1,600)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
        val canvas=Canvas(bitmap);canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val range=requireNotNull(snapshot.range);val max=snapshot.bucketAmounts.maxOrNull() ?: 0L
        val cell=width.toFloat()/range.buckets.size
        val baseline=height-4f;val available=(height-10f).coerceAtLeast(1f)
        range.buckets.forEachIndexed {index,bucket->
            val value=snapshot.bucketAmounts[index]
            val ratio=if(max==0L)0.0 else value.toDouble()/max.toDouble()
            val barHeight=if(value==0L)2f else (available*ratio).toFloat().coerceAtLeast(3f)
            // Deep grape on lavender keeps >=3:1 for the current bar; others stay quiet lilac.
            paint.color=if(barColor!=0)barColor else if(dark)0xff5E5580.toInt() else 0xffCFC9DF.toInt()
            paint.color=if(bucket.current){if(dark)0xffB9A3F9.toInt() else 0xff6546BA.toInt()} else paint.color
            paint.style=if(bucket.future)Paint.Style.STROKE else Paint.Style.FILL
            paint.strokeWidth=2f
            val barWidth=(cell*(if(range.buckets.size<=7).7f else .52f)).coerceAtMost(40f)
            val left=index*cell+(cell-barWidth)/2
            canvas.drawRoundRect(left,baseline-barHeight,left+barWidth,baseline,3f,3f,paint)
            if(bucket.current) {paint.style=Paint.Style.FILL;canvas.drawRect(left,baseline+1,left+barWidth,baseline+3,paint)}
        }
        check(bitmap.allocationByteCount<=MAX_BITMAP_BYTES)
        val description=range.buckets.mapIndexed {i,b->"${b.label} ${Money.format(snapshot.bucketAmounts[i])}元"}.joinToString("，")
        return Chart(bitmap,description,System.nanoTime()-started)
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }    fun renderSegment(snapshot:LedgerStatistics.Snapshot,indices:List<Int>,widthPixels:Int,heightPixels:Int,labelPixels:Float,plotPixels:Float,pixelBudget:Int,dark:Boolean=false):Chart {
        val started=System.nanoTime()
        val scale=min(1.0,kotlin.math.sqrt(pixelBudget.toDouble()/(widthPixels.toLong()*heightPixels)))
        val width=(widthPixels*scale).toInt().coerceAtLeast(1)
        val height=(heightPixels*scale).toInt().coerceAtLeast(1)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
        val canvas=Canvas(bitmap);canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        val normal=if(dark)0xff5E5580.toInt() else 0xffCFC9DF.toInt();val current=if(dark)0xffB9A3F9.toInt() else 0xff6546BA.toInt()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=normal;strokeWidth=(2*scale).toFloat().coerceAtLeast(1f)}
        val maximum=snapshot.bucketAmounts.maxOrNull()?:0L
        val cell=width.toFloat()/indices.size
        val baseline=height-(2*scale).toFloat()
        paint.color=if(dark)0x444A4266.toInt() else 0x44B9B3D1.toInt()
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=1f
        for(level in 0..2) {val y=baseline-plotPixels.toFloat()*scale.toFloat()*level/2f;canvas.drawLine(0f,y,width.toFloat(),y,paint)}
        paint.color=normal;paint.strokeWidth=(2*scale).toFloat().coerceAtLeast(1f)
        indices.forEachIndexed {slot,index->
            val bucket=snapshot.range!!.buckets[index]
            val value=snapshot.bucketAmounts[index]
            val barHeight=if(value==0L)(1*scale).toFloat().coerceAtLeast(1f) else
                ((if(maximum==0L)0.0 else value.toDouble()/maximum)*plotPixels*scale).toFloat().coerceAtLeast(1f)
            paint.color=if(bucket.current)current else normal
            paint.style=if(bucket.future)Paint.Style.STROKE else Paint.Style.FILL
            val barWidth=(cell*(if(indices.size<=7).7f else .48f)).coerceAtMost(((if(indices.size<=7)44 else 30)*scale).toFloat())
            val left=slot*cell+(cell-barWidth)/2
            canvas.drawRoundRect(left,baseline-barHeight,left+barWidth,baseline,2f,2f,paint)
            if(bucket.current){paint.style=Paint.Style.FILL;canvas.drawRect(left,baseline,left+barWidth,height.toFloat(),paint)}
        }
        check(bitmap.allocationByteCount<=pixelBudget*4)
        return Chart(bitmap,indices.joinToString("，"){i->"${snapshot.range!!.buckets[i].label} ${Money.format(snapshot.bucketAmounts[i])}元"},System.nanoTime()-started)
        } catch(error:Throwable) {bitmap.recycle();throw error}
    }

}
