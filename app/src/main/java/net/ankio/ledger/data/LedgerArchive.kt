package net.ankio.ledger.data

import android.graphics.BitmapFactory
import net.ankio.ledger.capture.ChannelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable full backup. Large image bytes are streamed one at a time, never Base64 in JSON. */
object LedgerArchive {
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 128 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_ZIP_BYTES = 544L * 1024 * 1024
    const val MAX_ATTACHMENTS = 100_000
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val attachmentPattern = Regex("images/([0-9a-f]{64})\\.bin")

    data class Summary(val records: Int, val attachments: Int, val attachmentBytes: Long)

    /** Call close after restore or cancellation. No database is changed during preparation. */
    class PreparedBackup internal constructor(
        internal val backup: ValidatedBackup,
        internal val directory: File?,
        internal val files: Map<String, File> = emptyMap(),
        internal val historyAssets: Map<String, Map<Int, String>> = emptyMap(),
        internal val iconAssets: Map<String, String> = emptyMap(),
        internal val taskAssets:Map<String,String> = emptyMap(),
        val legacy: Boolean = false,
    ) : Closeable {
        val recordCount get() = backup.records.size
        private var closed = false
        internal fun ensureOpen() = check(!closed) { "备份读取已结束，请重新选择文件" }
        override fun close() {
            if (closed) return
            closed = true
            // This directory contains only flat files created here, never archive-supplied paths.
            directory?.listFiles()?.forEach { it.delete() }
            directory?.delete()
        }
    }

    /** Ledger rows and media are fixed first; compression and destination I/O hold no ledger lock. */
    fun write(store:LedgerStore,channels:ChannelSettings,output:OutputStream):Summary=StagedLedgerBackup.capture(store).use { snapshot->
        val ledger=JSONObject(LedgerBackup.encode(snapshot.records,channels,snapshot.accounts,snapshot.categories,snapshot.wallets,snapshot.categoryDirectory,snapshot.classification,snapshot.recognitionBatches,snapshot.recognitionTasks,snapshot.recognitionReceipts,snapshot.captureEvents))
        val histories=JSONObject();val icons=JSONObject(snapshot.iconAssets)
        snapshot.histories.forEach { (uid,entries)->histories.put(uid,JSONArray(entries.map { entry->
            LedgerBackup.historyJson(entry).put("asset",snapshot.historyAssets[uid]?.get(entry.sequence) ?: JSONObject.NULL)
        })) }
        for(i in 0 until snapshot.recognitionTasks.length()) {
            val row=snapshot.recognitionTasks.getJSONObject(i)
            row.put("image",JSONObject.NULL).put("asset",snapshot.taskAssets[row.getString("operation_uid")] ?: JSONObject.NULL)
        }
        ledger.put("recognitionTasks",snapshot.recognitionTasks)
        ledger.put("histories",histories)
        val manifest=JSONObject().put("format","simple-ledger-archive").put("version",1).put("ledger",ledger).put("merchantIcons",icons).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size<=MAX_MANIFEST_BYTES) { "备份清单超过容量限制" }
        snapshot.files.values.forEach(::validateImage)
        var total=0L
        ZipOutputStream(object:FilterOutputStream(output) { override fun close() { flush() } }).use { zip->
            for((expected,file) in snapshot.files) {
                zip.putNextEntry(ZipEntry("images/$expected.bin"));val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
                file.inputStream().use { input->val buffer=ByteArray(8192);while(true) {
                    val count=input.read(buffer);if(count<0)break;bytes+=count;total+=count
                    require(bytes<=MAX_IMAGE_BYTES && total+manifest.size<=MAX_TOTAL_BYTES) { "备份图片超过容量限制" }
                    digest.update(buffer,0,count);zip.write(buffer,0,count)
                } }
                require(bytes==file.length() && hex(digest.digest())==expected) { "备份图片已损坏" }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry()
        }
        Summary(snapshot.records.size,snapshot.files.size,total)
    }

