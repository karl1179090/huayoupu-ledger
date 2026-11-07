package net.ankio.ledger.core

/** Structured bank identity is independent of the small built-in icon collection. */
object BankCodes {
    data class Bank(val code: String, val label: String)
    private val aliases = linkedMapOf(
        "icbc" to listOf("中国工商银行", "工商银行", "工行"),
        "abc" to listOf("中国农业银行", "农业银行", "农行"),
        "boc" to listOf("中国银行", "中行"),
        "ccb" to listOf("中国建设银行", "建设银行", "建行"),
        "bocom" to listOf("交通银行", "交行"),
        "cmb" to listOf("招商银行", "招行"),
        "citic" to listOf("中信银行", "中信"),
        "ceb" to listOf("中国光大银行", "光大银行", "光大"),
        "cmbc" to listOf("中国民生银行", "民生银行", "民生"),
        "spdb" to listOf("上海浦东发展银行", "浦发银行", "浦发"),
        "cib" to listOf("兴业银行", "兴业"),
        "pab" to listOf("平安银行"),
        "cgb" to listOf("广发银行", "广东发展银行", "广发"),
        "hxb" to listOf("华夏银行", "华夏"),
        "psbc" to listOf("中国邮政储蓄银行", "邮政储蓄银行", "邮储银行", "邮储"),
        "bob" to listOf("北京银行"),
        "bosh" to listOf("上海银行"),
        "nbcb" to listOf("宁波银行"),
        "hzcb" to listOf("杭州银行"),
        "jsb" to listOf("江苏银行"),
        "njcb" to listOf("南京银行"),
        "cbhb" to listOf("渤海银行"),
        "czb" to listOf("浙商银行"),
        "hsbc" to listOf("汇丰银行", "汇丰"),
    )
    val entries: List<Bank> get() = aliases.map { (code, names) -> Bank(code, names.first()) }

    fun normalize(value: String?): String? {
        val text = value?.let { net.ankio.ledger.core.normalize(it) }?.trim()?.lowercase()?.removePrefix("bank_") ?: return null
        if (text in aliases) return text
        return aliases.entries.singleOrNull { (_, names) -> text in names }?.key
    }

    internal fun find(value: String): Set<String> = aliases.filterValues { names -> names.any(value::contains) }.keys
}
