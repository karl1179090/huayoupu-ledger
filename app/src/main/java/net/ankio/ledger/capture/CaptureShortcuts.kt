package net.ankio.ledger.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.ankio.ledger.R
import net.ankio.ledger.ui.MainActivity

/** Static shortcuts force TASK_ON_HOME, which hides the bill when a NoDisplay entry finishes. */
object CaptureShortcuts {
    // Removed manifest shortcuts are immutable, including pinned copies. Publish a fresh ID.
    const val ID="capture_current_bill_direct"
    fun info(context:Context)=ShortcutInfoCompat.Builder(context,ID)
        .setActivity(ComponentName(context,MainActivity::class.java))
        .setShortLabel(context.getString(R.string.capture_shortcut_label))
        .setLongLabel(context.getString(R.string.capture_shortcut_long_label))
        .setIcon(IconCompat.createWithResource(context,R.mipmap.ic_launcher))
        .setIntent(Intent(context,CaptureShortcutActivity::class.java)
            .setAction(CaptureShortcutActivity.CAPTURE_CURRENT_BILL).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        .build()
    fun publish(context:Context) {
        runCatching {
            val current=context.getSystemService(ShortcutManager::class.java).dynamicShortcuts
                .firstOrNull { it.id==ID }
            if(current?.intent?.flags==Intent.FLAG_ACTIVITY_NEW_TASK && current.isEnabled)return
            check(ShortcutManagerCompat.addDynamicShortcuts(context,listOf(info(context))))
        }.onFailure { android.util.Log.w("CaptureShortcuts","Could not publish capture shortcut",it) }
    }
}
