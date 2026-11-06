package net.ankio.ledger.core

/** Account balances permit a sign; ordinary transaction amounts still use [Money.parseCents]. */
object AccountMoney {
    fun parseCents(input: String): Long? {
        val value = normalize(input).replace('−', '-').trim()
        val negative = value.startsWith('-')
        val magnitude = Money.parseCents(if (negative || value.startsWith('+')) value.substring(1).trim() else value)
            ?: return null
        return if (negative) -magnitude else magnitude
    }
}
