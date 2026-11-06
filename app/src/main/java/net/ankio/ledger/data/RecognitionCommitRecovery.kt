package net.ankio.ledger.data

import net.ankio.ledger.core.Money
import net.ankio.ledger.core.RecordState

/** Rebuild task feedback from ledger.db after a crash between the two database commits. */
object RecognitionCommitRecovery {
    fun recover(tasks:RecognitionTasks,ledger:LedgerStore,taskId:Long):Boolean {
        val operation=tasks.operationKey(taskId) ?: return false
        val committed=ledger.committedCapture(operation)
            ?: tasks.paymentIdentity(taskId)?.let(ledger::committedPaymentEvent) ?: return false
        val record=committed.record
        val state=when {
            record==null->"DUPLICATE"
            record.state==RecordState.REVIEW->"REVIEW"
            else->"SUCCEEDED"
        }
        val message=when {
            record==null->"账单已移除，未新增记账"
            record.state==RecordState.REVIEW->record.reason.ifBlank { "金额待核对，请打开账单" }
            record.bill.refund!=null->"退款已更新"
            else->"已记账 ¥${Money.format(requireNotNull(record.bill.amountCents))}"
        }
        tasks.update(taskId,state,message,record?.id)
        return true
    }

    fun recoverAll(tasks:RecognitionTasks,ledger:LedgerStore) {
        val ids=tasks.readableDatabase.rawQuery(
            "SELECT id FROM tasks WHERE status IN ('CAPTURING','QUEUED','RECOGNIZING','FAILED','CANCELLED') ORDER BY id",null
        ).use { cursor->buildList { while(cursor.moveToNext())add(cursor.getLong(0)) } }
        ids.forEach { recover(tasks,ledger,it) }
    }
}
