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

    @Bean
    public ReconciliationJob reconciliationJob(MongoTemplate mongoTemplate) {
        return new ReconciliationJob(mongoTemplate);
    }

    @Bean
    public ReconEndpoint reconEndpoint(ReconciliationJob job) {
        return new ReconEndpoint(job);
    }
}
