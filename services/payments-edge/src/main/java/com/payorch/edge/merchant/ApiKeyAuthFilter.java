package com.payorch.edge.merchant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import com.payorch.infra.logging.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a merchant from the {@code X-Api-Key} header.
 *
 * <p><strong>The key must never reach a log line.</strong> That is not achieved
 * by remembering to avoid it - it is structural. {@code apiKey} is not a member
 * of {@link LogFields}, so {@code LogEvent.with("apiKey", ...)} throws rather
 * than logging, and this filter puts the merchant <em>id</em> into MDC, never
 * the credential that produced it. {@code ApiKeyAuthFilterTest} verifies that
 * rather than assuming it.
 *
 * <p><strong>Since 9b a merchant has several keys.</strong> The lookup starts at
 * the key rather than the merchant and arrives at the merchant by id, and a key
 * authenticates only while {@link MerchantApiKey#isUsableAt} says so — which
 * covers the overlap window during a rotation and closes it on time whether or
 * not any scheduled job is running.
 *
 * <p><strong>Why a filter and not Spring Security.</strong> Phase 1 needs one
 * header checked against one hashed column. Spring Security would bring a filter
 * chain, an authentication manager and a security context to express that, and
 * every one of those is a thing to configure wrongly. Phase 9b is where key
 * rotation, scopes and mTLS arrive, and that is when the framework earns its
 * complexity.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String HEADER = "X-Api-Key";

    /** Where the authenticated merchant id is left for the controller. */
    public static final String MERCHANT_ATTRIBUTE = "payorch.merchantId";

    private static final String API_PREFIX = "/v1/";

    private final MerchantRepository merchants;
    private final MerchantApiKeyRepository keys;
    private final ApiKeyUsageRecorder usage;

    public ApiKeyAuthFilter(MerchantRepository merchants,
                            MerchantApiKeyRepository keys,
                            ApiKeyUsageRecorder usage) {
        this.merchants = merchants;
        this.keys = keys;
        this.usage = usage;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            reject(response, "missing_api_key", "An X-Api-Key header is required.");
            return;
        }

        Instant now = Instant.now();
        Optional<MerchantApiKey> key = keys.findByApiKeyHash(sha256Hex(apiKey));

        // A revoked or expired key that is still being presented is worth a log
        // line, and it is the ONLY signal that a rotation was completed too
        // early - somebody is still using the key that was just turned off. It
        // is logged by key label and merchant id, never by the credential.
        if (key.isPresent() && !key.get().isUsableAt(now)) {
            log.warn("a retired api key was presented: label={} status={} merchant={}",
                    key.get().getLabel(), key.get().getStatus(), key.get().getMerchantId());
        }

        Optional<Merchant> merchant = key
                .filter(k -> k.isUsableAt(now))
                .flatMap(k -> merchants.findById(k.getMerchantId()));

        if (merchant.isEmpty() || !merchant.get().isActive()) {
            // One response for "no such key", "revoked key" and "key belongs to
            // a suspended merchant". Distinguishing them would let a caller
            // enumerate valid keys, and neither case is one the caller can fix
            // by knowing which it was.
            reject(response, "invalid_api_key", "The API key is not valid.");
            return;
        }

        usage.recordUsed(key.get().getId(), now);

        request.setAttribute(MERCHANT_ATTRIBUTE, merchant.get().getId());
        MDC.put(LogFields.MERCHANT_ID, merchant.get().getId().toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Carrier threads are reused. A leaked MDC entry attributes one
            // merchant's log line to another, which is worse than having no
            // merchant id at all.
            MDC.remove(LogFields.MERCHANT_ID);
        }
    }

    /**
     * Actuator and anything outside {@code /v1/} is unauthenticated.
     *
     * <p>The health endpoint is polled by Docker's healthcheck and, from phase 4,
     * by a metrics scraper; requiring a merchant credential for it would mean
     * putting a merchant credential into the container definition.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    public int getOrder() {
        // After the correlation filter, so a rejected request still carries a
        // correlation id in its log line and in its response.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    /**
     * SHA-256, not bcrypt or Argon2.
     *
     * <p>The usual advice - use a slow KDF - is about passwords, which are
     * low-entropy and chosen by humans. An API key here is 128+ bits of
     * generated randomness, so there is no dictionary to run and no
     * cost-per-guess that makes brute force feasible. A slow KDF would add tens
     * of milliseconds to every authenticated request in exchange for defending
     * against an attack that does not apply.
     *
     * <p>The real exposure reduction for API keys is rotation, which phase 9b
     * adds: two live keys per merchant with an overlap window.
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Written directly rather than thrown.
     *
     * <p>An exception from a filter does not reach {@code @RestControllerAdvice} -
     * it escapes the filter chain and the container renders whatever its default
     * error page is, which on a REST API is an HTML page with a stack trace. So
     * the RFC-7807 body is written here by hand.
     */
    private static void reject(HttpServletResponse response, String errorCode, String detail)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String correlationId = MDC.get(LogFields.CORRELATION_ID);
        response.getWriter().write("""
                {"type":"https://payorch.dev/problems/%s","title":"Unauthorized","status":401,\
                "detail":"%s","errorCode":"%s","correlationId":"%s"}"""
                .formatted(errorCode, detail, errorCode, correlationId == null ? "" : correlationId));
    }
}
