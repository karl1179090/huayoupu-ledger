package net.ankio.ledger.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.ankio.ledger.R
import net.ankio.ledger.ledger
import net.ankio.ledger.ui.MainActivity

/** Finish without a window so a gesture can capture the bill underneath it. */
class CaptureShortcutActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.action==Intent.ACTION_CREATE_SHORTCUT) {
            val shortcut=ShortcutInfoCompat.Builder(this,"capture_current_bill")
                .setShortLabel(getString(R.string.capture_shortcut_label))
                .setIcon(IconCompat.createWithResource(this,R.mipmap.ic_launcher))
                .setIntent(Intent(this,CaptureShortcutActivity::class.java).setAction(CAPTURE_CURRENT_BILL))
                .build()
            setResult(RESULT_OK,ShortcutManagerCompat.createShortcutResultIntent(this,shortcut))
            finish();return
        }
        finish()
        if(intent.action!=CAPTURE_CURRENT_BILL || savedInstanceState!=null)return
        if(ledger.channels.selectedShakePackages().isEmpty()) {
            startActivity(Intent(this,MainActivity::class.java).setAction(SELECT_APPLICATIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return
        }
        if(!ledger.channels.permitted(ChannelSettings.Channel.OCR)) {
            Toast.makeText(this,"请开启花有谱的页面识别授权，再回到账单详情触发快捷方式",Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        val service=ScreenCaptureService.current
        if(service==null)Toast.makeText(this,"页面识别服务尚未连接，请稍后重试",Toast.LENGTH_LONG).show()
        else service.captureFromShortcut()
    }
    companion object {
        const val CAPTURE_CURRENT_BILL="net.ankio.ledger.action.CAPTURE_CURRENT_BILL"
        const val SELECT_APPLICATIONS="net.ankio.ledger.action.SELECT_CAPTURE_APPLICATIONS"
    }
}
