package net.ankio.ledger.core

/** Qualified only by the real production-service snapshot/image pair below. No private fixture data. */
internal object VerifiedWechatBalanceDebitLayout {
    private const val LINEAR="android.widget.LinearLayout"
    private const val FRAME="android.widget.FrameLayout"
    private const val TEXT="android.widget.TextView"
    private const val IMAGE="android.widget.ImageView"
    private const val RECYCLER="androidx.recyclerview.widget.RecyclerView"
    private const val SERVICE="「先享后付」服务使用通知"
    private const val PAID="通过零钱扣款"
    private const val MARKETING="按时支付，记入微信支付分记录"
    private const val FOOTER="查看订单详情"
    private val amount=Regex("[0-9]{1,9}\\.[0-9]{2}")
    private val describedAmount=Regex("[￥¥]([0-9]{1,9}\\.[0-9]{2})(?![0-9])")
    private val verification=NodeLayoutVerification("wechat_balance_mixed_manual_3182_s9210",PaymentApps.WECHAT,"8.0.79",3182,
        "16/api36/samsung/e1qzcx/e1q:16/BP4A.251205.006/S9210ZCS6DZI1:user/release-keys","samsung/SM-S9210",
        setOf("com.tencent.mm.ui.chatting.ChattingUI"),false,"f754a6a2-b387-4339-9ece-296b5e122fe2",
        "Manual mixed service-use notice and completed WeChat balance-debit card; original field comparison; no printed time; no automatic coverage")
    val adapter=NodeLayoutAdapter.scoped(verification,::matches,::parse,::rawText)

