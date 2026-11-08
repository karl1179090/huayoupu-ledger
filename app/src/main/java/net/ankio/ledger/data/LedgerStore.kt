package net.ankio.ledger.data

import android.content.ContentValues
import net.ankio.ledger.ocr.MerchantIconEncoding
import net.ankio.ledger.ocr.IconLocalization
import android.graphics.BitmapFactory
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.ankio.ledger.core.*
import java.util.UUID

data class LedgerRecord(
    val id: Long = 0,
    val uid: String = UUID.randomUUID().toString(),
    val capture: Capture,
    val bill: ParsedBill,
    val state: RecordState,
    val reason: String = bill.reason,
    val duplicateId: Long? = null,
    val note: String = "",
    val identities: List<TransactionIdentity> = emptyList(),
    val wallet: RecordWalletLink? = null,
    val transfer: RecordTransferDetails? = null,
    val walletEffects: Map<String, Long> = emptyMap(),
) {
    fun existing() = ExistingBill(id, capture.source, capture.origin, capture.text,
        bill.amountCents, bill.direction, bill.merchant, bill.occurredAt, bill.transactionId, bill.timeExplicit)
}

sealed class IngestResult {
    data class Saved(val record: LedgerRecord, val updated: Boolean = false, val supplemented: Boolean = false) : IngestResult()
    data object DuplicateSkipped : IngestResult()
    data object Ignored : IngestResult()
    data class Skipped(val message:String):IngestResult()
}

data class DeletedRecord(val id:Long,val expiresAt:Long)

class LedgerStore(context: Context, private val name: String = "ledger.db", private val screenshots: ScreenshotStore = ScreenshotStore.get(context)) : SQLiteOpenHelper(context, name, null, 13) {
    private val backupContext=context.applicationContext
    internal fun createBackupStagingDirectory():java.io.File = java.io.File(backupContext.cacheDir,"ledger-backup-stage-${UUID.randomUUID()}").apply {
        check(mkdir()) { "无法准备备份临时目录" }
    }
    internal fun stageBackupScreenshot(recordId:Long,sequence:Int,directory:java.io.File):java.io.File? =
        screenshots.stageFile(historyOwner(recordId,sequence),directory)

