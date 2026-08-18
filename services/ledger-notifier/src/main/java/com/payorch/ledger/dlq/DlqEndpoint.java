package com.payorch.ledger.dlq;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * The admin surface for the dead-letter queue.
 *
 * <pre>{@code
 * curl localhost:8084/actuator/dlq                    # depth and what is in it
 * curl -XPOST localhost:8084/actuator/dlq \
 *      -H 'content-type: application/json' -d '{"limit":500}'   # replay
 * }</pre>
 *
 * <h2>Why replay is a WRITE operation on an actuator endpoint</h2>
 *
 * <p>Because it is an operator action taken during an incident, and the
 * management surface is where those live in this project - alongside
 * {@code /actuator/chaosseams} and the provider health endpoint. It is not on
 * the public API: replaying a payment event is not something a merchant does,
 * and putting it behind {@code /v1/} would have put it behind the edge, the
 * rate limiters and the API-key check, none of which have anything to say about
 * it.
 *
 * <h2>The limit is mandatory in spirit</h2>
 *
 * <p>It defaults to 500 rather than unbounded. A DLQ that has been accumulating
 * for a week can hold more than anyone intends to replay in one command, and the
 * failure mode of an unbounded replay is a self-inflicted load spike on the
 * service that was already having a bad day.
 */
@Endpoint(id = "dlq")
public class DlqEndpoint {

    /**
     * Deliberately modest. Replay is meant to be run repeatedly and watched,
     * not fired once and hoped over.
     */
    private static final int DEFAULT_LIMIT = 500;

    private final DlqAdmin dlq;

    public DlqEndpoint(DlqAdmin dlq) {
        this.dlq = dlq;
    }

    /**
     * Depth plus a sample of what is in there.
     *
     * <p>The sample is capped hard at 20. The point of this operation is to
     * answer "what broke and is it all the same thing" - which twenty records
     * answers as well as five hundred, and without turning a diagnostic into a
     * bulk export.
     */
    @ReadOperation
    public Map<String, Object> state(@Nullable Integer sample) {
        Map<String, Object> out = new LinkedHashMap<>(dlq.state());
        out.put("sample", dlq.peek(sample == null ? 20 : Math.min(sample, 20)));
        return out;
    }

    @WriteOperation
    public Map<String, Object> replay(@Nullable Integer limit) {
        return dlq.replay(limit == null ? DEFAULT_LIMIT : limit);
    }
}
