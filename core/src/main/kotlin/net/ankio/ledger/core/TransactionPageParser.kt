package net.ankio.ledger.core

/** Geometry/hierarchy adapters supply isolated blocks; only this parser creates the candidates. */
data class TransactionBlock(val id:CandidateId,val lines:List<LocalOcrLine>,val origin:ReceiptBlockOrigin?=null)

internal fun hasReliableVerticalCardOrder(lines: List<LocalOcrLine>, headings: List<LocalOcrLine>): Boolean {
    if (headings.size < 2) return true
    val positioned = lines.count { it.bottom > it.top && it.right > it.left }
    // Text-only receipts still have explicit heading order. Partial coordinates cannot establish card layout.
    if (positioned == 0) return true
    if (positioned != lines.size) return false
    return headings.zipWithNext().all { (first, next) ->
        val height = maxOf(first.bottom - first.top, next.bottom - next.top)
        first.bottom <= next.top && first.left < next.right && next.left < first.right &&
            kotlin.math.abs(first.left - next.left) <= height * 2
    }
}

enum class TransactionPageScope { PAGE, ISOLATED_BLOCKS }

sealed interface TransactionReceiptResult {
    data class Candidate(val bill: ParsedBill) : TransactionReceiptResult
    data class Rejected(val reason: String) : TransactionReceiptResult
    data object Unrecognized : TransactionReceiptResult
}

data class ParsedTransactionReceipt(
    val id: CandidateId,
    val rawText: String,
    val origin: ReceiptBlockOrigin?,
    val result: TransactionReceiptResult,
) {
    val candidate: TransactionCandidate? get() = (result as? TransactionReceiptResult.Candidate)?.let {
        TransactionCandidate(id, it.bill, rawText)
    }
}

data class ParsedTransactionPage(val scope: TransactionPageScope, val receipts: List<ParsedTransactionReceipt>) {
    val candidates = receipts.mapNotNull { it.candidate }
    val skipReason: String? get() = if (candidates.isEmpty()) {
        receipts.firstNotNullOfOrNull { (it.result as? TransactionReceiptResult.Rejected)?.reason }
    } else null
}

object TransactionPageParser {
    /** Stable within the saved page, including two cards containing identical business text. */
    internal fun bankBlocks(lines: List<LocalOcrLine>): List<TransactionBlock>? {
        val hasGeometry = lines.all { it.bottom > it.top && it.right > it.left }
        val ordered = if (hasGeometry) lines.sortedWith(compareBy({it.top},{it.left})) else lines
        val headings = ordered.indices.filter { BankReceiptParser.isHeading(ordered[it].text) }
        if (headings.size < 2) return null
        if (!hasReliableVerticalCardOrder(ordered, headings.map(ordered::get))) return emptyList()
        val starts=headings.map { index ->
            val previous=ordered.getOrNull(index-1)
            if(previous!=null && !BankReceiptParser.isHeading(previous.text) && BankReceiptParser.issuerCode(previous.text)!=null)index-1 else index
        }
        return starts.mapIndexed { ordinal,start -> TransactionBlock(CandidateId("bank_$ordinal"),
            ordered.subList(start,starts.getOrNull(ordinal+1) ?: ordered.size)) }
    }
    fun parse(lines:List<LocalOcrLine>,width:Int,height:Int,time:RecognitionTimeContext,
        livePaymentAt:Long?=null,policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED,origin:String=""):List<TransactionCandidate> =
        parsePage(lines, width, height, time, livePaymentAt, policy, origin).candidates

    fun parsePage(lines: List<LocalOcrLine>, width: Int, height: Int, time: RecognitionTimeContext,
        livePaymentAt: Long? = null, policy: RecognitionPolicy = RecognitionPolicy.USER_INITIATED, origin: String = ""): ParsedTransactionPage {
        val partition = MixedReceiptPartitioner.partition(lines, width, height) ?: bankBlocks(ReceiptEvidenceParser.businessLines(lines))
        if (partition != null) return parsePartition(partition, width, height, time, livePaymentAt, policy, origin)
        val receipt = parseReceipt(TransactionBlock(CandidateId.SINGLE, lines), TransactionPageScope.PAGE, width, height, time, livePaymentAt, policy, origin)
        return ParsedTransactionPage(TransactionPageScope.PAGE, listOf(receipt))
    }
    fun parseBlocks(blocks:List<TransactionBlock>,width:Int,height:Int,time:RecognitionTimeContext,
        livePaymentAt:Long?=null,policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED,origin:String=""):List<TransactionCandidate> =
        parsePartition(blocks, width, height, time, livePaymentAt, policy, origin).candidates

