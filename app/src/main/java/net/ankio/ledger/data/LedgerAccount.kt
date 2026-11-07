package net.ankio.ledger.data

import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import java.util.UUID

enum class AccountType { ASSET, LIABILITY }

/** Wallet balances use signed cents for assets and nonnegative amounts for liabilities. */
data class LedgerAccount(
    val id: Long = 0,
    val uid: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val amountCents: Long,
    val kind: String = "custom",
    val type: AccountType = AccountType.ASSET,
    val bankCode: String? = null,
    val cardLast4: String? = null,
    val archived: Boolean = false,
    val revision: Long = 0,
) {
    fun validate() {
        require(id >= 0) { "账户编号无效" }
        require(UUID.fromString(uid).toString() == uid) { "账户标识无效" }
        require(name.isNotBlank() && name.length <= 40 && name == name.trim()) { "账户名称需为 1 至 40 个字符" }
        require(description.length <= 80) { "账户说明最多 80 个字符" }
        require(amountCents in (if (type == AccountType.ASSET) -Money.MAX_CENTS else 0L)..Money.MAX_CENTS) { "账户余额无效" }
        require(kind in KINDS) { "账户类型无效" }
        require(bankCode == null || bankCode.matches(Regex("[a-z][a-z0-9_]{1,31}"))) { "银行代码无效" }
        require(cardLast4 == null || cardLast4.matches(Regex("[0-9]{4}"))) { "银行卡尾号需为四位数字" }
        require(revision >= 0) { "账户版本无效" }
    }

    companion object {
        val KINDS = setOf("bank", "alipay", "wechat", "cash", "custom") + AccountBanks.entries.map { it.kind }
    }
}

/** Stable kind values reuse the existing account/backup schema, including legacy generic cards. */
object AccountBanks {
    data class Bank(val kind: String, val label: String, val icon: String)
    val entries = listOf(
        Bank("bank_icbc", "工商银行", "banks/icbc.png"),
        Bank("bank_abc", "农业银行", "banks/abc.png"),
        Bank("bank_boc", "中国银行", "banks/boc.png"),
        Bank("bank_ccb", "建设银行", "banks/ccb.png"),
        Bank("bank_bocom", "交通银行", "banks/bocom.png"),
    )
    fun codeForKind(kind: String): String? = when (kind) {
        "bank_icbc" -> "icbc"; "bank_abc" -> "abc"; "bank_boc" -> "boc"
        "bank_ccb" -> "ccb"; "bank_bocom" -> "bocom"; else -> null
    }
}

data class CustomCategory(val name: String, val direction: Direction) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 40 && name == name.trim()) { "分类名称需为 1 至 40 个字符" }
    }
}
