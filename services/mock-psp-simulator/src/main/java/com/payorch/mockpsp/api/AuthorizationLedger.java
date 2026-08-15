package com.payorch.mockpsp.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * What the simulated provider remembers. In memory, on purpose.
 *
 * <p>A real acquirer's ledger is durable; this one must not be. Every experiment
 * starts from a known-empty provider, and the cheapest way to guarantee that is
 * for the state to die with the container. Persisting it would also mean
 * deciding where to put a card number, which is a decision worth not having.
 *
 * <p><strong>No card number is stored here.</strong> The authorization request
 * carries a PAN, the simulator reads the last four from it and discards the
 * rest. The simulator stands in for a system outside our PCI boundary, but the
 * phase-1 exit criterion greps every container's log output and every table for
 * Luhn-valid numbers, and a simulator that hoarded PANs would fail it - fairly.
 */
@Component
public class AuthorizationLedger {

    /** providerRef -> record */
    private final Map<String, Authorization> byProviderRef = new ConcurrentHashMap<>();

    /** reference -> every providerRef issued for it. More than one is a double charge. */
    private final Map<String, List<String>> byReference = new ConcurrentHashMap<>();

    /**
     * @param captured mutable in the sense that capture replaces the record;
     *        the record itself stays immutable
     */
    public record Authorization(
            String providerRef,
            String reference,
            ProviderApi.Outcome outcome,
            String errorCode,
            String authCode,
            long amountMinor,
            String currency,
            String last4,
            boolean captured,
            long capturedAmountMinor,
            Instant createdAt) {
    }

    public Optional<Authorization> findByProviderRef(String providerRef) {
        return Optional.ofNullable(byProviderRef.get(providerRef));
    }

    /** The first authorization issued for a reference, if any. */
    public Optional<Authorization> findFirstByReference(String reference) {
        return byReference.getOrDefault(reference, List.of()).stream()
                .findFirst()
                .map(byProviderRef::get);
    }

    public List<Authorization> findAllByReference(String reference) {
        return byReference.getOrDefault(reference, List.of()).stream()
                .map(byProviderRef::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public Authorization record(String reference,
                                ProviderApi.Outcome outcome,
                                String errorCode,
                                long amountMinor,
                                String currency,
                                String last4) {
        Authorization authorization = new Authorization(
                newProviderRef(),
                reference,
                outcome,
                errorCode,
                outcome == ProviderApi.Outcome.APPROVED ? newAuthCode() : null,
                amountMinor,
                currency,
                last4,
                false,
                0,
                Instant.now());

        byProviderRef.put(authorization.providerRef(), authorization);
        byReference.computeIfAbsent(reference, k -> new CopyOnWriteArrayList<>())
                .add(authorization.providerRef());
        return authorization;
    }

    public Authorization capture(Authorization authorization, long amountMinor) {
        Authorization captured = new Authorization(
                authorization.providerRef(),
                authorization.reference(),
                authorization.outcome(),
                authorization.errorCode(),
                authorization.authCode(),
                authorization.amountMinor(),
                authorization.currency(),
                authorization.last4(),
                true,
                amountMinor,
                authorization.createdAt());
        byProviderRef.put(captured.providerRef(), captured);
        return captured;
    }

    private static String newProviderRef() {
        return "mock_" + randomAlphanumeric(20);
    }

    private static String newAuthCode() {
        return randomAlphanumeric(6).toUpperCase(java.util.Locale.ROOT);
    }

    private static String randomAlphanumeric(int length) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return out.toString();
    }
}
