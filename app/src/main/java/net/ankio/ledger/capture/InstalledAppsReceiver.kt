package net.ankio.ledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import net.ankio.ledger.ledger

/** Package metadata only. Updates preserve selection; uninstall permanently revokes its generation. */
class InstalledAppsReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in ACTIONS)return
        val pkg=intent.data?.schemeSpecificPart?:return
        InstalledAppsRepository.get(context).invalidate()
        val pending=goAsync()
        context.ledger.scope.launch {
            try {
                if(intent.action in setOf(Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED) && !intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))
                    context.ledger.channels.setShakePackage(pkg,false)
                else context.ledger.channels.reconcileShakePackages()
                context.ledger.changed()
            } finally { pending.finish() }
        }
    }
    companion object { val ACTIONS=setOf(Intent.ACTION_PACKAGE_ADDED,Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED,Intent.ACTION_PACKAGE_REPLACED,Intent.ACTION_PACKAGE_CHANGED) }
}
