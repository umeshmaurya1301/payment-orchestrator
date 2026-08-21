package com.payorch.ledger.recon;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Phase 8. Reconciliation against a provider settlement file.
 *
 * <p>Not behind a property, unlike the webhook dispatcher and the compensation
 * consumer. Those reach out to something - a merchant's URL, a provider's
 * reversal endpoint - so a deployment that has not been configured for them must
 * not start them. This one only reads two Mongo collections and answers a
 * question when asked, so there is nothing to switch off and no reason to make
 * an operator find the flag before they can run a report during an incident.
 */
@Configuration
public class ReconConfiguration {

    /**
     * 9c. Creates the Mongo indexes that {@code @Indexed} never created, and the
     * TTL on raw settlement input.
     *
     * <p>Retention defaults to 7 days: long enough that a reconciliation dispute
     * raised the next working day still has the batch, short enough that this
     * system is not an indefinite archive of another company's file. It is
     * configurable because that number is a policy decision rather than a
     * technical one, and the technical part - that the expiry actually happens -
     * is what {@code tools/security/settlement-retention.sh} measures.
     */
    /**
     * Disabled in tests that have no Mongo, the same way
     * {@code payorch.vault.verify-on-startup} is.
     *
     * <p>Startup index creation talks to the database before the service reports
     * healthy, which is the behaviour that makes it worth having and also the
     * behaviour that fails a context load with no Mongo behind it. A property
     * rather than a swallowed exception: an index creation that silently gives
     * up is precisely the failure mode this class was written to end.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "payorch.recon", name = "create-indexes",
            havingValue = "true", matchIfMissing = true)
    public MongoIndexes mongoIndexes(
            MongoTemplate mongoTemplate,
            @org.springframework.beans.factory.annotation.Value("${payorch.recon.settlement-retention-days:7}")
            long retentionDays) {

        return new MongoIndexes(mongoTemplate, java.time.Duration.ofDays(retentionDays));
    }

    @Bean
    public ReconciliationJob reconciliationJob(MongoTemplate mongoTemplate) {
        return new ReconciliationJob(mongoTemplate);
    }

    @Bean
    public ReconEndpoint reconEndpoint(ReconciliationJob job) {
        return new ReconEndpoint(job);
    }
}
