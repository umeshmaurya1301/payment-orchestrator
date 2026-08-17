package com.payorch.orchestrator.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The merchant table, seen through the one column the orchestrator needs.
 *
 * <p><strong>Deliberately not the whole entity.</strong> {@code payments-edge}
 * owns {@code Merchant} - the API key hash, the status, authentication - and
 * this service has no business reading any of it. Mapping only
 * {@code routing_strategy} means a change to how merchants authenticate cannot
 * break routing, and it makes the dependency legible: the orchestrator cares
 * about exactly one merchant attribute.
 *
 * <p>Two services mapping different subsets of one table is a deliberate trade
 * against the alternative, which is the edge passing the strategy in every
 * request. That would put a routing decision's input in the hands of the caller
 * and make it spoofable from outside; a column read locally cannot be.
 */
@Entity
@Table(name = "merchant")
public class MerchantRouting {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "routing_strategy", nullable = false)
    private String routingStrategy;

    protected MerchantRouting() {
    }

    public UUID getId() {
        return id;
    }

    public String getRoutingStrategy() {
        return routingStrategy;
    }
}
