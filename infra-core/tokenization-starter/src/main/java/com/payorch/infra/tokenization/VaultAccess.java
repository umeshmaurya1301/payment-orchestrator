package com.payorch.infra.tokenization;

/**
 * Why a card is being read, and for what. Phase 9c.
 *
 * <p>An audit log recording only <em>that</em> psp-connector detokenized a card
 * answers a question nobody asks. The grants already say it may; the interesting
 * findings are about context — a read attached to no payment, a purpose that is
 * not one of the two this system has, a burst from an actor that normally reads
 * once per authorization.
 *
 * <p>So the caller states its purpose, and the vault records it. This is
 * self-reported and therefore trusted, which is the same honest limitation the
 * whole trail has: the service being audited writes its own entries. What it
 * cannot do is unwrite them.
 *
 * @param purpose   what the read is for, e.g. {@code authorize}
 * @param reference the payment this belongs to, or null when there is none —
 *                  and a null here is itself a signal worth being able to query
 */
public record VaultAccess(String purpose, String reference) {

    /**
     * For callers with no context to give: tests, tooling, a startup probe.
     *
     * <p>Named rather than defaulted to an empty string, because
     * {@code purpose = ''} in a report is indistinguishable from a bug that
     * forgot to set it, and this one is a deliberate statement that no purpose
     * was available.
     */
    public static VaultAccess unattributed() {
        return new VaultAccess("unattributed", null);
    }

    public static VaultAccess forPayment(String purpose, String reference) {
        return new VaultAccess(purpose, reference);
    }
}
