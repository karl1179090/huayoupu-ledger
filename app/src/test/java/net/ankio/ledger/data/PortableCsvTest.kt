package net.ankio.ledger.data

import net.ankio.ledger.core.Money
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PortableCsvTest {
    @Test fun quotedUnicodeMultilineFormulaAndLeadingZeroTextRoundTrip() {
        val cells=listOf("0007","123456789012345678","=SUM(1,2)","  +1","'普通","备注,\"尺码\"\r\n第二行🙂")
        val output=ByteArrayOutputStream();PortableCsv.write(sequenceOf(cells),output)
        val bytes=output.toByteArray();assertTrue(bytes.toString(Charsets.UTF_8).contains("\"'0007\""))
        assertEquals(cells,PortableCsv.read(bytes.inputStream()).first.single().map(PortableCsv::unescape))
    }
    @Test fun limitsAndMalformedInputsAreRejectedBeforeAnyImport() {
        for(bytes in listOf(byteArrayOf(0xC3.toByte(),0x28),"\"unterminated".toByteArray(),"a\u0000b".toByteArray(),ByteArray(PortableCsv.MAX_BYTES+1) { 65 }))
            assertNotNull(runCatching { PortableCsv.read(bytes.inputStream()) }.exceptionOrNull())
        val tooMany=(0..PortableCsv.MAX_ROWS+1).joinToString("\n") { "row" }
        assertNotNull(runCatching { PortableCsv.read(tooMany.byteInputStream()) }.exceptionOrNull())
    }
    @Test fun decimalCentsAreExactSignedAndNeverRounded() {
        assertEquals(-5000L,PortableCsv.cents("-50.00",signed=true));assertEquals(Money.MAX_CENTS,PortableCsv.cents(Money.format(Money.MAX_CENTS)))
        for(value in listOf("1.001","1e2","NaN","-1","+1","=1+2"))assertNotNull(runCatching { PortableCsv.cents(value) }.exceptionOrNull())
    }
    @Test fun walletExportRetainsFourDigitTailNegativeAssetsAndDoesNotCloseOutput() {
        val output=object:ByteArrayOutputStream() { var closed=false;override fun close() { closed=true;super.close() } }
        WalletCsvExport.write(listOf(LedgerAccount(name="合成",amountCents=-5000,kind="bank",bankCode="cmb",cardLast4="0007")),output)
        assertFalse(output.closed);val rows=PortableCsv.read(output.toByteArray().inputStream()).first
        val fields=rows[0].zip(rows[1].map(PortableCsv::unescape)).toMap();assertEquals("0007",fields["银行卡尾号"]);assertEquals("-50.00",fields["余额"])
    }
}
