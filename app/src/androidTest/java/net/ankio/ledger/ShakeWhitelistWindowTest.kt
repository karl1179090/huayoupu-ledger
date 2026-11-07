package net.ankio.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.capture.ShakeWindowObservation
import net.ankio.ledger.capture.resolveShakeTarget
import net.ankio.ledger.capture.shakeWindowEventChangesTarget
import android.view.accessibility.AccessibilityEvent as Event
import android.view.accessibility.AccessibilityWindowInfo as Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The probe callback returns metadata only; this gate never requests page text or pixels. */
@RunWith(AndroidJUnit4::class)
class ShakeWhitelistWindowTest {
    private val allowed="com.example.allowed"

    @Test fun knownUnselectedForegroundStopsBeforeWaitingForASelectedApplication()=runBlocking {
        var reads=0
        val result=resolveShakeTarget({ ++reads;ShakeWindowObservation(7,11,if(reads==1)"com.example.unselected" else allowed) }) { it==allowed }
        assertNull(result);assertEquals(1,reads)
    }

    @Test fun unknownOwnerCanResolveOnlyWithinTheInitiallyObservedWindow()=runBlocking {
        var reads=0
        val result=resolveShakeTarget({ ++reads;ShakeWindowObservation(7,11,if(reads==1)null else allowed) }) { it==allowed }
        assertEquals(allowed,result?.packageName);assertEquals(7,result?.windowId);assertEquals(2,reads)
    }

    @Test fun replacingAnUnknownWindowCannotTurnAnEarlierShakeIntoAuthorization()=runBlocking {
        var reads=0
        val result=resolveShakeTarget({ ++reads;if(reads==1)ShakeWindowObservation(7,11,null) else ShakeWindowObservation(8,12,allowed) }) { it==allowed }
        assertNull(result);assertEquals(2,reads)
    }

    @Test fun sameWindowIdAfterSwitchStillRejectsTheOldShake()=runBlocking {
        var reads=0
        val result=resolveShakeTarget({ ++reads;ShakeWindowObservation(7,if(reads==1)11 else 12,if(reads==1)null else allowed) }) { it==allowed }
        assertNull(result);assertEquals(2,reads)
    }

    @Test fun noInitialWindowFailsClosedWithoutFollowingLaterForeground()=runBlocking {
        var reads=0
        val result=resolveShakeTarget({ ++reads;if(reads==1)ShakeWindowObservation(null,11,null) else ShakeWindowObservation(8,12,allowed) }) { it==allowed }
        assertNull(result);assertEquals(1,reads)
    }

    @Test fun unknownOwnershipHasABoundedProbeCount()=runBlocking {
        var reads=0
        assertNull(resolveShakeTarget({ ++reads;ShakeWindowObservation(7,11,null) }) { it==allowed })
        assertTrue(reads in 2..8)
    }
    @Test fun delayedRemovalOfThePreviousActivityCannotInvalidateTheCurrentSafeWindow() {
        assertFalse(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_REMOVED,123,127,Window.TYPE_APPLICATION))
        assertTrue(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_REMOVED,127,127,Window.TYPE_APPLICATION))
        assertTrue(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_REMOVED,123,null,Window.TYPE_APPLICATION))
    }
    @Test fun backgroundRemovalDoesNotHideFocusChangesAndFeedbackNeverBecomesForeground() {
        assertTrue(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_FOCUSED,123,127,Window.TYPE_APPLICATION))
        assertTrue(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_ACTIVE,123,127,Window.TYPE_APPLICATION))
        assertTrue(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_ADDED,123,127,null))
        assertFalse(shakeWindowEventChangesTarget(Event.TYPE_WINDOWS_CHANGED,Event.WINDOWS_CHANGE_REMOVED,123,127,Window.TYPE_ACCESSIBILITY_OVERLAY))
    }
}
