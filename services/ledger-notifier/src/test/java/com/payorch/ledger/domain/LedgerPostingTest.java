package com.payorch.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.payorch.ledger.consume.PaymentEventMessage;
import com.payorch.ledger.journal.JournalRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which accounts an event moves, and <em>how the balance gets updated</em>.
 *
 * <p>The second half is the regression, and it is the kind that comes back.
 * Balances were updated by loading the account entity and adding to it in Java -
 * which reads perfectly, is what JPA invites, and loses money under concurrency.
 * Two consumer threads posting to {@code settlement:clearing} both read the same
 * balance, both write their own total, and one posting disappears from the cached
 * figure while its entries stay on the table.
 *
 * <p>Measured before the fix, after two days of phase-6 experiments:
 * <strong>1,911,000 minor units</strong> of drift on one merchant account, while
 * {@code SUM(amount_minor)} over every entry was zero on every single check.
 *
 * <p>So the assertions below are deliberately about the MECHANISM rather than
 * about a resulting number. A test that posted an event and asserted a balance
 * would pass against the broken version - single-threaded, nothing races - and
 * that is exactly how the bug survived phase 6e.
 */
class LedgerPostingTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final EntryRepository entries = mock(EntryRepository.class);
    private final JournalRepository journal = mock(JournalRepository.class);
    private final ReversedCaptureRepository tombstones = mock(ReversedCaptureRepository.class);
    private final LedgerPosting ledger =
            new LedgerPosting(accounts, entries, journal, tombstones);

    private final LedgerAccount merchantAccount = LedgerAccount.open("merchant:m1", "INR");
    private final LedgerAccount clearingAccount = LedgerAccount.open(LedgerPosting.CLEARING, "INR");
    private final LedgerAccount networkAccount = LedgerAccount.open(LedgerPosting.NETWORK, "INR");

    private static final UUID MERCHANT = UUID.randomUUID();

    private PaymentEventMessage event(String state, String type) {
        return new PaymentEventMessage(
                UUID.randomUUID(), UUID.randomUUID(), MERCHANT,
                type, state, 4200, "INR",
                "mockpsp", "tok_test", "424242", "4242", Instant.now());
    }

    private void stubAccounts() {
        when(entries.existsByEventId(any())).thenReturn(false);
        when(journal.existsByEventId(any())).thenReturn(false);
        when(tombstones.existsById(any())).thenReturn(false);
        when(accounts.findByAccountRefAndCurrency(anyString(), eq("INR")))
                .thenAnswer(invocation -> {
                    String ref = invocation.getArgument(0);
                    if (LedgerPosting.CLEARING.equals(ref)) {
                        return Optional.of(clearingAccount);
                    }
                    if (LedgerPosting.NETWORK.equals(ref)) {
                        return Optional.of(networkAccount);
                    }
                    return Optional.of(merchantAccount);
                });
    }

    /**
     * THE REGRESSION. The balance must move through an atomic SQL update, never
     * by mutating the loaded entity - see {@link AccountRepository#applyDelta}.
     */
    @Test
    void aBalanceMovesByAnAtomicUpdateAndNotByMutatingTheEntity() {
        stubAccounts();
        long merchantBefore = merchantAccount.getBalanceMinor();

        ledger.post(event("AUTHORIZED", "payment.authorized"));

        verify(accounts).applyDelta(merchantAccount.getId(), 4200L);
        verify(accounts).applyDelta(clearingAccount.getId(), -4200L);
        assertThat(merchantAccount.getBalanceMinor())
                .as("read-modify-write on the entity is what lost 1,911,000 minor units")
                .isEqualTo(merchantBefore);
    }

    /**
     * An authorization is a promise: the merchant is owed, and we carry the
     * liability on clearing until somebody actually collects.
     */
    @Test
    void anAuthorizationCreditsTheMerchantAndDebitsClearing() {
        stubAccounts();

        assertThat(ledger.post(event("AUTHORIZED", "payment.authorized"))).isTrue();

        verify(accounts).applyDelta(merchantAccount.getId(), 4200L);
        verify(accounts).applyDelta(clearingAccount.getId(), -4200L);
        verify(accounts, never()).applyDelta(eq(networkAccount.getId()), any(Long.class));
    }

    /**
     * A capture is collection: the funds arrive into clearing from the network,
     * which is what discharges the liability the authorization created. Posting
     * a capture as a second merchant credit would balance perfectly and pay the
     * merchant twice.
     */
    @Test
    void aCaptureCreditsClearingAndDebitsTheNetwork() {
        stubAccounts();

        assertThat(ledger.post(event("CAPTURED", "payment.captured"))).isTrue();

        verify(accounts).applyDelta(clearingAccount.getId(), 4200L);
        verify(accounts).applyDelta(networkAccount.getId(), -4200L);
        verify(accounts, never()).applyDelta(eq(merchantAccount.getId()), any(Long.class));
    }

    /**
     * The two pairs together leave clearing where it started, which is what makes
     * the clearing balance mean "outstanding uncaptured exposure" rather than
     * "some number that grows".
     */
    @Test
    void authorizeThenCaptureLeavesClearingWhereItStarted() {
        stubAccounts();

        ledger.post(event("AUTHORIZED", "payment.authorized"));
        ledger.post(event("CAPTURED", "payment.captured"));

        verify(accounts).applyDelta(clearingAccount.getId(), -4200L);
        verify(accounts).applyDelta(clearingAccount.getId(), 4200L);
    }

    /**
     * Unchanged from phase 6e, and re-asserted because 6j touched the branch that
     * decides it. A ledger that guesses about an unresolved payment is worse than
     * one that is behind.
     */
    @Test
    void anUnknownOutcomePostsNothingAtAll() {
        stubAccounts();

        assertThat(ledger.post(event("UNKNOWN", "payment.unknown"))).isTrue();

        verify(accounts, never()).applyDelta(any(), any(Long.class));
        verify(entries, never()).save(any());
    }

    @Test
    void aFailedPaymentPostsNothingAtAll() {
        stubAccounts();

        assertThat(ledger.post(event("FAILED", "payment.failed"))).isTrue();

        verify(accounts, never()).applyDelta(any(), any(Long.class));
    }

    /** Drift with a zero delta is not drift, and must not be reported as such. */
    @Test
    void onlyAccountsThatActuallyDisagreeAreReportedAsDrifted() {
        when(accounts.drift()).thenReturn(List.of(
                new AccountRepository.Drift(UUID.randomUUID(), "merchant:m1", 100, 100),
                new AccountRepository.Drift(UUID.randomUUID(), LedgerPosting.CLEARING, -80, -100)));

        assertThat(ledger.drift())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.accountRef()).isEqualTo(LedgerPosting.CLEARING);
                    assertThat(d.delta()).isEqualTo(20);
                });
    }

    /**
     * The repair writes the correcting delta, not the absolute total. Setting the
     * balance outright would race with a posting landing at the same moment and
     * turn a repair into a second lost update.
     */
    @Test
    void repairAppliesTheCorrectingDeltaRatherThanOverwriting() {
        UUID id = UUID.randomUUID();
        when(accounts.drift()).thenReturn(List.of(
                new AccountRepository.Drift(id, LedgerPosting.CLEARING, -80, -100)));

        assertThat(ledger.repairBalances()).isEqualTo(1);

        verify(accounts).applyDelta(id, -20L);
    }

    // --- phase 6k: the compensating reversal ----------------------------

    /**
     * THE ONE THAT IS EASY TO GET BACKWARDS.
     *
     * <p>A reversal compensates a capture the ledger never posted, so there is
     * no clearing/card-network pair to undo. What exists is the AUTHORIZATION,
     * and the reversal cancels that.
     *
     * <p>Reversing the capture legs instead - credit card-network, debit
     * clearing - would balance perfectly, keep {@code SUM(amount_minor)} at
     * zero, and leave two accounts permanently wrong. The invariant cannot see
     * the difference; this test is the thing that can.
     */
    @Test
    void aReversalUndoesTheAuthorizationAndNotTheCapture() {
        stubAccounts();

        assertThat(ledger.post(event("REVERSED", "payment.reversed"))).isTrue();

        verify(accounts).applyDelta(merchantAccount.getId(), -4200L);
        verify(accounts).applyDelta(clearingAccount.getId(), 4200L);
        verify(accounts, never()).applyDelta(eq(networkAccount.getId()), any(Long.class));
    }

    /** Authorize then reverse leaves every account this payment touched at zero. */
    @Test
    void authorizeThenReverseNetsToNothing() {
        stubAccounts();

        ledger.post(event("AUTHORIZED", "payment.authorized"));
        ledger.post(event("REVERSED", "payment.reversed"));

        verify(accounts).applyDelta(merchantAccount.getId(), 4200L);
        verify(accounts).applyDelta(merchantAccount.getId(), -4200L);
        verify(accounts).applyDelta(clearingAccount.getId(), -4200L);
        verify(accounts).applyDelta(clearingAccount.getId(), 4200L);
    }

    /** The tombstone is written in the same transaction as the reversal legs. */
    @Test
    void aReversalWritesTheTombstoneThatSuppressesTheCapture() {
        stubAccounts();

        ledger.post(event("REVERSED", "payment.reversed"));

        verify(tombstones).save(any(ReversedCapture.class));
    }

    /**
     * THE REPLAY HOLE, closed.
     *
     * <p>A compensated capture is still sitting in payment.events.dlq, and the
     * replay tool built in phase 6f exists precisely so a human can push it back
     * through. Nothing else in this service would stop it: the event genuinely
     * has never been posted, so {@code existsByEventId} is false and the unique
     * constraint would accept both legs happily.
     */
    @Test
    void aCaptureReplayedAfterItsReversalPostsNothing() {
        stubAccounts();
        when(tombstones.existsById(any())).thenReturn(true);

        // true, not false: this is not a duplicate. The event was handled -
        // deliberately, by ignoring it - and it is journalled as IGNORED so the
        // decision is auditable rather than invisible.
        assertThat(ledger.post(event("CAPTURED", "payment.captured"))).isTrue();

        verify(accounts, never()).applyDelta(any(), any(Long.class));
        verify(entries, never()).save(any());
    }

    /**
     * The tombstone suppresses the CAPTURE and nothing else. A payment that was
     * reversed and later re-authorized is not this system's flow today, but a
     * tombstone that swallowed every subsequent event would be a silent data
     * loss waiting for the day it is.
     */
    @Test
    void theTombstoneDoesNotSuppressOtherStates() {
        stubAccounts();
        when(tombstones.existsById(any())).thenReturn(true);

        ledger.post(event("AUTHORIZED", "payment.authorized"));

        verify(accounts).applyDelta(merchantAccount.getId(), 4200L);
    }
}
