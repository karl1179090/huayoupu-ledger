package net.ankio.ledger.data

/** Wallet configuration belongs to the ledger transaction, never to SharedPreferences. */
class WalletSettingsStore(private val ledger: LedgerStore) {
    fun get(): WalletSettings = ledger.walletSettings()
    fun setDefault(accountUid: String?): WalletSettings = ledger.setDefaultWallet(accountUid)
    fun bindApp(packageName: String, accountUid: String?): WalletSettings = ledger.setAppWallet(packageName, accountUid)
}
