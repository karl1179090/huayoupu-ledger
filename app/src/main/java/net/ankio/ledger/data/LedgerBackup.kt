package net.ankio.ledger.data

import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

data class ValidatedBackup(val records: List<LedgerRecord>, val channels: Map<ChannelSettings.Channel, Boolean>,
    val accounts: List<LedgerAccount> = emptyList(), val categories: List<CustomCategory> = emptyList(),
    val histories:Map<String,List<RestoredCaptureHistory>> = emptyMap(),
    val wallets:WalletSnapshot? = null, val metadataVersion:Int = 3,
    val categoryDirectory:List<LedgerCategory> = emptyList(), val recordCategoryIds:Map<String,String> = emptyMap())

object LedgerBackup {
    const val MAX_BYTES = 16 * 1024 * 1024

    /** Shared by archive preflight and database restore; no database mutation is needed. */
    internal fun validateHistory(record:LedgerRecord, entries:List<RestoredCaptureHistory>) {
        require(entries.map { it.entry.sequence } == (1..entries.size).toList()) { "识别记录序号不连续" }
        var previousRefund=0L
        val ids=mutableSetOf<String>()
        val modes=mutableSetOf<String>()
        entries.forEachIndexed { index, restored ->
            val entry=restored.entry
            require(entry.at>0 && entry.rawText.length<=32_768 && (restored.image==null || restored.image.isNotEmpty() && restored.image.size<=5*1024*1024))
            require(entry.amountCents==null || entry.amountCents in 0..Money.MAX_CENTS)
            require(entry.refundedCents>=previousRefund)
            if(index==0)require(entry.kind in setOf("payment","refund_pending","partial_refund","full_refund"))
            if(index==0 && entry.kind in setOf("payment","refund_pending")) Unit
            else if(entry.kind in setOf("payment_supplement","refund_supplement","identity_conflict","transfer_supplement","transfer_conflict")) {
                require(entry.refundedCents==previousRefund && entry.refundId==null && entry.refundAmountCents==null) { "补充截图不能改变退款金额" }
                if(previousRefund>0) {
                    val summary=requireNotNull(record.bill.refund)
                    require(entry.amountCents==summary.originalAmountCents-previousRefund) { "补充截图净额不一致" }
                }
            } else {
                val summary=requireNotNull(record.bill.refund)
                require(entry.kind in setOf("partial_refund","full_refund") && entry.amountCents==summary.originalAmountCents-entry.refundedCents)
                require(entry.refundedCents<=summary.refundedCents && entry.refundMode in setOf("single","cumulative"))
                if(index==0)require(entry.refundMode=="cumulative") { "首次入账需要完整累计退款信息" }
                modes.add(entry.refundMode);require(modes.size==1) { "退款口径冲突" }
                if(entry.refundMode=="single") {
                    require(!entry.refundId.isNullOrBlank() && ids.add(entry.refundId))
                    require(entry.refundAmountCents==entry.refundedCents-previousRefund && entry.refundAmountCents!!>0)
                }
            }
            previousRefund=entry.refundedCents
        }
        if(entries.size>1 || entries.firstOrNull()?.entry?.kind in setOf("partial_refund","full_refund"))
            require(previousRefund==(record.bill.refund?.refundedCents ?: 0L)) { "退款历史与汇总不一致" }
    }
    fun encode(store:LedgerStore,channels:ChannelSettings):String = StagedLedgerBackup.capture(store,includeIcons=false).use { snapshot->
        val root=JSONObject(encode(snapshot.records,channels,snapshot.accounts,snapshot.categories,snapshot.wallets,snapshot.categoryDirectory))
        val histories=JSONObject();var imageBytes=0L
        snapshot.records.forEach { record->
            val entries=JSONArray()
            snapshot.histories[record.uid].orEmpty().forEach { entry->
                val image=snapshot.historyAssets[record.uid]?.get(entry.sequence)?.let { hash->
                    snapshot.files.getValue(hash).readBytes().also { require(PortableCsv.digest(it)==hash) { "备份截图已损坏" };BackupMediaValidator.validate(it) }
                }
                imageBytes+=image?.size ?: 0
                require(imageBytes*4/3<MAX_BYTES) { "截图超过备份容量，请使用完整 ZIP 备份" }
                entries.put(historyJson(entry).put("image",image?.let { android.util.Base64.encodeToString(it,android.util.Base64.NO_WRAP) } ?: JSONObject.NULL))
            }
            if(entries.length()>0)histories.put(record.uid,entries)
        }
        root.put("histories",histories).toString().also { require(it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份及截图超过 16 MB，请使用完整 ZIP" } }
    }

    fun restore(store:LedgerStore,backup:ValidatedBackup,restoreMedia:(LedgerRecord)->Unit={ record ->
        backup.histories[record.uid]?.let { store.restoreHistory(record.id,it) }
    }):Int=restoreDetailed(store,backup,restoreMedia).imported

    /** Only newly imported UUIDs receive history; restoring must not overwrite later local edits. */
    fun restoreDetailed(store:LedgerStore,backup:ValidatedBackup,restoreMedia:(LedgerRecord)->Unit={ record ->
        backup.histories[record.uid]?.let { store.restoreHistory(record.id,it) }
    }):RestoreSummary=synchronized(store) {
        preflight(backup)
        val old=store.activeRecordIdsByUid()
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            val count=store.restoreBackupDetailed(backup)
            for((uid,id) in store.activeRecordIdsByUid())if(uid !in old)restoreMedia(requireNotNull(store.get(id)))
            db.setTransactionSuccessful();count
        } catch(error:Throwable) {
            // ScreenshotStore uses a separate database. Release all imported owners while
            // uncommitted history rows still identify them. Financial rows roll back with
            // this transaction; deleting a restored bill could overflow a calibrated balance.
            runCatching {
                store.releaseImportedMedia(store.activeRecordIdsByUid().filterKeys { it !in old }.values)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally { db.endTransaction() }
    }

    internal fun preflight(backup:ValidatedBackup) {
        val records=backup.records.associateBy { it.uid };require(records.size==backup.records.size)
        preflightCategories(backup)
        backup.records.forEach(WalletBackupCodec::validateRecord)
        require(backup.histories.keys.all { it in records }) { "截图没有关联账单" }
        backup.histories.forEach { (uid,entries)->validateHistory(records.getValue(uid),entries);entries.forEach { it.image?.let(BackupMediaValidator::validate) } }
        backup.wallets?.let { WalletBackupCodec.validate(backup.records,backup.accounts,it) }
    }

    private fun preflightCategories(backup:ValidatedBackup) {
        val records=backup.records.associateBy { it.uid }
        CategoryDirectory.validateSnapshot(backup.categoryDirectory)
        val categories=backup.categoryDirectory.associateBy { it.id }
        if (categories.isNotEmpty()) backup.records.filter { it.bill.direction in setOf(Direction.EXPENSE,Direction.INCOME) }.forEach {
            require(it.uid in backup.recordCategoryIds) { "账单缺少稳定分类标识" }
        }
        backup.recordCategoryIds.forEach { (uid,id) ->
            val record=requireNotNull(records[uid]) { "分类没有关联账单" }
            val category=requireNotNull(categories[id]) { "账单分类不存在" }
            require(category.direction==record.bill.direction && category.name==record.bill.category) { "账单分类方向或名称不一致" }
        }
    }

    internal fun historyJson(e:RecordCaptureHistory)=JSONObject().put("sequence",e.sequence).put("at",e.at)
        .put("kind",e.kind).put("amountCents",e.amountCents?:JSONObject.NULL).put("refundedCents",e.refundedCents)
        .put("rawText",e.rawText).put("refundMode",e.refundMode).put("refundId",e.refundId?:JSONObject.NULL)
        .put("refundAmountCents",e.refundAmountCents?:JSONObject.NULL).put("image",JSONObject.NULL)

    fun encode(records: List<LedgerRecord>, channels: ChannelSettings,
               accounts: List<LedgerAccount> = emptyList(), categories: List<CustomCategory> = emptyList(),wallets:WalletSnapshot?=null,
               categoryDirectory:List<LedgerCategory> = emptyList()): String {
        CategoryDirectory.validateSnapshot(categoryDirectory)
        val categoryIndex=categoryDirectory.associateBy { it.direction to it.name }
        val settings = JSONObject()
        ChannelSettings.Channel.entries.forEach { settings.put(it.key, channels.enabled(it)) }
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().apply {
            put("uid", r.uid); put("source", r.capture.source.name); put("origin", r.capture.origin)
            put("text", r.capture.text); put("receivedAt", r.capture.receivedAt)
            put("amount", r.bill.amountCents ?: JSONObject.NULL)
            put("direction", r.bill.direction?.name ?: JSONObject.NULL)
            put("merchant", r.bill.merchant); put("category", r.bill.category)
            put("categoryId",categoryIndex[r.bill.direction to r.bill.category]?.id ?: JSONObject.NULL)
            put("occurredAt", r.bill.occurredAt); put("transactionId", r.bill.transactionId ?: JSONObject.NULL)
            put("reliable", r.bill.reliable); put("timeExplicit", r.bill.timeExplicit)
            put("state", r.state.name); put("reason", r.reason)
            put("note", r.note)
            put("identities",JSONArray(TransactionIdentities.encode(r.identities)))
            r.bill.paymentMethod?.let { put("paymentMethod",WalletBackupCodec.evidenceJson(it)) }
            r.bill.standaloneRefund?.let { put("standaloneRefund",WalletBackupCodec.standaloneJson(it)) }
            r.bill.receipt?.let { put("receipt",ReceiptEvidenceCodec.json(it)) }
            r.bill.refund?.let { refund -> put("refund",JSONObject().put("provider",refund.provider)
                .put("originalAmountCents",refund.originalAmountCents).put("refundedCents",refund.refundedCents)
                .put("mode",refund.mode).put("refundId",refund.refundId?:JSONObject.NULL)) }
        }) }
        val accountArray = JSONArray()
        accounts.forEach { account ->
            account.validate()
            accountArray.put(JSONObject().put("uid", account.uid).put("name", account.name)
                .put("description", account.description).put("amountCents", account.amountCents)
                .put("kind", account.kind).put("type", account.type.name)
                .put("bankCode",account.bankCode?:JSONObject.NULL).put("cardLast4",account.cardLast4?:JSONObject.NULL)
                .put("archived",account.archived).put("revision",account.revision))
        }
        val custom = JSONObject()
        listOf(Direction.EXPENSE,Direction.INCOME).forEach { direction ->
            val names = JSONArray()
            categories.filter { it.direction == direction }.forEach { it.validate(); names.put(it.name) }
            custom.put(if (direction == Direction.EXPENSE) "expense" else "income", names)
        }
        // This overload has no committed balance snapshot, so preserve only excluded history.
        val accountIds=accounts.map { it.uid }.toSet()
        val snapshot=wallets ?: WalletSnapshot(WalletSettings(),records.map { record->
            val old=record.wallet
            val reference=old?.accountReference ?: old?.accountUid?.takeIf { it !in accountIds }?.let { WalletFileReference(it).json() }
            RecordWalletLink(record.uid,old?.eligibility?.takeIf { it!=WalletEligibility.ACTIVE } ?: WalletEligibility.RESTORE_EXCLUDED,
                accountUid=old?.accountUid?.takeIf { it in accountIds },paymentEvidence=record.bill.paymentMethod?.let { WalletBackupCodec.evidenceJson(it).toString() },accountReference=reference)
        },emptyList(),records.mapNotNull { record->record.transfer?.let { d->d.copy(
            fromAccountUid=d.fromAccountUid?.takeIf { it in accountIds },toAccountUid=d.toAccountUid?.takeIf { it in accountIds },
            fromReference=d.fromReference ?: d.fromAccountUid?.let { WalletFileReference(it).json() },
            toReference=d.toReference ?: d.toAccountUid?.let { WalletFileReference(it).json() },
            recognitionEvidence=d.recognitionEvidence ?: record.bill.transfer?.let(TransferEvidenceCodec::encode)) } },records.associate { it.uid to emptyMap() })
        WalletBackupCodec.validate(records,accounts,snapshot)
        return JSONObject().put("format", "simple-ledger").put("version", 7).put("wallets",WalletBackupCodec.encode(snapshot))
            .put("channels", settings).put("records", array).put("accounts", accountArray)
            .put("categoryDirectory",JSONArray().apply { categoryDirectory.forEach { put(JSONObject().put("id",it.id).put("name",it.name)
                .put("direction",it.direction.name).put("icon",it.icon).put("order",it.order).put("isFallback",it.isFallback)) } })
            .put("customCategories", custom).toString(2)
    }

    fun decode(stream: InputStream): ValidatedBackup {
        val buffer = ByteArray(8192)
        val collected = ByteArrayOutputStream()
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(collected.size() + count <= MAX_BYTES) { "备份文件超过 16 MB" }
            collected.write(buffer, 0, count)
        }
        val bytes = collected.toByteArray()
        return decodeMetadata(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    /** Reuse the legacy schema validation for ZIP metadata, whose image bytes live separately. */
    internal fun decodeMetadata(root: JSONObject): ValidatedBackup {
        val version = root.get("version")
        require(root.get("format") == "simple-ledger" && version is Int && version in 1..7) { "备份格式或版本不支持" }
        val array = root.getJSONArray("records")
        require(array.length() <= 100_000) { "备份记录过多" }
        val uids = mutableSetOf<String>()
        val records = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            fun text(key: String, max: Int = 1024): String = ((obj.get(key) as? String)
                ?: error("字段类型错误：$key")).also { require(it.length <= max) { "字段过长：$key" } }
            fun integer(key: String): Long {
                val value = obj.get(key)
                require(value is Int || value is Long) { "字段必须是整数：$key" }
                return (value as Number).toLong()
            }
            fun positiveTime(key: String) = integer(key).also { require(it in 1..4_102_444_800_000L) { "时间格式错误" } }
            fun bool(key: String) = (obj.get(key) as? Boolean) ?: error("开关格式错误：$key")
            val uid = text("uid", 36)
            require(UUID.fromString(uid).toString() == uid && uids.add(uid)) { "账单标识错误或重复" }
            val amount = if (obj.isNull("amount")) null else integer("amount")
            val refund=obj.optJSONObject("refund")?.let { r ->
                fun cents(key:String):Long { val v=r.get(key);require(v is Long || v is Int);return (v as Number).toLong() }
                RefundInfo(r.getString("provider"),cents("originalAmountCents"),cents("refundedCents"),
                    r.optString("mode","cumulative"),if(r.isNull("refundId"))null else r.getString("refundId")).also {
                    val review=obj.optString("state")==RecordState.REVIEW.name
                    require(it.provider in setOf("wechat","alipay") && it.originalAmountCents in (if(review)0L else 1L)..Money.MAX_CENTS &&
                        it.refundedCents in 0..(if(review)Money.MAX_CENTS else it.originalAmountCents) && it.mode in setOf("cumulative","single")) { "退款信息无效" }
                    require(review || it.mode!="single" || !it.refundId.isNullOrBlank()) { "退款单号缺失" }
                    require((it.refundId?.length?:0)<=160) { "退款单号过长" }
                }
            }
            require(amount == null || amount in 1..Money.MAX_CENTS || amount==0L && (obj.optString("state")==RecordState.REVIEW.name || refund!=null && refund.refundedCents==refund.originalAmountCents)) { "账单金额无效" }
            val direction = if (obj.isNull("direction")) null else Direction.valueOf(text("direction"))
            require(version>=5 || direction!=Direction.TRANSFER) { "旧版备份不支持转账类型" }
            val state = RecordState.valueOf(text("state"))
            if(refund!=null)require(direction!=Direction.TRANSFER && (state!=RecordState.POSTED || direction==Direction.EXPENSE && amount==refund.originalAmountCents-refund.refundedCents)) { "退款净额不一致" }
            require(state != RecordState.POSTED || amount != null && direction != null) { "已入账记录缺少金额或收支类型" }
            LedgerRecord(uid = uid,
                capture = Capture(Source.valueOf(text("source")), text("origin"), text("text", 32_768), positiveTime("receivedAt")),
                bill = ParsedBill(amount, direction, text("merchant"), text("category"), positiveTime("occurredAt"),
                    if (obj.isNull("transactionId")) null else text("transactionId"), bool("reliable"), text("reason"), bool("timeExplicit"),refund,
                    if(obj.isNull("paymentMethod"))null else WalletBackupCodec.evidence(obj.getJSONObject("paymentMethod")),
                    if(obj.isNull("standaloneRefund"))null else WalletBackupCodec.standalone(obj.getJSONObject("standaloneRefund")),
                    receipt=if(obj.isNull("receipt"))null else ReceiptEvidenceCodec.decode(obj.getJSONObject("receipt"))),
                state = state, reason = text("reason"), note = text("note", 500),
                identities=obj.optJSONArray("identities")?.let { TransactionIdentities.decode(it.toString()) } ?: emptyList())
        }
        val settings = root.getJSONObject("channels")
        val channels = ChannelSettings.Channel.entries.associateWith { channel ->
            if(channel!=ChannelSettings.Channel.OCR) false
            else (settings.get(channel.key) as? Boolean) ?: error("渠道设置格式错误")
        }
        val accounts = if (version == 1) emptyList() else {
            val arrayOfAccounts = root.getJSONArray("accounts")
            require(arrayOfAccounts.length() <= 100_000) { "备份账户过多" }
            val accountUids = mutableSetOf<String>()
            (0 until arrayOfAccounts.length()).map { index ->
                val obj = arrayOfAccounts.getJSONObject(index)
                fun text(key: String, max: Int): String = ((obj.get(key) as? String)
                    ?: error("账户字段类型错误：$key")).also { require(it.length <= max) { "账户字段过长：$key" } }
                val amount = obj.get("amountCents")
                require(amount is Int || amount is Long) { "账户余额必须为整数分" }
                val account = LedgerAccount(uid = text("uid", 36), name = text("name", 40),
                    description = text("description", 80), amountCents = (amount as Number).toLong(),
                    kind = text("kind", 16), type = AccountType.valueOf(text("type", 16)),
                    bankCode=if(version>=4 && !obj.isNull("bankCode"))text("bankCode",32) else AccountBanks.codeForKind(text("kind",16)),
                    cardLast4=if(version>=4 && !obj.isNull("cardLast4"))text("cardLast4",4) else null,
                    archived=if(version>=4)(obj.get("archived") as? Boolean ?: error("归档状态格式错误")) else false,
                    revision=if(version>=4) { val revision=obj.get("revision");require(revision is Int || revision is Long);(revision as Number).toLong() } else 0)
                account.validate()
                require(version>=4 || account.amountCents>=0) { "旧版备份账户余额不能为负" }
                require(accountUids.add(account.uid)) { "账户标识重复" }
                account
            }
        }
        val categories = if (version == 1) emptyList() else {
            val custom = root.getJSONObject("customCategories")
            listOf(Direction.EXPENSE,Direction.INCOME).flatMap { direction ->
                val key = if (direction == Direction.EXPENSE) "expense" else "income"
                val names = custom.getJSONArray(key)
                require(names.length() <= 1_000) { "备份分类过多" }
                val unique = mutableSetOf<String>()
                (0 until names.length()).map { index ->
                    val name = names.get(index) as? String ?: error("分类名称格式错误")
                    CustomCategory(name, direction).also {
                        it.validate()
                        require(unique.add(name)) { "分类名称重复" }
                    }
                }
            }
        }
        val histories=mutableMapOf<String,List<RestoredCaptureHistory>>()
        root.optJSONObject("histories")?.let { all ->
            require(all.length()<=records.size)
            for(uid in all.keys()) {
                require(uid in uids) { "截图没有关联账单" }
                val entries=all.getJSONArray(uid);require(entries.length()<=1000)
                var previous=0
                histories[uid]=(0 until entries.length()).map { index ->
                    val e=entries.getJSONObject(index)
                    fun num(key:String):Long { val v=e.get(key);require(v is Int || v is Long);return (v as Number).toLong() }
                    val sequence=num("sequence");require(sequence>previous && sequence<=Int.MAX_VALUE);previous=sequence.toInt()
                    val at=num("at");require(at in 1..4_102_444_800_000L)
                    val kind=e.getString("kind");require(kind.length<=40)
                    val amount=if(e.isNull("amountCents"))null else num("amountCents");require(amount==null || amount in 0..Money.MAX_CENTS)
                    val refunded=num("refundedCents");require(refunded in 0..Money.MAX_CENTS)
                    val raw=e.getString("rawText");require(raw.length<=32_768)
                    val mode=e.optString("refundMode","cumulative");require(mode in setOf("cumulative","single"))
                    val refundId=if(e.isNull("refundId"))null else e.getString("refundId");require(refundId==null || refundId.length in 1..160)
                    val refundAmount=if(e.isNull("refundAmountCents"))null else num("refundAmountCents");require(refundAmount==null || refundAmount in 1..Money.MAX_CENTS)
                    val image=if(e.isNull("image"))null else {
                        val encoded=e.getString("image");require(encoded.length<=7_000_000)
                        android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP).also { require(it.isNotEmpty() && it.size<=5*1024*1024) }
                    }
                    RestoredCaptureHistory(RecordCaptureHistory(sequence.toInt(),at,kind,amount,refunded,raw,image!=null,mode,refundId,refundAmount),image)
                }
            }
        }
        val wallets=if(version>=4)WalletBackupCodec.decode(root.getJSONObject("wallets"),version) else null
        val links=wallets?.links?.associateBy { it.recordUid };val transfers=wallets?.transfers?.associateBy { it.recordUid }
        val restored=records.map { record->val details=transfers?.get(record.uid)
            record.copy(wallet=links?.get(record.uid),transfer=details,walletEffects=wallets?.effects?.get(record.uid).orEmpty(),
                bill=record.bill.copy(transfer=details?.recognitionEvidence?.let(TransferEvidenceCodec::decode)))
        }
        if(wallets!=null)WalletBackupCodec.validate(restored,accounts,wallets)
        val directory=if(version<6)emptyList() else root.getJSONArray("categoryDirectory").let { entries ->
            require(entries.length()<=2_000) { "备份分类过多" }
            (0 until entries.length()).map { index -> val item=entries.getJSONObject(index)
                fun text(key:String)=(item.get(key) as? String) ?: error("分类字段类型错误")
                val order=item.get("order");require(order is Int && order>=0) { "分类顺序格式错误" }
                LedgerCategory(text("id"),text("name"),Direction.valueOf(text("direction")),text("icon"),order,
                    item.get("isFallback") as? Boolean ?: error("分类兜底格式错误"))
            }
        }
        val categoryIds=if(version<6)emptyMap() else (0 until array.length()).mapNotNull { index ->
            val item=array.getJSONObject(index)
            if(item.isNull("categoryId"))null else item.getString("uid") to ((item.get("categoryId") as? String) ?: error("分类标识格式错误"))
        }.toMap()
        return ValidatedBackup(restored,channels,accounts,categories,histories,wallets,version,directory,categoryIds).also(::preflightCategories)
    }
}
