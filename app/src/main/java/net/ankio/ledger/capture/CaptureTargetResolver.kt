package net.ankio.ledger.capture

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A shake may wait for metadata only in this initially observed foreground window. */
internal data class ShakeWindowObservation(val windowId:Int?,val switchGeneration:Long,val packageName:String?)

internal suspend fun resolveShakeTarget(read:suspend ()->ShakeWindowObservation,allowed:(String)->Boolean):ShakeWindowObservation? = withTimeoutOrNull(1_400) {
    val first=read()
    if(first.windowId==null)return@withTimeoutOrNull null
    var observed=first
    repeat(8) { attempt ->
        if(observed.windowId!=first.windowId || observed.switchGeneration!=first.switchGeneration)return@withTimeoutOrNull null
        observed.packageName?.let { return@withTimeoutOrNull if(allowed(it))observed else null }
        if(attempt<7) { delay(100);observed=read() }
    }
    null
}

/** Kept separate from package lookup: a known owner does not prove readable safe content. */
internal suspend fun readManualCapturePage(read:suspend ()->CaptureProbeResult<net.ankio.ledger.core.PaymentPage>):CaptureProbeResult<net.ankio.ledger.core.PaymentPage> =
    withTimeoutOrNull(2_600) {
        var result=CaptureProbeResult<net.ankio.ledger.core.PaymentPage>(null,"query_empty")
        repeat(8) { attempt ->
            result=read()
            val page=result.value
            // Never turn a sensitive page into permission by waiting for another page.
            if(page!=null && (net.ankio.ledger.core.CapturePrivacy.manualAllowed(page) ||
                    net.ankio.ledger.core.CapturePrivacy.sensitive(page) ||
                    !net.ankio.ledger.core.CapturePrivacy.manualPackageAllowed(page.packageName)))return@withTimeoutOrNull result
            if(result.outcome=="query_closed")return@withTimeoutOrNull result
            if(attempt<7)delay(150)
        }
        result
    }?:CaptureProbeResult(null,"query_timeout")

/** Legacy metadata helper. A known excluded owner must never retry into another application. */
internal suspend fun resolveCaptureTarget(read: suspend () -> String?): String? = withTimeoutOrNull(3_000) {
    repeat(12) { attempt ->
        val pkg=read()
        if(net.ankio.ledger.core.CapturePrivacy.manualPackageAllowed(pkg)) return@withTimeoutOrNull pkg
        if(pkg!=null)return@withTimeoutOrNull null
        if(attempt<11) delay(180)
    }
    null
}
