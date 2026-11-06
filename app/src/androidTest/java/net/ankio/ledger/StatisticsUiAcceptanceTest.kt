package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StatisticsUiAcceptanceTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun formatted(cents:Long)=DecimalFormat("#,##0.00",DecimalFormatSymbols(Locale.US)).format(BigDecimal.valueOf(cents,2))

    @Test fun fullRefundZeroFeeAndFuturePostedRowsKeepCountsAndRealContributionIdentities() {
        val now=Instant.now();val baseline=LedgerStatistics(app.store).query(ExpensePeriod.YEAR,now)
        val future=baseline.range!!.endMillis-1
        val marker="合成独立全退${UUID.randomUUID().toString().take(4)}"
        fun row(amount:Long,direction:Direction,at:Long=now.toEpochMilli()):LedgerRecord {
            val uid=UUID.randomUUID().toString()
            return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"synthetic","合成独立计数",now.toEpochMilli()),
                bill=ParsedBill(amount,direction,marker,marker,at,null,true,""),state=RecordState.POSTED,
                transfer=if(direction==Direction.TRANSFER)RecordTransferDetails(uid,feeCents=0,feeMode=FeeMode.EXTRA,
                    fromReference="""{"version":1,"name":"合成原A"}""",toReference="""{"version":1,"name":"合成原B"}""") else null)
        }
        val refund=row(0,Direction.EXPENSE).let { it.copy(bill=it.bill.copy(refund=RefundInfo("wechat",10000,10000))) }
        val saved=app.store.importTransactions(listOf(refund,row(10000,Direction.TRANSFER),row(500,Direction.INCOME),row(123,Direction.EXPENSE,future)))
        try {
            val after=LedgerStatistics(app.store).query(ExpensePeriod.YEAR,now)
            assertEquals(baseline.totals.expense+123,after.totals.expense)
            assertEquals(baseline.totals.expenseCount+2,after.totals.expenseCount)
            assertEquals(baseline.totals.transferCount+1,after.totals.transferCount)
            assertEquals(baseline.totals.totalCount+4,after.totals.totalCount)
            assertEquals(baseline.bucketAmounts.last()+123,after.bucketAmounts.last())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val native=JSONObject(web.text("LedgerNative.statistics('YEAR')"))
                assertEquals(after.totals.expenseCount,native.getLong("contributionCount"))
                web.run("statsType='expense';rankingType='expense';openStatistics('YEAR')")
                assertTrue(web.text("document.querySelector('#stats-count').textContent").contains(after.totals.expenseCount.toString()))
                val feeRows=JSONObject(web.text("LedgerNative.contributionRecords('YEAR','system:transfer_fee','EXPENSE',0)")).getJSONArray("records")
                assertTrue((0 until feeRows.length()).none { feeRows.getJSONObject(it).getString("uid")==saved[1].uid })
                val rows=JSONObject(web.text("LedgerNative.contributionRecords('YEAR','category:$marker','EXPENSE',0)")).getJSONArray("records")
                val full=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("uid")==refund.uid }
                assertEquals("0",full.getString("contributionCents"));assertEquals("EXPENSE",full.getString("direction"))
            }
        } finally {saved.forEach {app.store.delete(it.id)};app.changed()}
    }

    @Test fun actualNativeHundredThousandRowsRemainExactThroughBridgeAndWebviewLabels() {
        val now=System.currentTimeMillis();val zone=ZoneId.systemDefault()
        val baseline=LedgerStatistics(app.store).query(ExpensePeriod.YEAR,Instant.ofEpochMilli(now),zone).totals.expense
        val prefix=UUID.randomUUID().toString().take(24)
        val db=app.store.writableDatabase
        try {
            synchronized(app.store) {
                db.beginTransaction()
                try {
                    val insert=db.compileStatement("""INSERT INTO records(uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,reliable,time_explicit,state,reason,note)
                        VALUES(?,'MANUAL','synthetic','',?,?,'EXPENSE','合成独立精度','合成独立精度',?,1,1,'POSTED','','')""")
                    insert.bindLong(2,now);insert.bindLong(3,Money.MAX_CENTS);insert.bindLong(4,now)
                    repeat(100000) { insert.bindString(1,prefix+it.toString().padStart(12,'0'));insert.executeInsert() }
                    insert.close();db.setTransactionSuccessful()
                } finally {db.endTransaction()}
            }
            val expected=Math.addExact(baseline,Math.multiplyExact(Money.MAX_CENTS,100000L))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                val native=JSONObject(web.text("LedgerNative.statistics('YEAR')"))
                assertEquals(expected.toString(),native.getString("totalExpenseCents"));assertTrue(native.get("totalExpenseCents") is String)
                web.run("statsType='expense';rankingType='expense';openStatistics('YEAR')")
                web.await("document.querySelectorAll('.expense-bar-value').length===12")
                assertTrue(web.text("document.querySelector('#analysis-total').textContent").contains(formatted(expected)))
                val displayed=JSONArray(web.text("JSON.stringify(Array.from(document.querySelectorAll('.expense-bar-value')).map(e=>e.textContent))"))
                val buckets=native.getJSONArray("buckets")
                for(index in 0 until 12)assertEquals(formatted(buckets.getJSONObject(index).getString("expenseCents").toLong()),displayed.getString(index))
                val labels=web.text("document.querySelector('#ranking').textContent")
                assertTrue(labels.contains(formatted(Math.multiplyExact(Money.MAX_CENTS,100000L))))
                assertEquals(expected.toString(),web.text("periodStats.totalExpenseCents"))
            }
        } finally {
            synchronized(app.store) {db.delete("records","uid LIKE ?",arrayOf("$prefix%"))}
            app.changed()
        }
    }
}
