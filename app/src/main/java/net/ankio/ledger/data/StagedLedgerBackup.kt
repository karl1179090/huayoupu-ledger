package net.ankio.ledger.data

import net.ankio.ledger.core.RecordState
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Financial rows and immutable media links are fixed before the potentially slow ZIP write. */
internal class StagedLedgerBackup private constructor(
    val records:List<LedgerRecord>,val accounts:List<LedgerAccount>,val categories:List<CustomCategory>,val wallets:WalletSnapshot,
    val histories:Map<String,List<RecordCaptureHistory>>,val files:Map<String,File>,
    val historyAssets:Map<String,Map<Int,String>>,val iconAssets:Map<String,String>,private val directory:File,
):Closeable {
    override fun close() { directory.listFiles()?.forEach { it.delete() };directory.delete() }
    companion object {
        fun capture(store:LedgerStore,includeIcons:Boolean=true):StagedLedgerBackup=synchronized(store) {
            val directory=store.createBackupStagingDirectory();val db=store.readableDatabase;db.beginTransactionNonExclusive()
            try {
                val records=store.all();val accounts=store.accounts();val categories=store.customCategories();val wallets=store.walletSnapshot()
                val grouped=store.captureHistories();val histories=linkedMapOf<String,List<RecordCaptureHistory>>()
                val files=linkedMapOf<String,File>();val historyAssets=linkedMapOf<String,Map<Int,String>>();val icons=linkedMapOf<String,String>();var totalBytes=0L
                fun register(file:File):String {
                    val hash=file.name.removeSuffix(".bin");require(hash.matches(Regex("[0-9a-f]{64}")))
                    require(file.length() in 1..LedgerArchive.MAX_IMAGE_BYTES.toLong()) { "备份图片大小无效" }
                    if(files.putIfAbsent(hash,file)==null) {
                        totalBytes=Math.addExact(totalBytes,file.length())
                        require(files.size<=LedgerArchive.MAX_ATTACHMENTS && totalBytes<=LedgerArchive.MAX_TOTAL_BYTES) { "备份图片超过容量限制" }
                    }
                    return hash
                }
                for(record in records) {
                    var entries=grouped[record.id].orEmpty();var primary:File?=null
                    if(entries.isEmpty()) {
                        primary=store.stageBackupScreenshot(record.id,1,directory)
                        if(primary!=null) { val refund=record.bill.refund
                            entries=listOf(RecordCaptureHistory(1,record.capture.receivedAt,if(refund==null)"payment" else if(record.bill.amountCents==0L)"full_refund" else "partial_refund",
                                record.bill.amountCents,refund?.refundedCents ?: 0L,record.capture.text,true,"cumulative"))
                        }
                    }
                    val refs=linkedMapOf<Int,String>()
                    val staged=entries.map { entry->val file=if(primary!=null)primary else store.stageBackupScreenshot(record.id,entry.sequence,directory)
                        if(file!=null)refs[entry.sequence]=register(file)
                        entry.copy(hasScreenshot=file!=null)
                    }
                    if(staged.isNotEmpty())histories[record.uid]=staged
                    if(refs.isNotEmpty())historyAssets[record.uid]=refs
                    if(includeIcons)store.merchantIcon(record.id)?.let { image->
                        require(image.isNotEmpty() && image.size<=LedgerArchive.MAX_IMAGE_BYTES)
                        val hash=MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
                        val file=File(directory,"$hash.bin");if(!file.exists())file.outputStream().use { it.write(image) }
                        icons[record.uid]=register(file)
                    }
                }
                val result=StagedLedgerBackup(records,accounts,categories,wallets,histories,files,historyAssets,icons,directory)
                db.setTransactionSuccessful();result
            } catch(error:Throwable) {
                directory.listFiles()?.forEach { it.delete() };directory.delete();throw error
            } finally { db.endTransaction() }
        }
    }
}