    private fun parsePartition(blocks: List<TransactionBlock>, width: Int, height: Int, time: RecognitionTimeContext,
        livePaymentAt: Long?, policy: RecognitionPolicy, origin: String): ParsedTransactionPage {
        require(blocks.map { it.id }.distinct().size==blocks.size)
        return ParsedTransactionPage(TransactionPageScope.ISOLATED_BLOCKS, blocks.map { block ->
            parseReceipt(block, TransactionPageScope.ISOLATED_BLOCKS, width, height, time, livePaymentAt, policy, origin)
        })
    }

    private fun parseReceipt(block: TransactionBlock, scope: TransactionPageScope, width: Int, height: Int, time: RecognitionTimeContext,
        livePaymentAt: Long?, policy: RecognitionPolicy, origin: String): ParsedTransactionReceipt {
        val lines = ReceiptEvidenceParser.businessLines(block.lines)
        val raw = lines.joinToString("\n") { it.text }
        TransactionRejection.reason(raw)?.let {
            return ParsedTransactionReceipt(block.id, raw, block.origin, TransactionReceiptResult.Rejected(it))
        }
        val parsed = if (block.origin?.kind == "NESTED_BANK") {
            if (policy == RecognitionPolicy.AUTOMATIC_PAYMENT) null else BankReceiptParser.parse(lines, time.recognitionAt, origin, time)
        } else LocalOcrBillParser.parse(lines, width, height, time.recognitionAt, livePaymentAt, policy, origin, time)
        if (parsed == null) return ParsedTransactionReceipt(block.id, raw, block.origin, TransactionReceiptResult.Unrecognized)
        val local = if (block.origin?.kind == "MIXED_DEBIT" && (parsed.paymentMethod == null || parsed.paymentMethod.medium == "UNKNOWN")) {
            parsed.copy(reliable = false, reason = listOf(parsed.reason, "请确认实际付款账户").filter(String::isNotBlank).joinToString("；"),
                paymentMethod = (parsed.paymentMethod ?: PaymentMethodEvidence("UNKNOWN", "DEBIT")).copy(ambiguous = true, reason = "ACTUAL_ACCOUNT_REQUIRED"))
        } else parsed
        val bill = if (scope == TransactionPageScope.PAGE) local else local.copy(receipt = local.receipt?.inBlock(block))
        return ParsedTransactionReceipt(block.id, raw, block.origin, TransactionReceiptResult.Candidate(bill))
    }

    /** Adapt existing recognition backends that already extracted one or more bills. */
    fun fromCandidates(rawText: String, candidates: List<TransactionCandidate>, skipReason: String? = null): ParsedTransactionPage {
        require(candidates.map { it.id }.distinct().size == candidates.size)
        fun receipt(id: CandidateId, raw: String, bill: ParsedBill?): ParsedTransactionReceipt {
            val rejection = skipReason ?: TransactionRejection.reason(raw)
            val result = when {
                rejection != null -> TransactionReceiptResult.Rejected(rejection)
                bill != null -> TransactionReceiptResult.Candidate(bill)
                else -> TransactionReceiptResult.Unrecognized
            }
            return ParsedTransactionReceipt(id, raw, bill?.receipt?.blockOrigin, result)
        }
        if (candidates.all { it.id == CandidateId.SINGLE }) {
            val candidate = candidates.singleOrNull()
            return ParsedTransactionPage(TransactionPageScope.PAGE, listOf(receipt(CandidateId.SINGLE, candidate?.rawText ?: rawText, candidate?.bill)))
        }
        return ParsedTransactionPage(TransactionPageScope.ISOLATED_BLOCKS, candidates.map { candidate ->
            receipt(candidate.id, candidate.rawText ?: rawText, candidate.bill)
        })
    }

    private fun ReceiptEvidence.inBlock(block:TransactionBlock):ReceiptEvidence {
        val id=block.id.value.let { value -> if(value.length<=80)value else "block_"+java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
        fun ReceiptField?.local()=this?.copy(blockId=id)
        return copy(blockId=id,merchant=merchant.local(),productDescription=productDescription.local(),
            transactionDescription=transactionDescription.local(),orderRemark=orderRemark.local(),
            serviceProvider=serviceProvider.local(),receiptIssuer=receiptIssuer.local(),sourceApp=sourceApp.local(),
            bank=bank?.copy(transactionType=bank.transactionType.local()),blockOrigin=block.origin)
    }
}
