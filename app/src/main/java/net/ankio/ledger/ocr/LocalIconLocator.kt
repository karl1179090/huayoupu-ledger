package net.ankio.ledger.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class IconCandidate(val box: Rect, val anchor: String, val score: Double, val rejection: String?) {
    fun json() = JSONObject().put("box", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
        .put("anchor", anchor).put("layoutScore", score).put("rejection", rejection ?: JSONObject.NULL)
}

data class LocalIconResult(val candidates: List<IconCandidate>, val selected: IconCandidate?, val reason: String, val elapsedMs: Long) {
    fun json() = JSONObject().put("method", "local_geometry_and_ocr_v1")
        .put("coordinateSpace", "input_image_pixels").put("elapsedMs", elapsedMs)
        .put("reason", reason).put("selected", selected?.json() ?: JSONObject.NULL)
        .put("candidates", JSONArray().also { a -> candidates.forEach { a.put(it.json()) } })
}

/** Layout-assisted merchant-icon locator bound to local OCR evidence.
 * Returns no icon when no unique merchant anchor exists. Never invents an OCR name.
 */
object LocalIconLocator {
    private val status = Regex("支付成功|付款成功|转账成功|交易成功|已入账|已记账|已收款|识别成功|记账成功|当前状态")
    private val transfer = Regex("扫.{0,3}码付款|转账给|转账成功|转账时间|收款方备注|二维码收款")
    private val platform = Regex("^(微信|微信支付|支付宝|支付寶|财付通|云闪付)$")
    private val interfaceCopy = Regex("订单|交易|账单|当前|状态|金额|详情|零钱|余额|成功|失败|退款|备注|小程序|门店|喜欢|收藏|优惠|完成|返回")
    private fun compact(text: String) = text.filterNot { it.isWhitespace() || it == '|' }
    private fun difference(color:Int,background:IntArray)=maxOf(abs(Color.red(color)-background[0]),
        abs(Color.green(color)-background[1]),abs(Color.blue(color)-background[2]))
    private fun background(colors:List<Int>)=intArrayOf(
        colors.map(Color::red).sorted()[colors.size/2],colors.map(Color::green).sorted()[colors.size/2],
        colors.map(Color::blue).sorted()[colors.size/2])
    private fun nameLike(text: String): Boolean {
        val t = compact(text)
        return t.length in 2..48 && t.any { it.code in 0x3400..0x9fff || it.isLetter() } &&
            !status.containsMatchIn(t) && !Regex("金额|余额|支付方式|付款方式|订单|单号|账单详情|取消|保存|返回|本地识别|待确认|备注").containsMatchIn(t) &&
            !Regex("^[¥￥+\\-0-9.,:：/年月日时分秒 ]+$").matches(t)
    }

    internal fun merchantAnchorMatches(anchor:String,merchant:String):Boolean {
        fun key(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        val a=key(anchor);val b=key(merchant)
        return a.length>=2 && b.length>=2 && (a==b || minOf(a.length,b.length)>=3 && (a.startsWith(b)||b.startsWith(a)))
    }

    fun locate(bitmap: Bitmap, ocr: LocalOcrResult, merchant:String?=null): LocalIconResult {
        val started = SystemClock.elapsedRealtime()
        val scale = minOf(1.0, 640.0 / bitmap.width)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        // Uniform themes can be light or dark; a photograph is not a verified page background.
        val edge = (0 until h step maxOf(1, h / 100)).flatMap { y -> listOf(pixels[y*w], pixels[y*w+w-1]) }
        val bg = background(edge)
        fun done(c: List<IconCandidate>, selected: IconCandidate?, reason: String) =
            LocalIconResult(c, selected, reason, SystemClock.elapsedRealtime() - started)
        if (Regex("保存修改|编辑记账信息|删除账单").containsMatchIn(compact(ocr.rawText)))
            return done(emptyList(), null, "ledger_editor_not_payment_source")
        if (bg.min()<210 && edge.count { difference(it,bg)<=30 }<edge.size*.8)
            return done(emptyList(), null, "unsupported_background")
        val yStart = (h * .06).toInt()
        val yEnd = minOf((h * .65).toInt(), (w * 1.65).toInt()).coerceAtMost(h)
        val mask = ByteArray(w * h)
        for (y in yStart until yEnd) for (x in 0 until w) {
            val c = pixels[y*w+x]
            if (difference(c,bg) > 18)
                mask[y*w+x] = 1
        }
        val queue = IntArray(w * maxOf(1, yEnd-yStart))
        val candidates = ArrayList<IconCandidate>()
        for (start in yStart*w until yEnd*w) {
            if (mask[start].toInt() == 0) continue
            var head = 0; var tail = 1; queue[0] = start; mask[start] = 0
            var l = w; var r = 0; var t = h; var b = 0
            while (head < tail) {
                val index = queue[head++]; val x = index % w; val y = index / w
                l = minOf(l,x); r = maxOf(r,x); t = minOf(t,y); b = maxOf(b,y)
                for (ny in maxOf(yStart,y-1)..minOf(yEnd-1,y+1)) for (nx in maxOf(0,x-1)..minOf(w-1,x+1)) {
                    val next = ny*w+nx
                    if (mask[next].toInt() == 1) { mask[next] = 0; queue[tail++] = next }
                }
            }
            val cw = r-l+1; val ch = b-t+1
            if (l == 0 || r == w-1 || t == yStart || b == yEnd-1 || cw.toDouble()/ch !in .8..1.25 ||
                cw !in (w*.05).toInt()..(w*.22).toInt() || ch !in (w*.05).toInt()..(w*.22).toInt() ||
                tail < cw*ch*.15) continue
            val box = Rect(floor(l/scale).toInt(), floor(t/scale).toInt(), ceil((r+1)/scale).toInt(), ceil((b+1)/scale).toInt())
            // Bind to one visible line directly below, or to the right of the icon.
            val nearby = ocr.lines.mapNotNull { line -> line.box?.let { bounds ->
                val below = bounds.top >= box.bottom - bitmap.width*.012 && bounds.top-box.bottom < bitmap.width*.18 &&
                    abs(bounds.exactCenterX()-box.exactCenterX()) < bitmap.width*.13
                val sameRow=abs(bounds.exactCenterY()-box.exactCenterY()) < box.height()*.55
                val mergedStore=merchant!=null && merchantAnchorMatches(line.text,merchant) &&
                    Regex("旗舰店|专卖店|专营店|官方店").containsMatchIn(line.text) &&
                    box.left<bitmap.width*.20 && bounds.left>=box.left-bitmap.width*.02 && bounds.left<box.right &&
                    bounds.right>box.right+bitmap.width*.10
                val right = sameRow && (bounds.left >= box.right-bitmap.width*.012 && bounds.left-box.right < bitmap.width*.10 || mergedStore)
                if (!below && !right) null else Triple(line, if(below) (bounds.top-box.bottom).toDouble() else maxOf(0,bounds.left-box.right).toDouble(), below)
            } }.sortedBy { it.second }
            val first = if(merchant.isNullOrBlank())nearby.firstOrNull() else nearby.firstOrNull { merchantAnchorMatches(it.first.text,merchant) }
            val anchor = first?.first?.text.orEmpty()
            val textInside = ocr.lines.filter { line -> line.box?.let { Rect.intersects(it,box) && box.contains(it.centerX(),it.centerY()) } == true }
                .joinToString("") { compact(it.text) }
            val rejection = when {
                platform.matches(textInside) || platform.matches(compact(anchor)) -> "platform_icon"
                status.containsMatchIn(compact(anchor)) -> "status_symbol"
                interfaceCopy.containsMatchIn(textInside) -> "interface_copy_inside_icon"
                transfer.containsMatchIn(compact(ocr.rawText)) && first?.third == true -> "generic_transfer_header_unverified"
                first == null || !nameLike(anchor) -> "no_merchant_anchor"
                else -> null
            }
            val score = if(first == null) 0.0 else (1.0-first.second/bitmap.width).coerceIn(0.0,1.0)
            candidates += IconCandidate(box, anchor, score, rejection)
        }
        val accepted = candidates.filter { it.rejection == null }.sortedByDescending { it.score }
        if (accepted.size > 1) return done(candidates, null, "ambiguous_candidates")
        // Thin/lettered logos need their whole image cell, bound to a verified name.
        if(!merchant.isNullOrBlank() && !transfer.containsMatchIn(compact(ocr.rawText))) {
            val imageCells=ArrayList<Pair<OcrLine,Rect>>()
            val anchor=ocr.lines.singleOrNull { merchantAnchorMatches(it.text,merchant) && it.box?.let { b ->
                b.top in (bitmap.height*.08).toInt()..(bitmap.height*.65).toInt() &&
                    b.exactCenterX() in bitmap.width*.12..bitmap.width*.88 &&
                    ocr.lines.any { line -> line.box?.let { amount ->
                        Regex("^[¥￥+−–\\- ]*[0-9]+[.,][0-9]{1,2}$").matches(compact(line.text)) &&
                            amount.top>=b.bottom && amount.top-b.bottom<bitmap.width*.30 &&
                            abs(amount.exactCenterX()-b.exactCenterX())<bitmap.width*.14
                    }==true }
            }==true }
            val bounds=anchor?.box
            if(bounds!=null) {
                imageCells+=anchor to Rect((bounds.exactCenterX()-bitmap.width*.115).toInt().coerceAtLeast(0),
                    (bounds.top-bitmap.width*.20).toInt().coerceAtLeast(0),
                    (bounds.exactCenterX()+bitmap.width*.115).toInt().coerceAtMost(bitmap.width),
                    (bounds.top-bitmap.width*.025).toInt())
                // Expand around the verified text anchor, not the screen's center or a known logo shape.
                imageCells.add(0,anchor to Rect((bounds.exactCenterX()-bitmap.width*.14).toInt().coerceAtLeast(0),
                    (bounds.top-bitmap.width*.28).toInt().coerceAtLeast(0),
                    (bounds.exactCenterX()+bitmap.width*.14).toInt().coerceAtMost(bitmap.width),
                    (bounds.top-bitmap.width*.015).toInt()))
            }
            // A merchant card repeats its title below the amount. Customer avatars
            // live to the right of the logo cell and must never enter its crop.
            val cardTitle=ocr.lines.singleOrNull { line -> merchantAnchorMatches(line.text,merchant) && line.box?.let { b ->
                b.top in (bitmap.height*.08).toInt()..(bitmap.height*.30).toInt() &&
                    b.left in (bitmap.width*.18).toInt()..(bitmap.width*.65).toInt() &&
                    ocr.lines.any { amount -> amount.box?.let { a ->
                        Regex("^[¥￥+−–\\- ]*[0-9]+[.,][0-9]{1,2}$").matches(compact(amount.text)) && a.top>b.bottom &&
                            ocr.lines.any { repeated -> merchantAnchorMatches(repeated.text,merchant) && repeated.box?.let { r ->
                                r.top>=a.bottom && r.top-a.bottom<bitmap.width*.14 &&
                                    abs(r.exactCenterX()-a.exactCenterX())<bitmap.width*.16
                            }==true }
                    }==true }
            }==true }
            cardTitle?.box?.let { b ->
                imageCells+=cardTitle to Rect((b.left-bitmap.width*.22).toInt().coerceAtLeast(0),
                    (b.top-bitmap.width*.05).toInt().coerceAtLeast(0),
                    (b.left-bitmap.width*.02).toInt(),(b.top+bitmap.width*.19).toInt().coerceAtMost(bitmap.height))
            }
            if(Regex("实付款|实付价").containsMatchIn(ocr.rawText)) {
                val entry=ocr.lines.singleOrNull { Regex("^进店(?:逛逛|看看)?[>〉]?$").matches(compact(it.text)) }?.box
                val store=entry?.let { row -> ocr.lines.singleOrNull { line -> line.box?.let { b ->
                    merchantAnchorMatches(line.text,merchant) && b.right<=row.left &&
                        b.top>=bitmap.height*.08 && b.bottom<bitmap.height*.65 &&
                        abs(b.exactCenterY()-row.exactCenterY())<bitmap.width*.05
                }==true } }
                store?.box?.let { b ->
                    imageCells+=store to Rect((b.left-bitmap.width*.22).toInt().coerceAtLeast(0),
                        (b.exactCenterY()-bitmap.width*.14).toInt().coerceAtLeast(0),
                        (b.left-bitmap.width*.02).toInt(),
                        (b.exactCenterY()+bitmap.width*.14).toInt().coerceAtMost(bitmap.height))
                }
            }
            for((imageAnchor,cell) in imageCells) {
                // Short lettering may belong to the logo; payment copy still makes the cell ambiguous.
                if(cell.width()>0 && cell.height()>0 && (accepted.isEmpty() || cell.contains(accepted[0].box)) && ocr.lines.none { line -> line.box?.let { b ->
                    val t=compact(line.text)
                    // OCR can read a broad graphic as one digit; ordinary numeric copy is still excluded.
                    val lettering=t.any(Char::isLetter) || t.length in 1..4 && t.all(Char::isDigit) &&
                        b.width()>b.height()*t.length*1.2
                    Rect.intersects(cell,b) && !(cell.contains(b) && t.length in 1..14 && lettering &&
                        !platform.matches(t) && !interfaceCopy.containsMatchIn(t) &&
                        b.width()<bitmap.width*.20 && b.height()<bitmap.width*.07)
                }==true }) {
                    var left=cell.right;var right=cell.left;var top=cell.bottom;var bottom=cell.top;var ink=0
                    val cwCell=cell.width();val chCell=cell.height()
                    val border=(cell.left until cell.right).flatMap { x -> listOf(bitmap.getPixel(x,cell.top),bitmap.getPixel(x,cell.bottom-1)) }+
                        (cell.top until cell.bottom).flatMap { y -> listOf(bitmap.getPixel(cell.left,y),bitmap.getPixel(cell.right-1,y)) }
                    val localBackground=background(border)
                    // A toast may cross one edge; the remaining guard band must agree on the background.
                    if(border.count { difference(it,localBackground)<=18 }<border.size*.6)continue
                    val cellMask=ByteArray(cwCell*chCell)
                    for(y in cell.top until cell.bottom)for(x in cell.left until cell.right) {
                        val color=bitmap.getPixel(x,y)
                        if(difference(color,localBackground)<=18)continue
                        cellMask[(y-cell.top)*cwCell+x-cell.left]=1
                    }
                    val cellQueue=IntArray(cellMask.size)
                    for(start in cellMask.indices) {
                        if(cellMask[start].toInt()!=1)continue
                        var head=0;var tail=1;cellQueue[0]=start;cellMask[start]=0
                        var l=cwCell;var r=0;var t=chCell;var b=0
                        while(head<tail) {
                            val i=cellQueue[head++];val x=i%cwCell;val y=i/cwCell
                            l=minOf(l,x);r=maxOf(r,x);t=minOf(t,y);b=maxOf(b,y)
                            for(ny in maxOf(0,y-1)..minOf(chCell-1,y+1))for(nx in maxOf(0,x-1)..minOf(cwCell-1,x+1)) {
                                val next=ny*cwCell+nx
                                if(cellMask[next].toInt()==1) { cellMask[next]=0;cellQueue[tail++]=next }
                            }
                        }
                        // An overlay crossing the cell edge is not part of its isolated logo.
                        if(l==0||r==cwCell-1||t==0||b==chCell-1)continue
                        ink+=tail;left=minOf(left,l+cell.left);right=maxOf(right,r+cell.left+1)
                        top=minOf(top,t+cell.top);bottom=maxOf(bottom,b+cell.top+1)
                    }
                    val cw=right-left;val ch=bottom-top
                    if(cw in (bitmap.width*.025).toInt()..(bitmap.width*.25).toInt() &&
                        ch in (bitmap.width*.015).toInt()..(bitmap.width*.25).toInt() &&
                        cw.toDouble()/ch in .2..8.0 && ink.toDouble()/(cw*ch) in .025.. .85 &&
                        left>cell.left && right<cell.right && top>cell.top && bottom<cell.bottom) {
                        val side=maxOf(cw,ch)
                        if(side<=cell.width() && side<=cell.height()) {
                            // Letterbox a wide/tall mark inside its verified cell without dropping any ink.
                            val x=((left+right-side)/2).coerceIn(cell.left,cell.right-side)
                            val y=((top+bottom-side)/2).coerceIn(cell.top,cell.bottom-side)
                            val square=Rect(x,y,x+side,y+side)
                            val header=IconCandidate(square,imageAnchor.text,.91,null)
                            return done(candidates+header,header,"located_by_header_image_cell")
                        }
                    }
                }
            }
        }
        return done(candidates, accepted.singleOrNull(), if(accepted.isEmpty()) "no_verified_candidate" else "located_by_layout")
    }

    /** A square crop with a guard band. Original coordinates remain unpadded in JSON. */
    fun crop(bitmap: Bitmap, candidate: IconCandidate): Bitmap {
        val box = candidate.box
        val side = ceil(maxOf(box.width(),box.height()) * 1.12).toInt()
        val left = floor(box.exactCenterX()-side/2.0).toInt()
        val top = floor(box.exactCenterY()-side/2.0).toInt()
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square); canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, -left.toFloat(), -top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val output = Bitmap.createScaledBitmap(square,128,128,true)
        if(output !== square) square.recycle()
        return output
    }

    fun overlay(bitmap: Bitmap, result: LocalIconResult): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888,true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=maxOf(3f,bitmap.width/150f) }
        result.candidates.forEach { candidate ->
            paint.color = if (candidate === result.selected) Color.rgb(125,91,226) else Color.rgb(230,150,30)
            canvas.drawRect(candidate.box,paint)
        }
        return output
    }
}
