package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.FeeMode
import net.ankio.ledger.core.TransferTarget

/** The local selections and bounded source references belonging to one transfer bill. */
data class RecordTransferDetails(
    val recordUid: String,
    val fromAccountUid: String? = null,
    val toAccountUid: String? = null,
    val feeCents: Long? = null,
    val feeMode: FeeMode? = null,
    val fromSelectionSource: String? = null,
    val toSelectionSource: String? = null,
    val fromManualLocked: Boolean = false,
    val toManualLocked: Boolean = false,
    val fromEvidence: String? = null,
    val toEvidence: String? = null,
    val fromEvidenceFingerprint: String? = null,
    val toEvidenceFingerprint: String? = null,
    val fromReference: String? = null,
    val toReference: String? = null,
    val evidenceVersion: Int = 1,
    val reviewCode: String? = null,
    val feeManualLocked: Boolean = false,
) {
    fun target() = TransferTarget(fromAccountUid, toAccountUid, feeCents, feeMode)
}

internal object TransferAccounting {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE record_transfer_details(record_uid TEXT PRIMARY KEY NOT NULL,
            from_account_uid TEXT,to_account_uid TEXT,fee_cents INTEGER,fee_mode TEXT CHECK(fee_mode IN ('EXTRA','DEDUCTED')),
            from_selection_source TEXT,to_selection_source TEXT,from_manual_locked INTEGER NOT NULL DEFAULT 0,
            to_manual_locked INTEGER NOT NULL DEFAULT 0,from_evidence TEXT,to_evidence TEXT,
            from_evidence_fingerprint TEXT,to_evidence_fingerprint TEXT,from_reference TEXT,to_reference TEXT,
            evidence_version INTEGER NOT NULL DEFAULT 1,review_code TEXT,fee_manual_locked INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX transfer_from_account ON record_transfer_details(from_account_uid)")
        db.execSQL("CREATE INDEX transfer_to_account ON record_transfer_details(to_account_uid)")
    }
    fun read(db: SQLiteDatabase, uid: String): RecordTransferDetails? = db.query("record_transfer_details", null,
        "record_uid=?", arrayOf(uid), null, null, null).use { c ->
        if (!c.moveToFirst()) return@use null
        fun text(key: String): String? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getString(it) }
        fun flag(key: String) = c.getInt(c.getColumnIndexOrThrow(key)) == 1
        RecordTransferDetails(uid, text("from_account_uid"), text("to_account_uid"),
            c.getColumnIndexOrThrow("fee_cents").let { if (c.isNull(it)) null else c.getLong(it) },
            text("fee_mode")?.let(FeeMode::valueOf), text("from_selection_source"), text("to_selection_source"),
            flag("from_manual_locked"), flag("to_manual_locked"), text("from_evidence"), text("to_evidence"),
            text("from_evidence_fingerprint"), text("to_evidence_fingerprint"), text("from_reference"), text("to_reference"),
            c.getInt(c.getColumnIndexOrThrow("evidence_version")), text("review_code"), flag("fee_manual_locked"))
    }
    fun save(db: SQLiteDatabase, details: RecordTransferDetails) {
        require(details.evidenceVersion > 0)
        listOf(details.fromEvidence, details.toEvidence, details.fromReference, details.toReference).forEach {
            require(it == null || it.length <= 8192) { "转账引用过长" }
        }
        db.insertWithOnConflict("record_transfer_details", null, ContentValues().apply {
            put("record_uid", details.recordUid); put("from_account_uid", details.fromAccountUid); put("to_account_uid", details.toAccountUid)
            put("fee_cents", details.feeCents); put("fee_mode", details.feeMode?.name)
            put("from_selection_source", details.fromSelectionSource); put("to_selection_source", details.toSelectionSource)
            put("from_manual_locked", if (details.fromManualLocked) 1 else 0); put("to_manual_locked", if (details.toManualLocked) 1 else 0)
            put("from_evidence", details.fromEvidence); put("to_evidence", details.toEvidence)
            put("from_evidence_fingerprint", details.fromEvidenceFingerprint); put("to_evidence_fingerprint", details.toEvidenceFingerprint)
            put("from_reference", details.fromReference); put("to_reference", details.toReference)
            put("evidence_version", details.evidenceVersion); put("review_code", details.reviewCode)
            put("fee_manual_locked", if (details.feeManualLocked) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
}
