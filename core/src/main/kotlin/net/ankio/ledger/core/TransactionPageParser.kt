package net.ankio.ledger.core

/** Geometry/hierarchy adapters supply isolated blocks; only this parser creates the candidates. */
data class TransactionBlock(val id:CandidateId,val lines:List<LocalOcrLine>,val origin:ReceiptBlockOrigin?=null)

object TransactionPageParser {
    private val heading=Regex("^(?:.*银行[：: ]*)?(?:交易成功提醒|交易提醒|动账通知|消费提醒|账户变动通知)$")
    /** Stable within the saved page, including two cards containing identical business text. */
    fun bankBlocks(lines:List<LocalOcrLine>):List<TransactionBlock> {
        val ordered=if(lines.all { it.bottom>it.top })lines.sortedWith(compareBy({it.top},{it.left})) else lines
        val headings=ordered.indices.filter { heading.matches(normalize(ordered[it].text).filterNot(Char::isWhitespace)) }
        if(headings.size<2)return emptyList()
        val starts=headings.map { index ->
            val previous=ordered.getOrNull(index-1)
            if(previous!=null && BankCodes.normalize(normalize(previous.text).filterNot(Char::isWhitespace))!=null)index-1 else index
        }
        return starts.mapIndexed { ordinal,start -> TransactionBlock(CandidateId("bank_$ordinal"),
            ordered.subList(start,starts.getOrNull(ordinal+1) ?: ordered.size)) }
    }
    fun parse(lines:List<LocalOcrLine>,width:Int,height:Int,time:RecognitionTimeContext,
        livePaymentAt:Long?=null,policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED,origin:String=""):List<TransactionCandidate> {
        val blocks=bankBlocks(lines)
        if(blocks.isEmpty())return LocalOcrBillParser.parse(lines,width,height,time.recognitionAt,livePaymentAt,policy,origin,time)
            ?.let { listOf(TransactionCandidate(CandidateId.SINGLE,it)) }.orEmpty()
        return parseBlocks(blocks,width,height,time,livePaymentAt,policy,origin)
    }
    fun parseBlocks(blocks:List<TransactionBlock>,width:Int,height:Int,time:RecognitionTimeContext,
        livePaymentAt:Long?=null,policy:RecognitionPolicy=RecognitionPolicy.USER_INITIATED,origin:String=""):List<TransactionCandidate> {
        require(blocks.map { it.id }.distinct().size==blocks.size)
        return blocks.mapNotNull { block ->
            val bill=if(block.origin?.kind=="NESTED_BANK") {
                if(policy==RecognitionPolicy.AUTOMATIC_PAYMENT)null else BankReceiptParser.parse(block.lines,time.recognitionAt,origin,time)
            } else LocalOcrBillParser.parse(block.lines,width,height,time.recognitionAt,livePaymentAt,policy,origin,time)
            bill?.let { parsed ->
                val local=if(block.origin?.kind=="MIXED_DEBIT" && (parsed.paymentMethod==null || parsed.paymentMethod.medium=="UNKNOWN"))
                    parsed.copy(reliable=false,reason=listOf(parsed.reason,"请确认实际付款账户").filter(String::isNotBlank).joinToString("；"),
                        paymentMethod=(parsed.paymentMethod ?: PaymentMethodEvidence("UNKNOWN","DEBIT")).copy(
                            ambiguous=true,reason="ACTUAL_ACCOUNT_REQUIRED")) else parsed
                TransactionCandidate(block.id,local.copy(receipt=local.receipt?.inBlock(block)),block.lines.joinToString("\n") { line -> line.text })
            }
        }
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
