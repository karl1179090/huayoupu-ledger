package net.ankio.ledger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.InstalledAppsRepository
import net.ankio.ledger.capture.ShakeAuthorization
import net.ankio.ledger.data.RecognitionTasks
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated preferences; installed package metadata only, never real application content. */
@RunWith(AndroidJUnit4::class)
class ShakeWhitelistTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private val preferenceName="shake-whitelist-${UUID.randomUUID()}"
    private lateinit var settingsContext:Context
    private lateinit var settings:ChannelSettings
    private val installedFixture get()=InstrumentationRegistry.getInstrumentation().context.packageName

    @Before fun setup() {
        settingsContext=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=
                super.getSharedPreferences("$preferenceName-$name",mode)
        }
        settings=ChannelSettings(settingsContext)
    }
    @After fun cleanup() { app.deleteSharedPreferences("$preferenceName-channels") }

    @Test fun firstInstallAndLegacyAutomaticConsentNeverPreselectShakeApplications() {
        settingsContext.getSharedPreferences("channels",0).edit().putStringSet("auto_apps_v1",setOf("com.tencent.mm"))
            .putBoolean("automatic_enabled",true).putBoolean("ocr",true).commit()
        val reopened=ChannelSettings(settingsContext)
        assertEquals(setOf("com.tencent.mm"),reopened.selectedAutoPackages())
        assertTrue(reopened.selectedShakePackages().isEmpty())
        assertFalse(reopened.shakeAllowed("com.tencent.mm"))
        assertNull(reopened.shakeAuthorization("com.tencent.mm"))
    }

    @Test fun revokeAndReaddInvalidateOldTaskAcrossSettingsRecreation() {
        settings.set(ChannelSettings.Channel.OCR,true)
        settings.setShakePackage(installedFixture,true)
        val previous=settings.shakeAuthorization(installedFixture)!!
        assertTrue(settings.shakeAuthorized(previous))
        settings.setShakePackage(installedFixture,false)
        settings.setShakePackage(installedFixture,true)
        val reopened=ChannelSettings(settingsContext)
        assertTrue(reopened.shakeAllowed(installedFixture))
        assertFalse(reopened.shakeAuthorized(previous))
        assertTrue(reopened.shakeApplicationGeneration(installedFixture)>previous.applicationGeneration)
        assertEquals(settings.shakeMasterGeneration(),reopened.shakeMasterGeneration())
    }

    @Test fun switchingMasterOffAndOnCannotRevivePreviouslyCapturedAuthorization() {
        settings.setShakePackage(installedFixture,true)
        settings.set(ChannelSettings.Channel.OCR,true)
        val old=settings.shakeAuthorization(installedFixture)!!
        settings.set(ChannelSettings.Channel.OCR,false)
        settings.set(ChannelSettings.Channel.OCR,true)
        val reopened=ChannelSettings(settingsContext)
        assertTrue(reopened.shakeAllowed(installedFixture))
        assertFalse(reopened.shakeAuthorized(old))
        assertTrue(reopened.shakeMasterGeneration()>old.masterGeneration)
    }

    @Test fun selectionDoesNotGrantAnUninstalledOrForbiddenSurface() {
        for(pkg in listOf(app.packageName,"com.android.systemui","android","invalid", "net.ankio.never.installed")) {
            try { settings.setShakePackage(pkg,true);fail("Selection accepted forbidden package $pkg") }
            catch(_:IllegalArgumentException) { }
            assertFalse(settings.shakeAllowed(pkg));assertFalse(pkg in settings.selectedShakePackages())
        }
    }

    @Test fun durableAuthorizationUsesStrictTypesAndBoundedPackageNames() {
        val value=ShakeAuthorization(installedFixture,17,23)
        assertEquals(value,ShakeAuthorization.decode(value.encode()))
        assertNull(ShakeAuthorization.decode("{}"))
        assertNull(ShakeAuthorization.decode("{\"type\":\"SHAKE\",\"packageName\":\"$installedFixture\",\"masterGeneration\":\"17\",\"applicationGeneration\":23}"))
        assertNull(ShakeAuthorization.decode("{\"type\":\"SHAKE\",\"packageName\":\"$installedFixture\",\"masterGeneration\":-1,\"applicationGeneration\":23}"))
        assertNull(ShakeAuthorization.decode("{\"type\":\"PAYMENT_AUTO\",\"packageName\":\"$installedFixture\",\"masterGeneration\":17,\"applicationGeneration\":23}"))
        assertNull(ShakeAuthorization.decode("x".repeat(4_096)))
    }
    @Test fun shortcutAuthorizationIsIndependentOfTheMotionSwitchButRevokesWithPermissionOrApplication() {
        settings.setShakePackage(installedFixture,true)
        val shortcut=settings.shortcutAuthorization(installedFixture)!!
        assertTrue(shortcut.shortcut)
        assertEquals(shortcut,ShakeAuthorization.decode(shortcut.encode()))
        assertEquals("shortcut:$installedFixture",shortcut.origin)
        assertTrue(settings.shakeAuthorized(shortcut));assertFalse(settings.shakeAllowed(installedFixture))
        settings.set(ChannelSettings.Channel.OCR,true);settings.set(ChannelSettings.Channel.OCR,false)
        assertTrue(ChannelSettings(settingsContext).shakeAuthorized(shortcut))
        settings.invalidateShortcuts()
        assertFalse(settings.shakeAuthorized(shortcut))
        val fresh=settings.shortcutAuthorization(installedFixture)!!
        settings.setShakePackage(installedFixture,false);settings.setShakePackage(installedFixture,true)
        assertFalse(settings.shakeAuthorized(fresh))
    }
    @Test fun aReplacementInstallationRequiresFreshSelectionAndCannotReuseTheOldTask() {
        settings.set(ChannelSettings.Channel.OCR,true);settings.setShakePackage(installedFixture,true)
        val old=settings.shakeAuthorization(installedFixture)!!
        // Represent the persisted identity of an installation that has since been replaced.
        settingsContext.getSharedPreferences("channels",0).edit().putString("shake_installation_v1:$installedFixture","previous-installation").commit()
        assertFalse(settings.shakeAllowed(installedFixture))
        settings.reconcileShakePackages()
        assertFalse(installedFixture in settings.selectedShakePackages())
        settings.setShakePackage(installedFixture,true)
        assertFalse(settings.shakeAuthorized(old))
    }
    @Test fun legacyRecognitionDatabaseCancelsUnconsentedShakeWithoutChangingAutomaticQueue() {
        val name="shake-recognition-migration-${UUID.randomUUID()}.db"
        val database=SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name).also { it.parentFile?.mkdirs() },null)
        database.use { db ->
            db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,origin TEXT NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,record_id INTEGER,image BLOB,diagnostic TEXT NOT NULL DEFAULT '{}')")
            db.execSQL("INSERT INTO tasks(source,origin,created_at,status,message) VALUES('OCR',?,1,'QUEUED','legacy')",arrayOf("shake:$installedFixture"))
            db.execSQL("INSERT INTO tasks(source,origin,created_at,status,message) VALUES('OCR','payment_auto:com.tencent.mm',1,'QUEUED','automatic')")
            db.version=3
        }
        try {
            RecognitionTasks(app,name).use { tasks ->
                tasks.recoverInterrupted()
                assertEquals(8,tasks.readableDatabase.version)
                assertEquals("CANCELLED",tasks.status(1));assertEquals("QUEUED",tasks.status(2))
                assertNull(tasks.shakeAuthorization(1))
                assertEquals(listOf(2L to "payment_auto:com.tencent.mm"),tasks.pendingQueued())
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun installedCatalogIncludesNonLauncherPackagesAndNeverExposesRawIconsInRows() {
        val repository=InstalledAppsRepository.get(app)
        val page=repository.page(installedFixture,0,60,true)
        val rows=page.getJSONArray("applications")
        val fixture=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("packageName")==installedFixture }
        assertEquals(installedFixture,fixture.getString("packageName"))
        assertTrue(fixture.getBoolean("selectable"))
        assertFalse(fixture.toString().contains("base64"))
        val icon=fixture.optString("iconUrl")
        assertTrue(icon.matches(Regex("/installed-app-icon/[a-f0-9]{64}\\.png")))
        assertNull(repository.iconAsset("../channels.xml"))
        assertNull(repository.iconAsset("a".repeat(65)))
        assertTrue(page.getLong("version")>0)
        assertFalse(page.getBoolean("hasMore"))
    }
}
