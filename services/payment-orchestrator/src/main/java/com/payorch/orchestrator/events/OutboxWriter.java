package com.payorch.orchestrator.events;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.payorch.infra.observability.TraceCarrier;
import com.payorch.orchestrator.domain.Payment;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes the outbox row, <strong>inside the caller's transaction</strong>.
 *
 * <h2>Where this is called from is the entire design</h2>
 *
 * <p>An outbox row written after the payment's transaction commits is not an
 * outbox. It is a second write that can fail independently of the first - which
 * is precisely the dual-write phase 6a measured at twenty permanently lost
 * events. The row has to be inserted by the same transaction that moves the
 * payment to its terminal state, so that the database's own atomicity guarantees
 * "authorized" and "an event is owed" together, or neither.
 *
 * <p>This was a real trap rather than a theoretical one. Phase 6a emits its event
 * from {@code PaymentService}, <em>after</em> the {@code @Transactional}
 * persistence method has returned and committed. Reusing that call site for the
 * outbox would have produced code that looks like a textbook outbox, passes a
 * casual review, and loses events at exactly the same rate as the dual-write it
 * replaced. So the outbox write lives in {@code PaymentPersistence} instead,
 * beside the state change, and {@link #assertTransactional} refuses to write at
 * all if that ever stops being true.
 *
 * <h2>No Kafka here</h2>
 *
 * <h2>Phase 6g: the trace context is captured HERE, and only here</h2>
 *
 * <p>This method runs on the request thread, inside the payment's transaction,
 * which makes it the last place in the event's life where the originating trace
 * still exists as a thread-local. Everything downstream - the relay, Debezium,
 * the consumer, the retry tiers - is on some other thread at some other time.
 * Capturing it anywhere later captures the wrong thing, and captures it
 * convincingly enough that the traces look fine until somebody follows one.
 *
 * <p>Nothing in this class talks to a broker, which is the second half of the
 * point. A network call inside a database transaction holds a pooled connection
 * for the duration of a remote call - phase 2 measured what that does to this
 * system - and it would reintroduce the failure the outbox exists to remove. The
 * relay publishes, later, on its own thread.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    /**
     * {@code ObjectProvider} because this service must start and work with
     * tracing switched off - phases 1 to 3 run without a collector, and an
     * outbox that refused to record an event because nobody was watching would
     * be a resilience regression dressed up as observability.
     */
    private final ObjectProvider<TraceCarrier> traces;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper mapper,
                        ObjectProvider<TraceCarrier> traces) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.traces = traces;
    }

    /**
     * Records that an event is owed for this payment.
     *
     * <p>Does nothing for non-terminal states, for the reason in
     * {@link PaymentEvents}: a ledger has no interest in the fact that a payment
     * is currently in flight.
     */
    public void record(Payment payment) {
        PaymentEvent event = PaymentEvents.toEvent(payment);
        if (event == null) {
            return;
        }
        assertTransactional();
        TraceCarrier carrier = traces.getIfAvailable();
        outbox.save(OutboxEvent.of(
                event.paymentId(),
                event.type(),
                mapper.writeValueAsString(event),
                carrier == null ? null : carrier.capture()));
    }

    /**
     * Fails loudly if there is no transaction to join.
     *
     * <p>This is a guard against a future refactor, and it is deliberately fatal
     * rather than a warning. An outbox that has quietly become non-transactional
     * behaves identically to a working one in every test that does not kill a
     * broker at the wrong moment - it is the exact bug this phase exists to
     * remove, wearing the costume of the fix. A payment failing to save is a
     * visible, fixable problem; an outbox silently degraded to a dual write is
     * neither.
     */
    private void assertTransactional() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "outbox write attempted outside a transaction - this is a dual write "
                            + "wearing an outbox's clothes, and it loses events exactly as "
                            + "fast. The insert must share the payment's transaction.");
        }
    }
}
