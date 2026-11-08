package net.ankio.ledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.ledger
import org.json.JSONObject

/** Explicit local invocation only; no collection on startup/connect, no permission changes. */
class NodeDiagnosticReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(!BuildConfig.DEBUG || !BuildConfig.NODE_DIAGNOSTIC || !context.packageName.endsWith(".nodediagnostic") ||
            intent.action!="${context.packageName}.NODE_COMPARE")return
        val requestId=java.util.UUID.randomUUID().toString()
        val output=java.io.File(context.cacheDir,"node-comparison-status.json")
        output.writeText(JSONObject().put("ok",false).put("reason","PENDING").put("requestId",requestId).toString())
        android.system.Os.chmod(output.absolutePath,384)
        val pending=goAsync()
        context.ledger.scope.launch {
            val status=try {
                val service=ScreenCaptureService.current ?: error("service_disconnected")
                service.collectNodeComparison(intent.getStringExtra("package").orEmpty())
            } catch(error:Exception) {
                JSONObject().put("ok",false).put("reason",when(error) {
                    is ScreenshotAcquisitionException->error.reason.name
                    is kotlinx.coroutines.CancellationException->"CONTEXT_CHANGED"
                    else->"GATE_OR_EXPORT_FAILED"
                })
            }
            try {
                output.writeText(status.put("requestId",requestId).toString());android.system.Os.chmod(output.absolutePath,384)
            } finally { pending.finish() }
        }
    }
}
