package net.ankio.ledger

import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReferenceV21UiTest {
    @get:Rule val wallet = CaptureWalletFixture()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun keypadPreservesCentsAcrossRecreationAndSearchFindsTheSavedNote() {
        val note="V21-${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("beginManual();for(const key of ['1','2','.','3','4','9','.','00'])document.querySelector('.entry-keyboard [aria-label=\"'+key+'\"]').click();document.querySelector('#manual-note').value=${JSONObject.quote(note)};document.querySelector('#manual-note').dispatchEvent(new Event('input',{bubbles:true}))")
                assertEquals("12.34",web.text("document.querySelector('#manual-amount').value"))
                scenario.recreate();web.ready()
                web.await("document.querySelector('#manual-amount').value==='12.34' && document.querySelector('#manual-note').value===${JSONObject.quote(note)}")
                web.run("document.querySelector('.entry-save').click()")
                web.await("document.querySelector('#ledger').classList.contains('mobile-active')")
                val saved=app.store.all().single { it.note==note }
                assertEquals(1234L,saved.bill.amountCents)
                web.run("document.querySelector('#search-toggle').click();document.querySelector('#ledger-search').value=${JSONObject.quote(note)};document.querySelector('#ledger-search').dispatchEvent(new Event('input'))")
                web.await("document.querySelectorAll('#entries [data-record]').length===1 && document.querySelector('[data-record=\"${saved.id}\"]')")
                web.run("document.querySelector('#ledger-search').value='V21-no-match';document.querySelector('#ledger-search').dispatchEvent(new Event('input'))")
                web.await("document.querySelectorAll('#entries [data-record]').length===0 && document.querySelector('#entries').textContent.includes('未找到')")
                web.run("document.querySelector('#search-cancel').click()")
                web.await("document.querySelector('[data-record=\"${saved.id}\"]')")
            }
        } finally { app.store.all().filter { it.note==note }.forEach { app.store.delete(it.id) };app.changed() }
    }
    @Test fun newImportHasAVisiblePickerWhileManualDetailsHaveNoImage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready()
            web.run("newDraft();go('import')")
            assertEquals("true",web.value("document.querySelector('.scanner').getBoundingClientRect().height>100 && document.querySelector('.scanner').getAttribute('aria-disabled')==='false'"))
        }
    }
    private fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
}
