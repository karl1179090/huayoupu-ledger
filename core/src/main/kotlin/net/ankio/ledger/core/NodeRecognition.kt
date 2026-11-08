package net.ankio.ledger.core

import java.security.MessageDigest
import java.util.Collections

/** Original screen coordinates, before screenshot resizing. */
data class NodeBounds(val left:Int,val top:Int,val right:Int,val bottom:Int)
data class CaptureNode(val id:Int,val parentId:Int?,val depth:Int,val bounds:NodeBounds,
    val text:String?=null,val description:String?=null,val className:String="",val resourceId:String="",
    val visible:Boolean=true,val enabled:Boolean=true,val clickable:Boolean=false,val selected:Boolean=false,
    val checked:Boolean=false,val scrollable:Boolean=false,val editable:Boolean=false,val password:Boolean=false)
data class NodeEnvironment(val appVersion:String,val appVersionCode:Long,val system:String,val device:String)

/** Owned immutable values only. A complete bounded traversal never proves complete pixel coverage. */
class NodeSnapshot(val packageName:String,val windowId:Int,val activity:String,val generation:Long,
    val readStartedMs:Long,val readCompletedMs:Long,val capturedAt:Long,val environment:NodeEnvironment,nodes:List<CaptureNode>) {
    val nodes:List<CaptureNode> = Collections.unmodifiableList(nodes.toList())
    init {
        require(packageName.length in 1..256 && activity.length<=512 && windowId>=0 && generation>=0)
        require(readStartedMs>=0 && readCompletedMs>=readStartedMs && capturedAt>0)
        require(this.nodes.size in 1..400)
        val seen=mutableSetOf<Int>()
        var characters=0
        this.nodes.forEach { node ->
            require(node.id>=0 && seen.add(node.id) && node.depth in 0..64)
            require(if(node.parentId==null)node.depth==0 && node.id==this.nodes.first().id else node.parentId in seen && node.parentId!=node.id)
            require(listOfNotNull(node.text,node.description).all { it.length<=2048 })
            require(node.className.length<=512 && node.resourceId.length<=512)
            characters+=listOfNotNull(node.text,node.description).sumOf(String::length)
        }
        require(characters<=16000)
    }
    val texts:List<String> get()=nodes.filter { it.visible }.flatMap { listOfNotNull(it.text,it.description).distinct() }.filter(String::isNotBlank)
    fun page()=PaymentPage(packageName,windowId,activity,texts,nodes.any { it.visible && (it.editable || it.password) },this)
    /** Includes structure, geometry and state. Clock/marketing changes conservatively invalidate a pair. */
    val fingerprint:String by lazy {
        val digest=MessageDigest.getInstance("SHA-256")
        fun field(value:Any?) { val bytes=value.toString().toByteArray(Charsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes) }
        field(packageName);field(windowId);field(activity);field(environment.appVersion);field(environment.appVersionCode);field(environment.system);field(environment.device)
        this.nodes.forEach { node -> field(node.id);field(node.parentId);field(node.depth);field(node.bounds);field(node.text);field(node.description)
            field(node.className);field(node.resourceId);field(node.visible);field(node.enabled);field(node.clickable);field(node.selected)
            field(node.checked);field(node.scrollable);field(node.editable);field(node.password) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun sameTransaction(other:NodeSnapshot)=packageName==other.packageName && windowId==other.windowId && activity==other.activity && generation==other.generation && fingerprint==other.fingerprint
    fun withEnvironment(value:NodeEnvironment)=NodeSnapshot(packageName,windowId,activity,generation,readStartedMs,readCompletedMs,capturedAt,value,nodes)
}

/** Exact observed scope, backed by a private same-page production service snapshot/image comparison. */
data class NodeLayoutVerification(val id:String,val packageName:String,val appVersion:String,val appVersionCode:Long,
    val system:String,val device:String,val activities:Set<String>,val automatic:Boolean,val comparisonId:String,val scope:String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && comparisonId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(scope.isNotBlank() && scope.length<=512 && activities.isNotEmpty() && activities.size<=32) }
    fun matches(snapshot:NodeSnapshot,automatic:Boolean)=packageName==snapshot.packageName && appVersion==snapshot.environment.appVersion &&
        appVersionCode==snapshot.environment.appVersionCode && system==snapshot.environment.system && device==snapshot.environment.device &&
        snapshot.activity in activities && (!automatic || this.automatic)
}
class NodeLayoutAdapter(val verification:NodeLayoutVerification,val matches:(NodeSnapshot)->Boolean,
    val parse:(NodeSnapshot,RecognitionTimeContext)->List<TransactionCandidate>)
enum class NodeRouteReason { VERIFIED_LAYOUT, UNKNOWN_LAYOUT, INVALID_CANDIDATE, CONFLICTING_CANDIDATES }
data class NodeRoute(val reason:NodeRouteReason,val candidate:TransactionCandidate?=null,val verification:NodeLayoutVerification?=null,val parseMillis:Long?=null)

/** Injection is for synthetic boundary tests. Production registrations require real comparison evidence. */
class NodeRecognitionRegistry(adapters:List<NodeLayoutAdapter>) {
    private val adapters=adapters.map { NodeLayoutAdapter(it.verification.copy(activities=Collections.unmodifiableSet(it.verification.activities.toSet())),it.matches,it.parse) }
    fun select(snapshot:NodeSnapshot,automatic:Boolean,time:()->RecognitionTimeContext):NodeRoute {
        if(!CapturePrivacy.manualAllowed(snapshot.page()))return NodeRoute(NodeRouteReason.INVALID_CANDIDATE)
        val matching=adapters.filter { it.verification.matches(snapshot,automatic) && runCatching { it.matches(snapshot) }.getOrDefault(false) }
        if(matching.isEmpty())return NodeRoute(NodeRouteReason.UNKNOWN_LAYOUT)
        if(matching.size!=1)return NodeRoute(NodeRouteReason.CONFLICTING_CANDIDATES)
        val adapter=matching.single()
        val processingTime=time()
        val parseAt=System.nanoTime()
        val parsed=runCatching { adapter.parse(snapshot,processingTime) }.getOrNull()
        val parseMillis=(System.nanoTime()-parseAt)/1_000_000
        val candidates=parsed ?: return NodeRoute(NodeRouteReason.INVALID_CANDIDATE,parseMillis=parseMillis)
        if(candidates.size>1)return NodeRoute(NodeRouteReason.CONFLICTING_CANDIDATES,parseMillis=parseMillis)
        val candidate=candidates.singleOrNull()?.takeIf { it.id==CandidateId.SINGLE && it.bill.reliable &&
            it.bill.amountCents?.let { amount->amount in 1..Money.MAX_CENTS }==true && it.bill.direction!=null }
            ?:return NodeRoute(NodeRouteReason.INVALID_CANDIDATE,parseMillis=parseMillis)
        val receipt=candidate.bill.receipt ?: ReceiptEvidence()
        val resolved=receipt.time ?: TransactionTimeResolver.fallback(processingTime)
        if(resolved.recognitionAt!=processingTime.recognitionAt || candidate.bill.timeExplicit && !resolved.explicit)
            return NodeRoute(NodeRouteReason.INVALID_CANDIDATE,parseMillis=parseMillis)
        fun node(field:ReceiptField?)=field?.copy(source="node")
        val evidence=receipt.copy(merchant=node(receipt.merchant),productDescription=node(receipt.productDescription),transactionDescription=node(receipt.transactionDescription),
            orderRemark=node(receipt.orderRemark),serviceProvider=node(receipt.serviceProvider),receiptIssuer=node(receipt.receiptIssuer),sourceApp=node(receipt.sourceApp),time=resolved)
        return NodeRoute(NodeRouteReason.VERIFIED_LAYOUT,candidate.copy(bill=candidate.bill.copy(occurredAt=resolved.accountingAt,timeExplicit=resolved.explicit,receipt=evidence)),adapter.verification,parseMillis)
    }
    companion object {
        /** No real same-page comparison has qualified a layout in this source revision. */
        val production=NodeRecognitionRegistry(emptyList())
    }
}
