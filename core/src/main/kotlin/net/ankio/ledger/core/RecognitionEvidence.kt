package net.ankio.ledger.core

/** Durable provenance survives task deletion and an independent ledger backup. No private node text. */
data class RecognitionEvidence(val route:String,val reason:String,val adapter:NodeAdapterEvidence?=null,
    val snapshot:NodeSnapshotEvidence?=null,val screenshot:ScreenshotEvidence=ScreenshotEvidence(),val phaseTimingsMs:Map<String,Long> = emptyMap(),val version:Int=1) {
    init {
        require(version==1 && route in setOf("NODE","OCR"))
        require(reason in setOf("verified_layout","unknown_layout","invalid_candidate","conflicting_candidates","image_import","legacy_image"))
        require(route!="NODE" || reason=="verified_layout" && adapter!=null && snapshot!=null)
        require(phaseTimingsMs.keys.all { it in setOf("nodeRead","screenshot","route","ocr","parse","save") } && phaseTimingsMs.values.all { it>=0 })
    }
}
data class NodeAdapterEvidence(val id:String,val comparisonId:String,val scope:String,val appVersion:String,val appVersionCode:Long,val system:String,val device:String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && comparisonId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(scope.length in 1..512 && appVersion.length<=128 && system.length<=256 && device.length<=256 && appVersionCode>=0) }
}
data class NodeSnapshotEvidence(val fingerprint:String,val packageName:String,val activity:String,val windowId:Int,val capturedAt:Long,val nodeCount:Int) {
    init { require(fingerprint.matches(Regex("[a-f0-9]{64}")) && packageName.length in 1..256 && activity.length<=512 && windowId>=0 && capturedAt>0 && nodeCount in 1..400) }
}
data class ScreenshotEvidence(val state:String="AVAILABLE",val reason:String?=null) {
    init { require(state in setOf("AVAILABLE","LEGACY","UNAVAILABLE","TIMEOUT"));require(reason==null || reason.matches(Regex("[A-Z0-9_]{1,80}"))) }
}
