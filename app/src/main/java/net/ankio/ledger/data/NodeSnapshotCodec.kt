package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Private task/portable evidence, never included in generic diagnostic exports. */
object NodeSnapshotCodec {
    private const val MAX_BYTES=256*1024
    fun json(snapshot:NodeSnapshot)=JSONObject().put("version",1).put("packageName",snapshot.packageName).put("windowId",snapshot.windowId)
        .put("activity",snapshot.activity).put("generation",snapshot.generation).put("readStartedMs",snapshot.readStartedMs)
        .put("readCompletedMs",snapshot.readCompletedMs).put("capturedAt",snapshot.capturedAt).put("fingerprint",snapshot.fingerprint)
        .put("environment",JSONObject().put("appVersion",snapshot.environment.appVersion).put("appVersionCode",snapshot.environment.appVersionCode)
            .put("system",snapshot.environment.system).put("device",snapshot.environment.device))
        .put("nodes",JSONArray().apply { snapshot.nodes.forEach { n -> put(JSONObject().put("id",n.id).put("parentId",n.parentId?:JSONObject.NULL)
            .put("depth",n.depth).put("bounds",JSONArray(listOf(n.bounds.left,n.bounds.top,n.bounds.right,n.bounds.bottom)))
            .put("text",n.text?:JSONObject.NULL).put("description",n.description?:JSONObject.NULL).put("className",n.className).put("resourceId",n.resourceId)
            .put("visible",n.visible).put("enabled",n.enabled).put("clickable",n.clickable).put("selected",n.selected).put("checked",n.checked)
            .put("scrollable",n.scrollable).put("editable",n.editable).put("password",n.password)) } })
        .also { require(it.toString().toByteArray().size<=MAX_BYTES) }
    fun decode(text:String):NodeSnapshot {
        require(text.toByteArray().size<=MAX_BYTES)
        val j=JSONObject(text);require(j.getInt("version")==1)
        val e=j.getJSONObject("environment");val values=j.getJSONArray("nodes");require(values.length() in 1..400)
        val snapshot=NodeSnapshot(j.getString("packageName"),j.getInt("windowId"),j.getString("activity"),j.getLong("generation"),
            j.getLong("readStartedMs"),j.getLong("readCompletedMs"),j.getLong("capturedAt"),
            NodeEnvironment(e.getString("appVersion"),e.getLong("appVersionCode"),e.getString("system"),e.getString("device")),
            (0 until values.length()).map { index -> val n=values.getJSONObject(index);val b=n.getJSONArray("bounds");require(b.length()==4)
                CaptureNode(n.getInt("id"),if(n.isNull("parentId"))null else n.getInt("parentId"),n.getInt("depth"),NodeBounds(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)),
                    if(n.isNull("text"))null else n.getString("text"),if(n.isNull("description"))null else n.getString("description"),n.getString("className"),n.getString("resourceId"),
                    n.getBoolean("visible"),n.getBoolean("enabled"),n.getBoolean("clickable"),n.getBoolean("selected"),n.getBoolean("checked"),n.getBoolean("scrollable"),n.getBoolean("editable"),n.getBoolean("password")) })
        require(snapshot.fingerprint==j.getString("fingerprint"));require(CapturePrivacy.manualAllowed(snapshot.page()))
        return snapshot
    }
    fun summary(snapshot:NodeSnapshot)=JSONObject().put("fingerprint",snapshot.fingerprint).put("packageName",snapshot.packageName)
        .put("activity",snapshot.activity).put("windowId",snapshot.windowId).put("capturedAt",snapshot.capturedAt).put("nodeCount",snapshot.nodes.size)
    fun verification(value:NodeLayoutVerification)=JSONObject().put("id",value.id).put("comparisonId",value.comparisonId).put("scope",value.scope)
        .put("appVersion",value.appVersion).put("appVersionCode",value.appVersionCode).put("system",value.system).put("device",value.device)
}
