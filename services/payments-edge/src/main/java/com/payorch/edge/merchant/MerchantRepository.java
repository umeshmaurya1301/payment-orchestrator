package com.payorch.edge.merchant;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    // Authentication no longer starts here. Since 9b it starts at
    // MerchantApiKeyRepository.findByApiKeyHash and arrives at a merchant by id,
    // because a merchant has several keys during a rotation.
}
