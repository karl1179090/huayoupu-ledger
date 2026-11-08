package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONObject

/** Same bounded shape for stored receipt evidence and the UI bridge. */
object RecognitionEvidenceCodec {
    fun json(e:RecognitionEvidence):JSONObject=JSONObject().put("version",e.version).put("route",e.route).put("reason",e.reason)
        .put("adapter",e.adapter?.let { a -> JSONObject().put("id",a.id).put("comparisonId",a.comparisonId).put("scope",a.scope)
            .put("appVersion",a.appVersion).put("appVersionCode",a.appVersionCode).put("system",a.system).put("device",a.device) } ?: JSONObject.NULL)
        .put("snapshot",e.snapshot?.let { s -> JSONObject().put("fingerprint",s.fingerprint).put("packageName",s.packageName).put("activity",s.activity)
            .put("windowId",s.windowId).put("capturedAt",s.capturedAt).put("nodeCount",s.nodeCount) } ?: JSONObject.NULL)
        .put("screenshot",JSONObject().put("state",e.screenshot.state).put("reason",e.screenshot.reason ?: JSONObject.NULL))
        .put("phaseTimingsMs",JSONObject(e.phaseTimingsMs))
    fun decode(j:JSONObject):RecognitionEvidence {
        require(j.toString().toByteArray().size<=8192)
        fun keys(value:JSONObject,expected:Set<String>) { require(value.keys().asSequence().toSet()==expected) }
        fun text(value:JSONObject,key:String)=value.get(key) as? String ?: error("识别证据字段无效")
        fun integer(value:JSONObject,key:String):Long { require(value.get(key) is Int || value.get(key) is Long);return value.getLong(key) }
        keys(j,setOf("version","route","reason","adapter","snapshot","screenshot","phaseTimingsMs"))
        val adapter=if(j.isNull("adapter"))null else j.getJSONObject("adapter").let { a ->
            keys(a,setOf("id","comparisonId","scope","appVersion","appVersionCode","system","device"))
            NodeAdapterEvidence(text(a,"id"),text(a,"comparisonId"),text(a,"scope"),text(a,"appVersion"),integer(a,"appVersionCode"),text(a,"system"),text(a,"device")) }
        val snapshot=if(j.isNull("snapshot"))null else j.getJSONObject("snapshot").let { s ->
            keys(s,setOf("fingerprint","packageName","activity","windowId","capturedAt","nodeCount"))
            NodeSnapshotEvidence(text(s,"fingerprint"),text(s,"packageName"),text(s,"activity"),integer(s,"windowId").also { require(it<=Int.MAX_VALUE) }.toInt(),
                integer(s,"capturedAt"),integer(s,"nodeCount").also { require(it<=400) }.toInt()) }
        val screenshot=j.getJSONObject("screenshot").let { s -> keys(s,setOf("state","reason"));ScreenshotEvidence(text(s,"state"),if(s.isNull("reason"))null else text(s,"reason")) }
        val timing=j.getJSONObject("phaseTimingsMs");val timings=timing.keys().asSequence().associateWith { integer(timing,it) }
        return RecognitionEvidence(text(j,"route"),text(j,"reason"),adapter,snapshot,screenshot,timings,integer(j,"version").also { require(it==1L) }.toInt())
    }
}
