package com.payorch.ledger.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EntryRepository extends JpaRepository<LedgerEntry, UUID> {

    boolean existsByEventId(UUID eventId);

    /**
     * The double-entry invariant, as a query.
     *
     * <p>Every event posts two legs that sum to zero, so this must be zero at
     * all times. It is the phase-6 exit criterion's "correct balances" expressed
     * as something a script can assert rather than something a person believes.
     */
    @Query("SELECT COALESCE(SUM(e.amountMinor), 0) FROM LedgerEntry e")
    long sumOfAllEntries();
}
