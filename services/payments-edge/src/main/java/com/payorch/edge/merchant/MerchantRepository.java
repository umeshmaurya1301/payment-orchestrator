package com.payorch.edge.merchant;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    /**
     * Authentication is a single point lookup on a unique index, not a scan over
     * merchants comparing hashes. That matters as much for constant work as for
     * speed: the query cost does not depend on which key was presented.
     */
    Optional<Merchant> findByApiKeyHash(String apiKeyHash);
}
