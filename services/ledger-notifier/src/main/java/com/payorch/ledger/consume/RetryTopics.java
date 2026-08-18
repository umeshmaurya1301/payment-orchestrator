package com.payorch.ledger.consume;

/**
 * The names of the retry ladder's topics, in one place, because two things have
 * to agree on them and neither can see the other.
 *
 * <p>Spring Kafka derives retry topic names from the main topic plus a suffix
 * plus the backoff delay in milliseconds. {@code tools/kafka/topics.sh} creates
 * them ahead of time with {@code RF=3} and {@code min.insync.replicas=2}. If the
 * two disagree by one character, Spring does not fail - it finds the topic
 * missing and, with {@code autoCreateTopics} left at its default of true,
 * creates it using the broker defaults. That gives a retry ladder with
 * {@code RF=1}: the durability phase 6a and 6d were spent establishing,
 * quietly discarded for every message that failed once.
 *
 * <p>So {@code autoCreateTopics = "false"}, and these constants exist to be
 * greppable from the shell script that creates them.
 *
 * <h2>Why the delays are in the names</h2>
 *
 * <p>{@link org.springframework.kafka.retrytopic.TopicSuffixingStrategy#SUFFIX_WITH_DELAY_VALUE}
 * rather than the default index. {@code payment.events.retry-2} tells an
 * operator nothing at 3am; {@code payment.events.retry-600000} tells them that
 * anything sitting in it is at least ten minutes from the DLQ and that they
 * have time to fix the cause before it gets there.
 */
public final class RetryTopics {

    private RetryTopics() {
    }

    public static final String MAIN = "payment.events";

    /** Tier 1: 5 seconds. Absorbs a restarting downstream or a lock timeout. */
    public static final String RETRY_5S = "payment.events.retry-5000";

    /** Tier 2: 1 minute. Absorbs a rolling deploy. */
    public static final String RETRY_1M = "payment.events.retry-60000";

    /** Tier 3: 10 minutes. Absorbs an incident somebody is already paged for. */
    public static final String RETRY_10M = "payment.events.retry-600000";

    /**
     * The end of the ladder. Retention is 30 days rather than 7, because this is
     * the only topic here read by a human, days after the fact.
     */
    public static final String DLQ = "payment.events.dlq";

    /** The suffix that produces the three names above. See the class javadoc. */
    public static final String RETRY_SUFFIX = ".retry";

    public static final String DLQ_SUFFIX = ".dlq";
}
