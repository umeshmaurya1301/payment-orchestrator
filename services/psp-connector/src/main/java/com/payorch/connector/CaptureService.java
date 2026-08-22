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
 * Taking the money, as opposed to holding it.
 *
 * <h2>Why this is a separate class from {@link AuthorizationService}</h2>
 *
 * <p>Not tidiness. {@code AuthorizationService} exists to be the one auditable
 * detokenization path in the system - its javadoc is mostly about that, and the
 * value of the claim comes from the method being small and having one caller.
 * A capture needs no card number at all, so putting it in that class would add a
 * method that does not detokenize to the class whose entire purpose is
 * detokenizing, and the next reader would have to check which methods touch the
 * vault instead of knowing that all of them do.
 *
 * <h2>What a failed capture means, and why it is worse than a failed authorize</h2>
 *
 * <p>A failed authorize leaves a payment {@code UNKNOWN}: a hold may exist on a
 * card, and phase 8's poller resolves it. Nobody's money has moved.
 *
 * <p>A capture that does not answer is the same uncertainty about an operation
 * that <em>moves money</em>. The customer may have been charged and this system
 * may have no record of it. That is why the adapter retries on the provider
 * reference rather than a fresh id, and why phase 6k's saga exists at all: at
 * some point retrying stops being the answer and the only correct action left is
 * to give the money back.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    private final PspAdapterRegistry adapters;

    public CaptureService(PspAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    public ConnectorApi.CaptureResponse capture(ConnectorApi.CaptureRequest request) {
        PspAdapter adapter = adapters.require(request.pspId());

        long startedAt = System.nanoTime();
        PspAdapter.ProviderCapture result = adapter.capture(
                new PspAdapter.CaptureCommand(request.providerRef(), request.amountMinor()));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        log.info("provider capture completed",
                LogEvent.event()
                        .with(LogFields.PSP_ID, request.pspId())
                        .with(LogFields.OPERATION, "capture")
                        .with(LogFields.AMOUNT_MINOR, request.amountMinor())
                        .with(LogFields.OUTCOME, result.captured() ? "APPROVED" : "DECLINED")
                        .with(LogFields.ERROR_CODE, result.errorCode())
                        .with(LogFields.LATENCY_MS, latencyMs)
                        .args());

        return new ConnectorApi.CaptureResponse(
                result.providerRef(),
                result.captured() ? ConnectorApi.Outcome.APPROVED : ConnectorApi.Outcome.DECLINED,
                result.errorCode(),
                result.capturedAmountMinor());
    }
}
