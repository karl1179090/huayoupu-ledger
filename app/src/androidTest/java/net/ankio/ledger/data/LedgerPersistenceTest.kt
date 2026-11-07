package net.ankio.ledger.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite and JSON tests; every message and merchant below is synthetic. */
@RunWith(AndroidJUnit4::class)
class LedgerPersistenceTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private lateinit var channels: ChannelSettings
    private lateinit var databaseName: String
    private lateinit var preferenceName: String
    private val instant = 1_790_000_000_000L

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        databaseName = "ledger-instrumentation-$suffix.db"
        preferenceName = "ledger-instrumentation-$suffix"
        // Never touch the installed application's channel preferences or ledger database.
        context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                application.getSharedPreferences(preferenceName, mode)
        }
        store = LedgerStore(context, databaseName).prepareSyntheticDefaultWallet()
        channels = ChannelSettings(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun reliableTransactionPostsAndSurvivesReopeningDatabase() {
        val saved = ingest(capture(), bill())

        assertEquals(RecordState.POSTED, saved.state)
        assertEquals(listOf(saved.id), store.posted(instant, instant + 1).map { it.id })
        assertTrue(store.pending().isEmpty())
        store.close()
        store = LedgerStore(context, databaseName)
        assertEquals(saved, store.get(saved.id))
    }

    @Test
    fun uncertainTransactionStaysPendingUntilUserConfirmsIt() {
        val saved = ingest(capture(), bill(reliable = false).copy(reason = "合成样例：金额需核对"))

        assertEquals(RecordState.REVIEW, saved.state)
        assertEquals(listOf(saved.id), store.pending().map { it.id })
        assertTrue(store.posted(instant - 1, instant + 1).isEmpty())

        val confirmed = store.saveEdited(saved.copy(note = "用户核对的合成备注"))
        assertEquals(RecordState.POSTED, confirmed.state)
        assertEquals("", confirmed.reason)
        assertNull(confirmed.duplicateId)
        assertTrue(store.pending().isEmpty())
        assertEquals(confirmed, store.get(saved.id))
        assertEquals(listOf(confirmed.id), store.posted(instant - 1, instant + 1).map { it.id })
    }

    @Test
    fun uncertainCrossChannelMatchWaitsForReviewWithoutChangingTotals() {
        val first = ingest(capture(text = "合成通知一"), bill(transactionId = null))
        val second = ingest(capture(Source.SMS, "合成短信二", "95588", instant + 10_000),
            bill(transactionId = null).copy(occurredAt = instant + 10_000))

        assertEquals(RecordState.REVIEW, second.state)
        assertEquals(first.id, second.duplicateId)
        assertEquals(listOf(second.id), store.pending().map { it.id })
        assertEquals(1234L, store.posted(instant - 1, instant + 20_000).sumOf { it.bill.amountCents!! })
    }

    @Test
    fun replayOfSameTransactionDoesNotCreateSecondRecord() {
        val first = ingest(capture(), bill())
        val replay = store.ingest(capture(receivedAt = instant + 5_000), bill())

        assertEquals(IngestResult.DuplicateSkipped, replay)
        assertEquals(listOf(first), store.all())
    }

    @Test
    fun distinctOrderNumbersAllowRepeatedPurchasesOfEqualAmount() {
        val first = ingest(capture(text = "合成订单 A"), bill(transactionId = "SYNTHETIC-A"))
        val second = ingest(capture(text = "合成订单 B", receivedAt = instant + 5_000),
            bill(transactionId = "SYNTHETIC-B").copy(occurredAt = instant + 5_000))

        assertEquals(RecordState.POSTED, second.state)
        assertTrue(store.pending().isEmpty())
        assertEquals(setOf(first.id, second.id), store.posted(instant - 1, instant + 10_000).map { it.id }.toSet())
    }

    @Test
    fun postedQueryUsesInclusiveStartAndExclusiveEnd() {
        val first = ingest(capture(), bill())
        val second = ingest(capture(text = "合成另一笔", receivedAt = instant + 10_000),
            bill(transactionId = "SYNTHETIC-B").copy(occurredAt = instant + 10_000))

        assertEquals(listOf(first.id), store.posted(instant, instant + 10_000).map { it.id })
        assertEquals(listOf(second.id), store.posted(instant + 10_000, instant + 10_001).map { it.id })
    }

    @Test
    fun backupPreservesCaptureNotesPendingRecordsAndEveryChannel() {
        val posted = store.saveEdited(ingest(capture(), bill()).copy(note = "合成备注：早餐\n已核对"))
        val pending = ingest(capture(Source.IMAGE, "合成图片文字", "gallery", instant + 180_000),
            bill(reliable = false, transactionId = null).copy(
                amountCents = null, direction = null, occurredAt = instant + 180_000,
                reason = "合成样例：信息不完整", timeExplicit = true))
        channels.set(ChannelSettings.Channel.OCR, true)
        channels.set(ChannelSettings.Channel.PAGE, true)
        channels.set(ChannelSettings.Channel.SMS, false)
        channels.set(ChannelSettings.Channel.NOTIFICATION, true)

        val decoded = decode(LedgerBackup.encode(store.all(), channels))
        // Local numeric IDs and duplicate links are deliberately not portable backup identities.
        assertEquals(listOf(pending, posted).map { it.copy(id = 0, duplicateId = null, wallet = RecordWalletLink(it.uid, WalletEligibility.RESTORE_EXCLUDED,accountReference=it.wallet?.accountUid?.let { id->WalletFileReference(id).json() }),walletEffects=emptyMap()) }, decoded.records)
        assertEquals(mapOf(ChannelSettings.Channel.OCR to true, ChannelSettings.Channel.PAGE to false, ChannelSettings.Channel.SMS to false,
            ChannelSettings.Channel.NOTIFICATION to false), decoded.channels)

        store.delete(posted.id)
        store.delete(pending.id)
        assertEquals(2, store.merge(decoded.records))
        assertEquals(setOf(posted.uid, pending.uid), store.all().map { it.uid }.toSet())
        assertEquals(1, store.pending().size)
        assertEquals(posted.note, store.all().single { it.uid == posted.uid }.note)
    }

    @Test
    fun invalidBackupIsCompletelyRejectedBeforeAnyLocalWrite() {
        val existing = ingest(capture(), bill())
        channels.set(ChannelSettings.Channel.OCR, true)
        val validNew = existing.copy(id = 0, uid = UUID.randomUUID().toString(), note = "应拒绝导入")
        val invalidNew = validNew.copy(uid = UUID.randomUUID().toString())
        val json = JSONObject(LedgerBackup.encode(listOf(validNew, invalidNew), channels))
        json.getJSONArray("records").getJSONObject(1).put("amount", -1)

        expectFailure {
            val validated = decode(json.toString())
            store.merge(validated.records)
            validated.channels.forEach { (channel, enabled) -> channels.set(channel, enabled) }
        }
        assertEquals(listOf(existing), store.all())
        assertFalse(channels.enabled(ChannelSettings.Channel.SMS))
        assertTrue(channels.enabled(ChannelSettings.Channel.OCR))
    }

    @Test
    fun repeatedBackupMergeSkipsUuidAndPreservesSubsequentLocalEdits() {
        val original = ingest(capture(), bill())
        val backup = decode(LedgerBackup.encode(store.all(), channels))
        val edited = store.saveEdited(original.copy(note = "导出后修改", bill = original.bill.copy(amountCents = 4567)))

        assertEquals(0, store.merge(backup.records))
        assertEquals(listOf(edited), store.all())
    }

    @Test
    fun mergeRollsBackEveryInsertedRowWhenALaterInsertFails() {
        val existing = ingest(capture(), bill())
        val firstImport = existing.copy(id = 0, uid = UUID.randomUUID().toString())
        val rejectedImport = existing.copy(id = 0, uid = UUID.randomUUID().toString())
        // A SQLite trigger simulates a real insert failure after the first row succeeds.
        store.writableDatabase.execSQL("""CREATE TRIGGER fail_test_import BEFORE INSERT ON records
            WHEN NEW.uid = '${rejectedImport.uid}'
            BEGIN SELECT RAISE(ABORT, 'synthetic test failure'); END""")

        expectFailure { store.merge(listOf(firstImport, rejectedImport)) }
        assertEquals(listOf(existing), store.all())
        store.writableDatabase.execSQL("DROP TRIGGER fail_test_import")
        assertEquals(2, store.merge(listOf(firstImport, rejectedImport)))
    }

    @Test
    fun eventIdentitySkipsReplaysButKeepsIdenticalTextFromDistinctEventsForReview() {
        val noId = bill(transactionId = null)
        val first = store.ingest(capture(), noId, "event-a") as IngestResult.Saved
        assertEquals(IngestResult.DuplicateSkipped, store.ingest(capture(), noId, "event-a"))
        val second = store.ingest(capture(), noId, "event-b") as IngestResult.Saved
        assertEquals(RecordState.REVIEW, second.record.state)
        assertEquals(first.record.id, second.record.duplicateId)
        assertEquals(2, store.all().size)
    }

    @Test
    fun backupRejectsFractionalCentsAndMalformedFieldTypes() {
        val record = ingest(capture(), bill())
        for (invalid in listOf<Any>(12.5, "1234", true)) {
            val json = JSONObject(LedgerBackup.encode(listOf(record), channels))
            json.getJSONArray("records").getJSONObject(0).put("amount", invalid)
            expectFailure { decode(json.toString()) }
        }
        val json = JSONObject(LedgerBackup.encode(listOf(record), channels))
        json.getJSONArray("records").getJSONObject(0).put("merchant", 123)
        expectFailure { decode(json.toString()) }
    }

    private fun capture(source: Source = Source.NOTIFICATION, text: String = "合成支付通知",
                        origin: String = "com.tencent.mm", receivedAt: Long = instant) =
        Capture(source, origin, text, receivedAt)

    private fun bill(reliable: Boolean = true, transactionId: String? = "SYNTHETIC-A") =
        ParsedBill(1234, Direction.EXPENSE, "合成测试商户", "餐饮", instant,
            transactionId, reliable, "", timeExplicit = false)

    private fun ingest(capture: Capture, bill: ParsedBill): LedgerRecord =
        (store.ingest(capture, bill) as IngestResult.Saved).record

    private fun decode(json: String): ValidatedBackup =
        json.byteInputStream(Charsets.UTF_8).use(LedgerBackup::decode)

    private fun expectFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Expected operation to fail", failure)
    }
}
