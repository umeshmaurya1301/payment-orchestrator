package com.payorch.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.payorch.connector.api.ConnectorApi;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.PspAdapterRegistry;
import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;

/**
 * Giving the money back. Phase 6k.
 *
 * <h2>Why this is a third class and not a second method on {@link CaptureService}</h2>
 *
 * <p>Because of who calls it. Every other operation this connector exposes is on
 * a merchant's request path: somebody is waiting, and the answer goes back to
 * them. A reversal is on nobody's request path. It is issued by a saga on
 * discovering that two systems disagree about whether money moved, minutes or
 * hours after the customer left, and the only party that will ever read the
 * result is a log line and a ledger.
 *
 * <p>That difference decides how the failures are treated, which is the actual
 * reason for the separation. A declined capture is a business outcome to report
 * upstream. A declined reversal is an <em>unresolved compensation</em> - money
 * that this system has concluded should not have moved and cannot get back - and
 * it must end up somewhere a human looks, not in a 409 handed to a caller who
 * has already given up. Sharing a class with capture would make it easy to give
 * the two the same treatment, and they must not have it.
 *
 * <h2>What a reversal cannot fix</h2>
 *
 * <p>If the provider does not answer, the compensation is itself in the
 * {@code UNKNOWN} state its own existence was meant to resolve. There is no
 * fourth action - reversing the reversal is not a thing - and pretending
 * otherwise is where saga write-ups usually stop being honest. The only route
 * out is asking the provider later what it actually did, which is phase 8, and
 * saying so plainly is more useful than a diagram implying the loop closes.
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final PspAdapterRegistry adapters;

    public ReversalService(PspAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    public ConnectorApi.ReverseResponse reverse(ConnectorApi.ReverseRequest request) {
        PspAdapter adapter = adapters.require(request.pspId());

        long startedAt = System.nanoTime();
        PspAdapter.ProviderReversal result = adapter.reverse(
                new PspAdapter.ReverseCommand(request.providerRef()));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        // WARN, not INFO, and for a successful reversal. Every one of these is a
        // capture that should not have stood, so the line an operator wants is
        // "how often is the saga having to undo work", and INFO would bury it
        // among the captures that went fine.
        log.warn("capture reversed at the provider",
                LogEvent.event()
                        .with(LogFields.PSP_ID, request.pspId())
                        .with(LogFields.OPERATION, "reverse")
                        .with(LogFields.AMOUNT_MINOR, result.reversedAmountMinor())
                        .with(LogFields.OUTCOME, result.reversed() ? "APPROVED" : "DECLINED")
                        .with(LogFields.ERROR_CODE, result.errorCode())
                        .with(LogFields.LATENCY_MS, latencyMs)
                        .args());

        return new ConnectorApi.ReverseResponse(
                result.providerRef(),
                result.reversed() ? ConnectorApi.Outcome.APPROVED : ConnectorApi.Outcome.DECLINED,
                result.errorCode(),
                result.reversedAmountMinor());
    }
}
