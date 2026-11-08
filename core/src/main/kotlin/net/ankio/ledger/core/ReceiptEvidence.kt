package net.ankio.ledger.core

/** A field printed in one receipt block, or supplied by the capture envelope. Missing stays null. */
data class ReceiptField(
    val value: String,
    val rawText: String,
    val source: String = "ocr",
    val blockId: String = "single",
) {
    init {
        require(value.isNotBlank() && value.length <= 512)
        require(rawText.isNotBlank() && rawText.length <= 2048)
        require(source in setOf("ocr", "node", "capture"))
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(blockId))
    }
}

/** Business evidence is independent of ledger notes, classification, time and wallet decisions. */
data class ReceiptEvidence(
    val version: Int = 1,
    val blockId: String = "single",
    val merchant: ReceiptField? = null,
    val productDescription: ReceiptField? = null,
    val transactionDescription: ReceiptField? = null,
    val orderRemark: ReceiptField? = null,
    val serviceProvider: ReceiptField? = null,
    val receiptIssuer: ReceiptField? = null,
    val sourceApp: ReceiptField? = null,
) {
    init {
        require(version == 1 && Regex("[A-Za-z0-9_-]{1,80}").matches(blockId))
        require(listOfNotNull(merchant, productDescription, transactionDescription, orderRemark,
            serviceProvider, receiptIssuer, sourceApp).all { it.blockId == blockId })
    }
}
