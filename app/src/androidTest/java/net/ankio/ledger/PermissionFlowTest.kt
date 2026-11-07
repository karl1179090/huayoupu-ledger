package net.ankio.ledger

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun texts(view:View):List<TextView> = (if(view is TextView)listOf(view) else emptyList()) + (if(view is ViewGroup)(0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    private fun page(scenario:ActivityScenario<MainActivity>):View {
        var result:View?=null;val deadline=SystemClock.elapsedRealtime()+5000
        while(result==null&&SystemClock.elapsedRealtime()<deadline) { scenario.onActivity { result=it.window.decorView.findViewWithTag("model-editor-page") };SystemClock.sleep(50) }
        return requireNotNull(result)
    }
    @Test fun localDefaultOpensPermissionGuideWithoutKeyAndCancelDoesNotEnable() {
        app.recognitionSession.invalidate();app.channels.set(ChannelSettings.Channel.OCR,false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("go('settings');document.querySelector('[data-channel=ocr]').click()")
            web.await("document.querySelector('#detailDialog').open")
            assertFalse(web.text("document.querySelector('.flow-progress').textContent").contains("配置模型"))
            assertTrue(web.text("document.querySelector('#detailContent').textContent").contains("保存在本机"))
            scenario.onActivity { assertNull(it.modelPage) }
            assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
            web.run("document.querySelector('[data-setup-action=close]').click()")
            web.await("!document.querySelector('#detailDialog').open")
        }
    }
}
