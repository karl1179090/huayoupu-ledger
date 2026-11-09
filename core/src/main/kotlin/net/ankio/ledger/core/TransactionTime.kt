package net.ankio.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.text.Normalizer

/** Frozen when actual recognition begins; task creation and image import are separate clocks. */
data class RecognitionTimeContext(
    val recognitionAt: Long,
    val zoneId: String,
    val trustedOriginalCaptureDate: String? = null,
) {
    init {
        require(recognitionAt > 0)
        require(zoneId.length <= 100)
        ZoneId.of(zoneId)
        trustedOriginalCaptureDate?.let { LocalDate.parse(it) }
    }
    val zone: ZoneId get() = ZoneId.of(zoneId)
    val recognitionDate: LocalDate get() = Instant.ofEpochMilli(recognitionAt).atZone(zone).toLocalDate()
    val referenceDate: LocalDate get() = trustedOriginalCaptureDate?.let(LocalDate::parse) ?: recognitionDate
    companion object {
        fun now() = RecognitionTimeContext(System.currentTimeMillis(), ZoneId.systemDefault().id)
    }
}

/** The accounting-time basis is retained even when the receipt did not establish transaction time. */
data class ReceiptTimeEvidence(
    val rawText: String,
    val accountingAt: Long,
    val localDate: String,
    val zoneId: String,
    val precision: String,
    val source: String,
    val reference: String,
    val recognitionAt: Long,
) {
    init {
        require(rawText.length <= 2048 && accountingAt > 0 && recognitionAt > 0 && zoneId.length <= 100)
        require(precision in setOf("DATE", "MINUTE", "SECOND", "INSTANT"))
        require(source in setOf("EXPLICIT", "YEAR_INFERRED", "RECOGNITION_FALLBACK", "MANUAL"))
        require(reference in setOf("ORIGINAL_CAPTURE", "RECOGNITION", "USER"))
        require(when(source) {
            "RECOGNITION_FALLBACK" -> precision=="INSTANT" && reference=="RECOGNITION" && accountingAt==recognitionAt
            "MANUAL" -> precision=="INSTANT" && reference=="USER"
            "YEAR_INFERRED" -> precision!="INSTANT" && reference in setOf("ORIGINAL_CAPTURE","RECOGNITION")
            else -> precision!="INSTANT" && reference=="RECOGNITION"
        })
        require(LocalDate.parse(localDate) == Instant.ofEpochMilli(accountingAt).atZone(ZoneId.of(zoneId)).toLocalDate())
    }
    val explicit: Boolean get() = source in setOf("EXPLICIT", "YEAR_INFERRED")
    fun edited(at: Long) = copy(accountingAt=at,
        localDate=Instant.ofEpochMilli(at).atZone(ZoneId.of(zoneId)).toLocalDate().toString(),
        precision="INSTANT", source="MANUAL", reference="USER")
}

