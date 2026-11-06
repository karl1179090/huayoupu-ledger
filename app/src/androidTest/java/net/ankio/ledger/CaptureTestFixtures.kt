package net.ankio.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.data.LedgerAccount
import org.junit.Assert.assertFalse
import org.junit.rules.ExternalResource
import java.util.UUID

/** Explicit wallet for old capture cases that assert POSTED; cleans only its own synthetic data. */
class CaptureWalletFixture:ExternalResource() {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private var previous:String?=null
    private var uid:String?=null
    override fun before() {
        previous=app.store.walletSettings().defaultAccountUid
        val account=app.store.saveAccount(LedgerAccount(name="合成采集测试${UUID.randomUUID().toString().take(8)}",amountCents=0))
        uid=account.uid;app.store.setDefaultWallet(account.uid)
    }
    override fun after() {
        app.store.setDefaultWallet(previous)
        val syntheticUid=uid?:return
        synchronized(app.store) {
            val db=app.store.writableDatabase
            assertFalse("The capture test must clean its synthetic records before the wallet",db.rawQuery(
                "SELECT 1 FROM record_wallet_links WHERE account_uid=? OR applied_account_uid=? LIMIT 1",arrayOf(syntheticUid,syntheticUid)).use { it.moveToFirst() })
            db.beginTransaction()
            try {
                db.delete("wallet_movements","account_uid=?",arrayOf(syntheticUid))
                db.delete("accounts","uid=?",arrayOf(syntheticUid))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        app.changed()
    }
}

/** A separately installed, safe non-launcher APK makes legacy shake intent explicit. */
class CaptureWhitelistFixture:ExternalResource() {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    val packageName get()=InstrumentationRegistry.getInstrumentation().context.packageName
    private var previous=emptySet<String>()
    override fun before() { previous=app.channels.selectedShakePackages();app.channels.setShakePackage(packageName,true) }
    override fun after() {
        app.channels.selectedShakePackages().filter { it !in previous }.forEach { app.channels.setShakePackage(it,false) }
        previous.forEach { app.channels.setShakePackage(it,true) }
        app.changed()
    }
}
