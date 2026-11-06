package net.ankio.ledger.core

data class WalletDelta(val accountUid: String, val deltaCents: Long)

/** Pure arithmetic. Persistence reads applied state and commits all returned deltas together. */
object WalletEffectCalculator {
    fun target(amountCents: Long?, direction: Direction?, posted: Boolean, deleted: Boolean = false,
               eligibilityActive: Boolean = true): Long {
        if (!posted || deleted || !eligibilityActive) return 0
        require(amountCents != null && amountCents in 0..Money.MAX_CENTS && direction != null) { "INVALID_AMOUNT" }
        return when (direction) {
            Direction.EXPENSE -> Math.negateExact(amountCents)
            Direction.INCOME -> amountCents
            Direction.TRANSFER -> throw IllegalArgumentException("TRANSFER_WALLET_REQUIRED")
        }
    }

    /** Every transaction type uses this target set; excluded histories always stay empty. */
    fun targetEffects(amountCents: Long?, direction: Direction?, posted: Boolean, deleted: Boolean = false,
                      eligibilityActive: Boolean = true, accountUid: String? = null,
                      transfer: TransferTarget? = null): Map<String, Long> {
        if (!posted || deleted || !eligibilityActive) return emptyMap()
        require(direction != null) { "INVALID_AMOUNT" }
        return when (direction) {
            Direction.EXPENSE, Direction.INCOME -> {
                require(transfer == null) { "TRANSFER_IDENTITY_CONFLICT" }
                val cents = target(amountCents, direction, posted = true)
                require(accountUid != null && accountUid.isNotBlank()) { "WALLET_REQUIRED" }
                if (cents == 0L) emptyMap() else mapOf(accountUid to cents)
            }
            Direction.TRANSFER -> {
                require(transfer != null) { "TRANSFER_WALLET_REQUIRED" }
                val from = transfer.fromAccountUid
                val to = transfer.toAccountUid
                require(!from.isNullOrBlank() && !to.isNullOrBlank()) { "TRANSFER_WALLET_REQUIRED" }
                require(from != to) { "TRANSFER_SAME_WALLET" }
                require(accountUid == null) { "TRANSFER_IDENTITY_CONFLICT" }
                require(amountCents != null) { "INVALID_AMOUNT" }
                require(transfer.feeCents != null && transfer.feeMode != null) { "TRANSFER_FEE_AMBIGUOUS" }
                val amounts = TransferCalculator.calculate(amountCents, transfer.feeCents, transfer.feeMode)
                linkedMapOf(from to Math.negateExact(amounts.debitCents), to to amounts.creditCents)
            }
        }
    }

    /** Net by UID before checking balances: replacing both ends may touch four wallets. */
    fun changes(oldApplied: Map<String, Long>, target: Map<String, Long>): List<WalletDelta> {
        validateEffects(oldApplied)
        validateEffects(target)
        return (oldApplied.keys + target.keys).mapNotNull { uid ->
            val delta = Math.subtractExact(target[uid] ?: 0L, oldApplied[uid] ?: 0L)
            if (delta == 0L) null else WalletDelta(uid, delta)
        }
    }

    fun changes(oldAccountUid: String?, oldApplied: Long, newAccountUid: String?, target: Long): List<WalletDelta> {
        require(oldApplied in -Money.MAX_CENTS..Money.MAX_CENTS && target in -Money.MAX_CENTS..Money.MAX_CENTS) { "BALANCE_LIMIT" }
        require(oldApplied == 0L || oldAccountUid != null) { "MISSING_APPLIED_WALLET" }
        require(target == 0L || newAccountUid != null) { "WALLET_REQUIRED" }
        val old = if (oldApplied == 0L) emptyMap() else mapOf(requireNotNull(oldAccountUid) to oldApplied)
        val next = if (target == 0L) emptyMap() else mapOf(requireNotNull(newAccountUid) to target)
        return changes(old, next)
    }

    fun checkedBalance(balance: Long, delta: Long): Long {
        val result = try { Math.addExact(balance, delta) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("BALANCE_LIMIT")
        }
        require(result in -Money.MAX_CENTS..Money.MAX_CENTS) { "BALANCE_LIMIT" }
        return result
    }

    private fun validateEffects(effects: Map<String, Long>) {
        require(effects.keys.all { it.isNotBlank() }) { "WALLET_REQUIRED" }
        require(effects.values.all { it in -Money.MAX_CENTS..Money.MAX_CENTS }) { "BALANCE_LIMIT" }
    }
}
