package net.ankio.ledger.data

import android.content.ContentValues
import net.ankio.ledger.core.*
import java.util.UUID

/** Snapshot restore copies absolute balances and facts; it never invokes a target-to-balance mutation. */
internal object BackupRestore {
    fun restore(store:LedgerStore,backup:ValidatedBackup):RestoreSummary=synchronized(store) {
        LedgerBackup.preflight(backup)
        require(backup.wallets!=null || backup.records.none { it.bill.direction==Direction.TRANSFER || it.transfer!=null }) { "转账备份缺少钱包扩展信息" }
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val existingRows=store.importIdentityRecords();val existingUids=existingRows.map { it.uid }.toSet()
            fun identities(record:LedgerRecord)=(record.identities+TransactionIdentities.from(record.capture,record.bill)).toSet()
            val knownIdentities=existingRows.flatMap(::identities).toMutableSet()
            val recordMapping=linkedMapOf<String,String?>();val knownRows=existingRows.toMutableList()
            val accepted=mutableListOf<LedgerRecord>();val skipped=mutableSetOf<String>();var identityConflicts=0
            backup.records.forEach { record->val ids=identities(record)
                if(record.uid in existingUids || ids.any { it in knownIdentities }) {
                    val matches=knownRows.filter { it.uid==record.uid || identities(it).any { identity -> identity in ids } }
                    recordMapping[record.uid]=matches.firstOrNull { it.uid==record.uid }?.uid ?: matches.singleOrNull()?.uid
                    skipped.add(record.uid);if(record.uid !in existingUids)identityConflicts++
                } else { accepted.add(record);knownRows.add(record);knownIdentities.addAll(ids);recordMapping[record.uid]=record.uid }
            }
            val localAccounts=store.accounts().associateBy { it.uid };val newAccounts=backup.accounts.map { it.uid }.toSet()-localAccounts.keys
            val empty=existingRows.isEmpty() && localAccounts.isEmpty() && db.rawQuery("SELECT COUNT(*) FROM wallet_movements",null).use { it.moveToFirst();it.getLong(0)==0L } &&
                store.walletSettings().let { it.defaultAccountUid==null && it.appBindings.isEmpty() }
            val snapshot=backup.wallets;val links=snapshot?.links?.associateBy { it.recordUid }.orEmpty();val transfers=snapshot?.transfers?.associateBy { it.recordUid }.orEmpty()
            fun references(uid:String)=buildSet {
                links[uid]?.accountUid?.let(::add);transfers[uid]?.let { d->d.fromAccountUid?.let(::add);d.toAccountUid?.let(::add) }
                addAll(snapshot?.effects?.get(uid).orEmpty().keys)
            }
            val conflictedAccounts=skipped.flatMap(::references).filter { it in newAccounts }.toSet()
            val importedAccounts=backup.accounts.associateBy { it.uid }
            accepted.flatMap { references(it.uid) }.forEach { uid->
                val local=localAccounts[uid];val incoming=importedAccounts[uid]
                if(local!=null && incoming!=null && local.type!=incoming.type)throw WalletException("RESTORE_ACCOUNT_CONFLICT","已有同标识账户类型冲突，请先处理钱包")
            }
            val imported=store.merge(accepted,backup.accounts,backup.categories,backup.categoryDirectory,backup.recordCategoryIds);var excluded=0
            AtomicBackupTransaction.checkpoint("ledger")
            backup.classification?.let { ClassificationStorage.restore(db,it,CategoryDirectory.merge(db,backup.categoryDirectory)) }
            accepted.forEach { record->
                val original=links[record.uid]
                if(original==null) {
                    WalletAccounting.saveLink(db,RecordWalletLink(record.uid,WalletEligibility.LEGACY_EXCLUDED,
                        paymentEvidence=record.bill.paymentMethod?.let { WalletBackupCodec.evidenceJson(it).toString() }))
                    excluded++
                } else {
                    val refs=references(record.uid)
                    val canKeep=empty || refs.all { it in newAccounts && it !in conflictedAccounts }
                    val eligibility=if(original.eligibility==WalletEligibility.ACTIVE && !canKeep)WalletEligibility.RESTORE_EXCLUDED else original.eligibility
                    val effects=if(eligibility==WalletEligibility.ACTIVE)snapshot?.effects?.get(record.uid).orEmpty() else emptyMap()
                    WalletAccounting.saveEffects(db,record.uid,effects)
                    WalletAccounting.saveLink(db,WalletAccounting.project(original.copy(eligibility=eligibility),effects))
                    transfers[record.uid]?.let { TransferAccounting.save(db,it) }
                    if(eligibility!=WalletEligibility.ACTIVE)excluded++
                }
            }
            if(snapshot!=null) {
                if(empty) {
                    db.execSQL("UPDATE wallet_settings SET default_account_uid=?,revision=? WHERE singleton=1",arrayOf<Any?>(snapshot.settings.defaultAccountUid,snapshot.settings.revision))
                    snapshot.settings.appBindings.forEach { (name,uid)->db.insertOrThrow("app_wallet_bindings",null,ContentValues().apply { put("package_name",name);put("account_uid",uid) }) }
                }
                snapshot.movements.filter { it.accountUid in newAccounts }.forEach { m->db.insertOrThrow("wallet_movements",null,ContentValues().apply {
                    put("movement_uid",m.movementUid);put("operation_uid",m.operationUid);put("account_uid",m.accountUid);put("record_uid",m.recordUid)
                    put("kind",m.kind);put("delta_cents",m.deltaCents);put("balance_before",m.balanceBefore);put("balance_after",m.balanceAfter);put("created_at",m.createdAt);put("summary",m.summary)
                }) }
            }
            backup.accounts.filter { it.uid in newAccounts }.forEach { account->db.insertOrThrow("wallet_movements",null,ContentValues().apply {
                put("movement_uid",UUID.randomUUID().toString());put("operation_uid",UUID.randomUUID().toString());put("account_uid",account.uid)
                putNull("record_uid");put("kind","RESTORE_SNAPSHOT");put("delta_cents",0L);put("balance_before",account.amountCents);put("balance_after",account.amountCents)
                put("created_at",System.currentTimeMillis());put("summary","恢复钱包快照")
            }) }
            var aliasConflicts=0
            if(snapshot!=null) {
                val localAliases=store.walletImportAliases()
                snapshot.aliases.forEach { (source,target)->
                    if(localAliases[source]?.let { it!=target }==true || source in localAccounts && source!=target)aliasConflicts++
                    else store.saveWalletImportAlias(source,target)
                }
                ImportAuditStore.restore(db,snapshot.audits)
            }
            accepted.forEach { record -> backup.recordDuplicates[record.uid]?.let { duplicate ->
                val uid=recordMapping[duplicate] ?: duplicate
                val target=db.rawQuery("SELECT id FROM records WHERE uid=?",arrayOf(uid)).use { if(it.moveToFirst())it.getLong(0) else null }
                db.update("records",ContentValues().apply { put("duplicate_id",target) },"uid=?",arrayOf(record.uid))
            } }
            RecognitionReceiptBackup.restore(db,backup.recognitionReceipts,backup.captureEvents,recordMapping)
            RecognitionBatchStore.restore(store,backup.recognitionBatches,recordMapping,CategoryDirectory.merge(db,backup.categoryDirectory))
            db.setTransactionSuccessful()
            RestoreSummary(imported,excluded,backup.accounts.count { it.uid in localAccounts },newAccounts.size,identityConflicts,aliasConflicts,skipped.size).also { it.recordMapping=recordMapping }
        } finally { db.endTransaction() }
    }
}
