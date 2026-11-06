package net.ankio.ledger.core

data class WalletDelta(val accountUid: String, val deltaCents: Long)

/** Pure arithmetic. Persistence reads applied state and commits all returned deltas together. */
object WalletEffectCalculator {
    fun target(amountCents: Long?, direction: Direction?, posted: Boolean, deleted: Boolean = false,
               eligibilityActive: Boolean = true): Long {
        if (!posted || deleted || !eligibilityActive) return 0
        require(amountCents != null && amountCents in 0..Money.MAX_CENTS && direction != null) { "INVALID_AMOUNT" }
        return if (direction == Direction.EXPENSE) Math.negateExact(amountCents) else amountCents
    }

    fun changes(oldAccountUid: String?, oldApplied: Long, newAccountUid: String?, target: Long): List<WalletDelta> {
        require(oldApplied in -Money.MAX_CENTS..Money.MAX_CENTS && target in -Money.MAX_CENTS..Money.MAX_CENTS) { "BALANCE_LIMIT" }
        require(oldApplied == 0L || oldAccountUid != null) { "MISSING_APPLIED_WALLET" }
        require(target == 0L || newAccountUid != null) { "WALLET_REQUIRED" }
        if (oldAccountUid == newAccountUid) {
            val delta = Math.subtractExact(target, oldApplied)
            return if (delta == 0L) emptyList() else listOf(WalletDelta(requireNotNull(newAccountUid), delta))
        }
        return buildList {
            if (oldApplied != 0L) add(WalletDelta(requireNotNull(oldAccountUid), Math.negateExact(oldApplied)))
            if (target != 0L) add(WalletDelta(requireNotNull(newAccountUid), target))
        }
    }

    fun checkedBalance(balance: Long, delta: Long): Long {
        val result = try { Math.addExact(balance, delta) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("BALANCE_LIMIT")
        }
        require(result in -Money.MAX_CENTS..Money.MAX_CENTS) { "BALANCE_LIMIT" }
        return result
    }
}
