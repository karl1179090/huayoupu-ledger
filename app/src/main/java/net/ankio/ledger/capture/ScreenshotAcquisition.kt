package net.ankio.ledger.capture

import net.ankio.ledger.ocr.RecognitionException

/** T13 may continue only the ordinary failures, with an already reliable gated node result. */
enum class ScreenshotFailureReason { UNAVAILABLE, TIMEOUT, PROTECTED, AUTHORIZATION_CHANGED, WINDOW_CHANGED }
class ScreenshotAcquisitionException(val reason:ScreenshotFailureReason,message:String):RecognitionException(message) {
    val ordinary:Boolean get()=reason in setOf(ScreenshotFailureReason.UNAVAILABLE,ScreenshotFailureReason.TIMEOUT)
}
