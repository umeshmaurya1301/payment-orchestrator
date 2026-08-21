package com.payorch.infra.tokenization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An append-only record of every card read. Phase 9c.
 *
 * <h2>The question encryption does not answer</h2>
 *
 * <p>Phase 9b's envelope encryption answers "can somebody who steals this table
 * read it". The first question an auditor asks is a different one — <em>who
 * looked at this card, and why</em> — and no amount of encryption addresses it,
 * because the reader in question holds a legitimate credential and is doing
 * exactly what it is authorised to do. A service authorised to detokenize once
 * per authorization, doing it a million times on a Tuesday night, is invisible
 * to every control this project has built so far.
 *
 * <h2>Fail-closed, and why that is not obviously right</h2>
 *
 * <p>When the audit write fails, this refuses the read. That is a real cost: it
 * puts a second database write in front of every card decryption and makes a
 * detokenization fail for a reason unrelated to the card, so an audit table
 * problem becomes a payment outage.
 *
 * <p>It is chosen anyway, because the alternative fails in a worse place. An
 * audit log that is skipped when it is inconvenient is not an audit log — it is
 * a log with a gap exactly where the incident is, since the conditions that
 * break the write (load, an exhausted pool, a table nobody migrated) are
 * correlated with the conditions worth investigating. "We cannot record who read
 * this, so nobody reads it" is a defensible sentence. "We could not record it so
 * we did it anyway" is not.
 *
 * <p>{@code payorch.vault.audit.fail-closed=false} exists as the control arm,
 * and experiment 25 measures what the choice costs.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No PAN, no BIN, no last four. An audit log that records what was read is a
 * second copy of the thing it audits, and it is the copy nobody remembers to
 * encrypt. Everything stored here is a reference to a card rather than a card.
 */
public class VaultAccessLog {

    private static final Logger log = LoggerFactory.getLogger(VaultAccessLog.class);

    public static final String SUCCESS = "SUCCESS";
    public static final String UNKNOWN_TOKEN = "UNKNOWN_TOKEN";
    public static final String FAILED = "FAILED";

    private static final String INSERT = """
            INSERT INTO vault_access_log
                (token, actor, purpose, reference, correlation_id, trace_id, outcome)
            VALUES (:token, :actor, :purpose, :reference, :correlationId, :traceId, :outcome)
            """;

    private final JdbcClient jdbc;
    private final String actor;
    private final boolean failClosed;

    public VaultAccessLog(JdbcClient jdbc, String actor, boolean failClosed) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.failClosed = failClosed;
    }

    /**
     * Records one access attempt.
     *
     * @throws AuditUnavailableException when the record cannot be written and
     *                                   this log is fail-closed
     */
    public void record(String token, VaultAccess access, String outcome) {
        try {
            jdbc.sql(INSERT)
                    .param("token", token)
                    .param("actor", actor)
                    .param("purpose", access.purpose())
                    .param("reference", access.reference())
                    // Read from MDC rather than passed in: every request path in
                    // this system already populates these, and threading them
                    // through four signatures to arrive at the same values would
                    // add a parameter that is wrong whenever somebody forgets it.
                    .param("correlationId", MDC.get("correlationId"))
                    .param("traceId", MDC.get("traceId"))
                    .param("outcome", outcome)
                    .update();

        } catch (RuntimeException e) {
            if (failClosed) {
                // Deliberately not logged-and-swallowed. The caller must not
                // receive a card whose read went unrecorded, and the only way to
                // guarantee that is to make this loud.
                throw new AuditUnavailableException(e);
            }
            log.error("VAULT ACCESS WAS NOT AUDITED: token={} purpose={} outcome={}",
                    token, access.purpose(), outcome, e);
        }
    }

    /**
     * The audit trail could not be written, so the card was not disclosed.
     *
     * <p>Its own type rather than a generic failure, because the operational
     * response is completely different from a decryption or lookup problem:
     * nothing is wrong with the card or the key, and the fix is to the audit
     * table.
     */
    public static class AuditUnavailableException extends RuntimeException {

        public AuditUnavailableException(Throwable cause) {
            super("the vault access could not be audited, so the card was not read", cause);
        }
    }
}
