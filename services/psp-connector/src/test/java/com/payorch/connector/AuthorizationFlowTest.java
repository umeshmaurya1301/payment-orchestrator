package com.payorch.connector;

import java.util.ArrayList;
import java.util.List;

import com.payorch.connector.provider.PspAdapter;
import com.payorch.infra.tokenization.DetokenizedCard;
import com.payorch.infra.tokenization.TokenVault;
import com.payorch.infra.tokenization.TokenizedCard;
import com.payorch.infra.tokenization.VaultConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the detokenization boundary end to end inside this service: a token
 * arrives, the vault reverses it, and the adapter receives a real card.
 *
 * <p>The provider itself is stubbed. What is being tested here is the handoff,
 * not HTTP - {@code mock-psp-simulator} has its own tests, and phase 1's exit
 * criteria cover the two together over a live stack.
 */
@SpringBootTest(properties = {
        // An H2 stand-in for the vault schema. The real one lives in its own
        // MySQL database with its own credentials; here the point is only that
        // TokenVault is talking to something with a token_vault table.
        "payorch.vault.datasource.url=jdbc:h2:mem:connector-vault;DB_CLOSE_DELAY=-1",
        "payorch.vault.datasource.username=sa",
        "payorch.vault.datasource.password=",
        "payorch.vault.verify-on-startup=false",
        // 3f: providers are rows now. The base URL, the timeouts and the
        // limits all come from this table rather than from properties.
        "payorch.psp.config-datasource.url=jdbc:h2:mem:connector-config-flow;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:psp-config-test.sql'",
        "payorch.psp.config-datasource.username=sa",
        "payorch.psp.config-datasource.password=",
})
@AutoConfigureMockMvc
class AuthorizationFlowTest {

    /**
     * Phase 9c made the key scope a required argument to tokenize. This service
     * only ever DEtokenizes, so the value is arbitrary here - what matters is
     * that the fixture stores cards the way the edge does.
     */
    private static final String SCOPE = "merchant-under-test";

    private static final String PAN = "4242424242424242";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VaultConnection vaultConnection;

    @Autowired
    private TokenVault vault;

    @Autowired
    private RecordingAdapter adapter;

    @BeforeEach
    void createVaultSchema() {
        vaultConnection.jdbc().sql("""
                CREATE TABLE IF NOT EXISTS token_vault (
                    token        VARCHAR(48)   NOT NULL PRIMARY KEY,
                    pan_iv       VARBINARY(12) NOT NULL,
                    pan_cipher   VARBINARY(64) NOT NULL,
                    -- Phase 9b. The envelope. Nullable, because a row without a
                    -- kek_version is a pre-9b record read through the legacy cipher.
                    wrapped_dek  VARBINARY(64) NULL,
                    dek_iv       VARBINARY(12) NULL,
                    key_scope    VARCHAR(64)   NULL,
                    kek_version  VARCHAR(32)   NULL,
                    bin          CHAR(6)       NOT NULL,
                    last4        CHAR(4)       NOT NULL,
                    expiry_month TINYINT       NOT NULL,
                    expiry_year  SMALLINT      NOT NULL
                )
                """).update();

        // Phase 9c. Not optional, and the failure it caused is worth recording:
        // psp-connector now audits every card read and is FAIL-CLOSED, so
        // without this table every test in this class returned 500 - no card is
        // disclosed when the access cannot be recorded. That is the design
        // working, surfacing in the least convenient place, which is where a
        // design like this is supposed to surface.
        vaultConnection.jdbc().sql("""
                CREATE TABLE IF NOT EXISTS vault_access_log (
                    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                    token          VARCHAR(64) NOT NULL,
                    actor          VARCHAR(64) NOT NULL,
                    purpose        VARCHAR(64) NOT NULL,
                    reference      VARCHAR(64) NULL,
                    correlation_id VARCHAR(64) NULL,
                    trace_id       VARCHAR(64) NULL,
                    outcome        VARCHAR(24) NOT NULL,
                    at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """).update();

        adapter.reset();
    }

    @Test
    void reversesTheTokenAndHandsTheRealCardToTheProvider() throws Exception {
        TokenizedCard card = vault.tokenize(PAN, 11, 2031, SCOPE);

        mvc.perform(authorize(card.token(), "stubpsp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.providerRef").value("stub_ref_1"));

        assertThat(adapter.received).hasSize(1);
        assertThat(adapter.received.getFirst().pan()).isEqualTo(PAN);
        assertThat(adapter.received.getFirst().expiryMonth()).isEqualTo(11);
        assertThat(adapter.received.getFirst().expiryYear()).isEqualTo(2031);
    }

