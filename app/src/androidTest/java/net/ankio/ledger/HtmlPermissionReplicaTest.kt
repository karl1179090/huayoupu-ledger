package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HtmlPermissionReplicaTest {
    @get:org.junit.Rule val whitelist=CaptureWhitelistFixture()
    @Test fun configuredModelOpensThreeStepsWithRestrictedSettingsAsAnExplicitBranch() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val enabled=app.channels.enabled(ChannelSettings.Channel.OCR)
        app.channels.set(ChannelSettings.Channel.OCR,false)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("go('settings');document.querySelector('[data-channel=ocr]').click()")
                web.await("document.querySelector('#detailDialog').open && document.querySelector('#setup-title').textContent==='开启页面识别'")
                assertEquals("3",web.value("document.querySelectorAll('.flow-system-steps em').length"))
                assertEquals("去开启页面识别",web.text("document.querySelector('#setup-primary').textContent"))
                web.run("document.querySelector('[data-setup-action=restricted]').click()")
                web.await("document.querySelector('#setup-title').textContent==='先解除系统限制'")
                assertEquals("3",web.value("document.querySelectorAll('.restriction-steps>div').length"))
                web.run("document.querySelector('#setup-check').click()")
                web.await("document.querySelector('#setup-title').textContent==='开启页面识别'")
                assertFalse(app.channels.enabled(ChannelSettings.Channel.OCR))
                web.run("document.querySelector('.sms-sheet-close').click()")
                web.await("!document.querySelector('#detailDialog').open")
            }
        } finally { app.recognitionSession.invalidate();app.channels.set(ChannelSettings.Channel.OCR,enabled);app.changed() }
    }
}
