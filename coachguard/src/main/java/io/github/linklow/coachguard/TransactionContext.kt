package io.github.linklow.coachguard

/**
 * What the host app knows about the payment the user is about to confirm.
 *
 * The SDK never receives amounts, account numbers or payee details. The app decides what counts
 * as "new" or "unusual" for its own users and passes only the resulting flags.
 */
public data class TransactionContext @JvmOverloads constructor(
    /** The payee was added recently, or this is the first transfer to them. */
    public val isNewPayee: Boolean = false,
    /** The amount is unusual for this user, for example well above their typical transfer. */
    public val isAmountUnusual: Boolean = false,
)
