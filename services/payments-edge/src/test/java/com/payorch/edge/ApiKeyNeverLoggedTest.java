package com.payorch.edge;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 says the API key must never reach a log line, and to verify that
 * rather than assume it. This is the verification.
 *
 * <p>The control is the allowlist in {@link LogFields}, and the property being
 * checked is negative - that a name is absent - which is exactly the kind of
 * claim that quietly stops being true. A denylist would fail silently here; an
 * allowlist fails loudly, and this test is what proves the difference is real
 * rather than architectural.
 */
class ApiKeyNeverLoggedTest {

    private static final String API_KEY = "pk_test_dev_merchant_key";

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(appender);
    }

    @Test
    void noFieldNameInTheSchemaCouldHoldACredential() {
        assertThat(LogFields.ALLOWED)
                .doesNotContain("apiKey", "api_key", "authorization", "credential", "secret");
    }

    /**
     * The enforcement, not the convention. Adding the key to a log event is not
     * discouraged - it throws.
     */
    @Test
    void loggingAnApiKeyFieldThrowsRatherThanLogging() {
        assertThatThrownBy(() -> LogEvent.event().with("apiKey", API_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");

        List<ILoggingEvent> captured = appender.list;
        assertThat(captured).noneMatch(event -> event.getFormattedMessage().contains(API_KEY));
    }

    /**
     * A stored hash must not be reversible to the key by anyone reading it, and
     * must not be the key with a different name. Trivially true of SHA-256, and
     * worth pinning because "hashed at rest" is the sort of claim that gets
     * refactored into "encoded at rest" by accident.
     */
    @Test
    void theStoredFormIsADigestNotTheKey() {
        String hash = ApiKeyAuthFilter.sha256Hex(API_KEY);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain(API_KEY);
        assertThat(ApiKeyAuthFilter.sha256Hex(API_KEY))
                .as("the same key must hash to the same value, or lookup by hash cannot work")
                .isEqualTo(hash);
    }
}
