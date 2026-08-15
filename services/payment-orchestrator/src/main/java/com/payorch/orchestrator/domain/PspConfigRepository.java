package com.payorch.orchestrator.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PspConfigRepository extends JpaRepository<PspConfig, UUID> {

    List<PspConfig> findByEnabledTrueOrderByPriorityAsc();
}
