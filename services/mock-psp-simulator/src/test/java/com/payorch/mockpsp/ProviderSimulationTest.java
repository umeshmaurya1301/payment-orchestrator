package com.payorch.mockpsp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The simulator is a measuring instrument. If it misbehaves, every experiment
 * from phase 2 onward is measuring the instrument instead of the system.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderSimulationTest {

    private static final String TEST_PAN = "4242424242424242";

    @Autowired
    private MockMvc mvc;

    @AfterEach
    void resetChaos() throws Exception {
        mvc.perform(delete("/_chaos")).andExpect(status().isOk());
    }

    @Test
    void authorizesAndReturnsAProviderReference() throws Exception {
        mvc.perform(authorize("ref-approve-1", 1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.providerRef").exists())
                .andExpect(jsonPath("$.authCode").exists())
                .andExpect(jsonPath("$.last4").value("4242"));
    }

    /**
     * Provider-side idempotency. This is the guarantee that makes retrying an
     * authorize against the <em>same</em> provider safe, and its absence across
     * providers is why phase 5's failover has to be careful.
     */
    @Test
    void theSameReferenceTwiceReturnsTheSameAuthorization() throws Exception {
        String first = providerRefFrom(authorizeAndRead("ref-idem-1", 1000));
        String second = providerRefFrom(authorizeAndRead("ref-idem-1", 1000));

        assertThat(second).isEqualTo(first);

        mvc.perform(get("/psp/v1/references/{r}", "ref-idem-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizations.length()").value(1));
    }

    /**
     * A decline is a business outcome, not a failure. It has to be reachable
     * without turning on {@code errorRate}, or every test that needs one becomes
     * probabilistic.
     */
    @Test
    void amountsEndingInFiveAreDeclined() throws Exception {
        mvc.perform(authorize("ref-decline-1", 1005))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.errorCode").value("insufficient_funds"))
                .andExpect(jsonPath("$.authCode").doesNotExist());
    }

    @Test
    void chaosConfigurationIsReadableAndTakesEffectWithoutARestart() throws Exception {
        mvc.perform(post("/_chaos").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latencyMs":25,"errorRate":0,"hangRate":0,"duplicateRate":0}"""))
                .andExpect(status().isOk())
                // The response is the PREVIOUS settings, so a runbook can restore them.
                .andExpect(jsonPath("$.latencyMs").value(0));

        mvc.perform(get("/_chaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs").value(25));

        long before = System.nanoTime();
        mvc.perform(authorize("ref-latency-1", 1000)).andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(25);
    }

    @Test
    void errorRateOfOneFailsEveryCall() throws Exception {
        mvc.perform(post("/_chaos").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"latencyMs":0,"errorRate":1.0,"hangRate":0,"duplicateRate":0}"""));

        mvc.perform(authorize("ref-error-1", 1000))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("provider_error"));

        mvc.perform(delete("/_chaos"));

        mvc.perform(authorize("ref-error-1", 1000)).andExpect(status().isOk());
    }

    /**
     * The double charge, made visible. One reference, two live authorizations -
     * which is exactly what a caller retrying against a provider without
     * idempotency gets, and exactly what phase 3's retry work must not cause.
     */
    @Test
    void duplicateRateProducesTwoAuthorizationsForOneReference() throws Exception {
        authorizeAndRead("ref-dup-1", 1000);

        mvc.perform(post("/_chaos").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"latencyMs":0,"errorRate":0,"hangRate":0,"duplicateRate":1.0}"""));

        String second = providerRefFrom(authorizeAndRead("ref-dup-1", 1000));

        mvc.perform(get("/psp/v1/references/{r}", "ref-dup-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizations.length()").value(2));
        assertThat(second).isNotBlank();
    }

    @Test
    void capturesAnApprovedAuthorization() throws Exception {
        String providerRef = providerRefFrom(authorizeAndRead("ref-capture-1", 2500));

        mvc.perform(post("/psp/v1/capture").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerRef\":\"" + providerRef + "\",\"amountMinor\":2500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAmountMinor").value(2500));

        mvc.perform(get("/psp/v1/authorizations/{ref}", providerRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captured").value(true));
    }

    @Test
    void refusesToCaptureADecline() throws Exception {
        String providerRef = providerRefFrom(authorizeAndRead("ref-capture-decline", 1005));

        mvc.perform(post("/psp/v1/capture").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerRef\":\"" + providerRef + "\",\"amountMinor\":1005}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("not_authorized"));
    }

    @Test
    void unknownProviderReferenceIsNotFound() throws Exception {
        mvc.perform(get("/psp/v1/authorizations/{ref}", "mock_does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("unknown_provider_ref"));
    }

    /**
     * The simulator receives a real card number - it stands in for a provider,
     * so it must. It has to give none of it back: the phase-1 exit criterion
     * greps every service's output for Luhn-valid numbers, and a response body
     * echoing the PAN would fail it.
     */
    @Test
    void noResponseEchoesTheCardNumber() throws Exception {
        String body = authorizeAndRead("ref-pan-1", 1000);
        String providerRef = providerRefFrom(body);

        assertThat(body).doesNotContain(TEST_PAN);

        String status = mvc.perform(get("/psp/v1/authorizations/{ref}", providerRef))
                .andReturn().getResponse().getContentAsString();

        assertThat(status).doesNotContain(TEST_PAN).contains("4242\"");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorize(
            String reference, long amountMinor) {
        return post("/psp/v1/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"%s","amountMinor":%d,"currency":"INR",
                         "pan":"%s","expiryMonth":12,"expiryYear":2030}"""
                        .formatted(reference, amountMinor, TEST_PAN));
    }

    private String authorizeAndRead(String reference, long amountMinor) throws Exception {
        return mvc.perform(authorize(reference, amountMinor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String providerRefFrom(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.providerRef");
    }
}
