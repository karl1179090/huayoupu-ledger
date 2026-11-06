package net.ankio.ledger

import android.graphics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MerchantEnrichmentUiTest {
    @get:Rule val wallet = CaptureWalletFixture()
    @Test fun secondRecognitionRefreshesOpenDetailAndKeepsUnsavedUserInput() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val image=try {
            bitmap.eraseColor(Color.WHITE);Canvas(bitmap).drawCircle(64f,64f,50f,Paint().apply{color=Color.RED})
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() }
        } finally { bitmap.recycle() }
        for(editing in listOf(false,true)) {
            val at=System.currentTimeMillis();val transaction=UUID.randomUUID().toString().replace("-","")
            fun capture(name:String)=Capture(Source.OCR,PaymentApps.WECHAT,"$name\n支付成功\n商品\n$name\n交易单号\n$transaction",at)
            fun bill(name:String)=ParsedBill(10060,Direction.EXPENSE,name,"其他",at,transaction,true,"",true)
            val original=(app.store.ingest(capture("合成商"),bill("合成商")) as IngestResult.Saved).record
            try {
                app.changed()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val web=WebUiProbe(scenario);web.ready();web.run("openRecord(${original.id})")
                    web.await("recordPanelOpen && draft?.id===${original.id}")
                    if(editing)web.run("$('merchant').value='正在编辑的名称';$('note').value='正在编辑的备注'")
                    app.store.ingest(capture("合成商贸"),bill("合成商贸"),merchantIcon=image);app.changed()
                    web.await("draft.hasMerchantIcon===true && !!document.querySelector('#record-panel .merchant-icon img')")
                    web.await("document.querySelector('#record-panel .merchant-icon img').naturalWidth===128")
                    assertEquals(if(editing)"正在编辑的名称" else "合成商贸",web.text("$('merchant').value"))
                    if(editing)assertEquals("正在编辑的备注",web.text("$('note').value"))
                    web.run("syncRecordHistory();syncRecordHistory()")
                    assertEquals(if(editing)"正在编辑的名称" else "合成商贸",web.text("$('merchant').value"))
                    assertEquals("合成商贸",app.store.get(original.id)!!.bill.merchant)
                }
            } finally { app.store.delete(original.id);app.changed() }
        }
    }
}
