package net.ankio.ledger.core

/** Original bitmap coordinates of the selected business evidence, never a source of financial fields. */
data class ReceiptBlockOrigin(
    val kind:String,
    val pageWidth:Int,
    val pageHeight:Int,
    val left:Int,
    val top:Int,
    val right:Int,
    val bottom:Int,
) {
    init {
        require(kind in setOf("MIXED_DEBIT","NESTED_BANK"))
        require(pageWidth>0 && pageHeight>0)
        require(left>=0 && top>=0 && right>left && right<=pageWidth && bottom>top && bottom<=pageHeight)
    }
}
