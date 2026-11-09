package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class RecognitionEvidenceTest {
    @Test fun missingScreenshotCannotClaimAnOcrRouteInPortableReceiptEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionEvidence("OCR","unknown_layout",screenshot=ScreenshotEvidence("TIMEOUT","TIMEOUT"))
        }
    }

    @Test fun ordinaryFailureHasAnExplicitReasonAndVerifiedNodeProvenance() {
        val adapter=NodeAdapterEvidence("synthetic","synthetic-comparison","synthetic only","1",1,"test-api30","test-device")
        val snapshot=NodeSnapshotEvidence("0".repeat(64),"synthetic.example","synthetic.Result",7,1_799_400_000_000,2)
        val timeout=RecognitionEvidence("NODE","verified_layout",adapter,snapshot,ScreenshotEvidence("TIMEOUT","TIMEOUT"))
        assertEquals("TIMEOUT",timeout.screenshot.state)
        assertThrows(IllegalArgumentException::class.java) { timeout.copy(screenshot=ScreenshotEvidence("UNAVAILABLE",null)) }
    }
}
