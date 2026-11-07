package net.ankio.ledger.data

import net.ankio.ledger.core.Money
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One bounded, strict CSV codec for the two documented file families. */
internal object PortableCsv {
    const val MAX_BYTES=8*1024*1024
    const val MAX_ROWS=50_000
    fun read(input:InputStream):Pair<List<List<String>>,String> {
        val sink=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val count=input.read(buffer);if(count<0)break
            require(sink.size()+count<=MAX_BYTES) { "CSV 超过 8 MB，请分批导入" };sink.write(buffer,0,count) }
        val bytes=sink.toByteArray()
        require(bytes.size<=MAX_BYTES) { "CSV 超过 8 MB，请分批导入" }
        val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch(_:java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("CSV 编码须为 UTF-8") }
        return parse(text) to digest(bytes)
    }
    fun digest(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun uuid(value:String)=value.also { require(runCatching { UUID.fromString(it).toString() }.getOrNull()==it) { "钱包或账单 UID 须为规范 UUID" } }
    fun cents(value:String,signed:Boolean=false,zero:Boolean=true):Long {
        require(value.matches(Regex(if(signed)"-?[0-9]+(?:\\.[0-9]{1,2})?" else "[0-9]+(?:\\.[0-9]{1,2})?"))) { "金额须为数字，最多两位小数" }
        val negative=value.startsWith('-');val parts=value.removePrefix("-").split('.')
        val whole=parts[0].toLongOrNull() ?: throw IllegalArgumentException("金额过大")
        require(whole<=Money.MAX_CENTS/100) { "金额过大" }
        val amount=whole*100+parts.getOrNull(1).orEmpty().padEnd(2,'0').toLong()
        require(amount in (if(zero)0L else 1L)..Money.MAX_CENTS) { "金额超出范围" }
        return if(negative)-amount else amount
    }
    fun iso(at:Long)=DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).withNano(0))
    fun instant(value:String):Long {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:Z|[+-][0-9]{2}:[0-9]{2})"))) { "时间须为带 UTC 偏移的 ISO 8601，精确到秒" }
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrElse { throw IllegalArgumentException("时间格式无效") }
            .also { require(it in 1..4_102_444_800_000L) { "时间超出范围" } }
    }
    fun write(rows:Sequence<List<String>>,output:OutputStream) {
        val writer=output.writer(Charsets.UTF_8);writer.write("\uFEFF")
        rows.forEach { cells->
            writer.write(cells.joinToString(",") { value->"\"${escape(value).replace("\"","\"\"")}\"" });writer.write("\r\n")
        };writer.flush()
    }
    private fun escape(value:String):String = if(value.startsWith("'") || value.trimStart().firstOrNull() in listOf('=','+','-','@') ||
        value.startsWith('\t') || value.startsWith('\r') || value.startsWith('\n') || (value.length>=15 || value.length>1 && value.startsWith('0')) && value.all(Char::isDigit)) "'$value" else value
    fun unescape(value:String):String {
        if(!value.startsWith("'"))return value
        val rest=value.substring(1)
        return if(rest.startsWith("'") || (rest.length>=15 || rest.length>1 && rest.startsWith('0')) && rest.all(Char::isDigit) || rest.trimStart().firstOrNull() in listOf('=','+','-','@') ||
            rest.startsWith('\t') || rest.startsWith('\r') || rest.startsWith('\n'))rest else value
    }
    private fun parse(text:String):List<List<String>> {
        val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val cell=StringBuilder()
        var quoted=false;var closed=false;var i=0
        fun addCell() { require(row.size<64) { "CSV 列数过多" };row.add(cell.toString());cell.setLength(0);closed=false }
        fun addRow() { addCell();if(row.any { it.isNotEmpty() })rows.add(row);row=mutableListOf();require(rows.size<=MAX_ROWS+1) { "最多导入 50000 行" } }
        while(i<text.length) {
            val c=text[i++]
            if(quoted) { if(c=='"') { if(i<text.length && text[i]=='"') { cell.append('"');i++ } else { quoted=false;closed=true } } else cell.append(c) }
            else when(c) {
                '"'->{require(cell.isEmpty() && !closed) { "CSV 引号格式错误" };quoted=true}
                ','->addCell()
                '\r'->{require(i<text.length && text[i++]=='\n') { "CSV 换行格式错误" };addRow()}
                '\n'->addRow()
                else->{require(!closed && c!='\u0000') { "CSV 字段格式错误" };cell.append(c)}
            }
            require(cell.length<=32768) { "CSV 单元格过长" }
        }
        require(!quoted) { "CSV 引号未闭合，文件可能不完整" }
        if(cell.isNotEmpty() || row.isNotEmpty() || closed)addRow()
        require(rows.isNotEmpty()) { "CSV 没有表头" };return rows
    }
}