    /** ZIP is staged and checked before restore; old JSON backups keep their original validation. */
    fun read(input: InputStream, cacheDir: File): PreparedBackup {
        val stream = PushbackInputStream(BufferedInputStream(input), 4)
        val prefix = ByteArray(4)
        var count = 0
        while (count < prefix.size) {
            val n = stream.read(prefix, count, prefix.size - count)
            if (n == -1) break
            count += n
        }
        stream.unread(prefix, 0, count)
        if (count < 4 || !prefix.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
            return stream.use { PreparedBackup(LedgerBackup.decode(it), null, legacy = true) }
        }
        require(cacheDir.isDirectory || cacheDir.mkdirs()) { "无法创建备份缓存" }
        val directory = File.createTempFile("ledger-archive-", ".tmp", cacheDir)
        check(directory.delete() && directory.mkdir()) { "无法创建备份缓存" }
        val files = linkedMapOf<String, File>()
        try {
            var total = 0L
            var manifest: JSONObject? = null
            val names = mutableSetOf<String>()
            // ZipFile requires a complete central directory; ZipInputStream alone accepts
            // truncation after the last local entry and cannot prove a complete backup.
            val archive = File(directory, "archive.zip")
            stream.use { source -> FileOutputStream(archive).use { destination ->
                val buffer = ByteArray(8192)
                var compressed = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n == -1) break
                    compressed += n
                    require(compressed <= MAX_ZIP_BYTES) { "备份文件超过容量限制" }
                    destination.write(buffer, 0, n)
                }
            } }
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(!entry.isDirectory && names.add(entry.name) && names.size <= MAX_ATTACHMENTS + 1) { "备份包含重复或无效文件" }
                    val match = attachmentPattern.matchEntire(entry.name)
                    require(entry.name == "manifest.json" || match != null) { "备份包含不支持的文件" }
                    val limit = if (match == null) MAX_MANIFEST_BYTES else MAX_IMAGE_BYTES
                    require(entry.size < 0 || entry.size <= limit) { "备份文件超过容量限制" }
                    val target = File(directory, if (match == null) "manifest.json" else match.groupValues[1])
                    val digest = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { source -> FileOutputStream(target).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = source.read(buffer)
                            if (n == -1) break
                            size += n
                            total += n
                            require(size <= limit && total <= MAX_TOTAL_BYTES) { "备份解压内容超过容量限制" }
                            digest.update(buffer, 0, n)
                            crc.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    } }
                    require(size == entry.size && crc.value == entry.crc) { "备份文件已损坏" }
                    if (match == null) manifest = JSONObject(target.readText(Charsets.UTF_8))
                    else {
                        val hash = match.groupValues[1]
                        require(size > 0 && hex(digest.digest()) == hash) { "账单图片已损坏" }
                        validateImage(target)
                        files[hash] = target
                    }
                }
            }
            archive.delete()
            val root = requireNotNull(manifest) { "备份缺少账本清单" }
            require(root.get("format") == "simple-ledger-archive" && root.get("version") == 1) { "备份格式或版本不支持" }
            val ledger = root.getJSONObject("ledger")
            // ZIP metadata must not smuggle a second, unbounded Base64 image representation.
            val historyAssets = linkedMapOf<String, Map<Int, String>>()
            val histories = ledger.optJSONObject("histories") ?: JSONObject()
            for (uid in histories.keys()) {
                val entries = histories.getJSONArray(uid)
                val references = linkedMapOf<Int, String>()
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    require(e.isNull("image")) { "备份截图格式无效" }
                    if (!e.isNull("asset")) references[e.getInt("sequence")] = checkedReference(e.get("asset"), files)
                }
                historyAssets[uid] = references
            }
            val backup = LedgerBackup.decodeMetadata(ledger)
            for (record in backup.records) backup.histories[record.uid]?.let { LedgerBackup.validateHistory(record, it) }
            val uids = backup.records.map { it.uid }.toSet()
            val iconAssets = linkedMapOf<String, String>()
            val icons = root.getJSONObject("merchantIcons")
            require(icons.length() <= uids.size) { "商户图标数量无效" }
            for (uid in icons.keys()) {
                require(uid in uids) { "商户图标没有关联账单" }
                iconAssets[uid] = checkedReference(icons.get(uid), files)
            }
            val taskAssets=linkedMapOf<String,String>()
            for(i in 0 until backup.recognitionTasks.length()) {
                val row=backup.recognitionTasks.getJSONObject(i);require(row.isNull("image"))
                if(!row.isNull("imageHash")) {
                    val hash=checkedReference(row.get("asset"),files)
                    require(hash==row.getString("imageHash"))
                    taskAssets[row.getString("operation_uid")]=hash
                } else require(row.isNull("asset"))
            }
            val references = taskAssets.values.toSet() + historyAssets.values.flatMap { it.values }.toSet() + iconAssets.values
            require(references == files.keys) { "备份包含未关联的图片" }
            return PreparedBackup(backup, directory, files, historyAssets, iconAssets,taskAssets)
        } catch (error: Throwable) {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            throw error
        }
    }

    /** Repeated imports are idempotent. Existing edits, evidence and capture permissions survive. */
    fun restore(store: LedgerStore, prepared: PreparedBackup): Int = restoreDetailed(store,prepared).imported
    fun restoreDetailed(store:LedgerStore,prepared:PreparedBackup):RestoreSummary {
        prepared.ensureOpen()
        if(prepared.legacy)return LedgerBackup.restoreDetailed(store,prepared.backup)
        // Recheck all staged media before any ledger mutation, including changes since ZIP preflight.
        prepared.files.forEach { (hash,file)->attachmentBytes(prepared,hash);validateImage(file) }
        return LedgerBackup.restoreDetailed(store,prepared.backup,taskImageProvider={operation->prepared.taskAssets[operation]?.let { attachmentBytes(prepared,it) }}) { record->
            prepared.backup.histories[record.uid]?.let { entries->store.restoreHistory(record.id,entries) { restored->
                prepared.historyAssets[record.uid]?.get(restored.entry.sequence)?.let { attachmentBytes(prepared,it) }
            } }
            prepared.iconAssets[record.uid]?.let { store.restoreMerchantIcon(record.id,attachmentBytes(prepared,it)) }
        }
    }

    private fun attachmentBytes(prepared: PreparedBackup, assetHash: String): ByteArray {
        val file = prepared.files.getValue(assetHash)
        require(file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "备份图片已损坏" }
        return file.readBytes().also { require(hash(it) == assetHash) { "备份图片已损坏" } }
    }

    private fun checkedReference(value: Any, files: Map<String, File>): String {
        val hash = value as? String ?: error("图片引用格式错误")
        require(hashPattern.matches(hash) && hash in files) { "备份缺少关联图片" }
        return hash
    }

    private fun validateImage(file:File) = BackupMediaValidator.validate(file)

    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
