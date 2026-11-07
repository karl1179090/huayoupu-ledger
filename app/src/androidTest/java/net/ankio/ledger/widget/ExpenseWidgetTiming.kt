package net.ankio.ledger.widget

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Shared CI emulators record timings; strict two-second acceptance remains the default. */
internal object ExpenseWidgetTiming {
    fun assertPublication(label:String,elapsedMs:Long) {
        val mode=InstrumentationRegistry.getArguments().getString("ledgerWidgetTiming","strict")
        assertTrue("$label invalid elapsed time $elapsedMs ms",elapsedMs>=0)
        println("S7_TIMING label=$label elapsedMs=$elapsedMs targetMs=2000 mode=$mode withinTarget=${elapsedMs<2000}")
        when(mode) {
            "strict" -> assertTrue("$label $elapsedMs ms",elapsedMs<2000)
            "record" -> Unit
            else -> error("Unknown ledgerWidgetTiming: $mode")
        }
    }
}