    private class Tree(val snapshot:NodeSnapshot) {
        private val children=snapshot.nodes.groupBy { it.parentId }
        private val byId=snapshot.nodes.associateBy { it.id }
        fun parent(node:CaptureNode)=byId[node.parentId]
        fun children(node:CaptureNode)=children[node.id].orEmpty().filter { it.visible }
        fun descendants(node:CaptureNode):List<CaptureNode> {
            val result=mutableListOf<CaptureNode>();val queue=java.util.ArrayDeque<CaptureNode>();queue.add(node)
            while(queue.isNotEmpty()) { val next=queue.removeFirst();result+=next;children(next).forEach(queue::add) }
            return result
        }
        fun texts(node:CaptureNode)=descendants(node).filter { it.visible && it.className==TEXT }
        fun value(node:CaptureNode)=node.text?.trim().orEmpty()
    }
    private data class Card(val item:CaptureNode,val header:CaptureNode,val panel:CaptureNode,val details:CaptureNode)
    private data class Page(val tree:Tree,val cards:List<Card>,val unknownItem:Boolean)
    private fun locate(snapshot:NodeSnapshot):Page? {
        val tree=Tree(snapshot)
        val lists=snapshot.nodes.filter { it.visible && it.className==RECYCLER }
        if(lists.size!=1)return null
        val items=tree.children(lists.single())
        val service=items.filter { item -> tree.texts(item).any { tree.value(it)==SERVICE } }
        if(service.size!=1)return null
        val cards=mutableListOf<Card>();var unknown=false
        for(item in items.filter { it !in service }) {
            val found=tree.descendants(item).mapNotNull { candidate ->
                if(candidate.className!=LINEAR)return@mapNotNull null
                val parts=tree.children(candidate)
                if(parts.size!=3 || parts.map { it.className }!=listOf(FRAME,LINEAR,FRAME))return@mapNotNull null
                val (header,body,footer)=parts
                if(header.bounds.bottom>body.bounds.top || body.bounds.bottom>footer.bounds.top ||
                    tree.texts(footer).none { tree.value(it)==FOOTER } || tree.descendants(header).none { it.className==IMAGE })return@mapNotNull null
                val sections=tree.children(body)
                if(sections.size!=2 || sections.any { it.className!=LINEAR })return@mapNotNull null
                if(tree.children(sections.first()).none { it.className==TEXT })return@mapNotNull null
                Card(item,header,sections[0],sections[1])
            }
            if(found.isEmpty())unknown=true else cards+=found
        }
        return cards.takeIf { it.isNotEmpty() }?.let { Page(tree,it,unknown) }
    }
    private data class Field(val value:String?,val raw:String?)
    private data class Extracted(val cents:Long,val merchant:Field,val provider:Field,val content:Field,val raw:String)
    private fun field(value:String?,label:String?=null):Field {
        val safe=value?.trim()?.takeIf { it.isNotEmpty() && it.length<=512 }
        return Field(safe,safe?.let { if(label==null)it else "$label\n$it" })
    }
    private fun extract(tree:Tree,card:Card):Extracted? {
        val status=tree.children(card.panel).filter { it.className==TEXT }
        if(status.size!=1 || tree.value(status.single())!=PAID)return null
        val groups=tree.children(card.panel).filter { it.className==LINEAR }
        val money=groups.filter { group -> tree.children(group).any { tree.value(it) in setOf("￥","¥") } }
        if(money.size!=1)return null
        val slots=tree.children(money.single())
        if(slots.size!=2 || slots.any { it.className!=TEXT } || tree.value(slots[0]) !in setOf("￥","¥"))return null
        val literal=tree.value(slots[1]);if(!amount.matches(literal))return null
        val cents=Money.parseCents(literal)?.takeIf { it in 1..Money.MAX_CENTS } ?: return null
        val description=card.panel.description.orEmpty()
        if(description.contains('￥') || description.contains('¥')) {
            val described=describedAmount.findAll(description).mapNotNull { Money.parseCents(it.groupValues[1]) }.distinct().toList()
            if(described!=listOf(cents))return null
        }
        if(groups.filter { it!=money.single() }.any { group ->
                tree.texts(group).any { tree.value(it).isNotBlank() } && tree.texts(group).none { tree.value(it)==MARKETING }
            })return null
        val names=tree.texts(card.header).filter { tree.value(it).isNotBlank() }
        if(names.size>1)return null
        val logos=tree.descendants(card.header).filter { it.visible && it.className==IMAGE }
        if(logos.size!=1)return null
        names.singleOrNull()?.let { name ->
            val logo=logos.single();val row=tree.parent(logo) ?: return null
            if(row.className!=LINEAR || tree.descendants(row).none { it.id==name.id } || logo.bounds.left>=name.bounds.left ||
                maxOf(logo.bounds.top,name.bounds.top)>=minOf(logo.bounds.bottom,name.bounds.bottom))return null
        }
        val merchant=field(names.singleOrNull()?.let(tree::value))
        val rows=mutableMapOf<String,Field>()
        for(row in tree.children(card.details)) {
            if(row.className!=LINEAR)return null
            val values=tree.children(row)
            if(values.size!=2 || values.any { it.className!=TEXT })return null
            val label=tree.value(values.first())
            if(label !in setOf("扣费服务","扣费内容") || rows.containsKey(label))return null
            rows[label]=field(tree.value(values[1]),label)
        }
        val provider=rows["扣费服务"] ?: Field(null,null)
        val content=rows["扣费内容"] ?: Field(null,null)
        val raw=listOfNotNull(merchant.raw,PAID,tree.value(slots[0])+literal,provider.raw,content.raw).joinToString("\n")
        return Extracted(cents,merchant,provider,content,raw)
    }
    private fun matches(snapshot:NodeSnapshot)=locate(snapshot)!=null
    private fun extractions(snapshot:NodeSnapshot):List<Extracted> {
        val page=locate(snapshot) ?: return emptyList()
        if(page.unknownItem)return emptyList()
        val extracted=page.cards.map { extract(page.tree,it) }
        if(extracted.any { it==null })return emptyList()
        return extracted.filterNotNull()
    }
    private fun parse(snapshot:NodeSnapshot,time:RecognitionTimeContext):List<TransactionCandidate> = extractions(snapshot).map { extracted ->
        val resolved=TransactionTimeResolver.resolve(emptyList(),time)
        fun evidence(field:Field)=field.value?.let { ReceiptField(it,requireNotNull(field.raw),"node") }
        val receipt=ReceiptEvidence(merchant=evidence(extracted.merchant),serviceProvider=evidence(extracted.provider),
            transactionDescription=evidence(extracted.content),sourceApp=ReceiptField(snapshot.packageName,snapshot.packageName,"capture"),time=resolved)
        val payment=PaymentMethodParser.parse(listOf(LocalOcrLine(PAID)),Direction.EXPENSE,snapshot.packageName)
        TransactionCandidate(CandidateId.SINGLE,ParsedBill(extracted.cents,Direction.EXPENSE,extracted.merchant.value.orEmpty(),"其他",
            resolved.accountingAt,null,true,"",resolved.explicit,paymentMethod=payment,receipt=receipt))
    }
    private fun rawText(snapshot:NodeSnapshot)=extractions(snapshot).singleOrNull()?.raw.orEmpty()
}
