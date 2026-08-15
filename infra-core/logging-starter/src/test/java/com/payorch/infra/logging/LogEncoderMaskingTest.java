package com.payorch.infra.logging;

import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.payorch.infra.logging.jackson.SensitiveMapperBuilderDecorator;
import com.payorch.infra.logging.mask.SensitiveDataValueMasker;
import net.logstash.logback.argument.StructuredArguments;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real encoder with the real decorators, rather than testing the
 * masking primitives in isolation.
 *
 * <p>This is the test that corresponds to what actually ships: an
 * {@code ILoggingEvent} produced by an ordinary SLF4J call, encoded by
 * {@code LogstashEncoder}, with both masking decorators attached exactly as
 * {@code logback-payorch.xml} attaches them. A unit test of {@code Redactor}
 * alone would still pass if the wiring were wrong.
 */
class LogEncoderMaskingTest {

    private static final String TEST_PAN = "4242424242424242";

    private LoggerContext context;
    private Logger logger;
    private ListAppender<ILoggingEvent> captured;
    private LogstashEncoder encoder;

    record CardDetails(
            @Sensitive(MaskStrategy.PAN) String pan,
            @Sensitive String cvv,
            String last4) {
    }

    /** Non-Luhn on purpose - see {@link #annotationMasksWhatTheRegexCannotSee()}. */
    record InternalAccount(
            @Sensitive(MaskStrategy.LAST_FOUR) String accountNumber) {
    }

    @BeforeEach
    void setUp() {
        // The context must come from SLF4J rather than `new LoggerContext()`.
        // A hand-built context has no MDC adapter wired to it, so any event
        // carrying MDC blows up inside the encoder - which is precisely what
        // one of these tests is trying to exercise.
        context = (LoggerContext) LoggerFactory.getILoggerFactory();

        captured = new ListAppender<>();
        captured.setContext(context);
        captured.start();

        logger = context.getLogger("payorch-masking-test");
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(captured);

        MaskingJsonGeneratorDecorator masking = new MaskingJsonGeneratorDecorator();
        masking.addValueMasker(new SensitiveDataValueMasker());
        masking.start();

        encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.addDecorator(masking);
        encoder.addDecorator(new SensitiveMapperBuilderDecorator());
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        // The context is shared with the rest of the JVM, so detach rather
        // than stop it.
        logger.detachAppender(captured);
        captured.stop();
        encoder.stop();
    }

    private String encodeLast() {
        ILoggingEvent event = captured.list.get(captured.list.size() - 1);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a PAN interpolated into the message never reaches the output")
    void masksPanInMessage() {
        logger.info("authorizing card {}", TEST_PAN);

        String json = encodeLast();

        assertThat(json).doesNotContain(TEST_PAN);
        assertThat(json).contains("424242******4242");
    }

    @Test
    @DisplayName("a PAN in MDC never reaches the output")
    void masksPanInMdc() {
        try {
            org.slf4j.MDC.put("cardRef", TEST_PAN);
            logger.info("processing");

            assertThat(encodeLast()).doesNotContain(TEST_PAN);
        } finally {
            org.slf4j.MDC.remove("cardRef");
        }
    }

    @Test
    @DisplayName("a PAN inside a logged exception never reaches the output")
    void masksPanInStackTrace() {
        logger.info("call failed", new IllegalStateException("declined for " + TEST_PAN));

        assertThat(encodeLast()).doesNotContain(TEST_PAN);
    }

    @Test
    @DisplayName("@Sensitive masks a structured argument object")
    void masksStructuredArgument() {
        logger.info("card received",
                StructuredArguments.value("card", new CardDetails(TEST_PAN, "123", "4242")));

        String json = encodeLast();

        assertThat(json).doesNotContain(TEST_PAN);
        assertThat(json).doesNotContain("\"123\"");
        assertThat(json).contains("4242");
    }

    /**
     * The two controls are genuinely independent, and this proves it.
     *
     * <p>{@code 1234567812345678} fails the Luhn check, so {@link SensitiveDataValueMasker}
     * deliberately leaves it alone - it looks like an order ID, not a card. If
     * it still comes out masked, the only thing that could have done it is the
     * {@code @Sensitive} annotation being honoured on the encoder's own mapper.
     */
    @Test
    @DisplayName("annotation masks values the regex net is designed not to touch")
    void annotationMasksWhatTheRegexCannotSee() {
        String nonLuhn = "1234567812345678";

        logger.info("internal account",
                StructuredArguments.value("account", new InternalAccount(nonLuhn)));

        String json = encodeLast();

        assertThat(json).doesNotContain(nonLuhn);
        assertThat(json).contains("************5678");
    }

    @Test
    @DisplayName("ordinary log lines are left readable")
    void doesNotMangleCleanOutput() {
        logger.info("payment authorized in {}ms", 142);

        String json = encodeLast();

        assertThat(json).contains("payment authorized in 142ms");
        assertThat(json).contains("\"level\":\"INFO\"");
    }
}
