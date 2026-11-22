package net.ankio.ledger.data

import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.RecordState

internal data class RecognitionFeedback(
    val message: String, val recordId: Long? = null, val posted: Boolean = false, val editInPlace: Boolean = false,
)

internal data class RecognitionTaskResult(
    val status: String, val feedback: RecognitionFeedback, val needsFeedback: Boolean,
) {
    fun write(tasks: RecognitionTasks, taskId: Long) =
        tasks.update(taskId, status, feedback.message, feedback.recordId, replaceRecordLink = true)
}

/** Rebuild the task or an immediate prompt from the same submission fact and the live bill. */
object RecognitionCommitRecovery {
    internal const val REMOVED = "账单已移除，未新增记账"

    internal fun receipt(tasks: RecognitionTasks, ledger: LedgerStore, taskId: Long): LedgerStore.CommittedCapture? =
        tasks.operationKey(taskId)?.let(ledger::committedCapture)
            ?: tasks.paymentIdentity(taskId)?.let(ledger::committedPaymentEvent)

    internal fun project(effect: RecognitionCommitEffect?, record: LedgerRecord?): RecognitionTaskResult {
        val notify = effect?.needsFeedback == true
        val transferConflict = record?.state == RecordState.POSTED && record.wallet?.reviewCode?.startsWith("TRANSFER_") == true
        val review = record?.state == RecordState.REVIEW || transferConflict
        val status = when {
            review -> "REVIEW"
            record == null || !notify -> "DUPLICATE"
            record.bill.amountCents == null -> "DUPLICATE"
            else -> "SUCCEEDED"
        }
        val message = when {
            record == null && notify -> REMOVED
            record == null -> "已处理"
            transferConflict -> TransferRecognitionEnricher.message(record.wallet?.reviewCode)
            review && record.wallet?.reviewCode == "WALLET_REQUIRED" -> "请选择钱包后入账"
            review -> record.reason.ifBlank { "金额待核对，请打开账单" }
            !notify -> "已处理"
            effect == RecognitionCommitEffect.REFUND_UPDATED && record.bill.refund != null -> "退款已更新"
            effect == RecognitionCommitEffect.REFUND_UPDATED -> "已处理"
            record.bill.amountCents == null -> "账单未入账，请重试"
            else -> "已记账 ¥${Money.format(record.bill.amountCents!!)}" +
                if (record.bill.direction == Direction.TRANSFER) "（钱包转账）" else ""
        }
        val editInPlace = status == "SUCCEEDED" && effect == RecognitionCommitEffect.RECORDED &&
            record?.bill?.direction in setOf(Direction.EXPENSE, Direction.INCOME)
        return RecognitionTaskResult(status, RecognitionFeedback(message, record?.id, status == "SUCCEEDED", editInPlace), notify)
    }

    internal fun immediate(
        tasks: RecognitionTasks, ledger: LedgerStore, taskId: Long?, result: IngestResult,
    ): RecognitionFeedback? {
        if (result is IngestResult.Batch) {
            val batch = ledger.recognitionBatch("recognition:${result.result.operationUID}") ?: result.result
            return batchFeedback(ledger, batch)
        }
        val committed = taskId?.let { receipt(tasks, ledger, it) }
        val projected = when {
            committed != null -> project(committed.effect, committed.record)
            result is IngestResult.Saved -> project(result.effect, ledger.get(result.record.id))
            else -> null
        }
        if (projected != null) return projected.feedback.takeIf { projected.needsFeedback }
        return when (result) {
            IngestResult.Ignored -> RecognitionFeedback("未识别到金额，请重试")
            is IngestResult.Skipped -> RecognitionFeedback(result.message)
            else -> null
        }
    }

    private fun batchFeedback(ledger: LedgerStore, batch: RecognitionBatchResult): RecognitionFeedback? {
        val visibleItems = batch.items.filter { it.status in setOf("ADDED", "UPDATED", "REVIEW") }
        val current = visibleItems.map { item ->
            val effect = if (item.status == "UPDATED") RecognitionCommitEffect.REFUND_UPDATED else RecognitionCommitEffect.RECORDED
            project(effect, item.recordId?.let(ledger::get))
        }
        if (batch.complete && visibleItems.isEmpty() && batch.items.all { it.recordUID != null }) return null
        if (batch.complete && current.isNotEmpty() && current.all { it.feedback.recordId == null }) return RecognitionFeedback(REMOVED)
        val needsReview = current.filter { it.status == "REVIEW" }
        if (batch.complete && needsReview.size == 1) return needsReview.single().feedback
        if (batch.complete && visibleItems.size == 1 && visibleItems.single().status == "UPDATED")
            return current.single().feedback
        if (current.any { it.status != "SUCCEEDED" }) {
            val posted = visibleItems.zip(current).filter { (_, result) -> result.status == "SUCCEEDED" }
            val added = posted.count { (item, _) -> item.status == "ADDED" }
            val updated = posted.count { (item, _) -> item.status == "UPDATED" }
            return RecognitionFeedback(BatchCounts(added, batch.counts.skipped, needsReview.size, batch.counts.failed, updated).message)
        }
        return RecognitionFeedback(batch.message)
    }

    private fun recover(tasks: RecognitionTasks, ledger: LedgerStore, taskId: Long, needsReview: Boolean): Boolean {
        val operation = tasks.operationKey(taskId) ?: return false
        ledger.recognitionBatch(operation)?.let { batch ->
            if (batch.complete && (!needsReview || batch.taskStatus != "REVIEW"))
                tasks.update(taskId, batch.taskStatus, batch.message)
            return batch.complete
        }
        val committed = receipt(tasks, ledger, taskId) ?: return false
        if (!needsReview || committed.record?.id != tasks.recordId(taskId))
            project(committed.effect, committed.record).write(tasks, taskId)
        return true
    }

    fun recover(tasks: RecognitionTasks, ledger: LedgerStore, taskId: Long): Boolean =
        recover(tasks, ledger, taskId, needsReview = false)

    fun recoverAll(tasks: RecognitionTasks, ledger: LedgerStore) {
        val candidates = tasks.readableDatabase.rawQuery(
            "SELECT id,status FROM tasks WHERE status IN ('CAPTURING','QUEUED','RECOGNIZING','FAILED','CANCELLED','REVIEW') ORDER BY id",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to (cursor.getString(1) == "REVIEW")) } }
        candidates.forEach { (taskId, needsReview) -> recover(tasks, ledger, taskId, needsReview) }
    }

    /** A confirmed refund can redirect several tasks, each with its own submission effect. */
    internal fun reassignRefund(tasks: RecognitionTasks, ledger: LedgerStore, pendingId: Long, targetId: Long) {
        val linkedTasks = tasks.readableDatabase.rawQuery("SELECT id FROM tasks WHERE record_id=?", arrayOf(pendingId.toString()))
            .use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0)) } }
        for (id in linkedTasks) {
            val committed = receipt(tasks, ledger, id)
            project(committed?.effect, committed?.record ?: ledger.get(targetId)).write(tasks, id)
        }
    }
}
