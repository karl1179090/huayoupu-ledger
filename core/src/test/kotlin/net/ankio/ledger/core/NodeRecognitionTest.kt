package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class NodeRecognitionTest {
    private val time=RecognitionTimeContext(1_799_411_400_000,"Asia/Shanghai")
    private fun snapshot(nodes:List<CaptureNode> = listOf(CaptureNode(0,null,0,NodeBounds(0,0,100,200),"支付成功"),
        CaptureNode(1,0,1,NodeBounds(0,30,100,60),"付款金额：12.34"))) = NodeSnapshot(
        "com.tencent.mm",7,"com.tencent.mm.TestDetail",3,100,110,1_799_400_000_000,
        NodeEnvironment("synthetic-1",1,"test-api34","synthetic-device"),nodes)
    private val verification=NodeLayoutVerification("synthetic-layout","com.tencent.mm","synthetic-1",1,"test-api34","synthetic-device",
        setOf("com.tencent.mm.TestDetail"),false,"synthetic-comparison","manual detail; synthetic test only")
    private fun adapter(parse:(NodeSnapshot,RecognitionTimeContext)->List<TransactionCandidate>) = NodeLayoutAdapter(verification,{true},parse)
    private fun valid()=TransactionCandidate(CandidateId.SINGLE,ParsedBill(1234,Direction.EXPENSE,"","其他",time.recognitionAt,null,true,"",receipt=ReceiptEvidence(time=TransactionTimeResolver.resolve(emptyList(),time))))

    @Test fun verifiedSingleCandidateUsesNodesWithoutRequiringOptionalFields() {
        val route=NodeRecognitionRegistry(listOf(adapter { _,_->listOf(valid()) })).select(snapshot(),false) { time }
        assertEquals(NodeRouteReason.VERIFIED_LAYOUT,route.reason)
        assertEquals(1234L,route.candidate?.bill?.amountCents)
        assertEquals("",route.candidate?.bill?.merchant)
        assertNull(route.candidate?.bill?.transactionId)
        assertEquals(time.recognitionAt,route.candidate?.bill?.occurredAt)
    }
    @Test fun layoutQualificationAllowsTheNextTransactionAndUsesSharedParserWithNodeFieldSources() {
        val registry=NodeRecognitionRegistry(listOf(adapter { snapshot,clock ->
            val lines=snapshot.nodes.mapNotNull { node -> node.text?.let { LocalOcrLine(it,node.bounds.left,node.bounds.top,node.bounds.right,node.bounds.bottom) } }
            listOfNotNull(LocalOcrBillParser.parse(lines,100,200,clock.recognitionAt,policy=RecognitionPolicy.USER_INITIATED,
                origin=snapshot.packageName,timeContext=clock)?.let { TransactionCandidate(CandidateId.SINGLE,it) })
        }))
        val first=snapshot()
        val next=snapshot(listOf(CaptureNode(0,null,0,NodeBounds(0,0,100,200),"支付成功"),
            CaptureNode(1,0,1,NodeBounds(0,30,100,60),"付款金额：99.99")))
        assertFalse(first.sameTransaction(next))
        val initial=registry.select(first,false) { time }.candidate!!.bill
        val second=registry.select(next,false) { time }.candidate!!.bill
        assertEquals(1234L,initial.amountCents);assertEquals(9999L,second.amountCents)
        assertEquals("node",second.receipt!!.sourceApp!!.source)
    }
    @Test fun unknownProductionLayoutDoesNotFreezeProcessingClockOrInventCompatibility() {
        var clocks=0
        val route=NodeRecognitionRegistry.production.select(snapshot(),false) { clocks++;time }
        assertEquals(NodeRouteReason.UNKNOWN_LAYOUT,route.reason);assertNull(route.candidate);assertEquals(0,clocks)
    }
    @Test fun invalidOrConflictingAdaptersFallBackAndExactVersionScopeIsRequired() {
        assertEquals(NodeRouteReason.INVALID_CANDIDATE,NodeRecognitionRegistry(listOf(adapter { _,_->emptyList() })).select(snapshot(),false) { time }.reason)
        assertEquals(NodeRouteReason.CONFLICTING_CANDIDATES,NodeRecognitionRegistry(listOf(adapter { _,_->listOf(valid(),valid()) })).select(snapshot(),false) { time }.reason)
        val registry=NodeRecognitionRegistry(listOf(adapter { _,_->listOf(valid()) }))
        assertEquals(NodeRouteReason.UNKNOWN_LAYOUT,registry.select(snapshot().withEnvironment(NodeEnvironment("synthetic-2",2,"test-api34","synthetic-device")),false) { time }.reason)
        assertEquals(NodeRouteReason.UNKNOWN_LAYOUT,registry.select(snapshot(),true) { time }.reason)
    }
    @Test fun snapshotCopiesHierarchyAndSemanticBindingDetectsSameWindowTransactionChange() {
        val nodes=mutableListOf(CaptureNode(0,null,0,NodeBounds(0,0,100,100),"支付成功"))
        val first=snapshot(nodes);nodes.add(CaptureNode(1,0,1,NodeBounds(0,1,1,2),"密码"))
        assertEquals(1,first.nodes.size)
        assertTrue(first.sameTransaction(snapshot(listOf(CaptureNode(0,null,0,NodeBounds(0,0,100,100),"支付成功")))))
        assertFalse(first.sameTransaction(snapshot(listOf(CaptureNode(0,null,0,NodeBounds(0,0,100,100),"付款金额：99.99")))))
        assertEquals(64,first.fingerprint.length)
    }
}
