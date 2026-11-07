package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.view.MotionEvent
import net.ankio.ledger.core.*
import net.ankio.ledger.data.IngestResult
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerInteractionTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun failedAttemptsShareTheTimelineInsteadOfBeingPinned() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("""
                refresh=()=>render();month='2037-02';filter='expense';
                records=[{id:9001,merchant:'较新账单',category:'餐饮',direction:'EXPENSE',state:'POSTED',source:'MANUAL',amountCents:100,occurredAt:new Date(2037,1,5,12).getTime()},
                    {id:9002,merchant:'较旧账单',category:'餐饮',direction:'EXPENSE',state:'POSTED',source:'MANUAL',amountCents:200,occurredAt:new Date(2037,1,3,12).getTime()}];
                recognitionTasks=[{id:9003,status:'FAILED',source:'OCR',createdAt:new Date(2037,1,4,12).getTime(),message:'合成失败'},
                    {id:9004,status:'FAILED',source:'OCR',createdAt:new Date(2037,1,3,10).getTime(),message:'合成失败'}];render();
            """.trimIndent())
            assertEquals("9001,9003,9002,9004",web.text("Array.from(document.querySelectorAll('#entries [data-record],#entries [data-task]')).map(e=>e.dataset.record||e.dataset.task).join(',')"))
            assertEquals("true",web.value("Array.from(document.querySelectorAll('#entries [data-task]')).every(e=>e.closest('.transactions') && e.classList.contains('is-failed'))"))
            assertEquals("300",web.text("String(sums(monthRecords()).expense)"))
        }
    }

    @Test fun swipeThenOneTapDeletesWithoutDialogWhileDetailDeletionStillConfirms() {
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.MANUAL,"manual","swipe-${UUID.randomUUID()}",now),
            ParsedBill(2188,Direction.EXPENSE,"左滑测试","餐饮",now,null,true,"",true)) as IngestResult.Saved).record
        val task=app.tasks.create(Source.IMAGE,"image");app.tasks.update(task,"FAILED","合成识别失败")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('ledger');month=localMonth($now);filter='expense';refresh()")
                web.run("openRecord(${record.id});document.querySelector('#delete-record').click()")
                web.await("document.querySelector('#confirm-delete') && document.querySelector('#sheet').open")
                assertNotNull(app.store.get(record.id))
                web.run("closeSheet();cancelEditor()")
                web.await("!recordPanelOpen && !recordPanelClosing")
                web.run("""
                    window.swipeTest=(selector,vertical=false)=>{const e=document.querySelector(selector),target=e.querySelector('.entry-title')||e;target.dispatchEvent(new PointerEvent('pointerdown',{pointerId:71,clientX:240,clientY:120,bubbles:true,isPrimary:true}));e.dispatchEvent(new PointerEvent('pointermove',{pointerId:71,clientX:vertical?235:100,clientY:vertical?240:122,bubbles:true,isPrimary:true,cancelable:true}));if(target!==e)target.dispatchEvent(new PointerEvent('lostpointercapture',{pointerId:71,bubbles:true}));e.dispatchEvent(new PointerEvent('pointerup',{pointerId:71,clientX:vertical?235:100,clientY:vertical?240:122,bubbles:true,isPrimary:true}));};
                    swipeTest('[data-record="${record.id}"]',true);
                """.trimIndent())
                assertEquals("false",web.value("document.querySelector('[data-record=\"${record.id}\"]').closest('.swipe-row').classList.contains('swipe-open')"))
                web.run("swipeTest('[data-record=\"${record.id}\"]')")
                web.await("document.querySelector('[data-record=\"${record.id}\"]').closest('.swipe-row').classList.contains('swipe-open')")
                assertNotNull(app.store.get(record.id))
                web.run("closeSwipeRows()")
                assertNotNull(app.store.get(record.id))
                web.run("swipeTest('[data-record=\"${record.id}\"]');document.querySelector('[data-record=\"${record.id}\"]').closest('.swipe-row').querySelector('.swipe-delete').click()")
                web.await("!document.querySelector('[data-record=\"${record.id}\"]')")
                assertEquals("false",web.value("document.querySelector('#sheet').open"))
                assertNull(app.store.get(record.id))
                web.run("swipeTest('[data-task=\"$task\"]');document.querySelector('[data-task=\"$task\"]').closest('.swipe-row').querySelector('.swipe-delete').click()")
                web.await("!document.querySelector('[data-task=\"$task\"]')")
                assertEquals("false",web.value("document.querySelector('#sheet').open"))
                assertFalse(app.tasks.exists(task))
            }
        } finally {app.store.delete(record.id);app.tasks.delete(task);app.changed()}
    }

    @Test fun categoryRankingOpensOnlyMatchingMonthDirectionAndCategory() {
        val ids=mutableListOf<Long>()
        fun at(month:Int)=java.time.LocalDate.of(2037,month,5).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            for((name,category,direction,time) in listOf(
                arrayOf("本月餐饮","餐饮",Direction.EXPENSE,at(2)),arrayOf("本月交通","交通",Direction.EXPENSE,at(2)),
                arrayOf("其他月份","餐饮",Direction.EXPENSE,at(1)),arrayOf("同名收入","餐饮",Direction.INCOME,at(2)))) {
                val t=time as Long
                ids+=(app.store.ingest(Capture(Source.MANUAL,"manual",UUID.randomUUID().toString(),t),
                    ParsedBill(100,direction as Direction,name as String,category as String,t,null,true,"",true)) as IngestResult.Saved).record.id
            };app.changed()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("month='2037-02';statsType='expense';rankingType='expense';viewKey='';refresh();go('stats')")
                web.run("Array.from(document.querySelectorAll('#ranking .ranking-row')).find(e=>e.textContent.includes('餐饮')).click()")
                web.await("document.querySelector('#sheet').open")
                assertEquals(ids[0].toString(),web.text("Array.from(document.querySelectorAll('#sheet [data-category-record]')).map(e=>e.dataset.categoryRecord).join(',')"))
                web.run("document.querySelector('[data-category-record=\"${ids[0]}\"]').click()")
                web.await("recordPanelOpen && draft.id===${ids[0]}")
                assertEquals("stats",web.text("page"))
                web.run("cancelEditor()")
                web.await("!recordPanelOpen && !recordPanelClosing")
                assertEquals("stats",web.text("page"))
            }
        } finally {ids.forEach(app.store::delete);app.changed()}
    }

    @Test fun compactDetailsKeepFieldsEditableAndExposeClearDeleteAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("""
                newDraft();draft={...draft,id:9201,state:'POSTED',source:'MANUAL',merchant:'合成商户',category:'母婴亲子用品',amount:'26.90',occurredAt:Date.now()};updateEditor();showRecordPanel(null);
            """.trimIndent())
            web.await("!document.querySelector('#record-panel').classList.contains('morphing')")
            assertEquals("true",web.value("document.querySelector('#category').closest('.record-chips') && document.querySelector('#time-label').closest('.record-chips') && document.querySelectorAll('#amount').length===1"))
            assertEquals("true",web.value("document.querySelector('#delete-record').getBoundingClientRect().height>=44"))
            assertEquals("true",web.value("document.querySelector('#delete-record').closest('.actions') && !document.querySelector('#import-form .actions [onclick*=cancelEditor]')"))
            web.run("document.querySelector('#category').click();document.querySelector('#sheet [data-category=\"交通\"]').click();document.querySelector('#amount').value='31.20'")
            assertEquals("交通",web.text("readDraft().category"))
            assertEquals("31.20",web.text("readDraft().amount"))
            assertEquals("true",web.value("document.querySelector('.scanner').hidden"))
        }
    }

    @Test fun onePhysicalTapStillDeletesWhenSnapshotRefreshesDuringTouch() {
        val now=System.currentTimeMillis()
        val record=(app.store.ingest(Capture(Source.MANUAL,"manual","refresh-touch-${UUID.randomUUID()}",now),
            ParsedBill(199,Direction.EXPENSE,"单次触控测试","餐饮",now,null,true,"",true)) as IngestResult.Saved).record
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("go('ledger');month=localMonth($now);filter='expense';refresh();setSwipeOpen(document.querySelector('[data-record=\"${record.id}\"]').closest('.swipe-row'),true)")
                SystemClock.sleep(300)
                val point=org.json.JSONArray(web.text("JSON.stringify((()=>{const r=document.querySelector('[data-record=\"${record.id}\"]').closest('.swipe-row').querySelector('.swipe-delete').getBoundingClientRect();return [(r.x+r.width/2)*devicePixelRatio,(r.y+r.height/2)*devicePixelRatio]})())"))
                val offset=IntArray(2);scenario.onActivity { it.webView.getLocationOnScreen(offset) }
                val x=point.getDouble(0).toFloat()+offset[0];val y=point.getDouble(1).toFloat()+offset[1]
                val down=SystemClock.uptimeMillis();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                fun touch(action:Int){val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}}
                touch(MotionEvent.ACTION_DOWN)
                web.run("refresh()")
                touch(MotionEvent.ACTION_UP)
                web.await("!document.querySelector('[data-record=\"${record.id}\"]')")
                assertNull(app.store.get(record.id))
                assertEquals("false",web.value("document.querySelector('#sheet').open"))
            }
        } finally {app.store.delete(record.id);app.changed()}
    }

    @Test fun categoryIconsCoverAiNamesAndSettingsKeepOnlyPrimaryControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            assertEquals("true",web.value("iconForCategory('餐饮美食')==='utensils' && iconForCategory('母婴亲子')==='baby' && iconForCategory('高速代扣')==='road' && iconForCategory('城市停车服务')==='square-parking'"))
            assertEquals("true",web.value("categoryBadge('自建长分类').includes('自') && categoryBadge('<未知>').includes('&lt;')"))
            web.run("go('settings')")
            assertEquals("true",web.value("Boolean(document.querySelectorAll('#settings [data-payment-app]').length===2 && document.querySelectorAll('#settings .account-preferences>button').length===2 && document.querySelectorAll('#settings .shake .step').length===3 && document.querySelector('#settings .shake-illustration') && !document.querySelector('#settings .eco') && document.querySelector('#settings-help') && !document.querySelector('#model-info'))"))
            web.run("document.querySelector('#settings-help').click()")
            web.await("document.querySelector('#sheet').open && document.querySelector('#sheet-content').textContent.includes('支付成功') && document.querySelector('#sheet-content').textContent.includes('摇一摇')")
        }
    }

    @Test fun deleteTapWorksWithoutClickAndIgnoresCompatibilityClickDragAndCancel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            try {
                web.run("""
                    window.originalDelete37=deleteEntry;window.originalRefresh37=refresh;
                    refresh=()=>render();deleteEntry=(id,task)=>window.deleteCalls37.push({id,task});window.deleteCalls37=[];
                    month='2037-02';filter='expense';records=[{id:9701,merchant:'触控测试',category:'餐饮',source:'MANUAL',state:'POSTED',direction:'EXPENSE',amountCents:100,occurredAt:new Date(2037,1,5).getTime()}];recognitionTasks=[];go('ledger');render();
                    const row=document.querySelector('[data-record="9701"]').closest('.swipe-row');setSwipeOpen(row,true);
                    window.deleteTap37=mode=>{const button=row.querySelector('.swipe-delete'),target=button.querySelector('span'),r=button.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;
                        const send=(type,dx=0)=>target.dispatchEvent(new PointerEvent(type,{pointerId:81,clientX:x+dx,clientY:y,bubbles:true,isPrimary:true,cancelable:true}));
                        send('pointerdown');if(mode==='drag')send('pointermove',24);if(mode==='cancel')send('pointercancel');send('pointerup',mode==='drag'?24:0);
                    };
                """.trimIndent())
                web.run("deleteTap37('tap')")
                assertEquals("1",web.text("String(deleteCalls37.length)"))
                web.run("document.querySelector('.swipe-delete').dispatchEvent(new MouseEvent('click',{detail:1,bubbles:true,cancelable:true}))")
                assertEquals("1",web.text("String(deleteCalls37.length)"))
                web.run("deleteTap37('drag');deleteTap37('cancel');document.querySelector('.swipe-delete').dispatchEvent(new MouseEvent('click',{detail:1,bubbles:true,cancelable:true}))")
                assertEquals("1",web.text("String(deleteCalls37.length)"))
                web.run("document.querySelector('.swipe-delete').click()")
                assertEquals("2",web.text("String(deleteCalls37.length)"))
                web.run("setSwipeOpen(document.querySelector('.swipe-row'),false);deleteTap37('tap')")
                assertEquals("2",web.text("String(deleteCalls37.length)"))
            } finally {web.run("deleteEntry=window.originalDelete37;refresh=window.originalRefresh37;refresh()")}
        }
    }
}
