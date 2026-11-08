package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.LedgerWebBridge
import net.ankio.ledger.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import net.ankio.ledger.capture.ChannelSettings
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

/** Real bridge pages and shared statistics: DATE is a calendar fact, other precisions retain instant semantics. */
@RunWith(AndroidJUnit4::class)
class DateOnlyCalendarBridgeTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val context=RecognitionTimeContext(Instant.parse("2026-11-08T04:00:00Z").toEpochMilli(),"Asia/Shanghai")
    private fun parsed(id:String,amount:String,date:String,income:Boolean=false):Pair<Capture,ParsedBill> {
        val raw="微信支付\n${if(income)"收款成功\n收款金额" else "支付成功\n付款金额"}：${amount}元\n商户名称：合成日期投影\n交易时间：$date\n微信交易单号：$id"
        return Capture(Source.IMAGE,"image",raw,context.recognitionAt) to LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),
            0,0,context.recognitionAt,origin="image",timeContext=context)!!
    }
    private fun rows(value:JSONObject)=value.getJSONArray("records").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun frozenDateMonthPagesDirectionFiltersSummaryAndRangeUseOneCalendarWithoutMovingOtherRecords()=runBlocking {
        app.taskRecovery.await()
        val previousZone=TimeZone.getDefault()
        val settings=app.store.walletSettings()
        val wallet=app.store.saveAccount(LedgerAccount(name="合成日期分页钱包",amountCents=1_000_000,kind="cash"))
        app.store.setDefaultWallet(wallet.uid);app.store.setAppWallet(PaymentApps.WECHAT,wallet.uid)
        val created=mutableSetOf<Long>();val dateExpenses=mutableSetOf<Long>()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val suffix=UUID.randomUUID().toString()
            fun ingest(amount:String,date:String,income:Boolean=false,legacy:Boolean=false):LedgerRecord {
                val (capture,parsed)=parsed("SYNTHETIC-CALENDAR-$suffix-${created.size}",amount,date,income)
                val result=app.store.ingest(capture,if(legacy)parsed.copy(receipt=null) else parsed) as IngestResult.Saved
                created+=result.record.id
                assertEquals(RecordState.POSTED,result.record.state)
                return result.record
            }
            repeat(202) { dateExpenses+=ingest("1.00","2026-11-01").id }
            val fifth=ingest("3.45","2026-11-05");dateExpenses+=fifth.id
            val income=ingest("6.00","2026-11-01",income=true)
            val second=ingest("4.00","2026-11-01 00:00:00")
            val legacy=ingest("5.00","2026-11-01 00:00:00",legacy=true)
            assertEquals(979155L,app.store.getAccount(wallet.id)!!.amountCents)
            val movements=app.store.walletSnapshot().movements
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val bridge=LedgerWebBridge(activity)
                var result=JSONObject(bridge.snapshotFirst("2026-11","","EXPENSE",""))
                assertTrue(result.toString(),result.getBoolean("ok"))
                val summary=result.getJSONObject("ledgerView").getJSONObject("summary")
                val seen=mutableListOf<Long>()
                while(true) {
                    seen+=rows(result).map { it.getLong("id") }
                    val view=result.getJSONObject("ledgerView")
                    if(!view.getBoolean("hasMore"))break
                    val cursor=view.getJSONObject("nextCursor")
                    result=JSONObject(bridge.recordPage("2026-11","","EXPENSE",cursor.getLong("occurredAt"),cursor.getLong("id"),result.getString("dataRevision")))
                    assertFalse(result.optBoolean("reset"))
                }
                assertEquals(dateExpenses,seen.filter { it in created }.toSet())
                assertEquals(seen.size,seen.toSet().size)
                val offsetPages=JSONObject(bridge.snapshotView("2026-11","",400,"EXPENSE",""))
                assertEquals(dateExpenses,rows(offsetPages).map { it.getLong("id") }.filter { it in created }.toSet())
                assertFalse(offsetPages.getJSONObject("ledgerView").getBoolean("hasMore"))
                assertEquals("20545",summary.getString("totalExpenseCents"))
                assertEquals("600",summary.getString("incomeCents"))
                assertEquals(203L,summary.getJSONObject("totals").getLong("expenseCount"))
                val days=summary.getJSONArray("days").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                assertEquals("20200",days.single { it.getString("date")=="2026-11-01" && it.getString("direction")=="EXPENSE" }.getString("amount"))
                assertEquals("345",days.single { it.getString("date")=="2026-11-05" && it.getString("direction")=="EXPENSE" }.getString("amount"))
                assertFalse(days.any { it.getString("date")=="2026-11-04" })
                val incomePage=JSONObject(bridge.snapshotFirst("2026-11","","INCOME",""))
                assertEquals(setOf(income.id),rows(incomePage).map { it.getLong("id") }.filter { it in created }.toSet())
                val october=JSONObject(bridge.snapshotFirst("2026-10","","EXPENSE",""))
                assertEquals(setOf(second.id,legacy.id),rows(october).map { it.getLong("id") }.filter { it in created }.toSet())
            } }
            val range=LedgerStatistics(app.store).query(ExpensePeriod.MONTH,Instant.parse("2026-11-06T12:00:00Z"),ZoneId.of("America/New_York"))
            assertEquals(20545L,range.totals.expense);assertEquals(600L,range.totals.income)
            assertEquals(20200L,range.bucketAmounts[0]);assertEquals(345L,range.bucketAmounts[1])
            val read=LedgerReadView(app.store)
            val filtered=read.records("2026-11",limit=200,direction="EXPENSE",category="category:其他",contribution=true,
                calendarStart=java.time.LocalDate.parse("2026-11-01"),calendarEnd=java.time.LocalDate.parse("2026-12-01"))
            assertTrue(filtered.getBoolean("hasMore"));assertEquals(200,rows(filtered).size)
            assertEquals(movements,app.store.walletSnapshot().movements)
            assertEquals(Instant.parse("2026-11-04T16:00:00Z").toEpochMilli(),app.store.get(fifth.id)!!.bill.occurredAt)
            assertEquals("2026-11-05",app.store.get(fifth.id)!!.bill.receipt!!.time!!.localDate)
        } finally {
            created.forEach { app.store.delete(it) }
            app.store.setDefaultWallet(settings.defaultAccountUid);app.store.setAppWallet(PaymentApps.WECHAT,settings.appBindings[PaymentApps.WECHAT])
            app.store.writableDatabase.delete("wallet_movements","account_uid=?",arrayOf(wallet.uid));app.store.deleteAccount(wallet.id)
            TimeZone.setDefault(previousZone)
        }
    }
    @Test fun actualV13UpgradeIndexesOnlyTrustedDateReceiptsAndPreservesOriginalFactsAndMoney() {
        val previousZone=TimeZone.getDefault()
        val name="synthetic-date-migration-${UUID.randomUUID()}.db"
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val file=app.getDatabasePath(name);file.parentFile!!.mkdirs()
            val sql=InstrumentationRegistry.getInstrumentation().context.assets.open("time/ledger-v13.sql").bufferedReader().use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.execSQL("DROP TABLE IF EXISTS android_metadata")
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').filter(String::isNotBlank).forEach(db::execSQL)
                assertEquals(13,db.version)
            }
            repeat(2) { LedgerStore(app,name).use { store ->
                assertEquals(15,store.readableDatabase.version)
                val all=store.all().associateBy { it.bill.transactionId!!.removePrefix("SYNTHETIC-DATE-MIGRATION-") }
                assertEquals(setOf("ONE","FIFTH","SECOND","LEGACY"),all.keys)
                assertEquals(listOf(100L,345L,400L,500L),listOf("ONE","FIFTH","SECOND","LEGACY").map { all.getValue(it).bill.amountCents })
                assertEquals(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli(),all.getValue("ONE").bill.occurredAt)
                assertEquals(Instant.parse("2026-11-04T16:00:00Z").toEpochMilli(),all.getValue("FIFTH").bill.occurredAt)
                assertEquals("DATE",all.getValue("ONE").bill.receipt!!.time!!.precision)
                assertEquals("SECOND",all.getValue("SECOND").bill.receipt!!.time!!.precision)
                assertNull(all.getValue("LEGACY").bill.receipt)
                assertTrue(all.values.all { it.bill.category=="其他" && it.state==RecordState.POSTED })
                assertEquals(8655L,store.getAccountByUid("11111111-2222-4333-8444-555555555555")!!.amountCents)
                assertEquals(5,store.walletSnapshot().movements.size)
                val read=LedgerReadView(store)
                assertEquals(setOf(all.getValue("ONE").id,all.getValue("FIFTH").id),rows(read.records("2026-11")).map { it.getLong("id") }.toSet())
                assertEquals(setOf(all.getValue("SECOND").id,all.getValue("LEGACY").id),rows(read.records("2026-10")).map { it.getLong("id") }.toSet())
                val summary=read.summary("2026-11")
                assertEquals("445",summary.getString("totalExpenseCents"))
                val days=summary.getJSONArray("days").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("date") } }
                assertEquals(listOf("2026-11-01","2026-11-05"),days)
            } }
        } finally { TimeZone.setDefault(previousZone);app.deleteDatabase(name) }
    }

    @Test fun jsonAndZipRestoreDeriveDatesAndActualTimeEditReturnsToInstantSemanticsWithoutReplayingMoney() {
        val previousZone=TimeZone.getDefault()
        val sourceName="synthetic-date-backup-${UUID.randomUUID()}.db"
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val file=app.getDatabasePath(sourceName);file.parentFile!!.mkdirs()
            val sql=InstrumentationRegistry.getInstrumentation().context.assets.open("time/ledger-v13.sql").bufferedReader().use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.execSQL("DROP TABLE IF EXISTS android_metadata")
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').filter(String::isNotBlank).forEach(db::execSQL)
            }
            LedgerStore(app,sourceName).use { source ->
                val json=LedgerBackup.encode(source,ChannelSettings(app)).toByteArray()
                val zipped=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(app),it) }.toByteArray()
                for(bytes in listOf(json,zipped)) {
                    val targetName="synthetic-date-restored-${UUID.randomUUID()}.db"
                    try { LedgerStore(app,targetName).use { target ->
                        LedgerArchive.read(bytes.inputStream(),app.cacheDir).use { prepared ->
                            assertEquals(4,LedgerArchive.restore(target,prepared))
                            val read=LedgerReadView(target)
                            val restored=target.all().associateBy { it.bill.transactionId!!.removePrefix("SYNTHETIC-DATE-MIGRATION-") }
                            assertEquals(setOf(restored.getValue("ONE").id,restored.getValue("FIFTH").id),rows(read.records("2026-11")).map { it.getLong("id") }.toSet())
                            assertEquals("445",read.summary("2026-11").getString("totalExpenseCents"))
                            val movements=target.walletSnapshot().movements
                            val editedAt=Instant.parse("2026-10-31T18:00:00Z").toEpochMilli()
                            val original=restored.getValue("ONE")
                            val edited=target.saveEdited(original.copy(bill=original.bill.copy(occurredAt=editedAt)))
                            assertEquals("MANUAL",edited.bill.receipt!!.time!!.source)
                            assertEquals("INSTANT",edited.bill.receipt!!.time!!.precision)
                            assertEquals(setOf(restored.getValue("FIFTH").id),rows(read.records("2026-11")).map { it.getLong("id") }.toSet())
                            assertEquals("345",read.summary("2026-11").getString("totalExpenseCents"))
                            assertTrue(rows(read.records("2026-10")).any { it.getLong("id")==original.id })
                            assertEquals(8655L,target.getAccountByUid("11111111-2222-4333-8444-555555555555")!!.amountCents)
                            assertEquals(movements,target.walletSnapshot().movements)
                            assertEquals(0,LedgerArchive.restore(target,prepared))
                            assertEquals(editedAt,target.get(original.id)!!.bill.occurredAt)
                            assertEquals("MANUAL",target.get(original.id)!!.bill.receipt!!.time!!.source)
                            assertEquals(movements,target.walletSnapshot().movements)
                        }
                    } } finally { app.deleteDatabase(targetName) }
                }
            }
        } finally { TimeZone.setDefault(previousZone);app.deleteDatabase(sourceName) }
    }

    @Test fun historicalMismatchedDateEvidenceKeepsItsRawMetadataButCannotOverrideTheActualMonth() {
        val previousZone=TimeZone.getDefault();val name="synthetic-date-mismatch-${UUID.randomUUID()}.db"
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val file=app.getDatabasePath(name);file.parentFile!!.mkdirs()
            val sql=InstrumentationRegistry.getInstrumentation().context.assets.open("time/ledger-v13.sql").bufferedReader().use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.execSQL("DROP TABLE IF EXISTS android_metadata")
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').filter(String::isNotBlank).forEach(db::execSQL)
                // A test-only copy of actual v13 data: valid receipt metadata attached to another actual instant.
                db.execSQL("UPDATE records SET receipt_evidence=(SELECT receipt_evidence FROM records WHERE transaction_id='SYNTHETIC-DATE-MIGRATION-FIFTH') WHERE transaction_id='SYNTHETIC-DATE-MIGRATION-LEGACY'")
                assertEquals(13,db.version)
            }
            LedgerStore(app,name).use { store ->
                val all=store.all().associateBy { it.bill.transactionId!!.removePrefix("SYNTHETIC-DATE-MIGRATION-") }
                val record=all.getValue("LEGACY");val time=record.bill.receipt!!.time!!
                assertEquals("2026-11-05",time.localDate);assertEquals("DATE",time.precision)
                assertNotEquals(time.accountingAt,record.bill.occurredAt)
                assertEquals(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli(),record.bill.occurredAt)
                store.readableDatabase.rawQuery("SELECT accounting_date FROM records WHERE id=?",arrayOf(record.id.toString())).use { c ->
                    assertTrue(c.moveToFirst());assertTrue(c.isNull(0))
                }
                val read=LedgerReadView(store)
                assertEquals(setOf(all.getValue("SECOND").id,record.id),rows(read.records("2026-10")).map { it.getLong("id") }.toSet())
                assertEquals("445",read.summary("2026-11").getString("totalExpenseCents"))
                assertEquals("900",read.summary("2026-10").getString("totalExpenseCents"))
                val returned=rows(read.records("2026-10")).single { it.getLong("id")==record.id }
                assertEquals("2026-11-05",returned.getJSONObject("receipt").getJSONObject("time").getString("localDate"))
                assertEquals(record.bill.occurredAt,returned.getLong("occurredAt"))
                assertEquals(8655L,store.getAccountByUid("11111111-2222-4333-8444-555555555555")!!.amountCents)
                assertEquals(5,store.walletSnapshot().movements.size)
            }
        } finally { TimeZone.setDefault(previousZone);app.deleteDatabase(name) }
    }

    @Test fun mixedCalendarDaysStayOrderedAcrossCursorAndOffsetPagesAndOldOrOtherZoneCursorsReset()=runBlocking {
        app.taskRecovery.await()
        val previousZone=TimeZone.getDefault();val prefix="synthetic-mixed-${UUID.randomUUID()}"
        val normalAt=Instant.parse("2026-11-02T04:00:00Z").toEpochMilli() // New York Nov 1, 23:00.
        val dateAt=Instant.parse("2026-11-01T16:00:00Z").toEpochMilli() // Shanghai DATE Nov 2.
        val normal=(1..202).map { index -> LedgerRecord(uid="$prefix-normal-$index",
            capture=Capture(Source.MANUAL,"manual","合成普通日期",normalAt),
            bill=ParsedBill(100,Direction.EXPENSE,"合成混合页$prefix-$index","其他",normalAt,null,true,"",true),state=RecordState.POSTED) }
        val raw="支付成功\n付款金额：9.00元\n交易日期：2026-11-02"
        val bill=LocalOcrBillParser.parse(raw.lines().map(::LocalOcrLine),0,0,context.recognitionAt,origin="image",timeContext=context)!!
        val dateRecord=LedgerRecord(uid="$prefix-date",capture=Capture(Source.IMAGE,"image",raw,context.recognitionAt),bill=bill.copy(merchant="合成混合页$prefix"),state=RecordState.POSTED)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(203,app.store.merge(normal+dateRecord))
            val originals=app.store.all().filter { it.uid.startsWith(prefix) }
            val date=originals.single { it.uid==dateRecord.uid }
            val ordinary=originals.filter { it.uid!=date.uid }.sortedByDescending { it.id }
            val expected=listOf(date.id)+ordinary.map { it.id }
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val bridge=LedgerWebBridge(activity)
                val page=JSONObject(bridge.snapshotFirst("2026-11",prefix,"EXPENSE",""))
                assertEquals(date.id,rows(page).first { it.getLong("id") in expected }.getLong("id"))
                val revision=page.getString("dataRevision")
                var current=page;val seen=mutableListOf<Long>()
                while(true) {
                    seen+=rows(current).map { it.getLong("id") }.filter { it in expected }
                    val view=current.getJSONObject("ledgerView")
                    if(!view.getBoolean("hasMore"))break
                    val cursor=view.getJSONObject("nextCursor")
                    current=JSONObject(bridge.recordPage("2026-11",prefix,"EXPENSE",cursor.getLong("occurredAt"),cursor.getLong("id"),revision))
                    assertFalse(current.optBoolean("reset"))
                }
                assertEquals(expected,seen)
                val afterDate=JSONObject(bridge.recordPage("2026-11",prefix,"EXPENSE",dateAt,date.id,revision))
                assertEquals(ordinary.first().id,rows(afterDate).first().getLong("id"))
                assertTrue(JSONObject(bridge.recordPage("2026-11",prefix,"EXPENSE",dateAt,date.id,app.revision.value.toString())).getBoolean("reset"))
                assertTrue(JSONObject(bridge.recordPage("2026-11",prefix,"EXPENSE",dateAt+1,date.id,revision)).getBoolean("reset"))
                val known=page.getString("viewKey")
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
                assertTrue(JSONObject(bridge.recordPage("2026-11",prefix,"EXPENSE",dateAt,date.id,revision)).getBoolean("reset"))
                val changed=JSONObject(bridge.snapshotFirst("2026-11",prefix,"EXPENSE",known))
                assertFalse(changed.optBoolean("unchanged"));assertNotEquals(revision,changed.getString("dataRevision"))
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            } }
            val offsetPage=LedgerReadView(app.store).records("2026-11",prefix,offset=200,limit=200,direction="EXPENSE")
            assertEquals(expected.drop(200),rows(offsetPage).map { it.getLong("id") }.filter { it in expected })
            assertEquals(dateAt,app.store.get(date.id)!!.bill.occurredAt)
            assertTrue(ordinary.all { app.store.get(it.id)!!.bill.occurredAt==normalAt })
        } finally {
            app.store.all().filter { it.uid.startsWith(prefix) }.forEach { app.store.delete(it.id) }
            TimeZone.setDefault(previousZone)
        }
    }

    @Test fun midnightRollbackPagesOffsetsAndCalendarBucketsKeepEveryOriginalInstant()=runBlocking {
        app.taskRecovery.await()
        val previous=TimeZone.getDefault();val prefix="synthetic-rollback-${UUID.randomUUID()}"
        val highDay=Instant.parse("2010-03-04T14:30:00Z").toEpochMilli()
        val lowDay=Instant.parse("2010-03-04T15:30:00Z").toEpochMilli()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Antarctica/Casey"))
            val zone=ZoneId.systemDefault()
            assertEquals("2010-03-05",Instant.ofEpochMilli(highDay).atZone(zone).toLocalDate().toString())
            assertEquals("2010-03-04",Instant.ofEpochMilli(lowDay).atZone(zone).toLocalDate().toString())
            app.store.merge((1..203).map { n ->
                val at=if(n<=2)highDay else lowDay
                LedgerRecord(uid="$prefix-$n",capture=Capture(Source.MANUAL,"manual","合成跨午夜",at),
                    bill=ParsedBill(100,Direction.EXPENSE,prefix,"其他",at,null,true,"",true),state=RecordState.POSTED)
            })
            val saved=app.store.all().filter { it.uid.startsWith(prefix) }
            val expected=saved.sortedWith(compareByDescending<LedgerRecord> { Instant.ofEpochMilli(it.bill.occurredAt).atZone(zone).toLocalDate() }
                .thenByDescending { it.bill.occurredAt }.thenByDescending { it.id }).map { it.id }
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val bridge=LedgerWebBridge(activity);var page=JSONObject(bridge.snapshotFirst("2010-03",prefix,"EXPENSE",""))
                val actual=mutableListOf<Long>()
                while(true) {
                    actual+=rows(page).map { it.getLong("id") }
                    val view=page.getJSONObject("ledgerView");if(!view.getBoolean("hasMore"))break
                    val cursor=view.getJSONObject("nextCursor")
                    page=JSONObject(bridge.recordPage("2010-03",prefix,"EXPENSE",cursor.getLong("occurredAt"),cursor.getLong("id"),page.getString("dataRevision")))
                    assertFalse(page.optBoolean("reset"))
                }
                assertEquals(expected,actual)
                assertEquals(expected,rows(JSONObject(bridge.snapshotView("2010-03",prefix,400,"EXPENSE",""))).map { it.getLong("id") })
            } }
            val read=LedgerReadView(app.store)
            for(offset in listOf(1,199,200,203,400))assertEquals(expected.drop(offset).take(200),
                rows(read.records("2010-03",prefix,offset=offset)).map { it.getLong("id") })
            val period=LedgerStatistics(app.store).query(ExpensePeriod.WEEK,Instant.parse("2010-03-05T04:00:00Z"),zone)
            assertEquals(20100L,period.bucketAmounts[3]);assertEquals(200L,period.bucketAmounts[4])
            val calendarDay=read.records("2010-03",calendarStart=java.time.LocalDate.parse("2010-03-04"),calendarEnd=java.time.LocalDate.parse("2010-03-05"))
            val lowDayIds=saved.filter { it.bill.occurredAt==lowDay }.map { it.id }.sortedDescending()
            assertEquals(lowDayIds.take(200),rows(calendarDay).map { it.getLong("id") });assertTrue(calendarDay.getBoolean("hasMore"))
            assertEquals(lowDayIds.drop(200),rows(read.records("2010-03",offset=200,calendarStart=java.time.LocalDate.parse("2010-03-04"),calendarEnd=java.time.LocalDate.parse("2010-03-05"))).map { it.getLong("id") })
            val instantOnly=read.records("2010-03",rangeStart=Instant.parse("2010-03-04T15:00:00Z").toEpochMilli(),rangeEnd=Instant.parse("2010-03-04T16:00:00Z").toEpochMilli())
            assertEquals(200,rows(instantOnly).count { it.getLong("id") in expected });assertTrue(instantOnly.getBoolean("hasMore"))
            assertTrue(saved.all { app.store.get(it.id)!!.bill.occurredAt==it.bill.occurredAt })
        } finally { app.store.all().filter { it.uid.startsWith(prefix) }.forEach { app.store.delete(it.id) };TimeZone.setDefault(previous) }
    }

    @Test fun monthBoundaryRollbackUsesCalendarMonthsAndWeekDaysWhileExplicitInstantRangesStayInstantRanges()=runBlocking {
        app.taskRecovery.await()
        val previous=TimeZone.getDefault();val prefix="synthetic-month-rollback-${UUID.randomUUID()}"
        val november=Instant.parse("2009-11-01T02:30:30Z").toEpochMilli()
        val october=Instant.parse("2009-11-01T03:00:00Z").toEpochMilli()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/St_Johns"));val zone=ZoneId.systemDefault()
            assertEquals("2009-11-01",Instant.ofEpochMilli(november).atZone(zone).toLocalDate().toString())
            assertEquals("2009-10-31",Instant.ofEpochMilli(october).atZone(zone).toLocalDate().toString())
            app.store.merge(listOf(november,october).mapIndexed { n,at -> LedgerRecord(uid="$prefix-$n",capture=Capture(Source.MANUAL,"manual","合成月界",at),
                bill=ParsedBill(if(n==0)100 else 200,Direction.EXPENSE,prefix,"其他",at,null,true,"",true),state=RecordState.POSTED) })
            val saved=app.store.all().filter { it.uid.startsWith(prefix) };val nov=saved.single { it.bill.occurredAt==november };val oct=saved.single { it.bill.occurredAt==october }
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val bridge=LedgerWebBridge(activity)
                for((month,record,amount) in listOf(Triple("2009-10",oct,"200"),Triple("2009-11",nov,"100"))) {
                    val page=JSONObject(bridge.snapshotFirst(month,"","EXPENSE",""))
                    assertEquals(setOf(record.id),rows(page).map { it.getLong("id") }.filter { id -> saved.any { it.id==id } }.toSet())
                    assertEquals(amount,page.getJSONObject("ledgerView").getJSONObject("summary").getString("totalExpenseCents"))
                }
            } }
            val period=LedgerStatistics(app.store).query(ExpensePeriod.WEEK,Instant.parse("2009-11-01T12:00:00Z"),zone)
            assertEquals(200L,period.bucketAmounts[5]);assertEquals(100L,period.bucketAmounts[6])
            val read=LedgerReadView(app.store)
            val raw=rows(read.records("2009-10",rangeStart=november,rangeEnd=october+1))
            assertEquals(setOf(nov.id,oct.id),raw.map { it.getLong("id") }.toSet())
            assertEquals(listOf(november,october),listOf(nov.id,oct.id).map { app.store.get(it)!!.bill.occurredAt })
        } finally { app.store.all().filter { it.uid.startsWith(prefix) }.forEach { app.store.delete(it.id) };TimeZone.setDefault(previous) }
    }

    @Test fun mixedFrozenDatesAndOrdinaryRecordsKeepAll617RowsAcrossConventionalZonesAndLargeOffsets() {
        val previous=TimeZone.getDefault();val name="synthetic-calendar-zones-${UUID.randomUUID()}.db"
        try { LedgerStore(app,name).use { store ->
            val entries=(1..617).map { n ->
                val (capture,bill)=if(n<=430) {
                    val at=Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()+(n%17)*2_400_000L
                    Capture(Source.MANUAL,"manual","合成普通记录",at) to ParsedBill(100,Direction.EXPENSE,"合成多时区分页","其他",at,null,true,"",true)
                } else parsed("SYNTHETIC-CALENDAR-ZONES-$n","1.00","2026-11-0${1+n%4}")
                LedgerRecord(uid="synthetic-zone-$n",capture=capture,bill=bill.copy(merchant="合成多时区分页"),state=RecordState.POSTED)
            }
            assertEquals(617,store.merge(entries));val saved=store.all()
            for(zoneName in listOf("America/New_York","Asia/Shanghai","Europe/Berlin","Pacific/Apia","Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneName));val zone=ZoneId.systemDefault();val read=LedgerReadView(store)
                val expected=saved.sortedWith(compareByDescending<LedgerRecord> {
                    it.bill.receipt?.time?.takeIf { time -> time.precision=="DATE" }?.localDate?.let(java.time.LocalDate::parse)
                        ?: Instant.ofEpochMilli(it.bill.occurredAt).atZone(zone).toLocalDate()
                }.thenByDescending { it.bill.occurredAt }.thenByDescending { it.id }).map { it.id }
                var page=read.records("2026-11","合成多时区分页");val actual=mutableListOf<Long>()
                while(true) {
                    actual+=rows(page).map { it.getLong("id") };if(!page.getBoolean("hasMore"))break
                    val cursor=page.getJSONObject("nextCursor")
                    page=read.records("2026-11","合成多时区分页",beforeAt=cursor.getLong("occurredAt"),beforeId=cursor.getLong("id"))
                }
                assertEquals(zoneName,expected,actual)
                for(offset in listOf(1,199,200,400,617,700))assertEquals("$zoneName offset$offset",expected.drop(offset).take(200),
                    rows(read.records("2026-11","合成多时区分页",offset=offset)).map { it.getLong("id") })
            }
            assertEquals(entries.associate { it.uid to it.bill.occurredAt },store.all().associate { it.uid to it.bill.occurredAt })
        } } finally { TimeZone.setDefault(previous);app.deleteDatabase(name) }
    }

    @Test fun deletedCategoryReplacementAndLateHistoryRestoreKeepDateProjectionFrozenRuleAndOneWalletDebit() {
        val previous=TimeZone.getDefault();val sourceName="synthetic-date-replacement-${UUID.randomUUID()}.db";val targetName="synthetic-date-replacement-target-${UUID.randomUUID()}.db"
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            LedgerStore(app,sourceName).use { source -> LedgerStore(app,targetName).use { target ->
                val originalCategory=source.saveCategory(name="合成日期旧用途",direction=Direction.EXPENSE)
                val replacement=source.saveCategory(name="合成日期替代用途",direction=Direction.EXPENSE)
                val rule=source.saveClassificationRule(ClassificationRule("","合成日期用途规则",true,Direction.EXPENSE,originalCategory.id,0,listOf(ClassificationCondition(value="合成"))))
                val frozen=source.holdClassificationSnapshot("synthetic-date-replacement")
                val wallet=source.saveAccount(LedgerAccount(name="合成日期替代钱包",amountCents=10_000,kind="cash"))
                source.setDefaultWallet(wallet.uid);source.setAppWallet(PaymentApps.WECHAT,wallet.uid)
                val (capture,bill)=parsed("SYNTHETIC-DATE-REPLACEMENT","7.00","2026-11-01")
                val original=(source.ingest(capture,source.classify(bill,frozen,capture)) as IngestResult.Saved).record
                assertEquals(originalCategory.id,original.bill.categoryDecision!!.categoryId)
                assertEquals(9300L,source.getAccount(wallet.id)!!.amountCents)
                val movements=source.walletSnapshot().movements
                source.confirmCategoryDeletion(originalCategory.id,replacement.id,source.previewCategoryDeletion(originalCategory.id).previewToken)
                // A late old snapshot reaches values() with its deleted ID and unchanged time evidence.
                val (lateCapture,lateParsed)=parsed("SYNTHETIC-DATE-REPLACEMENT-LATE","8.00","2026-11-01")
                val late=LedgerRecord(capture=lateCapture,bill=lateParsed.copy(category=original.bill.category,categoryDecision=original.bill.categoryDecision),state=RecordState.POSTED)
                assertEquals(1,source.merge(listOf(late)))
                assertEquals(movements,source.walletSnapshot().movements)
                assertEquals(9300L,source.getAccount(wallet.id)!!.amountCents)
                val backup=LedgerBackup.decode(LedgerBackup.encode(source,app.channels).byteInputStream())
                assertEquals(2,LedgerBackup.restore(target,backup))
                for(store in listOf(source,target)) {
                    assertEquals(2,store.all().size)
                    for(record in store.all()) {
                        assertEquals(replacement.id,record.bill.categoryDecision!!.categoryId)
                        assertEquals(rule,record.bill.categoryDecision!!.rule)
                        assertEquals(original.bill.receipt!!.time,record.bill.receipt!!.time)
                        assertEquals(original.bill.occurredAt,record.bill.occurredAt)
                    }
                    val read=LedgerReadView(store)
                    assertEquals(2,rows(read.records("2026-11",category="category:"+replacement.name,contribution=true,direction="EXPENSE")).size)
                    assertEquals("1500",read.summary("2026-11").getString("totalExpenseCents"))
                    assertTrue(rows(read.records("2026-10")).isEmpty())
                    assertEquals(9300L,store.getAccountByUid(wallet.uid)!!.amountCents)
                    val restoredMovements=store.walletSnapshot().movements
                    assertEquals(movements,restoredMovements.filter { it.kind!="RESTORE_SNAPSHOT" })
                    assertEquals(1,restoredMovements.count { it.kind=="POST" && it.deltaCents== -700L })
                    val audits=restoredMovements.filter { it.kind=="RESTORE_SNAPSHOT" }
                    assertEquals(if(store===source)0 else 1,audits.size)
                    assertTrue(audits.all { it.deltaCents==0L && it.balanceBefore==9300L && it.balanceAfter==9300L })
                }
            } }
        } finally { TimeZone.setDefault(previous);app.deleteDatabase(sourceName);app.deleteDatabase(targetName) }
    }

    @Test fun explicitInstantWindowsUseSavedMillisecondsForDateEvidenceWithoutChangingCalendarViews() {
        val previous=TimeZone.getDefault();val name="synthetic-instant-window-${UUID.randomUUID()}.db"
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            LedgerStore(app,name).use { store ->
                val (capture,bill)=parsed("SYNTHETIC-INSTANT-DATE","1.00","2026-11-01")
                assertEquals(1,store.merge(listOf(LedgerRecord(capture=capture,bill=bill,state=RecordState.POSTED))))
                val record=store.all().single();val at=record.bill.occurredAt;val read=LedgerReadView(store)
                assertEquals(listOf(record.id),rows(read.records("2026-11")).map { it.getLong("id") })
                assertTrue(rows(read.records("2026-10")).isEmpty())
                assertEquals(listOf(record.id),rows(read.records("2026-10",rangeStart=at,rangeEnd=at+1)).map { it.getLong("id") })
                val start=java.time.LocalDate.parse("2026-11-01").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end=java.time.LocalDate.parse("2026-11-02").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                assertTrue(rows(read.records("2026-11",rangeStart=start,rangeEnd=end)).isEmpty())
                assertEquals(listOf(record.id),rows(read.records("2026-10",calendarStart=java.time.LocalDate.parse("2026-11-01"),calendarEnd=java.time.LocalDate.parse("2026-11-02"))).map { it.getLong("id") })
                assertEquals(at,store.get(record.id)!!.bill.occurredAt);assertEquals("DATE",store.get(record.id)!!.bill.receipt!!.time!!.precision)
                assertTrue(store.walletSnapshot().movements.isEmpty())
            }
        } finally { TimeZone.setDefault(previous);app.deleteDatabase(name) }
    }

    @Test fun refundCandidatesExposeTimeBasisWithoutReparsingLegacyReceiptsOrMutatingFunds()=runBlocking {
        app.taskRecovery.await();val previous=TimeZone.getDefault();val created=mutableListOf<Long>()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val suffix=UUID.randomUUID().toString()
            val (capture,bill)=parsed("SYNTHETIC-REFUND-DATE-$suffix","100.00","2026-11-05")
            val date=LedgerRecord(capture=capture,bill=bill,state=RecordState.POSTED)
            val (legacyCapture,legacyBill)=parsed("SYNTHETIC-REFUND-LEGACY-$suffix","100.00","2026-11-05 00:00:00")
            val legacy=LedgerRecord(capture=legacyCapture,bill=legacyBill.copy(receipt=null),state=RecordState.POSTED)
            val pending=LedgerRecord(capture=Capture(Source.IMAGE,"image","微信支付\n合成成功退款凭证",context.recognitionAt),
                bill=ParsedBill(null,Direction.EXPENSE,"合成待选择退款","其他",context.recognitionAt,null,false,"请选择原账单",
                    standaloneRefund=StandaloneRefundEvidence("wechat",1000,"SYNTHETIC-REFUND-$suffix")),state=RecordState.REVIEW)
            assertEquals(3,app.store.merge(listOf(date,legacy,pending)))
            val saved=app.store.all().filter { it.uid in setOf(date.uid,legacy.uid,pending.uid) };created+=saved.map { it.id }
            val byUid=saved.associateBy { it.uid };val original=byUid.getValue(date.uid);val old=byUid.getValue(legacy.uid);val review=byUid.getValue(pending.uid)
            val expected=app.store.refundCandidates(review.id).map { it.id }
            assertTrue(original.id in expected && old.id in expected)
            val wallet=app.store.walletSnapshot()
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val result=JSONObject(LedgerWebBridge(activity).refundCandidates(review.id));assertTrue(result.toString(),result.getBoolean("ok"))
                val array=result.getJSONArray("candidates");val candidates=(0 until array.length()).map { array.getJSONObject(it) }
                assertEquals(expected,candidates.map { it.getLong("id") })
                val shown=candidates.single { it.getLong("id")==original.id }
                val receipt=shown.getJSONObject("receipt");assertEquals(setOf("time"),receipt.keys().asSequence().toSet())
                val time=receipt.getJSONObject("time")
                assertEquals("DATE",time.getString("precision"));assertEquals("2026-11-05",time.getString("localDate"))
                assertEquals(original.bill.occurredAt,time.getLong("accountingAt"));assertEquals("EXPLICIT",time.getString("source"))
                assertEquals("Asia/Shanghai",time.getString("zoneId"));assertEquals(original.bill.occurredAt,shown.getLong("occurredAt"))
                assertFalse(candidates.single { it.getLong("id")==old.id }.has("receipt"))
            } }
            assertEquals(wallet,app.store.walletSnapshot())
            assertEquals(original,app.store.get(original.id));assertEquals(old,app.store.get(old.id));assertEquals(review,app.store.get(review.id))
        } finally { created.forEach { app.store.delete(it) };TimeZone.setDefault(previous) }
    }

}
