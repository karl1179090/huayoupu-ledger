package net.ankio.ledger.data

/** Existing transaction tests opt into a synthetic wallet explicitly. Production never creates one. */
internal fun LedgerStore.prepareSyntheticDefaultWallet(): LedgerStore = apply {
    if (walletSettings().defaultAccountUid == null) {
        val wallet = saveAccount(LedgerAccount(name = "合成回归钱包", amountCents = 100_000_000, kind = "cash"))
        setDefaultWallet(wallet.uid)
    }
}

internal fun LedgerRecord.asLegacyExcluded() = copy(
    wallet = RecordWalletLink(uid, WalletEligibility.LEGACY_EXCLUDED),
    bill = bill.copy(paymentMethod = null),
    walletEffects = emptyMap(),
)

/** Reconstruct actual old tables, rather than merely changing user_version on a current database. */
internal fun LedgerStore.rebuildPreWalletSchemaForTest(version: Int) {
    require(version in 2..8)
    val db = writableDatabase
    db.beginTransaction()
    try {
        val refundColumns = if (version >= 7) ",refund_provider TEXT,refund_original INTEGER,refund_amount INTEGER,refund_mode TEXT,refund_id TEXT" else ""
        val identityColumn = if (version >= 8) ",transaction_identities TEXT NOT NULL DEFAULT '[]'" else ""
        val baseColumns = "id,uid,source,origin,raw_text,received_at,amount,direction,merchant,category,occurred_at,transaction_id,reliable,time_explicit,state,reason,duplicate_id,note"
        val copyColumns = baseColumns + (if (version >= 7) ",refund_provider,refund_original,refund_amount,refund_mode,refund_id" else "") +
            (if (version >= 8) ",transaction_identities" else "")
        db.execSQL("""CREATE TABLE records_pre_wallet (
            id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,source TEXT NOT NULL,origin TEXT NOT NULL,
            raw_text TEXT NOT NULL,received_at INTEGER NOT NULL,amount INTEGER,direction TEXT,merchant TEXT NOT NULL,
            category TEXT NOT NULL,occurred_at INTEGER NOT NULL,transaction_id TEXT,reliable INTEGER NOT NULL,
            time_explicit INTEGER NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,duplicate_id INTEGER,note TEXT NOT NULL
            $refundColumns$identityColumn)""")
        db.execSQL("INSERT INTO records_pre_wallet($copyColumns) SELECT $copyColumns FROM records")
        db.execSQL("DROP TABLE records")
        db.execSQL("ALTER TABLE records_pre_wallet RENAME TO records")
        db.execSQL("CREATE INDEX records_time ON records(occurred_at)")
        db.execSQL("CREATE INDEX records_transaction ON records(transaction_id)")
        db.execSQL("CREATE INDEX records_received ON records(received_at)")
        val sequence = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='accounts'", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        db.execSQL("""CREATE TABLE accounts_pre_wallet(id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL,description TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>=0),kind TEXT NOT NULL,
            type TEXT NOT NULL CHECK(type IN ('ASSET','LIABILITY')))""")
        db.execSQL("INSERT INTO accounts_pre_wallet(id,uid,name,description,amount,kind,type) SELECT id,uid,name,description,amount,kind,type FROM accounts")
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE accounts_pre_wallet RENAME TO accounts")
        db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='accounts'", arrayOf(sequence))
        listOf("classification_configuration", "classification_snapshots", "category_directory", "record_wallet_links", "wallet_settings", "app_wallet_bindings", "wallet_movements", "wallet_submissions",
            "record_wallet_effects", "record_transfer_details", "import_batches", "import_batch_records", "wallet_import_aliases").forEach {
            db.execSQL("DROP TABLE $it")
        }
        if (version < 8) listOf("record_identities", "record_deletions").forEach { db.execSQL("DROP TABLE $it") }
        if (version < 7) db.execSQL("DROP TABLE capture_history")
        db.version = version
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
