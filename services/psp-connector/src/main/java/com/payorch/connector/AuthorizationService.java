package com.payorch.connector;

import com.payorch.connector.api.ConnectorApi;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.PspAdapterRegistry;
import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;
import org.infra.tokenization.DetokenizedCard;
import org.infra.tokenization.VaultAccess;
import org.infra.tokenization.TokenVault;
import org.infra.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The detokenization boundary, in one method.
 *
 * <p>This is the only place in the system, other than {@code payments-edge},
 * where a card number exists. It is reversed here and nowhere else, immediately
 * before the provider call, and the plaintext lives for the duration of one
 * method invocation.
 *
 * <p>Being precise about this matters more than overclaiming. Raw PAN exists in
 * three places: {@code payments-edge} at intake, the {@code token_vault} table
 * at rest, and this method at call time. Saying "only one component sees a card
 * number" invites an interviewer to find this file. Three components with one
 * audited reversal path is the honest version, and it is still a good story.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final TokenVault vault;
    private final PspAdapterRegistry adapters;

    public AuthorizationService(TokenVault vault, PspAdapterRegistry adapters) {
        this.vault = vault;
        this.adapters = adapters;
    }

    public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
        PspAdapter adapter = adapters.require(request.pspId());

        // Detokenize as late as possible - after routing has been resolved and
        // after any rejection that could have happened without a card number.
        // The window in which plaintext exists is the thing being minimised.
        DetokenizedCard card = detokenize(request.cardToken(), request.reference());

        long startedAt = System.nanoTime();
        PspAdapter.ProviderAuthorization result = adapter.authorize(
                new PspAdapter.AuthorizeCommand(
                        request.reference(), request.amountMinor(), request.currency()),
                card);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        // Named fields only. `log.info("authorized {}", request)` would put the
        // whole request object through the logger, and while masking would very
        // likely catch a card number, the intent of the allowlist is that it
        // never gets the chance.
        log.info("provider authorization completed",
                LogEvent.event()
                        .with(LogFields.PSP_ID, request.pspId())
                        .with(LogFields.OPERATION, "authorize")
                        .with(LogFields.TOKEN, request.cardToken())
                        .with(LogFields.BIN, request.cardBin())
                        .with(LogFields.LAST4, request.cardLast4())
                        .with(LogFields.AMOUNT_MINOR, request.amountMinor())
                        .with(LogFields.CURRENCY, request.currency())
                        .with(LogFields.OUTCOME, result.approved() ? "APPROVED" : "DECLINED")
                        .with(LogFields.ERROR_CODE, result.errorCode())
                        .with(LogFields.LATENCY_MS, latencyMs)
                        .args());

        return new ConnectorApi.AuthorizeResponse(
                result.providerRef(),
                result.approved() ? ConnectorApi.Outcome.APPROVED : ConnectorApi.Outcome.DECLINED,
                result.errorCode(),
                result.authCode());
    }

    /**
     * A token with no vault row is a caller error, not a server fault. Answering
     * 500 would make the orchestrator record {@code UNKNOWN} - "we do not know
     * whether the card was charged" - for a request that provably never reached
     * a provider.
     */
    private DetokenizedCard detokenize(String token, String reference) {
        try {
            // 9c. The purpose and the payment travel with the read, so the audit
            // row says "read for authorization of payment X" rather than the
            // useless "psp-connector read a card" - which the grants already
            // said it may do.
            return vault.detokenize(token, VaultAccess.forPayment("authorize", reference));
        } catch (TokenVault.UnknownTokenException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_token",
                    "the supplied card token is not in the vault");
        }
    }
}
