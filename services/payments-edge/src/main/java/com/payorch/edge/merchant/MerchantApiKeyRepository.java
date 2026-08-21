package com.payorch.edge.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantApiKeyRepository extends JpaRepository<MerchantApiKey, UUID> {

    /**
     * Authentication is a single point lookup on a unique index.
     *
     * <p>Not a scan over a merchant's keys comparing hashes, and the reason is
     * not only speed: the query does the same work whichever key is presented,
     * so it does not leak — through timing — how many keys a merchant has or
     * whether a prefix matched.
     *
     * <p>Status is deliberately not in the WHERE clause. A revoked key must be
     * distinguishable from a key that never existed <em>inside</em> this
     * service, because those want different log lines — a revoked key still
     * being presented is the signal that a rotation was completed too early. The
     * caller sees the same 401 either way.
     */
    Optional<MerchantApiKey> findByApiKeyHash(String apiKeyHash);

    List<MerchantApiKey> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);

    /**
     * Stamp the key as used.
     *
     * <p>Bulk update rather than a load-modify-save: the filter has the id
     * already, and reading the row back to write one column would double the
     * work on the authentication path for nothing. It also avoids a
     * lost-update race between two instances stamping the same key, because
     * whichever write lands second is the one that is correct anyway.
     */
    @Modifying
    @Query("UPDATE MerchantApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now);
}
