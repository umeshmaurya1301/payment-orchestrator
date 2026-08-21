package com.payorch.ledger.recon;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.jspecify.annotations.Nullable;

/**
 * Run a reconciliation, and read the answer.
 *
 * <p>An actuator endpoint rather than a scheduled job, deliberately. A settlement
 * file arrives on somebody else's schedule, and a recon that runs at 02:00
 * whether or not the file landed produces a report full of
 * {@code LEDGER_NOT_SETTLED} that means nothing but "the file is not here yet" -
 * which is how a team learns to ignore the report. Ingest is the trigger.
 *
 * <p>{@code GET /actuator/recon?batch=…} runs the comparison and returns it.
 * Reading is safe to repeat: the job derives everything from the two collections
 * and stores no state of its own, so two people running it during an incident
 * get the same answer rather than racing.
 */
@Endpoint(id = "recon")
public class ReconEndpoint {

    private final ReconciliationJob job;

    public ReconEndpoint(ReconciliationJob job) {
        this.job = job;
    }

    @ReadOperation
    public Map<String, Object> report(@Nullable String batch) {
        return job.run(batch == null ? "default" : batch);
    }

    /**
     * Ingests a settlement batch.
     *
     * <p>The payload is a list of {@code paymentId:amountMinor:providerRef}
     * triples rather than a file upload. An actuator write operation takes simple
     * types, and a real ingest would read the provider's SFTP drop and parse
     * their format - which is work about parsing rather than about
     * reconciliation, and would make this endpoint the wrong place to test the
     * comparison.
     */
    @WriteOperation
    public Map<String, Object> ingest(String batch, String lines) {
        List<SettlementLine> parsed = new ArrayList<>();
        for (String raw : lines.split(",")) {
            String[] parts = raw.trim().split(":");
            if (parts.length < 2 || parts[0].isBlank()) {
                continue;
            }
            parsed.add(SettlementLine.of(
                    batch,
                    UUID.fromString(parts[0]),
                    parts.length > 2 ? parts[2] : "sett_" + parts[0].substring(0, 8),
                    Long.parseLong(parts[1]),
                    "INR",
                    "mockpsp",
                    Instant.now()));
        }
        int n = job.ingest(batch, parsed);
        return Map.of("batch", batch, "ingested", n);
    }
}
