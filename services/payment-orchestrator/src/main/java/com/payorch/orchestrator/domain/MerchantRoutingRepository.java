package com.payorch.orchestrator.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRoutingRepository extends JpaRepository<MerchantRouting, UUID> {
}
