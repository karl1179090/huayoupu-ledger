package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.core.*
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerViews056Test {
    @get:org.junit.Rule val wallet = CaptureWalletFixture()
    @Test fun monthSearchStatisticsAndOneTapDeleteUndoUseNativeData() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.ledger
        val ids=mutableListOf<Long>()
        try {
            val at=System.currentTimeMillis();val old=at-90L*86400000
            for((index,time) in listOf(at,old).withIndex()) {
                val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.MANUAL,"manual","",time),
                    bill=ParsedBill(1234,Direction.INCOME,"fixture056-$index","工资",time,null,true,"",true),state=RecordState.POSTED))
                ids+=record.id
            };app.changed()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui=WebUiProbe(scenario);ui.ready()
                ui.run("filter='income';viewKey='';refresh()")
                assertEquals("true",ui.value("records.some(r=>r.id===${ids[0]})"))
                assertEquals("false",ui.value("records.some(r=>r.id===${ids[1]})"))
                ui.run("query='fixture056';viewLimit=200;refresh()")
                assertEquals("2",ui.value("records.filter(r=>r.merchant.startsWith('fixture056')).length"))
                ui.run("openRecord(${ids[1]})")
                assertEquals("${ids[1]}",ui.value("draft.id"))
                ui.run("closeRecordPanel(false);deleteEntry(${ids[1]})")
                assertNull(app.store.get(ids[1]));ui.await("document.querySelector('#delete-undo button')")
                ui.run("document.querySelector('#delete-undo button').click()")
                assertNotNull(app.store.get(ids[1]));assertEquals("false",ui.value("!!document.querySelector('#delete-undo')"))
                ui.run("query='';refresh();go('stats');statsType='income';drawStats();showDataTools()")
                assertEquals("true",ui.value("!!document.querySelector('#import-bills')"))
            }
        } finally { ids.forEach(app.store::delete);app.changed() }
    }
}