    /**
     * The connector's response must never carry a card number back upstream. It
     * is the one component that could, which makes it the one worth asserting
     * about.
     */
    @Test
    void theResponseCarriesNoCardData() throws Exception {
        TokenizedCard card = vault.tokenize(PAN, 11, 2031, SCOPE);

        String body = mvc.perform(authorize(card.token(), "stubpsp"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PAN);
    }

    /**
     * A token that is not in the vault provably never reached a provider, so it
     * is a 400. Answering 500 would make the orchestrator record UNKNOWN - "the
     * card may have been charged" - for a request that never left this service.
     */
    @Test
    void anUnknownTokenIsARejectionNotAServerFault() throws Exception {
        mvc.perform(authorize("tok_never_issued", "stubpsp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("unknown_token"));
    }

    @Test
    void anUnconfiguredProviderIsARejection() throws Exception {
        TokenizedCard card = vault.tokenize(PAN, 11, 2031, SCOPE);

        mvc.perform(authorize(card.token(), "no-such-psp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("unknown_psp"));
    }

    /**
     * No answer from the provider becomes 502, not 500. The distinction is what
     * the orchestrator turns into UNKNOWN rather than FAILED.
     */
    @Test
    void aProviderThatDoesNotAnswerBecomesABadGateway() throws Exception {
        TokenizedCard card = vault.tokenize(PAN, 11, 2031, SCOPE);
        adapter.failWithUnavailable = true;

        mvc.perform(authorize(card.token(), "stubpsp"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("provider_unavailable"));
    }

    @Test
    void aDeclineIsReturnedAsAnOutcomeNotAnError() throws Exception {
        TokenizedCard card = vault.tokenize(PAN, 11, 2031, SCOPE);
        adapter.decline = true;

        mvc.perform(authorize(card.token(), "stubpsp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.errorCode").value("insufficient_funds"));
    }

    /**
     * Phase 6j, and the assertion that matters is the NEGATIVE one.
     *
     * <p>{@code adapter.received} holds every {@link DetokenizedCard} the
     * provider was handed. A capture must leave it empty: the operation
     * references the provider's own handle, so the vault is never opened and the
     * plaintext window this whole class exists to bound is not widened by adding
     * a second money-moving operation to the service.
     */
    @Test
    void aCaptureNeverOpensTheVault() throws Exception {
        adapter.reset();

        mvc.perform(capture("prov_ref_1", "stubpsp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(1000));

        assertThat(adapter.captured).containsExactly("prov_ref_1");
        assertThat(adapter.received)
                .as("a capture must not detokenize - it names an authorization, not a card")
                .isEmpty();
    }

    /** A provider that does not answer a capture is a 502, same as for authorize. */
    @Test
    void aCaptureThatGetsNoAnswerIsABadGateway() throws Exception {
        adapter.reset();
        adapter.failWithUnavailable = true;

        mvc.perform(capture("prov_ref_1", "stubpsp"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("provider_unavailable"));
    }

    /** A refused capture is an outcome, not an error - the authorization survives it. */
    @Test
    void aRefusedCaptureIsAnOutcomeNotAnError() throws Exception {
        adapter.reset();
        adapter.decline = true;

        mvc.perform(capture("prov_ref_1", "stubpsp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.errorCode").value("capture_exceeds_authorization"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder capture(
            String providerRef, String pspId) {
        return post("/internal/v1/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"providerRef":"%s","pspId":"%s","amountMinor":1000}"""
                        .formatted(providerRef, pspId));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorize(
            String token, String pspId) {
        return post("/internal/v1/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"attempt-1","pspId":"%s","amountMinor":1000,"currency":"INR",
                         "cardToken":"%s","cardBin":"424242","cardLast4":"4242"}"""
                        .formatted(pspId, token));
    }

    @TestConfiguration
    static class StubProvider {

        @Bean
        RecordingAdapter recordingAdapter() {
            return new RecordingAdapter();
        }
    }

    /** Stands in for a provider and remembers exactly what it was handed. */
    static class RecordingAdapter implements PspAdapter {

        final List<DetokenizedCard> received = new ArrayList<>();
        boolean failWithUnavailable;
        boolean decline;

        @Override
        public String pspId() {
            return "stubpsp";
        }

        @Override
        public ProviderAuthorization authorize(AuthorizeCommand command, DetokenizedCard card) {
            received.add(card);
            if (failWithUnavailable) {
                throw new ProviderUnavailableException(pspId(), null);
            }
            if (decline) {
                return new ProviderAuthorization("stub_ref_1", false, "insufficient_funds", null);
            }
            return new ProviderAuthorization("stub_ref_1", true, null, "AUTH01");
        }

        /**
         * Records the providerRef it was asked to capture, and NOT a card - the
         * assertion in the capture test is that {@link #received} stays empty,
         * because a capture must never open the vault.
         */
        final List<String> captured = new ArrayList<>();

        @Override
        public ProviderCapture capture(CaptureCommand command) {
            captured.add(command.providerRef());
            if (failWithUnavailable) {
                throw new ProviderUnavailableException(pspId(), null);
            }
            if (decline) {
                return new ProviderCapture(command.providerRef(), false,
                        "capture_exceeds_authorization", 0);
            }
            return new ProviderCapture(command.providerRef(), true, null, command.amountMinor());
        }

        /**
         * Records what it was asked to reverse, and never a card. Phase 6k adds
         * a third money-moving operation and the vault assertion still holds
         * over all of them.
         */
        final List<String> looked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> reversed = new ArrayList<>();

        @Override
        public ProviderLookup lookup(LookupCommand command) {
            // Phase 7f. This flow never looks a reference up - the fan-out has
            // its own test - but the method has to exist, and answering
            // "not found" is the honest stub: this adapter records what it was
            // asked to do rather than holding any state a lookup could consult.
            looked.add(command.reference());
            return ProviderLookup.notFound(pspId());
        }

        @Override
        public ProviderReversal reverse(ReverseCommand command) {
            reversed.add(command.providerRef());
            if (failWithUnavailable) {
                throw new ProviderUnavailableException(pspId(), null);
            }
            if (decline) {
                return new ProviderReversal(command.providerRef(), false, "not_captured", 0);
            }
            return new ProviderReversal(command.providerRef(), true, null, 1000);
        }

        void reset() {
            captured.clear();
            reversed.clear();
            looked.clear();
            received.clear();
            failWithUnavailable = false;
            decline = false;
        }
    }
}