/** Only transaction-local labels establish a date; viewer, status bar and notification clocks do not. */
object TransactionTimeResolver {
    private val label = Regex("^(?:原(?:支付|付款|交易|转账)(?:时间|日期)|(?:支付|付款|交易|转账|充值|提现|到账|完成)(?:时间|日期))[：:]?")
    // A lost space is repairable for printed DDHH or an explicit 日 boundary. Short digit runs are ambiguous.
    private val fullDate = Regex("(?<![0-9])([0-9]{4})[年/.-]([0-9]{1,2})[月/.-]([0-9]{1,2})日?(?:(?![0-9:：])|(?<=日)(?=[0-9]{1,2}[:：])|(?<=[0-9]{2})(?=[0-9]{2}[:：]))")
    private val monthDay = Regex("(?<![0-9])([0-9]{1,2})[月/.-]([0-9]{1,2})日?(?:(?![0-9:：])|(?<=日)(?=[0-9]{1,2}[:：])|(?<=[0-9]{2})(?=[0-9]{2}[:：]))")
    private val clock = Regex("(?<![0-9])([0-9]{1,2})[:：]([0-9]{2})(?:[:：]([0-9]{2}))?(?![0-9])")
    private fun clockText(value:String) = value.replace(fullDate, "").replace(monthDay, "")
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("(?<=[0-9])\\s+(?=[0-9][月/.-])"), "")

    fun resolve(lines: List<LocalOcrLine>, context: RecognitionTimeContext): ReceiptTimeEvidence {
        val scoped = ReceiptEvidenceParser.businessLines(lines).let { business ->
            if (business.any { it.bottom > it.top }) business.sortedWith(compareBy({ it.top }, { it.left })) else business
        }
        fun timeValue(value: String):Boolean {
            val text=normalized(value).trim()
            val hasValue=fullDate.containsMatchIn(text) || monthDay.containsMatchIn(text) || clock.containsMatchIn(text)
            return hasValue && text.replace(fullDate, "").replace(monthDay, "").replace(clock, "").isBlank()
        }
        val values = scoped.mapIndexedNotNull { index, line ->
            val match = label.find(line.text.trim()) ?: return@mapIndexedNotNull null
            val inline = line.text.trim().substring(match.range.last + 1).trim()
            val associated = if (line.bottom > line.top) {
                val h = line.bottom - line.top
                val row = scoped.filter { it !== line && it.left >= line.right - h &&
                    kotlin.math.abs((it.top + it.bottom - line.top - line.bottom) / 2.0) <= h * .7 && timeValue(it.text) }
                    .sortedBy { it.left }
                val established=normalized((listOf(inline)+row.map { it.text }).joinToString(" "))
                val hasDate=fullDate.containsMatchIn(established) || monthDay.containsMatchIn(established)
                val hasClock=clock.containsMatchIn(clockText(established))
                val end=(row.lastOrNull() ?: line).bottom
                val below=if(hasDate && hasClock)emptyList() else scoped.filter { candidate ->
                    candidate.top>=end && candidate.top-end<=h*2 && timeValue(candidate.text) &&
                        (!hasDate || clock.matches(normalized(candidate.text).trim())) &&
                        scoped.none { heading -> heading!==candidate && heading!==line &&
                            kotlin.math.abs(heading.top-candidate.top)<h*.6 &&
                            Regex("^(?:创建|下单|发货|收货|退款|通知|消息|发送|导入|查看|支付|付款|交易|转账)(?:时间|日期)")
                                .containsMatchIn(heading.text.trim()) }
                }.take(2)
                row + below
            } else scoped.drop(index + 1).take(2).takeWhile { timeValue(it.text) }
            val raw = (listOf(line) + associated).joinToString("\n") { it.text }
            (listOf(inline) + associated.map { it.text }).joinToString(" ") to raw
        }
        val dateValues=values.filter { fullDate.containsMatchIn(normalized(it.first)) || monthDay.containsMatchIn(normalized(it.first)) }
        val clockValues=values.filter { clock.matches(normalized(it.first).trim()) }.distinctBy { normalized(it.first).trim() }
        val candidates=if(dateValues.size==1 && !clock.containsMatchIn(clockText(normalized(dateValues.single().first))) && clockValues.size==1) {
            val date=dateValues.single();val time=clockValues.single()
            listOf("${date.first} ${time.first}" to "${date.second}\n${time.second}")
        } else values
        if(clockValues.size>1)return fallback(context,values.joinToString("\n") { it.second })
        val dates = candidates.mapNotNull { (value, raw) ->
            val normalized = normalized(value)
            val fullDates=fullDate.findAll(normalized).toList()
            if(fullDates.size>1)return@mapNotNull null
            val date = fullDates.singleOrNull()
            val partials=if(date==null)monthDay.findAll(normalized).toList() else emptyList()
            if(partials.size>1)return@mapNotNull null
            val partial = partials.singleOrNull()
            if(date == null && partial == null)return@mapNotNull null
            runCatching {
                val day = if(date != null) LocalDate.of(date.groupValues[1].toInt(), date.groupValues[2].toInt(), date.groupValues[3].toInt())
                    else {
                        val md = MonthDay.of(partial!!.groupValues[1].toInt(), partial.groupValues[2].toInt())
                        (context.referenceDate.year downTo context.referenceDate.year - 400).firstNotNullOf { year ->
                            if(md.isValidYear(year))md.atYear(year).takeIf { it <= context.referenceDate } else null
                        }
                    }
                val time = clock.find(clockText(normalized))
                val instant = if (time == null) day.atStartOfDay(context.zone).toInstant() else {
                    val g = time.groupValues
                    LocalDateTime.of(day, LocalTime.of(g[1].toInt(), g[2].toInt(), g[3].toIntOrNull() ?: 0))
                        .atZone(context.zone).toInstant()
                }
                require(instant.toEpochMilli() <= context.recognitionAt + 5 * 60_000)
                ReceiptTimeEvidence(raw.take(2048), instant.toEpochMilli(), day.toString(), context.zoneId,
                    if (time == null) "DATE" else if (time.groupValues[3].isEmpty()) "MINUTE" else "SECOND",
                    if(date == null) "YEAR_INFERRED" else "EXPLICIT",
                    if(date == null && context.trustedOriginalCaptureDate != null) "ORIGINAL_CAPTURE" else "RECOGNITION", context.recognitionAt)
            }.getOrNull()
        }.distinctBy { it.accountingAt to it.precision }
        return dates.singleOrNull() ?: fallback(context, values.joinToString("\n") { it.second })
    }

    fun fallback(context: RecognitionTimeContext, rawText: String = "") = ReceiptTimeEvidence(rawText.take(2048),
        context.recognitionAt, context.recognitionDate.toString(), context.zoneId, "INSTANT", "RECOGNITION_FALLBACK",
        "RECOGNITION", context.recognitionAt)
}
