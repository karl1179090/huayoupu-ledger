package net.ankio.ledger.ocr

import android.content.Context
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong

open class RecognitionException(val userMessage:String):Exception(userMessage)

/** Local recognition lifecycle. Former remote credentials are never read or reused. */
class RecognitionSession(context:Context) {
    private val context=context.applicationContext
    private val epoch=AtomicLong()
    val generation get()=epoch.get()
    init { retireRemoteConfiguration() }
    fun invalidate() { epoch.incrementAndGet();retireRemoteConfiguration() }
    private fun retireRemoteConfiguration() {
        // Keep retrying on startup if storage is temporarily unavailable. Removing
        // the key also renders any remaining encrypted preference value unusable.
        val obsolete=this.context.getSharedPreferences("remote_ai",Context.MODE_PRIVATE)
        if(obsolete.all.isNotEmpty())obsolete.edit().clear().commit()
        this.context.deleteSharedPreferences("remote_ai")
        runCatching {
            val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if(keys.containsAlias("simple_ledger_remote_ai_v1"))keys.deleteEntry("simple_ledger_remote_ai_v1")
        }
    }
}
