package net.ankio.ledger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RetiredPageCaptureTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun upgradeDisablesLegacyPageCaptureWithoutEnablingShake() {
        val name="retired-capture-${UUID.randomUUID()}"
        val legacyContext=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(value:String,mode:Int):SharedPreferences=super.getSharedPreferences("$name-$value",mode)
        }
        val prefs=legacyContext.getSharedPreferences("channels",0)
        try {
            prefs.edit().putBoolean("page",true).putBoolean("page_consent_v1",true).putBoolean("ocr",false)
                .putStringSet("auto_apps_v1",setOf("com.tencent.mm")).putBoolean("automatic_enabled",false).commit()
            val channels=ChannelSettings(legacyContext)
            assertFalse(channels.enabled(ChannelSettings.Channel.PAGE));assertFalse(channels.captureEnabled())
            assertEquals(setOf("com.tencent.mm"),channels.selectedAutoPackages())
            assertTrue(channels.selectedShakePackages().isEmpty())
            assertFalse(prefs.getBoolean("page",false));assertFalse(prefs.getBoolean("page_consent_v1",false))
            channels.set(ChannelSettings.Channel.PAGE,true)
            assertFalse(channels.enabled(ChannelSettings.Channel.PAGE))
            assertFalse(channels.enabled(ChannelSettings.Channel.OCR));assertTrue(channels.selectedShakePackages().isEmpty())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                assertEquals("true",web.value("!document.querySelector('[data-channel=page],[data-channel-info=page]')"))
                assertEquals("ocr",web.text("[...document.querySelectorAll('[data-channel]')].map(e=>e.dataset.channel).join(',')"))
            }
        } finally { app.deleteSharedPreferences("$name-channels") }
    }
}
