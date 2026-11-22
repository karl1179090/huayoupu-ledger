package net.ankio.ledger.data

import android.content.Context
import java.io.File
import java.util.UUID

/** A fresh destination for a full backup, including recognition history and its media. */
internal fun <T> withCompleteRestoreTarget(context: Context, label: String, action: (LedgerStore) -> T): T {
    val namespace = "$label-${UUID.randomUUID()}"
    val ledgerName = "$namespace-ledger.db"
    val taskName = "$namespace-tasks.db"
    val mediaNamespace = "$namespace-media"
    try {
        return ScreenshotStore(context, mediaNamespace).use { media ->
            LedgerStore(context, ledgerName, media).use { store ->
                RecognitionTasks(context, taskName, media, store).use { action(store) }
            }
        }
    } finally {
        context.deleteDatabase(taskName)
        context.deleteDatabase(ledgerName)
        context.deleteDatabase("$mediaNamespace.db")
        File(context.filesDir, mediaNamespace).deleteRecursively()
        context.deleteSharedPreferences("${mediaNamespace}_policy")
    }
}