    private fun imageOwner(id: Long) = "ledger:$name:$id"
    private fun historyOwner(id: Long, sequence: Int) = if (sequence == 1) imageOwner(id) else "ledger:$name:$id:history:$sequence"
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        if(db.isReadOnly)return
        ImportAuditStore.create(db)
        TransferAccounting.ensureEvidenceColumn(db)
        // Migrate one durable reference before clearing each legacy BLOB; safe to resume after a crash.
        var migratedBytes=0L
        db.rawQuery("SELECT i.record_id,length(i.image),r.received_at FROM capture_images i JOIN records r ON i.record_id=r.id WHERE i.image IS NOT NULL",null).use { c ->
            while(c.moveToNext()) {
                val image=LegacyScreenshots.read(db,"capture_images","record_id",c.getLong(0),c.getInt(1))
                screenshots.put(imageOwner(c.getLong(0)),image,c.getLong(2),"HISTORY")
                db.execSQL("UPDATE capture_images SET image=NULL WHERE record_id=?",arrayOf(c.getLong(0)))
                migratedBytes+=image.size
            }
        }
        db.rawQuery("SELECT h.record_id,h.sequence,d.record_id FROM capture_history h LEFT JOIN record_deletions d ON d.record_id=h.record_id", null).use { c ->
            while(c.moveToNext()) screenshots.state(historyOwner(c.getLong(0),c.getInt(1)),if(c.isNull(2))"HISTORY" else "UNDO")
        }
        LegacyScreenshots.compact(db,migratedBytes)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE records (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
            source TEXT NOT NULL, origin TEXT NOT NULL, raw_text TEXT NOT NULL,
            received_at INTEGER NOT NULL, amount INTEGER, direction TEXT,
            merchant TEXT NOT NULL, category TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            transaction_id TEXT, reliable INTEGER NOT NULL, time_explicit INTEGER NOT NULL,
            state TEXT NOT NULL, reason TEXT NOT NULL, duplicate_id INTEGER, note TEXT NOT NULL, standalone_refund TEXT, receipt_evidence TEXT)""")
        db.execSQL("CREATE INDEX records_time ON records(occurred_at)")
        db.execSQL("CREATE INDEX records_transaction ON records(transaction_id)")
        db.execSQL("CREATE INDEX records_received ON records(received_at)")
        db.execSQL("CREATE TABLE capture_events (event_key TEXT PRIMARY KEY NOT NULL, received_at INTEGER NOT NULL)")
        createAccountsAndCategories(db)
        createCaptureImages(db)
        createMerchantIcons(db)
        createRefunds(db)
        createIdentitiesAndUndo(db)
        WalletAccounting.create(db)
        CategoryDirectory.create(db)
        ClassificationStorage.create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..12 && newVersion == 13) { "Unsupported database migration $oldVersion → $newVersion" }
        // Preserve the complete historical upgrade chain before the wallet migration.
        if (oldVersion < 8) {
        if (oldVersion == 1) createAccountsAndCategories(db)
        if (oldVersion < 3) createCaptureImages(db)
        if (oldVersion < 5) {
            if (oldVersion == 4) db.execSQL("ALTER TABLE merchant_icons RENAME TO legacy_merchant_icons")
            createMerchantIcons(db)
            db.query("records", arrayOf("id", "merchant"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) linkMerchantIcon(db, cursor.getLong(0), cursor.getString(1), null)
            }
            if (oldVersion == 4) migratePreviewIcons(db)
        }
        if (oldVersion in 3..5) {
            db.execSQL("ALTER TABLE capture_images RENAME TO legacy_capture_images")
            createCaptureImages(db)
            db.execSQL("INSERT INTO capture_images(record_id,image) SELECT record_id,image FROM legacy_capture_images")
            db.execSQL("DROP TABLE legacy_capture_images")
        }
        createRefunds(db)
        db.execSQL("""INSERT OR IGNORE INTO capture_history(record_id,sequence,at,kind,amount,refunded,raw_text,refund_mode)
            SELECT id,1,received_at,'payment',amount,0,raw_text,'cumulative' FROM records""")
        createIdentitiesAndUndo(db)
        db.query("records",null,null,null,null,null,null).use { c -> while(c.moveToNext()) {
            val record=read(c)
            val captured=db.rawQuery("SELECT raw_text FROM capture_history WHERE record_id=?",arrayOf(record.id.toString())).use { h->
                buildList { while(h.moveToNext())addAll(TransactionIdentities.from(record.capture.copy(text=h.getString(0)),record.bill.copy(transactionId=null))) }
            }
            val recovered=(TransactionIdentities.from(record.capture,record.bill)+captured).distinct().groupBy { it.scope }.values.filter { it.size==1 }.flatten()
            persistIdentities(db,record.id,recovered)
        } }
        }
        if (oldVersion < 9) migrateWallets(db) else if (oldVersion < 10) WalletAccounting.migrateV10(db)
        if (oldVersion < 11) CategoryDirectory.create(db)
        if(oldVersion < 12)db.execSQL("ALTER TABLE records ADD COLUMN receipt_evidence TEXT")
        ClassificationStorage.create(db)
    }

    private fun migrateWallets(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN standalone_refund TEXT")
        val sequence=db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='accounts'",null).use { if(it.moveToFirst())it.getLong(0) else 0L }
        db.execSQL("ALTER TABLE accounts RENAME TO accounts_before_wallets")
        createAccounts(db)
        db.execSQL("""INSERT INTO accounts(id,uid,name,description,amount,kind,type,bank_code,card_last4,archived,revision)
            SELECT id,uid,name,description,amount,kind,type,
            CASE kind WHEN 'bank_icbc' THEN 'icbc' WHEN 'bank_abc' THEN 'abc' WHEN 'bank_boc' THEN 'boc'
            WHEN 'bank_ccb' THEN 'ccb' WHEN 'bank_bocom' THEN 'bocom' ELSE NULL END,NULL,0,0 FROM accounts_before_wallets""")
        db.execSQL("DROP TABLE accounts_before_wallets")
        db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='accounts'",arrayOf(sequence))
        WalletAccounting.create(db)
        db.execSQL("INSERT INTO record_wallet_links(record_uid,eligibility) SELECT uid,'LEGACY_EXCLUDED' FROM records")
    }

    private fun createIdentitiesAndUndo(db:SQLiteDatabase) {
        val columns=db.rawQuery("PRAGMA table_info(records)",null).use { c->buildSet { while(c.moveToNext())add(c.getString(1)) } }
        if("transaction_identities" !in columns)db.execSQL("ALTER TABLE records ADD COLUMN transaction_identities TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("CREATE TABLE IF NOT EXISTS record_identities(record_id INTEGER NOT NULL,scope TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(record_id,scope))")
        db.execSQL("CREATE INDEX IF NOT EXISTS identities_lookup ON record_identities(scope,value)")
        db.execSQL("CREATE TABLE IF NOT EXISTS record_deletions(record_id INTEGER PRIMARY KEY NOT NULL,expires_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_state_time ON records(state,occurred_at DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_time_id ON records(occurred_at DESC,id DESC)")
    }

    private fun identities(record:LedgerRecord)=(record.identities+TransactionIdentities.from(record.capture,record.bill)).distinct()
    private fun identitiesConflict(old:LedgerRecord,capture:Capture,bill:ParsedBill):Boolean {
        val incoming=TransactionIdentities.from(capture,bill)
        return old.identities.any { previous->incoming.any { it.scope==previous.scope && it.value!=previous.value } }
    }
    private fun primaryIssuerConflictsWithCapture(capture:Capture,bill:ParsedBill):Boolean {
        val id=bill.transactionId ?: return false
        if(TransactionIdentities.reservedPrimary(capture,id))return true
        val claimed=TransactionIdentities.from(capture,bill).filter { it.value==id }
        return claimed.isNotEmpty() && claimed.none { it.scope==DuplicateDetector.identityScope(capture) }
    }
    private fun persistIdentities(db:SQLiteDatabase,id:Long,ids:List<TransactionIdentity>) {
        val valid=ids.distinct();require(valid.groupBy { it.scope }.all { (_,items)->items.size==1 }) { "交易标识冲突" }
        db.delete("record_identities","record_id=?",arrayOf(id.toString()))
        valid.forEach { identity-> require(TransactionIdentities.valid(identity))
            db.insertOrThrow("record_identities",null,ContentValues().apply {
                put("record_id",id);put("scope",identity.scope);put("value",identity.value)
            })
        }
        db.update("records",ContentValues().apply { put("transaction_identities",TransactionIdentities.encode(valid)) },"id=?",arrayOf(id.toString()))
    }

    private fun identityMatch(db:SQLiteDatabase,capture:Capture,bill:ParsedBill):LedgerRecord? {
        val incoming=TransactionIdentities.from(capture,bill)
        if(incoming.isEmpty())return null
        val ids=incoming.flatMap { identity->db.rawQuery("SELECT record_id FROM record_identities WHERE scope=? AND value=?",arrayOf(identity.scope,identity.value))
            .use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } } }.distinct()
        if(ids.size!=1)return null
        val old=recordIncludingDeleted(ids.single())?:return null
        val combined=(old.identities+incoming).distinct()
        if(combined.groupBy { it.scope }.any { (_,items)->items.size>1 })return null
        return old.copy(identities=combined)
    }

    /** Read-only resolution for feedback after an already-known capture. */
    fun matchingIdentity(capture:Capture,bill:ParsedBill):LedgerRecord? = identityMatch(readableDatabase,capture,bill)?.let { get(it.id) }

    private fun createRefunds(db: SQLiteDatabase) {
        val columns=db.rawQuery("PRAGMA table_info(records)",null).use { c -> buildSet { while(c.moveToNext())add(c.getString(1)) } }
        mapOf("refund_provider" to "TEXT","refund_original" to "INTEGER","refund_amount" to "INTEGER",
            "refund_mode" to "TEXT","refund_id" to "TEXT").forEach { (column,type) ->
            if(column !in columns)db.execSQL("ALTER TABLE records ADD COLUMN $column $type")
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS capture_history(record_id INTEGER NOT NULL,sequence INTEGER NOT NULL,
            at INTEGER NOT NULL,kind TEXT NOT NULL,amount INTEGER,refunded INTEGER NOT NULL,
            raw_text TEXT NOT NULL,refund_mode TEXT NOT NULL,refund_id TEXT,refund_amount INTEGER,
            PRIMARY KEY(record_id,sequence))""")
    }

    private fun createMerchantIcons(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE merchant_icons (record_id INTEGER PRIMARY KEY NOT NULL, merchant_key TEXT NOT NULL)")
        db.execSQL("CREATE INDEX merchant_icons_key ON merchant_icons(merchant_key)")
        db.execSQL("CREATE TABLE merchant_icon_assets (merchant_key TEXT PRIMARY KEY NOT NULL, image BLOB NOT NULL)")
    }

    private fun linkMerchantIcon(db: SQLiteDatabase, id: Long, merchant: String, image: ByteArray?) {
        val key = MerchantNames.key(merchant) ?: return
        // References also exist for bills without a crop, so a later icon serves earlier same-name bills.
        db.insertWithOnConflict("merchant_icons", null, ContentValues().apply {
            put("record_id", id); put("merchant_key", key)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (image != null) {
            val existing=db.query("merchant_icon_assets",arrayOf("image"),"merchant_key=?",arrayOf(key),null,null,null).use { if(it.moveToFirst())it.getBlob(0) else null }
            if(existing==null || !IconLocalization.usableThumbnail(existing) && IconLocalization.usableThumbnail(image))
                db.insertWithOnConflict("merchant_icon_assets", null, ContentValues().apply {
                    put("merchant_key", key); put("image", image)
                }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun iconKey(db: SQLiteDatabase, id: Long): String? = db.query("merchant_icons", arrayOf("merchant_key"),
        "record_id = ?", arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun pruneIcon(db: SQLiteDatabase, key: String?) {
        if (key != null) db.execSQL("DELETE FROM merchant_icon_assets WHERE merchant_key = ? AND NOT EXISTS " +
            "(SELECT 1 FROM merchant_icons WHERE merchant_key = ?)", arrayOf(key, key))
    }

    /** Enrich a proven payment without replacing financial fields or user-authored names.
     * A short OCR truncation is repairable only when its original raw line survives and
     * the new screenshot independently corroborates the longer display name. */
    private fun enrichPaymentPresentation(db:SQLiteDatabase,original:LedgerRecord,capture:Capture,bill:ParsedBill,image:ByteArray?):LedgerRecord {
        fun compact(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
            .filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
        val before=compact(original.bill.merchant);val after=compact(bill.merchant)
        val sameName=MerchantNames.key(bill.merchant)?.let { it==MerchantNames.key(original.bill.merchant) }==true
        val incomingLines=capture.text.lineSequence().map(::compact).filter(String::isNotBlank).toList()
        val sharedIdentity=original.identities.any { it in TransactionIdentities.from(capture,bill) }
        val originalEvidence=original.capture.text.lineSequence().map(::compact).any { it==before }
        val corroborated=incomingLines.count { it==after }>=2 ||
            incomingLines.any { it==after } && incomingLines.any { it.contains(after) && it.endsWith("公司") }
        val repair=!sameName && bill.reliable && sharedIdentity &&
            original.capture.source in setOf(Source.OCR,Source.IMAGE) && capture.source in setOf(Source.OCR,Source.IMAGE) &&
            (LocalOcrBillParser.isMerchantInterfaceText(before) ||
                before.length>=3 && after.length-before.length in 1..2 && after.startsWith(before)) &&
            originalEvidence && corroborated && MerchantNames.key(bill.merchant)!=null
        val result=if(repair) {
            check(db.update("records",ContentValues().apply { put("merchant",bill.merchant) },"id=?",arrayOf(original.id.toString()))==1)
            val previousKey=iconKey(db,original.id)
            linkMerchantIcon(db,original.id,bill.merchant,image?:merchantIcon(original.id))
            pruneIcon(db,previousKey)
            original.copy(bill=original.bill.copy(merchant=bill.merchant))
        }else original
        if(sameName)linkMerchantIcon(db,original.id,original.bill.merchant,image)
        val incoming=bill.receipt ?: return result
        val previous=result.bill.receipt
        val receipt=when {
            previous==null -> incoming.copy(time=incoming.time?.takeIf { it.accountingAt==result.bill.occurredAt })
            previous.blockId!=incoming.blockId -> previous
            else -> previous.copy(merchant=previous.merchant ?: incoming.merchant,
                productDescription=previous.productDescription ?: incoming.productDescription,
                transactionDescription=previous.transactionDescription ?: incoming.transactionDescription,
                orderRemark=previous.orderRemark ?: incoming.orderRemark,
                serviceProvider=previous.serviceProvider ?: incoming.serviceProvider,
                receiptIssuer=previous.receiptIssuer ?: incoming.receiptIssuer,
                sourceApp=previous.sourceApp ?: incoming.sourceApp,
                time=previous.time?.takeIf { it.accountingAt==result.bill.occurredAt }
                    ?: incoming.time?.takeIf { it.accountingAt==result.bill.occurredAt })
        }
        if(receipt==previous)return result
        check(db.update("records",ContentValues().apply { put("receipt_evidence",ReceiptEvidenceCodec.encode(receipt)) },
            "id=?",arrayOf(result.id.toString()))==1)
        return result.copy(bill=result.bill.copy(receipt=receipt))
    }

    /** Internal pre-release v4 used one PNG per record; preserve these when upgrading a preview. */
    private fun migratePreviewIcons(db: SQLiteDatabase) {
        db.rawQuery("SELECT l.merchant_key, a.image FROM legacy_merchant_icons a JOIN merchant_icons l ON a.record_id = l.record_id ORDER BY a.record_id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val input = cursor.getBlob(1)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
                if (bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128) continue
                val bitmap = BitmapFactory.decodeByteArray(input, 0, input.size) ?: continue
                try {
                    val image = MerchantIconEncoding.encode(bitmap) ?: continue
                    db.insertWithOnConflict("merchant_icon_assets", null, ContentValues().apply {
                        put("merchant_key", cursor.getString(0)); put("image", image)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                } finally { bitmap.recycle() }
            }
        }
        db.execSQL("DROP TABLE legacy_merchant_icons")
    }

    private fun createCaptureImages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE capture_images (record_id INTEGER PRIMARY KEY NOT NULL, image BLOB)")
    }

    private fun createAccountsAndCategories(db: SQLiteDatabase) {
        createAccounts(db)
        db.execSQL("""CREATE TABLE custom_categories (
            name TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('EXPENSE', 'INCOME')),
            PRIMARY KEY(direction, name))""")
    }

    private fun createAccounts(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,name TEXT NOT NULL,
            description TEXT NOT NULL,amount INTEGER NOT NULL,
            kind TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('ASSET','LIABILITY')),
            bank_code TEXT,card_last4 TEXT,archived INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,
            CHECK(amount BETWEEN -${Money.MAX_CENTS} AND ${Money.MAX_CENTS}),
            CHECK(type='ASSET' OR amount>=0),CHECK(card_last4 IS NULL OR length(card_last4)=4 AND card_last4 NOT GLOB '*[^0-9]*'))""")
    }

    private fun withWallet(record: LedgerRecord): LedgerRecord {
        val link=WalletAccounting.link(readableDatabase,record.uid)
        val transfer=TransferAccounting.read(readableDatabase,record.uid)
        return record.copy(wallet=link,transfer=transfer,
            walletEffects=WalletAccounting.effects(readableDatabase,record.uid),
            bill=record.bill.copy(paymentMethod=decodePaymentEvidence(link?.paymentEvidence),
                transfer=transfer?.recognitionEvidence?.let(TransferEvidenceCodec::decode)))
    }

    private fun encodePaymentEvidence(e: PaymentMethodEvidence?): String? = e?.let {
        org.json.JSONObject().apply {
            put("version",1);put("medium",it.medium);put("role",it.role);put("bankCode",it.bankCode)
            put("cardLast4",it.cardLast4);put("platform",it.platform);put("rawLabel",it.rawLabel.take(80))
            put("rawValue",it.rawValue.take(160));put("ambiguous",it.ambiguous);put("reason",it.reason)
        }.toString()
    }
    private fun decodePaymentEvidence(value: String?): PaymentMethodEvidence? = value?.let {
        val json=org.json.JSONObject(it)
        fun optional(key:String)=if(json.isNull(key))null else json.optString(key).takeIf(String::isNotEmpty)
        PaymentMethodEvidence(json.getString("medium"),json.getString("role"),optional("bankCode"),optional("cardLast4"),
            optional("platform"),json.optString("rawLabel"),json.optString("rawValue"),json.optBoolean("ambiguous"),optional("reason"))
    }

    private fun encodeStandaloneRefund(e:StandaloneRefundEvidence?):String?=e?.let {
        org.json.JSONObject().apply {
            put("version",1);put("provider",it.provider);put("refundedCents",it.refundedCents)
            put("refundId",it.refundId);put("originalTransactionId",it.originalTransactionId)
        }.toString()
    }
    private fun decodeStandaloneRefund(value:String?):StandaloneRefundEvidence?=value?.let {
        val json=org.json.JSONObject(it)
        fun optional(key:String)=if(json.isNull(key))null else json.optString(key).takeIf(String::isNotBlank)
        StandaloneRefundEvidence(json.getString("provider"),json.getLong("refundedCents"),optional("refundId"),optional("originalTransactionId"))
    }

    /** The only bill-to-balance write path; applied effects are read exclusively from their table. */
    private fun syncWallet(db:SQLiteDatabase,record:LedgerRecord,isNew:Boolean=false,kind:String="EDIT",
        manualAccountUid:String?=null,manualSelected:Boolean=false,incomingEvidence:PaymentMethodEvidence?=record.bill.paymentMethod,
        deleted:Boolean=false,changed:Boolean=true,sourcePackage:String?=record.capture.origin,
        strictTransfer:Boolean=false,deferredDeltas:MutableMap<String,Long>?=null,
        initialEligibility:WalletEligibility=WalletEligibility.ACTIVE,originalPosted:Boolean?=null):LedgerRecord {
        val previous=WalletAccounting.link(db,record.uid)
        val oldEffects=WalletAccounting.effects(db,record.uid)
        val oldTransfer=TransferAccounting.read(db,record.uid)
        var link=previous ?: RecordWalletLink(record.uid,if(isNew)initialEligibility else WalletEligibility.LEGACY_EXCLUDED,
            reviewCode=if(isNew)null else "MISSING_WALLET_LINK")
        var final=record
        var transfer:RecordTransferDetails?=null
        if(record.bill.direction==Direction.TRANSFER || record.bill.direction==null && record.state==RecordState.REVIEW && record.transfer!=null) {
            if(record.bill.refund!=null || record.bill.standaloneRefund!=null)
                throw WalletException("TRANSFER_REFUND_CONFLICT","转账不能包含消费退款记录")
            transfer=(record.transfer ?: oldTransfer ?: RecordTransferDetails(record.uid)).copy(recordUid=record.uid)
            if(transfer.feeCents==0L)transfer=transfer.copy(feeMode=FeeMode.EXTRA)
            link=link.copy(accountUid=null,selectionSource=null,manualLocked=false,paymentEvidence=null,evidenceFingerprint=null)
            try {
                if(record.state==RecordState.POSTED && !deleted) validateTransfer(record,transfer,link.eligibility,oldTransfer.takeIf { originalPosted ?: (oldEffects.isNotEmpty() || !isNew && record.state==RecordState.POSTED && (link.eligibility!=WalletEligibility.ACTIVE || kind=="UNDO_DELETE")) },strictTransfer)
                link=link.copy(reviewCode=transfer.reviewCode)
            } catch(error:WalletException) {
                if(strictTransfer || !isNew || deferredDeltas!=null && error.code !in setOf("TRANSFER_WALLET_REQUIRED","INVALID_WALLET"))throw error
                final=record.copy(state=RecordState.REVIEW,reason=error.message.orEmpty())
                transfer=transfer.copy(reviewCode=error.code);link=link.copy(reviewCode=error.code)
            }
        } else if(link.eligibility==WalletEligibility.ACTIVE) {
            if(isNew && link.accountUid!=null) {
                val imported=getAccountByUid(link.accountUid)
                if(imported==null || imported.type!=AccountType.ASSET)throw WalletException("INVALID_WALLET","请选择有效的资产钱包")
                if(imported.archived) {
                    final=record.copy(state=RecordState.REVIEW,reason="关联钱包已归档，请选择有效钱包")
                    link=link.copy(reviewCode="INVALID_WALLET")
                }
            }
            val settings=WalletAccounting.settings(db)
            if(manualSelected && manualAccountUid==null)throw WalletException("INVALID_WALLET","请选择有效的资产钱包")
            val resolution=if(isNew && deferredDeltas!=null && previous?.selectionSource=="IMPORT")
                WalletResolution(link.accountUid,"IMPORT",link.reviewCode,link.evidenceFingerprint,link.manualLocked)
            else WalletResolver.resolve(accounts().map {
                WalletCandidate(it.uid,it.type.name,it.kind,it.bankCode,it.cardLast4,it.archived)
            },sourcePackage,incomingEvidence,settings.defaultAccountUid,settings.appBindings,
                if(manualSelected)manualAccountUid else null,
                previous?.takeIf { oldTransfer==null }?.let { WalletResolution(it.accountUid,it.selectionSource,it.reviewCode,it.evidenceFingerprint,it.manualLocked) })
            if(manualSelected && resolution.accountUid==null)throw WalletException("INVALID_WALLET","请选择有效的资产钱包")
            val evidence=if(incomingEvidence!=null && resolution.evidenceFingerprint==WalletResolver.fingerprint(incomingEvidence))
                encodePaymentEvidence(incomingEvidence) else link.paymentEvidence
            link=link.copy(accountUid=resolution.accountUid,selectionSource=resolution.selectionSource,
                manualLocked=resolution.manualLocked || link.manualLocked || manualSelected,
                paymentEvidence=evidence,evidenceFingerprint=resolution.evidenceFingerprint ?: link.evidenceFingerprint,
                reviewCode=if(final.state!=record.state)link.reviewCode else resolution.reviewCode)
            if(final.state==RecordState.POSTED && link.accountUid==null) {
                if(!isNew && oldEffects.isNotEmpty())throw WalletException("WALLET_REQUIRED","请选择钱包后入账")
                final=record.copy(state=RecordState.REVIEW,reason="请选择钱包后入账")
                link=link.copy(reviewCode="WALLET_REQUIRED")
            } else if(record.state==RecordState.REVIEW && previous?.reviewCode=="WALLET_REQUIRED" &&
                record.reason=="请选择钱包后入账" && link.accountUid!=null && record.bill.reliable && record.duplicateId==null) {
                final=record.copy(state=RecordState.POSTED,reason="")
            }
        } else if(manualSelected) {
            val account=manualAccountUid?.let(::getAccountByUid)
            if(account==null || account.type!=AccountType.ASSET || account.archived && account.uid!=previous?.accountUid)
                throw WalletException("INVALID_WALLET","请选择有效的资产钱包")
            link=link.copy(accountUid=account.uid,selectionSource="MANUAL",manualLocked=true,reviewCode=null)
        }
        var target=try { WalletEffectCalculator.targetEffects(final.bill.amountCents,final.bill.direction,final.state==RecordState.POSTED,
            deleted,link.eligibility==WalletEligibility.ACTIVE,link.accountUid,transfer?.target())
        } catch(error:IllegalArgumentException) { throw WalletException(error.message ?: "INVALID_AMOUNT","请核对账单金额与钱包") }
        var deltas=WalletEffectCalculator.changes(oldEffects,target).associate { it.accountUid to it.deltaCents }
        if(deferredDeltas==null)try { WalletAccounting.validateChanges(db,deltas) } catch(error:WalletException) {
            if(!isNew || strictTransfer || error.code!="BALANCE_LIMIT")throw error
            final=record.copy(state=RecordState.REVIEW,reason="钱包余额超出允许范围，请核对后保存")
            link=link.copy(reviewCode="BALANCE_LIMIT");transfer=transfer?.copy(reviewCode="BALANCE_LIMIT")
            target=emptyMap();deltas=emptyMap()
        }
        if(deferredDeltas!=null)deltas.forEach { (uid,delta)->
            deferredDeltas[uid]=try { Math.addExact(deferredDeltas[uid] ?: 0L,delta) } catch(_:ArithmeticException) {
                throw WalletException("BALANCE_LIMIT","批次钱包变化超出允许范围")
            }
        } else {
            val movementKind=if(oldTransfer==null && transfer==null && previous?.accountUid!=null && previous.accountUid!=link.accountUid && target.isNotEmpty())"TRANSFER" else kind
            WalletAccounting.moveAll(db,deltas,record.uid,movementKind,UUID.randomUUID().toString(),record.bill.merchant)
        }
        WalletAccounting.saveEffects(db,record.uid,target)
        link=WalletAccounting.project(link,target)
        val mutated=changed || previous==null || link!=previous || transfer!=oldTransfer
        link=link.copy(revision=(previous?.revision ?: 0)+(if(mutated)1 else 0))
        WalletAccounting.saveLink(db,link)
        if(transfer!=null)TransferAccounting.save(db,transfer)
        else db.delete("record_transfer_details","record_uid=?",arrayOf(record.uid))
        if(final.state!=record.state || final.reason!=record.reason)db.update("records",ContentValues().apply {
            put("state",final.state.name);put("reason",final.reason)
        },"id=?",arrayOf(record.id.toString()))
        return final.copy(wallet=link,transfer=transfer,walletEffects=target,bill=final.bill.copy(paymentMethod=decodePaymentEvidence(link.paymentEvidence)))
    }

    private fun validateTransfer(record:LedgerRecord,details:RecordTransferDetails,eligibility:WalletEligibility,
        previous:RecordTransferDetails?,strictWalletSelection:Boolean=false) {
        if(details.feeCents==null || details.feeMode==null)
            throw WalletException("TRANSFER_FEE_AMBIGUOUS","请确认转账手续费与费用方式")
        try { TransferCalculator.calculate(requireNotNull(record.bill.amountCents),details.feeCents,details.feeMode) }
        catch(error:IllegalArgumentException) { throw WalletException(error.message ?: "INVALID_AMOUNT","转账金额或手续费无效") }
        if(details.fromAccountUid!=null && details.fromAccountUid==details.toAccountUid)
            throw WalletException("TRANSFER_SAME_WALLET","转出与转入钱包不能相同")
        val existingReferences=listOfNotNull(previous?.fromAccountUid,previous?.toAccountUid)
        listOf(details.fromAccountUid to details.fromReference,details.toAccountUid to details.toReference).forEach { (uid,reference)->
            if(uid==null) {
                if(eligibility==WalletEligibility.ACTIVE || reference.isNullOrBlank())
                    throw WalletException("TRANSFER_WALLET_REQUIRED","请确认转出与转入钱包")
            } else {
                val account=getAccountByUid(uid) ?: throw WalletException("TRANSFER_WALLET_REQUIRED","关联转账钱包已不存在")
                if(account.type!=AccountType.ASSET)throw WalletException("TRANSFER_ACCOUNT_UNSUPPORTED","转账仅支持资产钱包")
                if((eligibility==WalletEligibility.ACTIVE || strictWalletSelection) && account.archived && uid !in existingReferences)throw WalletException("INVALID_WALLET","新转账不能选择已归档钱包")
            }
        }
    }

    private fun recordIncludingDeleted(id:Long):LedgerRecord?=readableDatabase.query("records",null,"id=?",arrayOf(id.toString()),null,null,null)
        .use { if(it.moveToFirst())withWallet(read(it)) else null }
    private fun getByUid(uid:String):LedgerRecord?=readableDatabase.query("records",null,"uid=?",arrayOf(uid),null,null,null)
        .use { if(it.moveToFirst())withWallet(read(it)) else null }
    private fun checkRecordRevision(record:LedgerRecord,expectedRevision:Long?) {
        if(expectedRevision!=null && record.wallet?.revision!=expectedRevision)
            throw WalletException("STALE_RECORD_REVISION","账单已更新，请重新核对")
    }

    data class CommittedCapture(val recordUid:String,val record:LedgerRecord?,val candidateId:CandidateId=CandidateId.SINGLE)

    fun committedCandidate(operationKey:String,candidateId:CandidateId):CommittedCapture? =
        committedCapture(candidateOperationKey(operationKey,candidateId))?.copy(candidateId=candidateId)

    fun ingestCandidate(capture:Capture,candidate:TransactionCandidate,operationKey:String,eventKey:String?=null,
        screenshot:ByteArray?=null,merchantIcon:ByteArray?=null):CandidateSubmission =
        CandidateSubmission(candidate.id,ingestRecognition(capture,candidate.bill,candidateOperationKey(operationKey,candidate.id),eventKey,screenshot,merchantIcon))

    /** This identity lives with the financial commit, independently of recognition.db updates. */
    @Synchronized fun committedCapture(operationKey:String):CommittedCapture? {
        val uid=WalletAccounting.submission(readableDatabase,operationKey,"CAPTURE_RESULT") ?: return null
        return CommittedCapture(uid,getByUid(uid)?.let { get(it.id) })
    }

    @Synchronized fun committedPaymentEvent(eventKey:String):CommittedCapture? {
        val uid=WalletAccounting.submission(readableDatabase,"capture-event:$eventKey","CAPTURE_EVENT_RESULT") ?: return null
        return CommittedCapture(uid,getByUid(uid)?.let { get(it.id) })
    }

    private fun redirectCaptureReceipts(db:SQLiteDatabase,fromUid:String,toUid:String) {
        db.update("wallet_submissions",ContentValues().apply { put("result_uid",toUid) },
            "result_uid=? AND kind IN ('CAPTURE_RESULT','CAPTURE_EVENT_RESULT')",arrayOf(fromUid))
    }

    @Synchronized fun ingestRecognition(capture:Capture,bill:ParsedBill,operationKey:String,eventKey:String?=null,
        screenshot:ByteArray?=null,merchantIcon:ByteArray?=null):IngestResult {
        val db=writableDatabase;db.beginTransaction()
        try {
            committedCapture(operationKey)?.let { committed ->
                db.setTransactionSuccessful()
                return if(committed.record==null)IngestResult.Skipped("账单已移除，未新增记账") else IngestResult.DuplicateSkipped
            }
            val result=ingest(capture,bill,eventKey,screenshot,merchantIcon)
            val record=when(result) {
                is IngestResult.Saved->result.record
                IngestResult.DuplicateSkipped->eventKey?.let(::committedPaymentEvent)?.record ?: matchingIdentity(capture,bill)
                else->null
            }
            record?.let {
                WalletAccounting.remember(db,operationKey,"CAPTURE_RESULT",it.uid)
                if(eventKey!=null && committedPaymentEvent(eventKey)==null)
                    WalletAccounting.remember(db,"capture-event:$eventKey","CAPTURE_EVENT_RESULT",it.uid)
            }
            db.setTransactionSuccessful();return result
        } finally { db.endTransaction() }
    }

    fun hasCaptureEvent(key:String):Boolean=readableDatabase.rawQuery("SELECT 1 FROM capture_events WHERE event_key=?",arrayOf(key)).use { it.moveToFirst() }

    @Synchronized fun rememberCaptureEvent(key:String) {
        writableDatabase.insertWithOnConflict("capture_events",null,ContentValues().apply {
            put("event_key",key);put("received_at",System.currentTimeMillis())
        },SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun ingest(capture: Capture, bill: ParsedBill, eventKey: String? = null, screenshot: ByteArray? = null, merchantIcon: ByteArray? = null, requireReview: Boolean = false): IngestResult {
        require(screenshot == null || screenshot.isNotEmpty() && screenshot.size <= 5 * 1024 * 1024) { "Invalid screenshot size" }
        require(merchantIcon == null || merchantIcon.isNotEmpty() && merchantIcon.size <= 128 * 1024) { "Invalid icon size" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val incomingIdentities=TransactionIdentities.from(capture,bill)
            val primaryIssuerConflict=primaryIssuerConflictsWithCapture(capture,bill)
            val recentlyDeleted=db.rawQuery("SELECT r.* FROM records r JOIN record_deletions d ON d.record_id=r.id",null).use { c->
                buildList { while(c.moveToNext())add(read(c)) }
            }.any { old ->
                old.identities.any { it in incomingIdentities } ||
                    old.bill.amountCents==bill.amountCents && old.bill.direction==bill.direction &&
                        old.capture.origin==capture.origin && old.capture.text.filterNot(Char::isWhitespace)==capture.text.filterNot(Char::isWhitespace)
            }
            if(recentlyDeleted) { db.setTransactionSuccessful();return IngestResult.Skipped("账单已删除") }
            if (eventKey != null) {
                val replay = db.rawQuery("SELECT 1 FROM capture_events WHERE event_key = ?", arrayOf(eventKey)).use { it.moveToFirst() }
                if (replay) { db.setTransactionSuccessful(); return IngestResult.DuplicateSkipped }
                db.insertOrThrow("capture_events", null, ContentValues().apply {
                    put("event_key", eventKey); put("received_at", capture.receivedAt)
                })
                db.delete("capture_events", "received_at < ?", arrayOf((capture.receivedAt - 7 * 86_400_000L).toString()))
            }
            if((bill.direction==Direction.TRANSFER || bill.transfer!=null) && (bill.refund!=null || bill.standaloneRefund!=null))
                throw WalletException("TRANSFER_REFUND_CONFLICT","转账不能包含消费退款记录")
            if(bill.standaloneRefund!=null) {
                val result=ingestStandaloneRefund(db,capture,bill,screenshot,requireReview,merchantIcon)
                db.setTransactionSuccessful();return result
            }
            if (bill.refund != null) {
                val result = ingestRefund(db, capture, bill, screenshot, requireReview, merchantIcon)
                db.setTransactionSuccessful()
                return result
            }
            val identified=identityMatch(db,capture,bill)
            if(identified!=null) {
                val previousAmount=identified.bill.refund?.originalAmountCents ?: identified.bill.amountCents
                val amountConflict=previousAmount!=bill.amountCents && !(identified.state==RecordState.REVIEW && previousAmount==null)
                val directionConflict=identified.bill.direction!=bill.direction && !(identified.state==RecordState.REVIEW && identified.bill.direction==null)
                val feeConflict=bill.transfer?.let { evidence->identified.transfer?.let { details->
                    evidence.feeCents!=null && details.feeCents!=null && evidence.feeCents!=details.feeCents ||
                        evidence.feeMode!=null && details.feeMode!=null && evidence.feeCents!=0L && evidence.feeMode!=details.feeMode
                } } == true
                if(amountConflict || directionConflict || feeConflict) {
                    val linked=identified.wallet?.copy(reviewCode="TRANSFER_IDENTITY_CONFLICT",revision=identified.wallet.revision+1)
                    if(linked!=null)WalletAccounting.saveLink(db,linked)
                    identified.transfer?.let { TransferAccounting.save(db,it.copy(reviewCode="TRANSFER_IDENTITY_CONFLICT",
                        recognitionEvidence=bill.transfer?.let(TransferEvidenceCodec::encode) ?: it.recognitionEvidence)) }
                    appendPaymentScreenshot(db,identified,capture,screenshot,"identity_conflict")
                    db.setTransactionSuccessful()
                    return IngestResult.Saved(requireNotNull(get(identified.id)),supplemented=true)
                }
                if(bill.transfer!=null && (bill.direction==Direction.TRANSFER || bill.direction==null && identified.state==RecordState.REVIEW)) {
                    val candidate=enrichRecognizedTransfer(identified,bill,!requireReview)
                    persistIdentities(db,identified.id,identified.identities)
                    val supplemented=appendPaymentScreenshot(db,identified,capture,screenshot)
                    if(candidate.bill!=identified.bill || candidate.state!=identified.state || candidate.reason!=identified.reason)
                        check(db.update("records",values(candidate),"id=?",arrayOf(candidate.id.toString()))==1)
                    val saved=syncWallet(db,candidate,changed=candidate!=identified,incomingEvidence=null)
                    db.setTransactionSuccessful()
                    return if(supplemented || saved!=identified)IngestResult.Saved(saved,
                        updated=identified.state==RecordState.REVIEW && saved.state==RecordState.POSTED,supplemented=supplemented)
                        else IngestResult.DuplicateSkipped
                }
                val enriched=identified.identities!=get(identified.id)?.identities
                persistIdentities(db,identified.id,identified.identities)
                val supplemented=appendPaymentScreenshot(db,identified,capture,screenshot)
                // Strong identity also owns ordinary incomplete drafts. Complete the original
                // draft under the same reliability gate as exact-duplicate supplementation.
                val promoted=identified.state==RecordState.REVIEW && identified.duplicateId==null &&
                    identified.bill.refund==null && identified.bill.standaloneRefund==null &&
                    identified.transfer==null && identified.bill.transfer==null && bill.transfer==null &&
                    identified.capture.source in setOf(Source.OCR,Source.IMAGE) && capture.source in setOf(Source.OCR,Source.IMAGE) &&
                    !requireReview && bill.reliable && bill.amountCents?.let { it in 1..Money.MAX_CENTS }==true &&
                    bill.direction in setOf(Direction.EXPENSE,Direction.INCOME)
                val presentation=if(promoted)identified.copy(bill=bill.copy(category=identified.bill.category,categoryDecision=identified.bill.categoryDecision),state=RecordState.POSTED,reason=bill.reason,duplicateId=null)
                    else enrichPaymentPresentation(db,identified,capture,bill,merchantIcon)
                if(promoted) {
                    check(db.update("records",values(presentation),"id=?",arrayOf(presentation.id.toString()))==1)
                    linkMerchantIcon(db,presentation.id,presentation.bill.merchant,merchantIcon)
                }
                val presented=syncWallet(db,presentation,
                    incomingEvidence=bill.paymentMethod,changed=enriched || promoted || presentation!=identified)
                db.setTransactionSuccessful()
                return if(supplemented || enriched || presented!=identified) IngestResult.Saved(presented,
                    updated=promoted && presented.state==RecordState.POSTED,supplemented=supplemented)
                    else IngestResult.DuplicateSkipped
            }
            val automatic = if(!requireReview) uniqueAutomaticPayment(capture,bill) else null
            if(automatic!=null) {
                val completed=syncWallet(db,completePaymentIdentity(db,automatic,bill),incomingEvidence=bill.paymentMethod)
                val supplemented=appendPaymentScreenshot(db,completed,capture,screenshot)
                linkMerchantIcon(db,completed.id,completed.bill.merchant,merchantIcon)
                db.setTransactionSuccessful()
                return IngestResult.Saved(completed,supplemented=supplemented)
            }
            val repeated=if(!requireReview && eventKey==null)uniqueRepeatedPayment(capture,bill) else null
            if(repeated!=null) {
                val supplemented=appendPaymentScreenshot(db,repeated,capture,screenshot)
                linkMerchantIcon(db,repeated.id,repeated.bill.merchant,merchantIcon)
                val linked=syncWallet(db,repeated,incomingEvidence=bill.paymentMethod,changed=false)
                db.setTransactionSuccessful()
                return IngestResult.Saved(linked,supplemented=supplemented)
            }
            // Reopening the original payment must never undo a later refund or add another expense.
            val refundedOriginals = if(bill.direction==Direction.EXPENSE && !primaryIssuerConflict)query(
                "state='POSTED' AND refund_provider IS NOT NULL AND refund_original=?",
                arrayOf(bill.amountCents?.toString() ?: "")).filter {
                    it.state == RecordState.POSTED && it.bill.refund != null &&
                        paymentProvider(it.capture) == paymentProvider(capture) &&
                        paymentProvider(capture) != null && it.bill.refund!!.originalAmountCents == bill.amountCents
                } else emptyList()
            val refundedOriginal = refundedOriginals.singleOrNull { old ->
                    (!bill.transactionId.isNullOrBlank() && old.bill.transactionId==bill.transactionId) ||
                        (screenshot!=null && screenshots.matches(imageOwner(old.id),screenshot) &&
                            (bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank() || old.bill.transactionId==bill.transactionId))
                }
            if (refundedOriginal != null) {
                val supplemented = bill.reliable && appendPaymentScreenshot(db, refundedOriginal, capture, screenshot)
                val linked=syncWallet(db,refundedOriginal,incomingEvidence=bill.paymentMethod,changed=false)
                db.setTransactionSuccessful()
                return if (supplemented || linked!=refundedOriginal) IngestResult.Saved(linked, supplemented=supplemented) else IngestResult.DuplicateSkipped
            }
            // Without a transaction ID an equal amount/name/time is only a candidate: a new purchase
            // can look the same. Keep it outside totals until the user decides which payment it is.
            val refundedPaymentCandidate=refundedOriginals.firstOrNull { old ->
                (bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank()) &&
                    bill.timeExplicit && old.bill.timeExplicit && kotlin.math.abs(bill.occurredAt-old.bill.occurredAt)<=120_000 &&
                    MerchantNames.key(bill.merchant)?.let { it==MerchantNames.key(old.bill.merchant) }==true
            }
            val near = db.query("records", null,
                "NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id) AND amount=? AND COALESCE(direction,'')=? AND (transaction_id = ? OR occurred_at BETWEEN ? AND ? OR received_at BETWEEN ? AND ?)",
                arrayOf(bill.amountCents?.toString() ?: "",bill.direction?.name ?: "",bill.transactionId ?: "", (bill.occurredAt - 120_000).toString(),
                    (bill.occurredAt + 120_000).toString(), (capture.receivedAt - 30_000).toString(),
                    (capture.receivedAt + 30_000).toString()), null, null, "id DESC")
                .use { cursor -> buildList { while (cursor.moveToNext()) {
                    val old=read(cursor)
                    if(!(primaryIssuerConflict && old.bill.transactionId==bill.transactionId) &&
                        old.identities.none { oldId->incomingIdentities.any { it.scope==oldId.scope && it.value!=oldId.value } })add(old.existing())
                } } }
            val detected = DuplicateDetector.find(capture, bill, near)
            val identityOwners=incomingIdentities.flatMap { identity->db.rawQuery("SELECT record_id FROM record_identities WHERE scope=? AND value=?",arrayOf(identity.scope,identity.value))
                .use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } } }.distinct()
            // A shared alias with conflicting other issuer IDs is ambiguous, even when
            // the old single-ID detector could otherwise choose the first row.
            val duplicate=if(detected is Duplicate.Exact && identityOwners.size>1)Duplicate.Possible(detected.id) else detected
            if (duplicate is Duplicate.Exact) {
                val previous = requireNotNull(get(duplicate.id))
                val promoted=previous.state==RecordState.REVIEW && previous.duplicateId==null && previous.bill.refund==null &&
                    previous.capture.source in setOf(Source.OCR,Source.IMAGE) && !requireReview && bill.reliable
                var original=if(promoted)previous.copy(bill=bill.copy(category=previous.bill.category,categoryDecision=previous.bill.categoryDecision),state=RecordState.POSTED,reason=bill.reason,duplicateId=null)
                    else enrichPaymentPresentation(db,previous,capture,bill,merchantIcon)
                if(promoted)check(db.update("records",values(original),"id=?",arrayOf(original.id.toString()))==1)
                if(promoted)linkMerchantIcon(db,original.id,original.bill.merchant,merchantIcon)
                persistIdentities(db,original.id,(original.identities+incomingIdentities).distinct())
                val supplemented = bill.reliable && appendPaymentScreenshot(db, original, capture, screenshot)
                original=syncWallet(db,original,incomingEvidence=bill.paymentMethod,changed=promoted)
                db.setTransactionSuccessful()
                return if (supplemented || promoted || original!=previous) IngestResult.Saved(original, supplemented=supplemented) else IngestResult.DuplicateSkipped
            }
            val automaticCandidate=automaticPaymentCandidates(capture,bill).firstOrNull { isAutomaticPayment(it) }
            val possible = duplicate as? Duplicate.Possible ?: refundedPaymentCandidate?.let { Duplicate.Possible(it.id) }
                ?: automaticCandidate?.let { Duplicate.Possible(it.id) }
            val reason = if(refundedPaymentCandidate!=null) "疑似为已退款账单的原付款页面，请核对后保存"
                else if (possible != null) "疑似与已有账单重复，请核对后保存" else bill.reason
            val draft = LedgerRecord(capture = capture, bill = bill.copy(reason = reason,
                category = CategoryDirectory.recognitionName(db, bill.direction, bill.category)),
                state = if (!requireReview && bill.reliable && bill.amountCents != null && bill.direction != null && possible == null)
                    RecordState.POSTED else RecordState.REVIEW,
                reason = reason,
                duplicateId = possible?.id)
            val record=enrichRecognizedTransfer(draft,bill,!requireReview)
            val id = db.insertOrThrow("records", null, values(record))
            persistIdentities(db,id,identities(record))
            if (screenshot != null) {
                screenshots.put(imageOwner(id), screenshot, capture.receivedAt, "HISTORY")
                db.insertOrThrow("capture_images", null, ContentValues().apply { put("record_id", id) })
            }
            insertHistory(db, id, RecordCaptureHistory(1,capture.receivedAt,"payment",bill.amountCents,0,capture.text,screenshot != null))
            linkMerchantIcon(db, id, bill.merchant, merchantIcon)
            syncWallet(db,record.copy(id=id),isNew=true,kind="POST")
            db.setTransactionSuccessful()
            return IngestResult.Saved(requireNotNull(get(id)))
        } finally { db.endTransaction() }
    }

    private fun enrichRecognizedTransfer(record:LedgerRecord,bill:ParsedBill,allowPromotion:Boolean=true):LedgerRecord =
        TransferRecognitionEnricher.enrich(record,bill,accounts().map {
            WalletCandidate(it.uid,it.type.name,it.kind,it.bankCode,it.cardLast4,it.archived)
        },WalletAccounting.settings(readableDatabase).appBindings,allowPromotion)

    /** Without an identifier only the identical captured payload can supplement a payment.
     * Merchant/amount/minute alone can describe two genuine purchases. */
    private fun uniqueRepeatedPayment(capture:Capture,bill:ParsedBill):LedgerRecord? {
        if(bill.direction==Direction.TRANSFER || bill.transfer!=null || !bill.reliable || bill.refund!=null || bill.reason.startsWith("已识别即时支付成功页") || capture.source !in setOf(Source.OCR,Source.IMAGE) ||
            bill.amountCents==null || bill.direction==null)return null
        val merchant=MerchantNames.key(bill.merchant)?:return null
        val provider=paymentProvider(capture)
        return query("state='POSTED' AND refund_provider IS NULL AND source IN ('OCR','IMAGE') AND amount=? AND direction=? AND received_at BETWEEN ? AND ?",
            arrayOf(bill.amountCents.toString(),bill.direction!!.name,(capture.receivedAt-120_000).toString(),(capture.receivedAt+120_000).toString())).filter { old ->
            val sameChannel=if(provider!=null)paymentProvider(old.capture)==provider else old.capture.origin==capture.origin
            val noConflictingId=bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank() || bill.transactionId==old.bill.transactionId
            val sameTime=old.capture.text.filterNot(Char::isWhitespace)==capture.text.filterNot(Char::isWhitespace) &&
                kotlin.math.abs(capture.receivedAt-old.capture.receivedAt)<=120_000
            old.state==RecordState.POSTED && old.bill.refund==null && old.capture.source in setOf(Source.OCR,Source.IMAGE) && sameChannel && noConflictingId && !identitiesConflict(old,capture,bill) &&
                (bill.transactionId.isNullOrBlank() || old.bill.transactionId.isNullOrBlank()) &&
                old.bill.amountCents==bill.amountCents && old.bill.direction==bill.direction &&
                MerchantNames.key(old.bill.merchant)==merchant && sameTime
        }.singleOrNull()
    }

    /** Upgrade only earlier linked duplicate drafts, retaining all evidence on the posted payment. */
    @Synchronized fun reconcilePendingDuplicates():Map<Long,Long> {
        val merged=mutableMapOf<Long,Long>()
        // Older shopping captures did not populate order IDs. Recover only explicit
        // labelled identity from stored OCR; never infer identity from amount/name.
        val db=writableDatabase
        // A narrow projection also handles Unicode-blank legacy IDs exactly as Kotlin
        // does, without decoding OCR text for the already-identified history.
        val unidentified=db.rawQuery("SELECT id,transaction_id FROM records WHERE source IN ('OCR','IMAGE') AND refund_provider IS NULL "+
            "AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)",null).use { c ->
            buildList { while(c.moveToNext())if(c.isNull(1) || c.getString(1).isBlank())add(c.getLong(0)) }
        }
        for(recordId in unidentified) {
            val record=get(recordId)?:continue
            val texts=(listOf(record.capture.text)+captureHistory(record.id).map { it.rawText }).distinct()
            val id=texts.mapNotNull { raw -> LocalOcrBillParser.transactionId(raw.lines().map { LocalOcrLine(it) }) }.distinct().singleOrNull()?:continue
            db.beginTransaction()
            try {
                db.update("records",ContentValues().apply { put("transaction_id",id) },"id=?",arrayOf(record.id.toString()))
                persistIdentities(db,record.id,(record.identities+TransactionIdentities.from(record.capture,record.bill.copy(transactionId=id))).distinct())
                syncWallet(db,requireNotNull(get(record.id)))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        // Only IDs repeated with the same financial fields can belong to a duplicate group.
        // Scope, conflicting identities and user-note checks remain in Kotlin below.
        val groups=query("state='POSTED' AND refund_provider IS NULL AND source IN ('OCR','IMAGE') AND transaction_id IN ("+
            "SELECT transaction_id FROM records WHERE state='POSTED' AND refund_provider IS NULL AND source IN ('OCR','IMAGE') "+
            "AND amount IS NOT NULL AND direction IS NOT NULL AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id) "+
            "GROUP BY transaction_id,amount,direction HAVING COUNT(*)>1)",null).filter { it.state==RecordState.POSTED && it.bill.refund==null &&
            !primaryIssuerConflictsWithCapture(it.capture,it.bill) &&
            it.capture.source in setOf(Source.OCR,Source.IMAGE) && !it.bill.transactionId.isNullOrBlank() &&
            it.bill.amountCents!=null && it.bill.direction!=null && DuplicateDetector.identityScope(it.capture)!=null }
            .groupBy { listOf(DuplicateDetector.identityScope(it.capture),it.bill.transactionId,it.bill.amountCents,it.bill.direction) }
        for(copies in groups.values.filter { it.size>1 }) {
            val ordered=copies.sortedWith(compareBy<LedgerRecord> { it.capture.receivedAt }.thenBy { it.id })
            val original=ordered.first()
            for(copy in ordered.drop(1)) {
                // Conflicting user notes require an explicit edit; don't discard one.
                val current=get(original.id)?:continue
                if(current.bill.direction==Direction.TRANSFER || copy.bill.direction==Direction.TRANSFER)continue
                if(current.note.isNotBlank() && copy.note.isNotBlank() && current.note!=copy.note)continue
                db.beginTransaction()
                try {
                    val entries=captureHistory(copy.id)
                    if(entries.isEmpty())appendPaymentScreenshot(db,current,copy.capture,screenshot(copy.id))
                    else entries.forEach { entry -> appendPaymentScreenshot(db,current,copy.capture.copy(text=entry.rawText,receivedAt=entry.at),historyScreenshot(copy.id,entry.sequence)) }
                    if(current.note.isBlank() && copy.note.isNotBlank()) {
                        db.update("records",ContentValues().apply { put("note",copy.note) },"id=?",arrayOf(current.id.toString()))
                        syncWallet(db,requireNotNull(get(current.id)))
                    }
                    if(MerchantNames.key(current.bill.merchant)==MerchantNames.key(copy.bill.merchant))linkMerchantIcon(db,current.id,current.bill.merchant,merchantIcon(copy.id))
                    redirectCaptureReceipts(db,copy.uid,current.uid)
                    delete(copy.id);merged[copy.id]=current.id
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
        for(pending in query("state='REVIEW' AND duplicate_id IS NOT NULL AND reason LIKE '疑似与已有账单重复%'",null)) {
            val original=uniqueRepeatedPayment(pending.capture,pending.bill)?:continue
            val db=writableDatabase;db.beginTransaction()
            try {
                val entries=captureHistory(pending.id)
                if(entries.isEmpty())appendPaymentScreenshot(db,original,pending.capture,screenshot(pending.id))
                else entries.forEach { entry -> appendPaymentScreenshot(db,original,pending.capture.copy(text=entry.rawText,receivedAt=entry.at),historyScreenshot(pending.id,entry.sequence)) }
                linkMerchantIcon(db,original.id,original.bill.merchant,merchantIcon(pending.id))
                redirectCaptureReceipts(db,pending.uid,original.uid)
                delete(pending.id);merged[pending.id]=original.id
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return merged
    }

    @Synchronized fun repairMerchantLabel(id:Long,recognized:ParsedBill,icon:ByteArray?):Boolean {
        val old=get(id)?:return false
        if(old.capture.source !in setOf(Source.OCR,Source.IMAGE) || !LocalOcrBillParser.isMerchantInterfaceText(old.bill.merchant) ||
            !recognized.reliable || recognized.amountCents!=old.bill.amountCents || recognized.direction!=old.bill.direction ||
            LocalOcrBillParser.isMerchantInterfaceText(recognized.merchant) || MerchantNames.key(recognized.merchant)==null)return false
        if(old.bill.merchant !in setOf("全称","名称") && old.capture.text.lineSequence().none {
            it.filterNot(Char::isWhitespace)==old.bill.merchant.filterNot(Char::isWhitespace)
        })return false
        val db=writableDatabase;db.beginTransaction()
        try {
            db.update("records",ContentValues().apply { put("merchant",recognized.merchant) },"id=?",arrayOf(id.toString()))
            val previousKey=iconKey(db,id)
            linkMerchantIcon(db,id,recognized.merchant,icon);pruneIcon(db,previousKey)
            syncWallet(db,requireNotNull(get(id)));db.setTransactionSuccessful();return true
        } finally { db.endTransaction() }
    }

    fun merchantLabelRepairCandidates():List<LedgerRecord> = query("source IN ('OCR','IMAGE') AND " +
        "(merchant IN ('全称','名称') OR merchant LIKE '%门店%' OR merchant LIKE '%人喜欢%' OR merchant LIKE '%人收藏%' OR merchant LIKE '%人关注%')",null)
        .filter { LocalOcrBillParser.isMerchantInterfaceText(it.bill.merchant) }

    @Synchronized fun discardStandaloneRefundReceipts():List<Long> = readableDatabase.rawQuery(
        "SELECT id,raw_text FROM records WHERE source IN ('OCR','IMAGE') AND refund_provider IS NULL "+
            "AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id) ORDER BY occurred_at DESC,id DESC",null).use { c ->
        buildList { while(c.moveToNext())if(StandaloneRefundReceipt.matches(c.getString(1)) && TransactionRejection.reason(c.getString(1))!=null)add(c.getLong(0)) }
    }.also { ids -> ids.forEach { delete(it) } }

    /** Withdraw only untouched machine drafts that the recognition gate now rejects. */
    @Synchronized fun discardRejectedDrafts():Map<Long,String> = query("state='REVIEW' AND source IN ('OCR','IMAGE')",null).filter {
        it.state==RecordState.REVIEW && it.note.isBlank() &&
            it.capture.source in setOf(Source.OCR,Source.IMAGE)
    }.mapNotNull { record -> TransactionRejection.reason(record.capture.text)?.let { record.id to it } }
        .toMap().also { drafts -> drafts.keys.forEach { delete(it) } }

    @Synchronized
    fun saveEdited(record: LedgerRecord, walletAccountUid:String?=null,manualWalletSelected:Boolean=false,
        expectedRevision:Long?=record.wallet?.revision,submissionToken:String?=null,
        transferDetails:RecordTransferDetails?=record.transfer): LedgerRecord {
        val db=writableDatabase;db.beginTransaction()
        try {
        val token=submissionToken ?: if(record.id==0L)record.uid else null
        WalletAccounting.submission(db,token,"RECORD_SAVE")?.let { uid ->
            val result=requireNotNull(getByUid(uid)) { "账单已不存在" };db.setTransactionSuccessful();return result
        }
        val amount = record.bill.amountCents
        val previous=record.id.takeIf { it!=0L }?.let(::get)
        previous?.let { require(it.uid==record.uid) { "账单标识无效" };checkRecordRevision(it,expectedRevision) }
        require(record.bill.standaloneRefund==null) { "请先关联原账单后确认退款" }
        require(record.bill.refund == null || record.id != 0L && previous?.state == RecordState.POSTED) { "请通过关联原账单确认退款" }
        require(amount != null && (amount > 0 || amount == 0L && validRefundEvidence(record.bill))) { "请输入有效金额" }
        require(record.bill.refund == null || validRefundEvidence(record.bill)) { "退款账单金额必须等于原付金额减累计退款" }
        if (record.id != 0L) {
            require(previous?.bill?.refund == null || record.bill.refund == previous.bill.refund && amount == previous.bill.amountCents) { "退款账单请保留实付金额" }
        }
        require(amount <= Money.MAX_CENTS) { "金额过大" }
        require(record.bill.direction != null) { "请选择交易类型" }
        // Category/note edits must not erase the native success-page provenance used by later details.
        val provenance=previous?.takeIf { isAutomaticPayment(it) && it.bill.amountCents==record.bill.amountCents &&
            it.bill.direction==record.bill.direction && it.bill.merchant==record.bill.merchant &&
            it.bill.occurredAt==record.bill.occurredAt && it.bill.timeExplicit==record.bill.timeExplicit }?.reason.orEmpty()
        val manualTransfer=transferDetails?.let { details->
            val old=previous?.transfer
            val fromChanged=details.fromAccountUid!=old?.fromAccountUid
            val toChanged=details.toAccountUid!=old?.toAccountUid
            details.copy(reviewCode=null,fromManualLocked=details.fromManualLocked || fromChanged,
                toManualLocked=details.toManualLocked || toChanged,
                fromSelectionSource=if(fromChanged)"MANUAL" else details.fromSelectionSource,
                toSelectionSource=if(toChanged)"MANUAL" else details.toSelectionSource,
                feeManualLocked=details.feeManualLocked || old==null || details.feeCents!=old.feeCents || details.feeMode!=old.feeMode)
        }
        val originalTime=previous?.bill?.receipt?.time
        val timeChanged=previous!=null && previous.bill.occurredAt!=record.bill.occurredAt
        val receipt=record.bill.receipt?.let { evidence ->
            if(originalTime==null)evidence else evidence.copy(time=if(timeChanged)originalTime.edited(record.bill.occurredAt) else originalTime)
        }
        val posted = record.copy(identities=previous?.identities ?: record.identities,
            transfer=if(record.bill.direction==Direction.TRANSFER)manualTransfer else null,
            bill = record.bill.copy(reliable = true, reason = provenance, receipt=receipt,
                timeExplicit=if(timeChanged)true else record.bill.timeExplicit, categoryDecision=if(record.bill.direction==Direction.TRANSFER)null else
                if(previous==null || record.bill.category!=previous.bill.category || record.bill.direction!=previous.bill.direction || record.bill.categoryDecision?.manual==true)
                    CategoryDecision(CategoryDirectory.ensure(db,record.bill.direction,record.bill.category)?.id,CategorySource.MANUAL,true,explanation="人工选择分类")
                else previous.bill.categoryDecision),
            state = RecordState.POSTED, reason = provenance, duplicateId = null)
            val previousKey = if (record.id == 0L) null else iconKey(db, record.id)
            val id = if (record.id == 0L) db.insertOrThrow("records", null, values(posted)) else {
                check(previous!=null && db.update("records", values(posted),
                    "id = ? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)", arrayOf(record.id.toString())) == 1) { "账单已被删除" }
                record.id
            }
            persistIdentities(db,id,identities(posted))
            if (record.id == 0L) insertHistory(db,id,RecordCaptureHistory(1,record.capture.receivedAt,"payment",amount,0,record.capture.text,false))
            db.delete("merchant_icons", "record_id = ?", arrayOf(id.toString()))
            linkMerchantIcon(db, id, posted.bill.merchant, null)
            pruneIcon(db, previousKey)
            val saved=syncWallet(db,posted.copy(id=id),record.id==0L,if(record.id==0L)"POST" else "EDIT",walletAccountUid,manualWalletSelected,strictTransfer=true,originalPosted=previous?.state==RecordState.POSTED)
            WalletAccounting.remember(db,token,"RECORD_SAVE",saved.uid)
            db.setTransactionSuccessful()
            return saved
        } finally { db.endTransaction() }
    }

    fun get(id: Long): LedgerRecord? = readableDatabase.query("records", null, "id = ? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)",
        arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) withWallet(read(it)) else null }

    fun captureHistory(recordId: Long): List<RecordCaptureHistory> = readableDatabase.query("capture_history", null,
        "record_id=?",arrayOf(recordId.toString()),null,null,"sequence ASC").use { c -> buildList {
        while(c.moveToNext()) {
            fun text(key:String)=c.getString(c.getColumnIndexOrThrow(key))
            fun number(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
            fun optional(key:String):Long?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it))null else c.getLong(it) }
            val sequence=number("sequence").toInt()
            add(RecordCaptureHistory(sequence,number("at"),text("kind"),optional("amount"),number("refunded"),text("raw_text"),
                screenshots.contains(historyOwner(recordId,sequence)),text("refund_mode"),text("refund_id"),optional("refund_amount")))
        }
    } }

    fun historyScreenshot(recordId: Long, sequence: Int): ByteArray? {
        val exists=readableDatabase.rawQuery("SELECT 1 FROM capture_history WHERE record_id=? AND sequence=?",
            arrayOf(recordId.toString(),sequence.toString())).use { it.moveToFirst() }
        if(!exists)return null
        return screenshots.image(historyOwner(recordId,sequence))
    }

    /** Lightweight change tokens; the home screen never needs every OCR transcript. */
    fun historyRevisions(availableOwners: Set<String>,recordIds:Set<Long>?=null): Map<Long,String> = readableDatabase.rawQuery(
        "SELECT record_id,sequence,at FROM capture_history"+idFilter(recordIds)+" ORDER BY record_id,sequence",null).use { cursor ->
        val values=linkedMapOf<Long,LongArray>()
        while(cursor.moveToNext()) {
            val id=cursor.getLong(0);val sequence=cursor.getInt(1)
            val summary=values.getOrPut(id) { longArrayOf(0,0,0,0) }
            summary[0]++;summary[1]=sequence.toLong();summary[2]=maxOf(summary[2],cursor.getLong(2))
            if(historyOwner(id,sequence) in availableOwners)summary[3]++
        }
        values.mapValues { it.value.joinToString(":") }
    }

    private fun idFilter(ids:Set<Long>?,column:String="record_id"):String {
        if(ids==null)return ""
        require(ids.all { it>0 })
        return if(ids.isEmpty())" WHERE 0" else " WHERE $column IN (${ids.joinToString(",")})"
    }

    fun availableScreenshotOwners(recordIds:Set<Long>):Set<String> {
        if(recordIds.isEmpty())return emptySet()
        val owners=recordIds.mapTo(mutableSetOf(),::imageOwner)
        readableDatabase.rawQuery("SELECT record_id,sequence FROM capture_history"+idFilter(recordIds),null).use { c->
            while(c.moveToNext())owners.add(historyOwner(c.getLong(0),c.getInt(1)))
        }
        return screenshots.availableOwners(owners)
    }

    /** Full history for rendering: avoid a separate history and screenshot query for each bill. */
    fun captureHistories(availableOwners: Set<String> = screenshots.availableOwners()): Map<Long,List<RecordCaptureHistory>> =
        readableDatabase.query("capture_history",null,null,null,null,null,"record_id ASC, sequence ASC").use { c ->
            val grouped=linkedMapOf<Long,MutableList<RecordCaptureHistory>>()
            fun text(key:String)=c.getString(c.getColumnIndexOrThrow(key))
            fun number(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
            fun optional(key:String):Long?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it))null else c.getLong(it) }
            while(c.moveToNext()) {
                val id=number("record_id");val sequence=number("sequence").toInt()
                grouped.getOrPut(id) { mutableListOf() }.add(RecordCaptureHistory(sequence,number("at"),text("kind"),
                    optional("amount"),number("refunded"),text("raw_text"),historyOwner(id,sequence) in availableOwners,
                    text("refund_mode"),text("refund_id"),optional("refund_amount")))
            }
            grouped
        }

    private fun insertHistory(db: SQLiteDatabase, id: Long, entry: RecordCaptureHistory) {
        db.insertOrThrow("capture_history",null,ContentValues().apply {
            put("record_id",id);put("sequence",entry.sequence);put("at",entry.at);put("kind",entry.kind)
            put("amount",entry.amountCents);put("refunded",entry.refundedCents);put("raw_text",entry.rawText)
            put("refund_mode",entry.refundMode);put("refund_id",entry.refundId);put("refund_amount",entry.refundAmountCents)
        })
    }

    /** Identity has already been checked by the caller. Evidence cannot change financial fields. */
    private fun appendPaymentScreenshot(db: SQLiteDatabase, original: LedgerRecord, capture: Capture, image: ByteArray?, kind: String = "payment_supplement"): Boolean {
        if (image == null || capture.source !in setOf(Source.OCR, Source.IMAGE)) return false
        val history = captureHistory(original.id)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        if (screenshots.fingerprint(imageOwner(original.id)) == fingerprint || history.any {
                screenshots.fingerprint(historyOwner(original.id,it.sequence)) == fingerprint
            }) return false
        if (history.isEmpty()) insertHistory(db,original.id,RecordCaptureHistory(1,original.capture.receivedAt,
            "payment",original.bill.amountCents,0,original.capture.text,screenshots.contains(imageOwner(original.id))))
        val sequence = (history.maxOfOrNull { it.sequence } ?: 1) + 1
        val owner = historyOwner(original.id,sequence)
        screenshots.put(owner,image,capture.receivedAt,"HISTORY")
        try {
            insertHistory(db,original.id,RecordCaptureHistory(sequence,capture.receivedAt,kind,
                original.bill.amountCents,original.bill.refund?.refundedCents ?: 0,capture.text,true))
        } catch (error: Throwable) {
            screenshots.release(owner)
            throw error
        }
        return true
    }

    /** Backup restore supplies only newly imported UIDs. Existing evidence is immutable. */
    @Synchronized fun restoreHistory(recordId:Long, entries:List<RestoredCaptureHistory>) =
        restoreHistory(recordId,entries) { it.image }

    /** ZIP restore supplies metadata first and loads at most one attachment for each insertion. */
    @Synchronized fun restoreHistory(recordId:Long, entries:List<RestoredCaptureHistory>,
                                    imageProvider:(RestoredCaptureHistory)->ByteArray?) {
        val record=requireNotNull(get(recordId)) { "账单已不存在" }
        LedgerBackup.validateHistory(record,entries)
        val db=writableDatabase
        val newImageOwners=mutableListOf<String>()
        db.beginTransaction()
        try {
            val existing=captureHistory(recordId).map { it.sequence }.toSet()
            entries.filter { it.entry.sequence !in existing }.forEach { restored ->
                val image=imageProvider(restored)
                require(image==null || image.isNotEmpty() && image.size<=5*1024*1024)
                image?.let {
                    val owner=historyOwner(recordId,restored.entry.sequence)
                    if(!screenshots.contains(owner)) {
                        screenshots.put(owner,image,restored.entry.at,"HISTORY")
                        newImageOwners.add(owner)
                    }
                }
                insertHistory(db,recordId,restored.entry)
                if(restored.entry.sequence==1 && image!=null)db.insertWithOnConflict("capture_images",null,
                    ContentValues().apply { put("record_id",recordId) },SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch(error:Throwable) {
            newImageOwners.forEach(screenshots::release)
            throw error
        } finally { db.endTransaction() }
    }

    private fun refundIdentityMatches(original:LedgerRecord,incoming:ParsedBill):Boolean {
        val provider=incoming.refund?.provider ?: return false
        val id=incoming.transactionId?.takeIf(String::isNotBlank) ?: return false
        return original.identities.any { it.scope==provider && it.value==id } ||
            paymentProvider(original.capture)==provider && original.bill.transactionId==id
    }

    /** SQL only narrows candidates; provider and conflict checks remain authoritative. */
    private fun postedRefundIdentityCandidates(bill:ParsedBill):List<LedgerRecord> {
        val id=bill.transactionId?.takeIf(String::isNotBlank) ?: return emptyList()
        val provider=bill.refund?.provider ?: return emptyList()
        return query("state='POSTED' AND (transaction_id=? OR EXISTS(SELECT 1 FROM record_identities i "+
            "WHERE i.record_id=records.id AND i.scope=? AND i.value=?))",arrayOf(id,provider,id))
    }

    private fun refundAmountCandidates(bill:ParsedBill):List<LedgerRecord> {
        val refund=bill.refund ?: return emptyList()
        // selectionArgs are strings; CASE has no column affinity, so compare an integer parameter.
        return query("state='POSTED' AND direction='EXPENSE' AND "+
            "(CASE WHEN refund_provider IS NULL THEN amount ELSE refund_original END)=CAST(? AS INTEGER) AND "+
            "(occurred_at BETWEEN ? AND ? OR transaction_id=? OR EXISTS(SELECT 1 FROM record_identities i "+
            "WHERE i.record_id=records.id AND i.scope=? AND i.value=?))",
            arrayOf(refund.originalAmountCents.toString(),(bill.occurredAt-120_000).toString(),(bill.occurredAt+120_000).toString(),
                bill.transactionId ?: "",refund.provider,bill.transactionId ?: ""))
    }

    private fun compatibleRefund(original:LedgerRecord, incoming:ParsedBill):Boolean {
        val refund=incoming.refund ?: return false
        return original.state==RecordState.POSTED && original.bill.direction==Direction.EXPENSE &&
            (paymentProvider(original.capture)==refund.provider || refundIdentityMatches(original,incoming)) &&
            (original.bill.refund?.originalAmountCents ?: original.bill.amountCents)==refund.originalAmountCents &&
            (original.bill.refund==null || validRefundSummary(original.bill)) &&
            (original.bill.transactionId.isNullOrBlank() || incoming.transactionId.isNullOrBlank() || refundIdentityMatches(original,incoming))
    }

    private fun isAutomaticPayment(record:LedgerRecord):Boolean = record.state==RecordState.POSTED &&
        record.capture.source==Source.OCR && !record.bill.timeExplicit && record.bill.transactionId.isNullOrBlank() &&
        record.reason.startsWith("已识别即时支付成功页") &&
        kotlin.math.abs(record.capture.receivedAt-record.bill.occurredAt)<=15_000

    /** Only a unique native live capture can gain identity from an explicitly timed order detail. */
    private fun uniqueAutomaticPayment(capture:Capture,bill:ParsedBill):LedgerRecord? {
        val provider=paymentProvider(capture) ?: return null
        if(!bill.transactionId.isNullOrBlank() && query("state='POSTED' AND transaction_id=?",arrayOf(bill.transactionId!!)).any { it.state==RecordState.POSTED && paymentProvider(it.capture)==provider &&
                it.bill.transactionId==bill.transactionId })return null
        // Count even candidates with conflicting IDs; ambiguity must never pick an arbitrary payment.
        return automaticPaymentCandidates(capture,bill).singleOrNull()?.takeIf {
            isAutomaticPayment(it) && it.bill.refund==null && it.bill.occurredAt-bill.occurredAt in -5_000L..120_000L
        }
    }

    private fun automaticPaymentCandidates(capture:Capture,bill:ParsedBill):List<LedgerRecord> {
        if(!bill.reliable || !bill.timeExplicit || bill.transactionId.isNullOrBlank() ||
            capture.source !in setOf(Source.OCR,Source.IMAGE) || bill.direction!=Direction.EXPENSE || primaryIssuerConflictsWithCapture(capture,bill))return emptyList()
        val provider=paymentProvider(capture) ?: return emptyList()
        if(bill.refund?.provider?.let { it!=provider }==true)return emptyList()
        val merchant=MerchantNames.key(bill.merchant) ?: return emptyList()
        val gross=bill.refund?.originalAmountCents ?: bill.amountCents ?: return emptyList()
        return query("state='POSTED' AND direction='EXPENSE' AND "+
            "(CASE WHEN refund_provider IS NULL THEN amount ELSE refund_original END)=CAST(? AS INTEGER) AND occurred_at BETWEEN ? AND ?",
            arrayOf(gross.toString(),(bill.occurredAt-120_000).toString(),(bill.occurredAt+120_000).toString())).filter { old -> old.state==RecordState.POSTED && old.bill.direction==Direction.EXPENSE &&
            paymentProvider(old.capture)==provider && !identitiesConflict(old,capture,bill) && (old.bill.refund?.originalAmountCents ?: old.bill.amountCents)==gross &&
            MerchantNames.key(old.bill.merchant)==merchant && kotlin.math.abs(old.bill.occurredAt-bill.occurredAt)<=120_000 }
    }

    private fun completePaymentIdentity(db:SQLiteDatabase,original:LedgerRecord,bill:ParsedBill):LedgerRecord {
        val time=bill.receipt?.time?.takeIf { it.accountingAt==bill.occurredAt }
            ?: original.bill.receipt?.time?.takeIf { it.accountingAt==bill.occurredAt }
        val receipt=(original.bill.receipt ?: bill.receipt)?.copy(time=time)
        val completed=original.copy(bill=original.bill.copy(transactionId=bill.transactionId,
            occurredAt=bill.occurredAt,timeExplicit=true,reason="",receipt=receipt),reason="")
        check(db.update("records",values(completed),"id=?",arrayOf(original.id.toString()))==1)
        persistIdentities(db,original.id,(original.identities+TransactionIdentities.from(original.capture,bill)).distinct())
        return requireNotNull(get(completed.id))
    }

    private fun matchingRefundCandidates(capture:Capture,bill:ParsedBill):List<LedgerRecord> {
        if(!validRefundEvidence(bill) || paymentProvider(capture)?.let { it!=bill.refund?.provider }==true)return emptyList()
        val merchant=MerchantNames.key(bill.merchant)
        return refundAmountCandidates(bill).filter { candidate -> compatibleRefund(candidate,bill) &&
            (refundIdentityMatches(candidate,bill) ||
                merchant!=null && merchant==MerchantNames.key(candidate.bill.merchant) &&
                    kotlin.math.abs(candidate.bill.occurredAt-bill.occurredAt)<=120_000) }
    }

    private fun standaloneRefundCandidates(capture:Capture,evidence:StandaloneRefundEvidence,exactOnly:Boolean):List<LedgerRecord> {
        if(evidence.provider !in setOf("wechat","alipay") || evidence.refundedCents !in 1..Money.MAX_CENTS ||
            evidence.refundId.isNullOrBlank() || paymentProvider(capture)?.let { it!=evidence.provider }==true)return emptyList()
        val identity=evidence.originalTransactionId?.takeIf(String::isNotBlank)
        if(exactOnly && identity==null)return emptyList()
        return query("state='POSTED' AND direction='EXPENSE'",null).filter { original ->
            val gross=original.bill.refund?.originalAmountCents ?: original.bill.amountCents
            val sameProvider=paymentProvider(original.capture)==evidence.provider || original.identities.any { it.scope==evidence.provider && it.value==identity }
            sameProvider && gross!=null && evidence.refundedCents<=gross &&
                (identity==null || original.bill.transactionId==identity || original.identities.any { it.scope==evidence.provider && it.value==identity })
        }
    }
    private fun completeStandaloneRefund(original:LedgerRecord,bill:ParsedBill):ParsedBill {
        val evidence=requireNotNull(bill.standaloneRefund)
        val gross=requireNotNull(original.bill.refund?.originalAmountCents ?: original.bill.amountCents)
        require(evidence.refundedCents in 1..gross && !evidence.refundId.isNullOrBlank()) { "退款证据不完整，请重新识别" }
        return bill.copy(amountCents=gross-evidence.refundedCents,direction=Direction.EXPENSE,
            transactionId=evidence.originalTransactionId ?: original.bill.transactionId,reliable=true,reason="",
            refund=RefundInfo(evidence.provider,gross,evidence.refundedCents,"single",evidence.refundId),standaloneRefund=null)
    }
    private fun ingestStandaloneRefund(db:SQLiteDatabase,capture:Capture,bill:ParsedBill,image:ByteArray?,requireReview:Boolean,merchantIcon:ByteArray?):IngestResult {
        val evidence=requireNotNull(bill.standaloneRefund)
        val original=if(!requireReview)standaloneRefundCandidates(capture,evidence,true).singleOrNull() else null
        if(original!=null) {
            val completed=completeStandaloneRefund(original,bill)
            if(refundConflict(original,completed)==null)return applyRefund(db,original,capture,completed,image)
        }
        val repeated=if(!evidence.refundId.isNullOrBlank())query("state='REVIEW' AND standalone_refund IS NOT NULL",null).singleOrNull {
            it.bill.standaloneRefund?.provider==evidence.provider && it.bill.standaloneRefund?.refundId==evidence.refundId &&
                it.bill.standaloneRefund==evidence
        } else null
        if(repeated!=null) {
            val supplemented=appendPaymentScreenshot(db,repeated,capture,image,"refund_supplement")
            return IngestResult.Saved(repeated,supplemented=supplemented)
        }
        val reason="退款已完成，请选择原账单核对"
        val pending=LedgerRecord(capture=capture,bill=bill.copy(amountCents=null,direction=Direction.EXPENSE,reliable=false,reason=reason),state=RecordState.REVIEW,reason=reason)
        val id=db.insertOrThrow("records",null,values(pending))
        persistIdentities(db,id,identities(pending))
        image?.let {
            screenshots.put(imageOwner(id),it,capture.receivedAt,"HISTORY")
            db.insertOrThrow("capture_images",null,ContentValues().apply { put("record_id",id) })
        }
        insertHistory(db,id,RecordCaptureHistory(1,capture.receivedAt,"refund_pending",null,evidence.refundedCents,capture.text,image!=null,
            "single",evidence.refundId,evidence.refundedCents))
        linkMerchantIcon(db,id,bill.merchant,merchantIcon)
        syncWallet(db,pending.copy(id=id),isNew=true,kind="REFUND")
        return IngestResult.Saved(requireNotNull(get(id)))
    }

    fun refundCandidates(pendingId:Long):List<LedgerRecord> {
        val pending=get(pendingId) ?: return emptyList()
        if(pending.state!=RecordState.REVIEW)return emptyList()
        pending.bill.standaloneRefund?.let { return standaloneRefundCandidates(pending.capture,it,false) }
        if(!validRefundEvidence(pending.bill))return emptyList()
        return matchingRefundCandidates(pending.capture,pending.bill).filter { it.id!=pendingId }
    }

    @Synchronized fun confirmRefund(pendingId:Long,targetId:Long):IngestResult.Saved {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val pending=requireNotNull(get(pendingId)) { "待确认退款已不存在" }
            require(pending.state==RecordState.REVIEW && (validRefundEvidence(pending.bill) || pending.bill.standaloneRefund!=null)) { "退款证据不完整，请重新识别" }
            require(paymentProvider(pending.capture)?.let { it==(pending.bill.refund?.provider ?: pending.bill.standaloneRefund?.provider) }!=false) { "退款平台与截图来源冲突，请重新识别" }
            require(refundCandidates(pendingId).any { it.id==targetId }) { "原账单已变更或与退款不匹配，请重新选择" }
            val original=requireNotNull(get(targetId))
            val refundBill=if(pending.bill.standaloneRefund!=null)completeStandaloneRefund(original,pending.bill) else pending.bill
            val reason=refundConflict(original,refundBill)
            require(reason==null) { reason ?: "退款状态冲突" }
            val result=applyRefund(db,original,pending.capture,refundBill,screenshot(pendingId))
            redirectCaptureReceipts(db,pending.uid,original.uid)
            delete(pendingId)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun ingestRefund(db:SQLiteDatabase,capture:Capture,bill:ParsedBill,image:ByteArray?,requireReview:Boolean,merchantIcon:ByteArray?=null):IngestResult {
        val refund=requireNotNull(bill.refund)
        val exact=if(!bill.transactionId.isNullOrBlank())postedRefundIdentityCandidates(bill).filter {
            compatibleRefund(it,bill) && refundIdentityMatches(it,bill)
        } else emptyList()
        val target=exact.singleOrNull() ?: if(exact.isEmpty())uniqueAutomaticPayment(capture,bill) else null
        val conflict=target?.let { refundConflict(it,bill) }
        val providerConflict=paymentProvider(capture)?.let { it!=refund.provider }==true
        if(!requireReview && bill.reliable && validRefundEvidence(bill) && !providerConflict && target!=null && conflict==null) {
            val original=if(isAutomaticPayment(target))completePaymentIdentity(db,target,bill) else target
            val result=applyRefund(db,original,capture,bill,image)
            if(MerchantNames.key(original.bill.merchant)==MerchantNames.key(bill.merchant))
                linkMerchantIcon(db,original.id,original.bill.merchant,merchantIcon)
            return result
        }
        val candidates=matchingRefundCandidates(capture,bill)
        val identityConflict=!bill.transactionId.isNullOrBlank() && postedRefundIdentityCandidates(bill).any {
            it.state==RecordState.POSTED && refundIdentityMatches(it,bill)
        }
        if(!requireReview && canCreateNetRefund(capture,bill))
            return insertNetRefund(db,capture,bill,image,merchantIcon)
        val reason=when {
            !validRefundEvidence(bill) -> "退款金额或口径不完整，请重新识别成功退款详情"
            providerConflict -> "退款平台与截图来源冲突，请重新识别"
            conflict!=null -> conflict
            !bill.reliable && bill.reason.isNotBlank() -> bill.reason
            candidates.isNotEmpty() -> "发现可能对应的原支出，请核对后更新"
            identityConflict -> "原交易单号对应的金额或状态冲突，请重新核对"
            refund.mode=="single" -> "只有单次退款信息，暂无法确定最终支出，请打开完整付款详情重新识别"
            else -> "付款与退款信息不完整，请打开完整付款详情重新识别"
        }
        val pending=LedgerRecord(capture=capture,bill=bill.copy(reason=reason),state=RecordState.REVIEW,reason=reason)
        val id=db.insertOrThrow("records",null,values(pending))
        persistIdentities(db,id,identities(pending))
        image?.let {
            screenshots.put(imageOwner(id),it,capture.receivedAt,"HISTORY")
            db.insertOrThrow("capture_images",null,ContentValues().apply { put("record_id",id) })
        }
        insertHistory(db,id,RecordCaptureHistory(1,capture.receivedAt,"refund_pending",bill.amountCents,refund.refundedCents,capture.text,image!=null,
            refund.mode,refund.refundId,refund.refundedCents))
        syncWallet(db,pending.copy(id=id),isNew=true,kind="POST")
        return IngestResult.Saved(requireNotNull(get(id)))
    }

    private fun canCreateNetRefund(capture:Capture,bill:ParsedBill):Boolean {
        val refund=bill.refund ?: return false
        return bill.reliable && bill.timeExplicit && !bill.transactionId.isNullOrBlank() && refund.refundedCents>0 && validRefundSummary(bill) &&
            paymentProvider(capture)?.let { it==refund.provider }!=false && matchingRefundCandidates(capture,bill).isEmpty() &&
            postedRefundIdentityCandidates(bill).none { it.state==RecordState.POSTED && refundIdentityMatches(it,bill) }
    }

    private fun insertNetRefund(db:SQLiteDatabase,capture:Capture,bill:ParsedBill,image:ByteArray?,merchantIcon:ByteArray?):IngestResult.Saved {
        val refund=requireNotNull(bill.refund)
        require(refund.mode=="cumulative" && validRefundSummary(bill))
        val record=LedgerRecord(capture=capture,bill=bill.copy(reason=""),state=RecordState.POSTED,reason="")
        val id=db.insertOrThrow("records",null,values(record))
        persistIdentities(db,id,identities(record))
        try {
            image?.let {
                screenshots.put(imageOwner(id),it,capture.receivedAt,"HISTORY")
                db.insertOrThrow("capture_images",null,ContentValues().apply { put("record_id",id) })
            }
            insertHistory(db,id,RecordCaptureHistory(1,capture.receivedAt,
                if(refund.refundedCents==refund.originalAmountCents)"full_refund" else "partial_refund",
                bill.amountCents,refund.refundedCents,capture.text,image!=null,"cumulative",null,refund.refundedCents))
            linkMerchantIcon(db,id,bill.merchant,merchantIcon)
            syncWallet(db,record.copy(id=id),isNew=true,kind="POST")
            return IngestResult.Saved(requireNotNull(get(id)))
        } catch(error:Throwable) { screenshots.release(imageOwner(id));throw error }
    }

    /** Repair old staged refunds only where the same current ingestion checks can settle them. */
    @Synchronized fun reconcilePendingRefunds():Map<Long,Long> {
        val resolved=mutableMapOf<Long,Long>()
        for(pending in query("state='REVIEW' AND refund_provider IS NOT NULL AND reliable=1 AND time_explicit=1",null).filter { it.bill.refund!=null && it.bill.reliable && it.bill.timeExplicit &&
            !it.bill.transactionId.isNullOrBlank() && validRefundEvidence(it.bill) }) {
            val db=writableDatabase
            db.beginTransaction()
            try {
                val target=postedRefundIdentityCandidates(pending.bill).filter { compatibleRefund(it,pending.bill) && refundIdentityMatches(it,pending.bill) }.singleOrNull()
                    ?: uniqueAutomaticPayment(pending.capture,pending.bill)
                if(target!=null && refundConflict(target,pending.bill)==null && paymentProvider(pending.capture)==pending.bill.refund!!.provider) {
                    val original=if(isAutomaticPayment(target))completePaymentIdentity(db,target,pending.bill) else target
                    applyRefund(db,original,pending.capture,pending.bill,screenshot(pending.id))
                    redirectCaptureReceipts(db,pending.uid,original.uid)
                    delete(pending.id);resolved[pending.id]=original.id
                } else if(canCreateNetRefund(pending.capture,pending.bill)) {
                    val settled=pending.copy(state=RecordState.POSTED,reason="",bill=pending.bill.copy(reason=""))
                    check(db.update("records",values(settled),"id=?",arrayOf(pending.id.toString()))==1)
                    db.update("capture_history",ContentValues().apply {
                        put("kind",if(pending.bill.amountCents==0L)"full_refund" else "partial_refund")
                    },"record_id=? AND sequence=1 AND kind='refund_pending'",arrayOf(pending.id.toString()))
                    syncWallet(db,settled,kind="REFUND")
                    resolved[pending.id]=pending.id
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return resolved
    }

    private fun refundConflict(original:LedgerRecord,bill:ParsedBill):String? {
        val refund=requireNotNull(bill.refund)
        if(!validRefundEvidence(bill))return "退款证据不完整，请重新识别"
        val history=captureHistory(original.id).filter { it.kind in setOf("partial_refund","full_refund") }
        if(history.any { it.refundMode!=refund.mode })return "累计退款与单次退款口径不同，请核对原账单"
        if(original.bill.refund!=null && history.isEmpty())return "原账单缺少退款明细，暂不能叠加退款"
        if(refund.mode=="single") {
            val reused=readableDatabase.rawQuery("SELECT 1 FROM capture_history h JOIN records r ON r.id=h.record_id WHERE h.refund_id=? AND r.refund_provider=? AND r.id<>? LIMIT 1",
                arrayOf(refund.refundId,refund.provider,original.id.toString())).use { it.moveToFirst() }
            if(reused)return "退款单号已关联其他账单，请核对原账单"
            val prior=history.find { it.refundId==refund.refundId }
            if(prior!=null && prior.refundAmountCents!=refund.refundedCents)return "相同退款单号的金额冲突，请重新核对"
            val current=original.bill.refund?.refundedCents ?: 0
            if(prior==null && refund.refundedCents>refund.originalAmountCents-current)return "累计退款超过原付款金额，请核对退款明细"
        }
        return null
    }

    private fun applyRefund(db:SQLiteDatabase,original:LedgerRecord,capture:Capture,bill:ParsedBill,image:ByteArray?):IngestResult.Saved {
        val incoming=requireNotNull(bill.refund)
        val current=original.bill.refund?.refundedCents ?: 0L
        val history=if(incoming.mode=="single")captureHistory(original.id) else null
        val prior=history?.any { it.refundId==incoming.refundId && it.kind!="payment" }==true
        val total=if(incoming.mode=="single")if(prior)current else current+incoming.refundedCents else maxOf(current,incoming.refundedCents)
        if(total==current && original.bill.refund!=null) {
            // Another view of the same successful refund may add evidence, but an older
            // cumulative page must not replace the more recent financial state.
            val supplemented=(incoming.mode=="single" || incoming.refundedCents==current) &&
                appendPaymentScreenshot(db,original,capture,image,"refund_supplement")
            return IngestResult.Saved(syncWallet(db,original,kind="REFUND",incomingEvidence=null,changed=false),supplemented=supplemented)
        }
        val entries=history ?: captureHistory(original.id)
        if(entries.isEmpty())insertHistory(db,original.id,RecordCaptureHistory(1,original.capture.receivedAt,
            "payment",original.bill.amountCents,0,original.capture.text,screenshots.contains(imageOwner(original.id))))
        val sequence=(entries.maxOfOrNull { it.sequence } ?: 1)+1
        // Save bytes before changing financial data; failure leaves the original amount intact.
        image?.let { screenshots.put(historyOwner(original.id,sequence),it,capture.receivedAt,"HISTORY") }
        try {
            val summary=incoming.copy(refundedCents=total,mode="cumulative",refundId=null)
            val updated=original.copy(bill=original.bill.copy(amountCents=incoming.originalAmountCents-total,refund=summary,
                transactionId=original.bill.transactionId ?: bill.transactionId))
            check(db.update("records",values(updated),"id=?",arrayOf(original.id.toString()))==1)
            persistIdentities(db,original.id,identities(updated))
            insertHistory(db,original.id,RecordCaptureHistory(sequence,capture.receivedAt,if(total==incoming.originalAmountCents)"full_refund" else "partial_refund",
                updated.bill.amountCents,total,capture.text,image!=null,incoming.mode,incoming.refundId,incoming.refundedCents))
            syncWallet(db,updated,kind="REFUND",incomingEvidence=null)
            return IngestResult.Saved(requireNotNull(get(original.id)),updated=true)
        } catch(error:Throwable) {
            // The ledger transaction will roll back; release its uncommitted external image reference too.
            if(image!=null)screenshots.release(historyOwner(original.id,sequence))
            throw error
        }
    }

    fun posted(start: Long, end: Long): List<LedgerRecord> = query(
        "state = ? AND occurred_at >= ? AND occurred_at < ?",
        arrayOf(RecordState.POSTED.name, start.toString(), end.toString()))

    fun pending(): List<LedgerRecord> = query("state = ?", arrayOf(RecordState.REVIEW.name))
    fun all(): List<LedgerRecord> = query(null, null)
    /** Undo-hidden rows still own their UID and transaction identities during CSV import. */
    @Synchronized internal fun importIdentityRecords():List<LedgerRecord> = readableDatabase.query(
        "records",null,null,null,null,null,null
    ).use { cursor->buildList { while(cursor.moveToNext())add(read(cursor)) } }
    internal fun activeRecordIdsByUid():Map<String,Long> = readableDatabase.rawQuery(
        "SELECT uid,id FROM records r WHERE NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)",null
    ).use { c->buildMap { while(c.moveToNext())put(c.getString(0),c.getLong(1)) } }

    /** Restore rollback discards ledger rows itself; only the separate screenshot store needs cleanup. */
    @Synchronized internal fun releaseImportedMedia(recordIds:Collection<Long>) {
        recordIds.forEach { id ->
            captureHistory(id).forEach { screenshots.release(historyOwner(id,it.sequence)) }
            screenshots.release(imageOwner(id))
        }
    }
    fun screenshotIds(availableOwners: Set<String> = screenshots.availableOwners(),recordIds:Set<Long>?=null): Set<Long> =
        readableDatabase.rawQuery("SELECT record_id FROM capture_images"+idFilter(recordIds),null)
            .use { cursor -> buildSet { while (cursor.moveToNext()) { val id=cursor.getLong(0); if(imageOwner(id) in availableOwners)add(id) } } }

    fun merchantIconKeys(recordIds:Set<Long>?=null): Map<Long, String> {
        if(recordIds?.isEmpty()==true)return emptyMap()
        return readableDatabase.rawQuery("SELECT l.record_id, l.merchant_key FROM merchant_icons l " +
            "JOIN merchant_icon_assets a ON l.merchant_key = a.merchant_key"+idFilter(recordIds,"l.record_id"), null)
            .use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) } }
    }

    fun merchantIconIds(): Set<Long> = merchantIconKeys().keys

    fun merchantIconRevisions(recordIds:Set<Long>?=null):Map<String,Int> = readableDatabase.rawQuery(
        if(recordIds==null)"SELECT merchant_key,image FROM merchant_icon_assets"
        else "SELECT DISTINCT a.merchant_key,a.image FROM merchant_icon_assets a JOIN merchant_icons l ON l.merchant_key=a.merchant_key"+idFilter(recordIds,"l.record_id"),null).use { cursor ->
        buildMap { while(cursor.moveToNext())put(cursor.getString(0),cursor.getBlob(1).contentHashCode()) }
    }

    fun merchantIconAsset(key: String): ByteArray? = readableDatabase.query("merchant_icon_assets", arrayOf("image"),
        "merchant_key = ?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun merchantIcon(id: Long): ByteArray? = readableDatabase.rawQuery("SELECT a.image FROM merchant_icons l " +
        "JOIN merchant_icon_assets a ON l.merchant_key = a.merchant_key WHERE l.record_id = ?",
        arrayOf(id.toString())).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }

    /** Restored artwork must not replace an icon already learned from a local bill. */
    @Synchronized fun restoreMerchantIcon(id:Long,image:ByteArray) {
        val record=requireNotNull(get(id)) { "账单已不存在" }
        require(image.isNotEmpty() && image.size<=5*1024*1024)
        val key=MerchantNames.key(record.bill.merchant) ?: return
        writableDatabase.insertWithOnConflict("merchant_icon_assets",null,ContentValues().apply {
            put("merchant_key",key);put("image",image)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun attachScreenshot(id:Long,image:ByteArray) {
        require(image.isNotEmpty()&&image.size<=5*1024*1024)
        require(get(id)!=null)
        screenshots.put(imageOwner(id),image,System.currentTimeMillis(),"HISTORY")
        writableDatabase.insertWithOnConflict("capture_images",null,ContentValues().apply { put("record_id",id) },SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun screenshot(id: Long): ByteArray? {
        readableDatabase // Finish a legacy migration before consulting the shared store.
        return screenshots.image(imageOwner(id))
    }

    @Synchronized fun delete(id: Long,expectedRevision:Long?=null,submissionToken:String?=null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            WalletAccounting.submission(db,submissionToken,"RECORD_HARD_DELETE")?.let { db.setTransactionSuccessful();return }
            val deleting=recordIncludingDeleted(id)
            deleting?.let { checkRecordRevision(it,expectedRevision);syncWallet(db,it,kind="DELETE",deleted=true) }
            val previousKey = iconKey(db, id)
            db.delete("record_deletions","record_id=?",arrayOf(id.toString()))
            db.delete("record_identities","record_id=?",arrayOf(id.toString()))
            db.delete("merchant_icons", "record_id = ?", arrayOf(id.toString()))
            db.delete("capture_images", "record_id = ?", arrayOf(id.toString()))
            captureHistory(id).forEach { screenshots.release(historyOwner(id,it.sequence)) }
            db.delete("capture_history", "record_id = ?", arrayOf(id.toString()))
            db.delete("records", "id = ?", arrayOf(id.toString()))
            deleting?.let {
                db.delete("record_wallet_links","record_uid=?",arrayOf(it.uid))
                db.delete("record_wallet_effects","record_uid=?",arrayOf(it.uid))
                db.delete("record_transfer_details","record_uid=?",arrayOf(it.uid))
            }
            screenshots.release(imageOwner(id))
            pruneIcon(db, previousKey)
            deleting?.let { WalletAccounting.remember(db,submissionToken,"RECORD_HARD_DELETE",it.uid) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** The deletion deadline is durable: process death cannot resurrect a removed bill. */
    @Synchronized fun softDelete(id:Long,now:Long=System.currentTimeMillis(),expectedRevision:Long?=null,submissionToken:String?=null):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            WalletAccounting.submission(db,submissionToken,"RECORD_DELETE")?.let { db.setTransactionSuccessful();return true }
            val record=get(id) ?: return false
            checkRecordRevision(record,expectedRevision)
            syncWallet(db,record,kind="DELETE",deleted=true)
            val deleted=db.insertWithOnConflict("record_deletions",null,ContentValues().apply {
                put("record_id",id);put("expires_at",Math.addExact(now,UNDO_WINDOW_MS))
            },SQLiteDatabase.CONFLICT_IGNORE)!=-1L
            WalletAccounting.remember(db,submissionToken,"RECORD_DELETE",record.uid)
            db.setTransactionSuccessful()
            if(deleted)markDeletedMedia(id,"UNDO")
            return deleted
        } finally { db.endTransaction() }
    }

    private fun markDeletedMedia(id:Long,state:String) {
        screenshots.state(imageOwner(id),state)
        captureHistory(id).forEach { screenshots.state(historyOwner(id,it.sequence),state) }
    }

    @Synchronized fun undoDelete(id:Long,now:Long=System.currentTimeMillis(),expectedRevision:Long?=null,submissionToken:String?=null):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            WalletAccounting.submission(db,submissionToken,"RECORD_UNDO")?.let { db.setTransactionSuccessful();return true }
            val record=recordIncludingDeleted(id) ?: return false
            checkRecordRevision(record,expectedRevision)
            val restored=db.delete("record_deletions","record_id=? AND expires_at>?",arrayOf(id.toString(),now.toString()))==1
            if(restored) {
                syncWallet(db,record,kind="UNDO_DELETE")
                WalletAccounting.remember(db,submissionToken,"RECORD_UNDO",record.uid)
                markDeletedMedia(id,"HISTORY")
            }
            db.setTransactionSuccessful();return restored
        } finally { db.endTransaction() }
    }

    fun pendingDeletion(now:Long=System.currentTimeMillis()):DeletedRecord? = readableDatabase.rawQuery(
        "SELECT record_id,expires_at FROM record_deletions WHERE expires_at>? ORDER BY expires_at DESC,record_id DESC LIMIT 1",arrayOf(now.toString())
    ).use { c-> if(c.moveToFirst())DeletedRecord(c.getLong(0),c.getLong(1)) else null }

    @Synchronized fun purgeExpiredDeletes(now:Long=System.currentTimeMillis()):List<Long> {
        val ids=readableDatabase.rawQuery("SELECT record_id FROM record_deletions WHERE expires_at<=?",arrayOf(now.toString()))
            .use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } }
        ids.forEach { delete(it) }
        return ids
    }

    companion object { const val UNDO_WINDOW_MS=6_000L }

    fun accounts(): List<LedgerAccount> = readableDatabase.query("accounts", null, null, null,
        null, null, "id ASC").use { cursor -> buildList { while (cursor.moveToNext()) add(readAccount(cursor)) } }

    fun getAccount(id: Long): LedgerAccount? = readableDatabase.query("accounts", null, "id = ?",
        arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) readAccount(it) else null }

    fun getAccountByUid(uid:String):LedgerAccount?=readableDatabase.query("accounts",null,"uid=?",arrayOf(uid),null,null,null)
        .use { if(it.moveToFirst())readAccount(it) else null }

    @Synchronized
    fun saveAccount(account: LedgerAccount, calibrateBalance:Boolean=false,expectedRevision:Long?=null,submissionToken:String?=null): LedgerAccount {
        account.validate()
        val db=writableDatabase;db.beginTransaction()
        try {
            WalletAccounting.submission(db,submissionToken,"ACCOUNT_SAVE")?.let { uid ->
                val result=requireNotNull(getAccountByUid(uid)) { "账户已不存在" };db.setTransactionSuccessful();return result
            }
            var previous=if(account.id==0L)null else getAccount(account.id)
            if(account.id!=0L)require(previous?.uid==account.uid) { "账户已不存在" }
            if(previous!=null && calibrateBalance && previous.revision!=(expectedRevision ?: account.revision))
                throw WalletException("STALE_ACCOUNT_REVISION","余额已更新，请重新核对")
            if(previous!=null && previous.type!=account.type && db.rawQuery(
                walletReferenceQuery(includeCalibration=false),
                Array(5) { account.uid }).use { it.moveToFirst() })
                throw WalletException("ACCOUNT_IN_USE","已有账单关联的账户不能更改资产类型")
            if(previous!=null && previous.type!=account.type && account.type==AccountType.LIABILITY) {
                val settings=WalletAccounting.settings(db)
                if(settings.defaultAccountUid==account.uid || account.uid in settings.appBindings.values)
                    throw WalletException("WALLET_CONFIGURATION_IN_USE","请先解除默认钱包和应用绑定，再更改账户类型")
                if(calibrateBalance && previous.amountCents<0) {
                    // Its old signed balance belongs to an asset. Calibrate before applying the liability constraint.
                    WalletAccounting.move(db,account.uid,null,Math.subtractExact(account.amountCents,previous.amountCents),
                        "CALIBRATION",UUID.randomUUID().toString(),account.name)
                    previous=requireNotNull(getAccount(account.id))
                }
            }
            if(previous!=null && !previous.archived && account.archived)validateArchive(db,account.uid)
            val saved=if(previous==null) {
                account.copy(id=db.insertOrThrow("accounts",null,accountValues(account.copy(amountCents=0,revision=0))),amountCents=0,revision=0)
            } else account.copy(amountCents=previous.amountCents,revision=previous.revision+1)
            if(previous!=null)db.update("accounts",accountValues(saved),"id=? AND uid=?",arrayOf(saved.id.toString(),saved.uid))
            if(previous==null || calibrateBalance)WalletAccounting.move(db,saved.uid,null,
                Math.subtractExact(account.amountCents,saved.amountCents),"CALIBRATION",UUID.randomUUID().toString(),account.name)
            if(saved.archived && db.delete("app_wallet_bindings","account_uid=?",arrayOf(saved.uid))>0)
                db.execSQL("UPDATE wallet_settings SET revision=revision+1 WHERE singleton=1")
            WalletAccounting.remember(db,submissionToken,"ACCOUNT_SAVE",saved.uid)
            db.setTransactionSuccessful();return requireNotNull(getAccountByUid(saved.uid))
        } finally { db.endTransaction() }
    }

    private fun walletReferenceQuery(includeCalibration:Boolean=true) =
        "SELECT 1 FROM record_wallet_links WHERE account_uid=? UNION ALL SELECT 1 FROM record_transfer_details WHERE from_account_uid=? OR to_account_uid=? " +
        "UNION ALL SELECT 1 FROM record_wallet_effects WHERE account_uid=? UNION ALL SELECT 1 FROM wallet_movements WHERE account_uid=?" +
        (if(includeCalibration) "" else " AND (record_uid IS NOT NULL OR kind IN ('BILL_IMPORT','WALLET_IMPORT_SNAPSHOT'))") + " LIMIT 1"
    private fun hasWalletHistory(db:SQLiteDatabase,uid:String):Boolean = db.rawQuery(walletReferenceQuery(),Array(5) { uid }).use { it.moveToFirst() }
    private fun validateArchive(db:SQLiteDatabase,uid:String) {
        if(WalletAccounting.settings(db).defaultAccountUid==uid)throw WalletException("DEFAULT_WALLET_IN_USE","请先选择其他默认钱包")
    }
    @Synchronized fun archiveAccount(id:Long):LedgerAccount {
        val account=requireNotNull(getAccount(id)) { "账户已不存在" }
        return saveAccount(account.copy(archived=true))
    }
    @Synchronized fun deleteAccount(id: Long) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val account=getAccount(id) ?: return
            val settings=WalletAccounting.settings(db)
            if(hasWalletHistory(db,account.uid))throw WalletException("ACCOUNT_IN_USE","账户已有账单或余额流水，请归档")
            if(settings.defaultAccountUid==account.uid || account.uid in settings.appBindings.values)
                throw WalletException("ACCOUNT_IN_USE","请先移除钱包配置引用")
            db.delete("wallet_import_aliases","local_account_uid=?",arrayOf(account.uid))
            db.delete("accounts","id=?",arrayOf(id.toString()));db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun walletSettings():WalletSettings=WalletAccounting.settings(readableDatabase)
    private fun requireAvailableWallet(uid:String):LedgerAccount {
        val account=getAccountByUid(uid)
        if(account==null || account.type!=AccountType.ASSET || account.archived)throw WalletException("INVALID_WALLET","请选择有效的资产钱包")
        return account
    }
    @Synchronized fun setDefaultWallet(uid:String?):WalletSettings {
        val db=writableDatabase;db.beginTransaction()
        try {
            uid?.let(::requireAvailableWallet)
            db.execSQL("UPDATE wallet_settings SET default_account_uid=?,revision=revision+1 WHERE singleton=1",arrayOf(uid))
            db.setTransactionSuccessful();return WalletAccounting.settings(db)
        } finally { db.endTransaction() }
    }
    @Synchronized fun setAppWallet(packageName:String,uid:String?):WalletSettings {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && packageName.length<=200) { "应用包名无效" }
        val db=writableDatabase;db.beginTransaction()
        try {
            if(uid==null)db.delete("app_wallet_bindings","package_name=?",arrayOf(packageName))
            else { requireAvailableWallet(uid);db.insertWithOnConflict("app_wallet_bindings",null,ContentValues().apply {
                put("package_name",packageName);put("account_uid",uid)
            },SQLiteDatabase.CONFLICT_REPLACE) }
            db.execSQL("UPDATE wallet_settings SET revision=revision+1 WHERE singleton=1")
            db.setTransactionSuccessful();return WalletAccounting.settings(db)
        } finally { db.endTransaction() }
    }
    @Synchronized fun walletMovements(accountUid:String,limit:Int=50,offset:Int=0):List<WalletMovement> {
        require(limit in 1..200 && offset>=0)
        return readableDatabase.query("wallet_movements",null,"account_uid=?",arrayOf(accountUid),null,null,
            "created_at DESC,rowid DESC","$offset,$limit").use { c->buildList { while(c.moveToNext())add(WalletAccounting.readMovement(c)) } }
    }
    @Synchronized fun walletSnapshot():WalletSnapshot {
        val db=readableDatabase
        val links=db.query("record_wallet_links",null,"record_uid IN (SELECT uid FROM records r WHERE NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id))",null,null,null,"record_uid").use { c->buildList {
            while(c.moveToNext())add(WalletAccounting.readLink(c).let { WalletAccounting.project(it,WalletAccounting.effects(db,it.recordUid)) })
        } }
        val movements=db.query("wallet_movements",null,null,null,null,null,"created_at,rowid").use { c->buildList {
            while(c.moveToNext())add(WalletAccounting.readMovement(c))
        } }
        return WalletSnapshot(WalletAccounting.settings(db),links,movements,
            links.mapNotNull { TransferAccounting.read(db,it.recordUid) },
            links.associate { it.recordUid to WalletAccounting.effects(db,it.recordUid) },walletImportAliases(),ImportAuditStore.read(db))
    }

    @Synchronized fun classificationMigrationBaseline():ClassificationSnapshot = ClassificationStorage.migrationBaseline(readableDatabase)
    @Synchronized fun classificationRules():ClassificationSnapshot = ClassificationStorage.configuration(readableDatabase)
    @Synchronized fun saveClassificationRule(rule:ClassificationRule):ClassificationRule = classificationTransaction { ClassificationStorage.save(it,rule) }
    @Synchronized fun deleteClassificationRule(id:String) = classificationTransaction { ClassificationStorage.delete(it,id) }
    @Synchronized fun reorderClassificationRules(direction:Direction,ids:List<String>) = classificationTransaction { ClassificationStorage.reorder(it,direction,ids) }
    @Synchronized fun holdClassificationSnapshot(key:String,snapshot:ClassificationSnapshot?=null,taskDatabase:String?=null):ClassificationSnapshot = classificationTransaction {
        ClassificationStorage.hold(it,key,snapshot ?: ClassificationStorage.configuration(it),taskDatabase)
    }
    @Synchronized fun reconcileClassificationSnapshots(taskDatabase:String,tasks:List<StoredClassificationSnapshot>) = classificationTransaction { ClassificationStorage.reconcile(it,taskDatabase,tasks) }
    @Synchronized fun releaseClassificationSnapshot(key:String) = classificationTransaction { ClassificationStorage.release(it,key) }
    @Synchronized fun forgetClassificationSnapshot(key:String) = classificationTransaction { ClassificationStorage.forget(it,key) }
    @Synchronized fun classificationBackup():ClassificationBackup = ClassificationStorage.backup(readableDatabase)
    private fun <T> classificationTransaction(action:(SQLiteDatabase)->T):T {
        val db=writableDatabase;db.beginTransaction()
        return try { action(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    @Synchronized fun classify(bill:ParsedBill,snapshot:ClassificationSnapshot):ParsedBill {
        val direction=bill.direction?.takeIf { it in listOf(Direction.EXPENSE,Direction.INCOME) } ?: return bill
        val decision=ClassificationEngine.classify(snapshot,ClassificationInput(direction,bill.merchant),bill.categoryDecision)
        val category=CategoryDirectory.requireCategory(readableDatabase,requireNotNull(decision.categoryId),direction)
        return bill.copy(category=category.name,categoryDecision=decision)
    }
    private fun guardClassificationReferences(db:SQLiteDatabase,id:String) {
        val count=ClassificationStorage.references(db,id)
        if(count>0)throw CategoryException("CATEGORY_REFERENCED","该分类仍被分类规则或识别任务引用，请先处理后再删除",count)
    }

    fun customCategories(): List<CustomCategory> = readableDatabase.query("custom_categories", null,
        null, null, null, null, "direction ASC, rowid ASC").use { cursor -> buildList {
        while (cursor.moveToNext()) add(CustomCategory(cursor.getString(cursor.getColumnIndexOrThrow("name")),
            Direction.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("direction")))))
    } }

    fun categoryDirectory(direction: Direction? = null): List<LedgerCategory> = CategoryDirectory.list(readableDatabase, direction)

    fun category(id: String, direction: Direction): LedgerCategory = CategoryDirectory.requireCategory(readableDatabase, id, direction)

    fun categoryFor(record: LedgerRecord): LedgerCategory? = record.bill.direction?.takeIf { it != Direction.TRANSFER }?.let {
        CategoryDirectory.byName(readableDatabase, it, record.bill.category)
    }

    @Synchronized fun saveCategory(id: String? = null, name: String, direction: Direction, icon: String = "tag"): LedgerCategory = categoryTransaction {
        CategoryDirectory.save(it, id, name, direction, icon)
    }

    @Synchronized fun reorderCategories(direction: Direction, ids: List<String>) = categoryTransaction {
        CategoryDirectory.reorder(it, direction, ids)
    }

    @Synchronized fun removeCategory(id: String) = categoryTransaction { guardClassificationReferences(it,id); CategoryDirectory.remove(it, id) }

    private fun <T> categoryTransaction(action: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { val result = action(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }

    @Synchronized
    fun addCategory(category: CustomCategory): Boolean = categoryTransaction { db ->
        category.validate(); CategoryDirectory.validateName(category.name); CategoryDirectory.validateDirection(category.direction)
        if (CategoryDirectory.byNormalized(db, category.direction, category.name) != null) false
        else { CategoryDirectory.save(db, null, category.name, category.direction, "tag"); true }
    }

    @Synchronized fun deleteCategory(category: CustomCategory) = categoryTransaction { db ->
        category.validate()
        val existing = CategoryDirectory.byName(db, category.direction, category.name)
            ?: throw CategoryException("CATEGORY_NOT_FOUND", "分类已不存在，请刷新后重试")
        guardClassificationReferences(db,existing.id)
        CategoryDirectory.remove(db, existing.id)
    }

    /** Compatibility API: merging saved history never posts a new financial transaction. */
    @Synchronized
    fun merge(records: List<LedgerRecord>, accounts: List<LedgerAccount> = emptyList(),
              categories: List<CustomCategory> = emptyList(), categoryDirectory: List<LedgerCategory> = emptyList(),
              categoryBindings: Map<String, String> = emptyMap()): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var imported = 0
            val mappedCategories = CategoryDirectory.merge(db, categoryDirectory)
            val incomingCategories = categoryDirectory.associateBy { it.direction to it.name }
            records.forEach { original ->
                val category = (categoryBindings[original.uid] ?: incomingCategories[original.bill.direction to original.bill.category]?.id)?.let { mappedCategories[it] }
                val record = if (category == null) original else original.copy(bill = original.bill.copy(category = category.name))
                // UUIDs identify records in repeated backups. Do not overwrite subsequent local edits.
                val exists = db.rawQuery("SELECT 1 FROM records WHERE uid = ?", arrayOf(record.uid)).use { it.moveToFirst() }
                if (!exists) {
                    val id = db.insertOrThrow("records", null, values(record.copy(id = 0, duplicateId = null)))
                    persistIdentities(db,id,identities(record))
                    WalletAccounting.saveLink(db,RecordWalletLink(record.uid,WalletEligibility.RESTORE_EXCLUDED))
                    linkMerchantIcon(db, id, record.bill.merchant, null)
                    imported++
                }
            }
            accounts.forEach { account ->
                account.validate()
                val exists = db.rawQuery("SELECT 1 FROM accounts WHERE uid = ?", arrayOf(account.uid)).use { it.moveToFirst() }
                if (!exists) db.insertOrThrow("accounts", null, accountValues(account.copy(id = 0)))
            }
            categories.forEach { original ->
                val mapped = incomingCategories[original.direction to original.name]?.id?.let { mappedCategories[it] }
                val category = if (mapped == null) original else original.copy(name = mapped.name)
                category.validate()
                CategoryDirectory.ensure(db, category.direction, category.name)
                db.insertWithOnConflict("custom_categories", null, categoryValues(category), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
            return imported
        } finally { db.endTransaction() }
    }

    /** Snapshot restore is financially distinct from importing new transactions. */
    @Synchronized fun restoreBackup(backup:ValidatedBackup):Int=restoreBackupDetailed(backup).imported
    @Synchronized fun restoreBackupDetailed(backup:ValidatedBackup):RestoreSummary = BackupRestore.restore(this,backup)

    /** Exclusion controls funding only; it never relaxes the stored business model. */
    private fun validateImportedModel(record:LedgerRecord) {
        val bill=record.bill;val amount=bill.amountCents;val direction=bill.direction
        if(record.state==RecordState.POSTED && (amount==null || direction==null))throw WalletException("INVALID_AMOUNT","正式账单缺少金额或交易类型")
        if(amount!=null && (amount !in 0..Money.MAX_CENTS || amount==0L && !validRefundEvidence(bill)))
            throw WalletException("INVALID_AMOUNT","导入账单金额无效")
        if(bill.refund!=null && (direction!=Direction.EXPENSE || !validRefundEvidence(bill)))
            throw WalletException("TRANSFER_REFUND_CONFLICT","消费退款金额或交易类型不一致")
        if(bill.standaloneRefund!=null && (record.state!=RecordState.REVIEW || amount!=null || bill.refund!=null || direction==Direction.TRANSFER))
            throw WalletException("TRANSFER_REFUND_CONFLICT","未关联退款必须保留为待确认")
        val details=record.transfer
        if(direction==Direction.TRANSFER || direction==null && record.state==RecordState.REVIEW && details!=null) {
            if(details==null || bill.refund!=null || bill.standaloneRefund!=null)throw WalletException("TRANSFER_REFUND_CONFLICT","转账资料或退款信息冲突")
            if(details.feeCents!=null && details.feeCents !in 0..Money.MAX_CENTS)throw WalletException("TRANSFER_FEE_AMBIGUOUS","转账手续费无效")
            if(record.state==RecordState.POSTED && (details.feeCents==null || details.feeMode==null))throw WalletException("TRANSFER_FEE_AMBIGUOUS","请确认手续费与费用方式")
            if(amount!=null && details.feeCents!=null && details.feeMode!=null)try { TransferCalculator.calculate(amount,details.feeCents,details.feeMode) }
                catch(error:IllegalArgumentException) { throw WalletException(error.message ?: "INVALID_AMOUNT","转账金额公式无效") }
            if(details.fromAccountUid!=null && details.fromAccountUid==details.toAccountUid)throw WalletException("TRANSFER_SAME_WALLET","转账双方不能为同一钱包")
            listOfNotNull(details.fromAccountUid,details.toAccountUid).forEach { uid->
                val account=getAccountByUid(uid) ?: throw WalletException("TRANSFER_WALLET_REQUIRED","本地关联钱包已不存在")
                if(account.type!=AccountType.ASSET)throw WalletException("TRANSFER_ACCOUNT_UNSUPPORTED","转账仅支持资产钱包")
            }
            if(direction==null && (details.fromAccountUid!=null || details.toAccountUid!=null || record.wallet?.accountUid!=null))
                throw WalletException("TRANSFER_IDENTITY_CONFLICT","待确认交易类型不能预选本地转账钱包")
        } else if(details!=null || bill.transfer!=null)throw WalletException("TRANSFER_IDENTITY_CONFLICT","普通账单不能包含转账专用信息")
    }

    /** A CSV batch is atomic and validates final per-wallet net changes before moving any balance. */
    @Synchronized fun importTransactions(records:List<LedgerRecord>,linkWalletBalances:Boolean=false,
        expectedConfigurationToken:String?=null,submissionToken:String?=null,fileSha256:String="",optionsDigest:String="",
        cancellationCheck:()->Unit={}):List<LedgerRecord> {
        val db=writableDatabase;db.beginTransaction()
        try {
            if(submissionToken!=null) {
                require(submissionToken.isNotBlank() && submissionToken.length<=160)
                db.query("import_batches",arrayOf("kind","result_json"),"submission_token=?",arrayOf(submissionToken),null,null,null).use { c->
                    if(c.moveToFirst()) {
                        require(c.getString(0)=="BILLS") { "提交标识已用于其他操作" }
                        val json=c.getString(1)
                        val uids=if(json.trimStart().startsWith("["))org.json.JSONArray(json) else org.json.JSONObject(json).getJSONArray("recordUids")
                        val recovered=(0 until uids.length()).mapNotNull { getByUid(uids.getString(it)) }
                        db.setTransactionSuccessful();return recovered
                    }
                }
            }
            if(expectedConfigurationToken!=null && expectedConfigurationToken!=walletConfigurationToken())
                throw WalletException("IMPORT_PREVIEW_STALE","账本或钱包已变化，请重新预览")
            val batchUid=UUID.randomUUID().toString()
            val saved=mutableListOf<LedgerRecord>();val deltas=mutableMapOf<String,Long>()
            records.forEach { record->
                cancellationCheck()
                val exists=db.rawQuery("SELECT 1 FROM records WHERE uid=?",arrayOf(record.uid)).use { it.moveToFirst() }
                val sameIdentity=identities(record).any { identity->db.rawQuery(
                    "SELECT 1 FROM record_identities WHERE scope=? AND value=?",arrayOf(identity.scope,identity.value)).use { it.moveToFirst() } }
                if(!exists && !sameIdentity) {
                    validateImportedModel(record)
                    require(record.bill.direction!=Direction.TRANSFER || record.bill.refund==null && record.bill.standaloneRefund==null) { "转账不能包含消费退款" }
                    val id=db.insertOrThrow("records",null,values(record.copy(id=0,duplicateId=null)))
                    persistIdentities(db,id,identities(record));linkMerchantIcon(db,id,record.bill.merchant,null)
                    insertHistory(db,id,RecordCaptureHistory(1,record.capture.receivedAt,
                        if(record.bill.refund==null)"payment" else if(record.bill.amountCents==0L)"full_refund" else "partial_refund",
                        record.bill.amountCents,record.bill.refund?.refundedCents ?: 0L,record.capture.text,false))
                    // Explicit import selections are already part of the reviewed plan. Never trust an imported eligibility.
                    val eligibility=if(linkWalletBalances)WalletEligibility.ACTIVE else WalletEligibility.IMPORT_EXCLUDED
                    val importedLink=record.wallet?.copy(recordUid=record.uid,eligibility=eligibility,appliedAccountUid=null,appliedCents=0,revision=0)
                    if(importedLink!=null)WalletAccounting.saveLink(db,importedLink)
                    val final=syncWallet(db,record.copy(id=id,duplicateId=null),isNew=true,kind="IMPORT",incomingEvidence=null,
                        sourcePackage=null,deferredDeltas=deltas,initialEligibility=eligibility)
                    saved.add(final)
                }
            }
            cancellationCheck()
            WalletAccounting.moveAll(db,deltas,null,"BILL_IMPORT",batchUid,"导入账单批次")
            db.insertOrThrow("import_batches",null,ContentValues().apply {
                put("batch_uid",batchUid);put("kind","BILLS");put("file_sha256",fileSha256)
                put("options_digest",optionsDigest);put("submission_token",submissionToken ?: batchUid)
                put("result_json",org.json.JSONArray(saved.map { it.uid }).toString());put("created_at",System.currentTimeMillis())
            })
            saved.forEach { record->db.insertOrThrow("import_batch_records",null,ContentValues().apply {
                put("batch_uid",batchUid);put("record_uid",record.uid)
            }) }
            cancellationCheck()
            db.setTransactionSuccessful();return saved
        } finally { db.endTransaction() }
    }

    @Synchronized fun walletImportAliases():Map<String,String> = readableDatabase.query("wallet_import_aliases",null,null,null,null,null,"source_uid").use { c->
        buildMap { while(c.moveToNext())put(c.getString(c.getColumnIndexOrThrow("source_uid")),c.getString(c.getColumnIndexOrThrow("local_account_uid"))) }
    }
    /** Alias creation requires an explicit reviewed import mapping, never a same-name guess. */
    @Synchronized fun saveWalletImportAlias(sourceUid:String,localAccountUid:String,replaceExisting:Boolean=false) {
        require(UUID.fromString(sourceUid).toString()==sourceUid) { "来源钱包标识无效" }
        val db=writableDatabase;db.beginTransaction()
        try {
            requireNotNull(getAccountByUid(localAccountUid)) { "映射钱包已不存在" }
            val previous=walletImportAliases()[sourceUid]
            if(previous!=null && previous!=localAccountUid && !replaceExisting)throw WalletException("IMPORT_ACCOUNT_CONFLICT","来源钱包已有不同映射")
            db.insertWithOnConflict("wallet_import_aliases",null,ContentValues().apply {
                put("source_uid",sourceUid);put("local_account_uid",localAccountUid)
            },if(replaceExisting)SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun previewWallet(record:LedgerRecord):WalletResolution {
        val settings=WalletAccounting.settings(readableDatabase)
        return WalletResolver.resolve(accounts().map { WalletCandidate(it.uid,it.type.name,it.kind,it.bankCode,it.cardLast4,it.archived) },
            null,null,settings.defaultAccountUid,settings.appBindings)
    }
    @Synchronized fun walletConfigurationToken():String {
        val settings=WalletAccounting.settings(readableDatabase)
        // Account revisions also cover balance changes that can invalidate an import at the limits.
        val value=buildString {
            append(settings.revision);append('|');append(settings.defaultAccountUid);append('|')
            settings.appBindings.toSortedMap().forEach { (pkg,uid)->append(pkg);append('=');append(uid);append(';') }
            accounts().forEach { append(it.uid);append(':');append(it.revision);append(';') }
            readableDatabase.rawQuery("SELECT r.uid,COALESCE(w.revision,0),d.expires_at FROM records r LEFT JOIN record_wallet_links w ON w.record_uid=r.uid LEFT JOIN record_deletions d ON d.record_id=r.id ORDER BY r.uid",null).use { c->
                while(c.moveToNext()) { append(c.getString(0));append(':');append(c.getLong(1));append(':');append(if(c.isNull(2))"" else c.getLong(2));append(';') }
            }
            walletImportAliases().forEach { (source,target)->append(source);append('=');append(target);append(';') }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun accountValues(account: LedgerAccount) = ContentValues().apply {
        put("uid", account.uid); put("name", account.name); put("description", account.description)
        put("amount", account.amountCents); put("kind", account.kind); put("type", account.type.name)
        put("bank_code",account.bankCode ?: AccountBanks.codeForKind(account.kind));put("card_last4",account.cardLast4)
        put("archived",if(account.archived)1 else 0);put("revision",account.revision)
    }

    private fun categoryValues(category: CustomCategory) = ContentValues().apply {
        put("name", category.name); put("direction", category.direction.name)
    }

    private fun readAccount(cursor: Cursor) = LedgerAccount(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        uid = cursor.getString(cursor.getColumnIndexOrThrow("uid")),
        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
        amountCents = cursor.getLong(cursor.getColumnIndexOrThrow("amount")),
        kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
        type = AccountType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
        bankCode=cursor.getString(cursor.getColumnIndexOrThrow("bank_code")),cardLast4=cursor.getString(cursor.getColumnIndexOrThrow("card_last4")),
        archived=cursor.getInt(cursor.getColumnIndexOrThrow("archived"))==1,revision=cursor.getLong(cursor.getColumnIndexOrThrow("revision")))

    private fun query(where: String?, args: Array<String>?): List<LedgerRecord> = readableDatabase.query(
        "records", null, "NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=records.id)"+(where?.let { " AND ($it)" }?:""), args, null, null, "occurred_at DESC, id DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(withWallet(read(cursor))) }
    }

    private fun values(r: LedgerRecord) = ContentValues().apply {
        put("uid", r.uid); put("source", r.capture.source.name); put("origin", r.capture.origin)
        put("raw_text", r.capture.text); put("received_at", r.capture.receivedAt)
        put("amount", r.bill.amountCents); put("direction", r.bill.direction?.name)
        put("merchant", r.bill.merchant); put("category", r.bill.category)
        put("category_id", CategoryDirectory.ensure(writableDatabase, r.bill.direction, r.bill.category)?.id)
        put("occurred_at", r.bill.occurredAt); put("transaction_id", r.bill.transactionId)
        put("reliable", if (r.bill.reliable) 1 else 0); put("time_explicit", if (r.bill.timeExplicit) 1 else 0)
        put("state", r.state.name); put("reason", r.reason); put("duplicate_id", r.duplicateId)
        put("note", r.note);put("standalone_refund",encodeStandaloneRefund(r.bill.standaloneRefund))
        put("receipt_evidence",ReceiptEvidenceCodec.encode(r.bill.receipt))
        put("category_decision",r.bill.categoryDecision?.let { ClassificationCodec.decision(it.copy(categoryId=CategoryDirectory.ensure(writableDatabase,r.bill.direction,r.bill.category)?.id)).toString() })
        put("transaction_identities",TransactionIdentities.encode(identities(r)))
        put("refund_provider", r.bill.refund?.provider);put("refund_original", r.bill.refund?.originalAmountCents)
        put("refund_amount", r.bill.refund?.refundedCents);put("refund_mode",r.bill.refund?.mode);put("refund_id",r.bill.refund?.refundId)
    }

    private fun read(c: Cursor): LedgerRecord {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        fun optionalNumber(key: String): Long? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getLong(it) }
        fun optionalText(key: String): String? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getString(it) }
        return LedgerRecord(number("id"), text("uid"), Capture(Source.valueOf(text("source")),
            text("origin"), text("raw_text"), number("received_at")),
            ParsedBill(optionalNumber("amount"), optionalText("direction")?.let(Direction::valueOf),
                text("merchant"), text("category"), number("occurred_at"), optionalText("transaction_id"),
                number("reliable") == 1L, text("reason"), number("time_explicit") == 1L,
                optionalText("refund_provider")?.let { RefundInfo(it,number("refund_original"),number("refund_amount"),text("refund_mode"),optionalText("refund_id")) },
                standaloneRefund=c.getColumnIndex("standalone_refund").takeIf { it>=0 && !c.isNull(it) }?.let { decodeStandaloneRefund(c.getString(it)) },
                categoryDecision=c.getColumnIndex("category_decision").takeIf { it>=0 && !c.isNull(it) }?.let { ClassificationCodec.readDecision(org.json.JSONObject(c.getString(it))) },
                receipt=c.getColumnIndex("receipt_evidence").takeIf { it>=0 && !c.isNull(it) }?.let { ReceiptEvidenceCodec.decode(c.getString(it)) }),
            RecordState.valueOf(text("state")), text("reason"), optionalNumber("duplicate_id"), text("note"),
            TransactionIdentities.decode(text("transaction_identities")))
    }
}
