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
            val window=read.window("2026-11",ZoneId.of("America/New_York"))
            val filtered=read.records("2026-11",limit=200,direction="EXPENSE",category="category:其他",contribution=true,
                rangeStart=window.start,rangeEnd=window.end)
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
                assertEquals(14,store.readableDatabase.version)
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

}
