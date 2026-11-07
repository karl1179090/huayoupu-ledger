package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerStatisticsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="statistics-${UUID.randomUUID()}.db"
    private val store=LedgerStore(context,name)
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-11-06T04:00:00Z")
    @After fun cleanup(){store.close();context.deleteDatabase(name)}
    private fun record(amount:Long,direction:Direction,at:String="2026-11-02T04:00:00Z",fee:Long=0,category:String="餐饮",state:RecordState=RecordState.POSTED):LedgerRecord {
        val uid=UUID.randomUUID().toString()
        return LedgerRecord(uid=uid,capture=Capture(Source.MANUAL,"csv","合成统计",now.toEpochMilli()),
            bill=ParsedBill(amount,direction,"合成统计",category,Instant.parse(at).toEpochMilli(),null,true,""),state=state,
            transfer=if(direction==Direction.TRANSFER)RecordTransferDetails(uid,feeCents=fee,feeMode=FeeMode.EXTRA,
                fromReference="""{"version":1,"name":"合成卡"}""",toReference="""{"version":1,"name":"合成余额"}""") else null)
    }
    @Test fun expenseProjectionCountsRefundNetFeeAndExcludedHistoryButNeverPrincipalOrReview() {
        val expense=record(7000,Direction.EXPENSE).let { it.copy(bill=it.bill.copy(refund=RefundInfo("wechat",10000,3000))) }
        val rows=listOf(expense,record(10000,Direction.TRANSFER,"2026-11-04T04:00:00Z",100),
            record(2000,Direction.EXPENSE,"2026-11-06T04:00:00Z"),record(500,Direction.INCOME),record(10000,Direction.TRANSFER),
            record(10000,Direction.TRANSFER,state=RecordState.REVIEW),record(0,Direction.EXPENSE).let { it.copy(bill=it.bill.copy(refund=RefundInfo("wechat",10000,10000))) })
        store.importTransactions(rows)
        val result=LedgerStatistics(store).query(ExpensePeriod.WEEK,now,zone)
        assertEquals(9100L,result.totals.expense);assertEquals(500L,result.totals.income)
        assertEquals(listOf(7000L,0L,100L,0L,2000L,0L,0L),result.bucketAmounts)
        assertEquals(4L,result.totals.expenseCount);assertEquals(2L,result.totals.transferCount);assertEquals(6L,result.totals.totalCount)
        assertEquals(1,result.categories.count { it.key==LedgerStatistics.TRANSFER_FEE_KEY })
        assertEquals(100L,result.categories.single { it.key==LedgerStatistics.TRANSFER_FEE_KEY }.amountCents)
        assertTrue(store.all().all { it.walletEffects.isEmpty() })
    }
    @Test fun feeSystemCategoryStaysSeparateFromAnOldOrdinaryCategoryWithTheSameSourceName() {
        store.importTransactions(listOf(record(10000,Direction.TRANSFER,fee=100),record(200,Direction.EXPENSE,category="转账手续费")))
        val categories=LedgerStatistics(store).query(ExpensePeriod.MONTH,now,zone).categories
        assertEquals(2,categories.size);assertEquals(2,categories.map { it.key }.distinct().size)
        assertEquals(setOf("system:transfer_fee","category:转账手续费"),categories.map { it.key }.toSet())
        assertEquals(setOf("转账手续费","转账手续费（自定义）"),categories.map { it.name }.toSet())
    }
    @Test fun completedRefundAlwaysChangesTheOriginalExpenseDateAndSoftDeletesAreExcluded() {
        val old=record(7000,Direction.EXPENSE,"2026-10-29T04:00:00Z").let { it.copy(bill=it.bill.copy(refund=RefundInfo("wechat",10000,3000))) }
        val removed=store.importTransactions(listOf(old,record(2000,Direction.EXPENSE))).last()
        store.softDelete(removed.id,now.toEpochMilli())
        assertEquals(0L,LedgerStatistics(store).query(ExpensePeriod.MONTH,now,zone).totals.expense)
        assertEquals(7000L,LedgerStatistics(store).query(ExpensePeriod.MONTH,Instant.parse("2026-10-30T04:00:00Z"),zone).totals.expense)
    }
    @Test fun typeFilteringAndExpenseContributionFilteringHaveDistinctRealRecordIdentities() {
        val previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            val saved=store.importTransactions(listOf(record(10000,Direction.TRANSFER,fee=100),record(200,Direction.EXPENSE)))
            val read=LedgerReadView(store)
            assertEquals(1,read.records("2026-11",direction="EXPENSE").getJSONArray("records").length())
            val contributions=read.records("2026-11",direction="EXPENSE",contribution=true).getJSONArray("records")
            assertEquals(2,contributions.length())
            val fee=read.records("2026-11",direction="EXPENSE",category="system:transfer_fee",contribution=true).getJSONArray("records").getJSONObject(0)
            assertEquals(saved.first().uid,fee.getString("uid"));assertEquals("TRANSFER",fee.getString("direction"));assertEquals("100",fee.getString("contributionCents"))
            assertEquals(100L,fee.getJSONObject("transfer").getLong("feeCents"));assertTrue(fee.getJSONObject("walletEffects").length()==0)
        } finally {TimeZone.setDefault(previous)}
    }
    @Test fun futurePostedExpensesStayVisibleAndEveryEmptyBucketIsPresent() {
        store.importTransactions(listOf(record(123,Direction.EXPENSE,"2026-11-08T04:00:00Z")))
        val result=LedgerStatistics(store).query(ExpensePeriod.WEEK,now,zone)
        assertEquals(7,result.bucketAmounts.size);assertEquals(123L,result.bucketAmounts.last())
        assertTrue(result.range!!.buckets.last().future)
    }
    @Test fun nativeAggregationAndLegacyMonthJsonStayExactAboveJavascriptSafeInteger() {
        val previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            val db=store.writableDatabase
            db.beginTransaction()
            try {
                val statement=db.compileStatement("""INSERT INTO records(uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,reliable,time_explicit,state,reason,note)
                    VALUES(?,'MANUAL','synthetic','','1793937600000',?,'EXPENSE','合成大额','合成大额',?,1,1,'POSTED','','')""")
                statement.bindLong(2,Money.MAX_CENTS);statement.bindLong(3,now.toEpochMilli())
                repeat(100000){statement.bindString(1,"eeeeeeee-eeee-4eee-8eee-"+it.toString().padStart(12,'0'));statement.executeInsert()}
                statement.close();db.setTransactionSuccessful()
            } finally {db.endTransaction()}
            val total=Math.multiplyExact(Money.MAX_CENTS,100000L)
            assertTrue(total>9_007_199_254_740_991L)
            val result=LedgerStatistics(store).query(ExpensePeriod.YEAR,now,zone)
            assertEquals(total,result.totals.expense);assertEquals(total,result.bucketAmounts[10])
            assertEquals(total.toString(),result.json().getString("totalExpenseCents"))
            val summary=LedgerReadView(store).summary("2026-11")
            assertTrue(summary.getJSONObject("totals").get("expense") is String)
            assertEquals(total.toString(),summary.getJSONObject("totals").getString("expense"))
            assertEquals(total.toString(),summary.getJSONArray("categories").getJSONObject(0).getString("amount"))
            assertEquals(total.toString(),summary.getJSONArray("days").getJSONObject(0).getString("amount"))
        } finally {TimeZone.setDefault(previous)}
    }
    @Test fun statisticsOverflowIsAnErrorRatherThanFloatingPointOrZero() {
        try {LedgerStatistics.add(Long.MAX_VALUE,1);fail("overflow must fail")}
        catch(error:IllegalArgumentException){assertEquals("STATISTICS_OVERFLOW",error.message)}
    }
}
